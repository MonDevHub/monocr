<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { onMount, onDestroy, tick } from 'svelte';
	import { fade, fly } from 'svelte/transition';
	import { initializeEngine, recognize, cleanup, onModelProgress } from '$lib/monocr';
	import { CONFIG } from '$lib/config';
	import { renderPdfPage, loadPdf } from '$lib/utils/pdf-util';
	import { feedbackStore } from '$lib/stores/feedback';
	import { HistorySection, Icon } from '$lib/components';
	import { getRecord, saveRecord } from '$lib/storage/db';
	import { combinePages, isTimeout, pageWarnings, readPdfPages } from '$lib/reading';
	import { createRunToken } from '$lib/run-token';
	import {
		checkFile,
		formatMegabytes,
		intakeAction,
		percent,
		resultView,
		retryTarget,
		statusKind,
		summariseDetail,
		worthSaving,
		type AppError,
		type EngineState
	} from '$lib/intake';
	import { recallResult, rememberResult } from '$lib/session-result';
	import * as m from '$lib/paraglide/messages';
	import { goto } from '$app/navigation';

	// 'loading' until the model is in; 'failed' when it would not load.
	let engineState = $state<EngineState>('loading');
	// A read is in progress. Not the model download: that is `engineState`.
	let reading = $state(false);
	let error = $state<AppError | null>(null);

	let modelReceived = $state(0);
	let modelTotal = $state(0);
	let stopProgress: (() => void) | null = null;

	let file = $state<File | null>(null);
	// The file last handed to a read, so Retry can read it again.
	let lastFile: File | null = null;
	// A file picked while the model was still loading; read once it is ready.
	let queuedFile = $state<File | null>(null);
	let previewUrl = $state<string | null>(null);
	let previewFailed = $state(false);
	let resultText = $state<string | null>(null);
	// What the result is missing, when part of it could not be read. An error
	// replaces the result; a warning sits beside one.
	let warnings = $state<string[]>([]);
	let processingTime = $state(0);
	let copied = $state(false);
	let isPdf = $state(false);
	let totalPages = $state(0);
	let currentPage = $state(0);
	let dragging = $state(false);
	let fileInput = $state<HTMLInputElement>();
	let copyButton = $state<HTMLButtonElement>();
	let resultCard = $state<HTMLElement>();
	let focusResult = false;

	// One token per read. Clearing or choosing another file invalidates it, and a
	// read checks it before every write: see run-token.ts for the failure this stops.
	const runs = createRunToken();

	const view = $derived(resultView({ loading: reading, error: error !== null, text: resultText }));
	const status = $derived(statusKind({ engine: engineState, reading, pdf: isPdf, view }));
	const dropDisabled = $derived(engineState === 'failed');

	// History reference for refreshing
	let historySection: ReturnType<typeof HistorySection>;

	async function loadHistory() {
		historySection?.refresh();
	}

	function handleDragOver(e: DragEvent) {
		e.preventDefault();
		if (!dropDisabled) dragging = true;
	}

	function handleDragLeave() {
		dragging = false;
	}

	function handleDrop(e: DragEvent) {
		e.preventDefault();
		dragging = false;
		const droppedFile = e.dataTransfer?.files[0];
		if (droppedFile) handleFile(droppedFile);
	}

	function handleFileSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		const selectedFile = input.files?.[0];
		// Cleared so choosing the same file again fires `change` again.
		input.value = '';
		if (selectedFile) handleFile(selectedFile);
	}

	async function startEngine() {
		try {
			engineState = 'loading';
			error = null;
			await Promise.all([initializeEngine(), loadHistory()]);
			engineState = 'ready';
			const waiting = queuedFile;
			queuedFile = null;
			if (waiting) handleFile(waiting);
		} catch (e: unknown) {
			engineState = 'failed';
			error = { kind: 'engine', detail: e instanceof Error ? e.message : String(e) };
		}
	}

	onMount(() => {
		// The model is downloaded once and cached. Report bytes while it arrives:
		// it is 46 MB, and on a slow connection an indeterminate spinner is
		// indistinguishable from a hang, which is what made people reload and start
		// the download again from zero.
		stopProgress = onModelProgress((received, total) => {
			modelReceived = received;
			modelTotal = total;
		});
		startEngine();
		restoreResult();
	});

	onDestroy(() => {
		stopProgress?.();
		cleanup();
		// Only revoke if we aren't heading to the report page
		if (previewUrl && !window.location.pathname.includes('/report')) {
			URL.revokeObjectURL(previewUrl);
		}
	});

	/**
	 * Bring back the result that was on screen before a reload (a language switch
	 * reloads the page). It was saved to history when it finished, so only its id
	 * was kept; see session-result.ts.
	 */
	async function restoreResult() {
		const id = recallResult(typeof sessionStorage === 'undefined' ? undefined : sessionStorage);
		if (!id) return;
		try {
			const record = await getRecord(id);
			// Not if the person has already started something, or the record is gone.
			if (!record || file || reading || queuedFile) return;
			file = new File([record.fileData], record.fileName, { type: record.fileType });
			isPdf = record.fileType === 'application/pdf';
			previewUrl = URL.createObjectURL(record.fileData);
			resultText = record.text;
			processingTime = record.processingTime;
		} catch (e) {
			console.error('Could not restore the last result:', e);
		}
	}

	function remember(id: string | null) {
		rememberResult(typeof sessionStorage === 'undefined' ? undefined : sessionStorage, id);
	}

	function handleFile(newFile: File) {
		const problem = checkFile(newFile, CONFIG.UI.MAX_IMAGE_SIZE_MB, CONFIG.UI.ALLOWED_FILE_TYPES);
		if (problem) {
			error = problem;
			return;
		}

		const action = intakeAction(engineState);
		// Nothing to read it with; the engine error is already on screen.
		if (action === 'block') return;
		if (action === 'queue') {
			error = null;
			queuedFile = newFile;
			return;
		}
		startRead(newFile);
	}

	function startRead(newFile: File) {
		const run = runs.begin();
		// A read still running holds the worker, which has no queue. Stop it, so the
		// new file does not wait behind it (that wait was 136 s in one measured run).
		if (reading) cleanup();

		lastFile = newFile;
		file = newFile;
		isPdf = newFile.type === 'application/pdf';
		queuedFile = null;
		resultText = null;
		warnings = [];
		error = null;
		currentPage = 0;
		totalPages = 0;
		previewFailed = false;
		remember(null);
		revokePreview();
		reading = true;

		if (isPdf) {
			processPdf(newFile, run);
		} else {
			previewUrl = URL.createObjectURL(newFile);
			processImage(newFile, run);
		}
	}

	function revokePreview() {
		if (!previewUrl) return;
		try {
			URL.revokeObjectURL(previewUrl);
		} catch {
			// Ignore cleanup errors
		}
		previewUrl = null;
	}

	async function processPdf(source: File, run: number) {
		const current = () => runs.isCurrent(run);
		try {
			const buffer = await source.arrayBuffer();
			if (!current()) return;

			// Load the PDF once to avoid detaching the buffer multiple times
			const pdf = await loadPdf(buffer);
			if (!current()) return;
			totalPages = pdf.numPages;

			const startTime = performance.now();
			let firstPageBlob: Blob | null = null;

			// Every page gets an outcome, and pages already read are kept when a later
			// one fails. A timed-out page leaves the worker busy with it, so the
			// worker is stopped (cleanup) before the next page; see readPdfPages.
			const pages = await readPdfPages(
				totalPages,
				async (i) => {
					const { imageBytes } = await renderPdfPage(pdf, i);
					// Taken before recognition, which transfers the buffer to the worker.
					const blob = new Blob([imageBytes.buffer as ArrayBuffer], { type: 'image/jpeg' });
					// The preview follows the page being read; the first page that
					// rendered is the one kept for history.
					firstPageBlob ??= blob;
					runs.commit(run, () => {
						revokePreview();
						previewUrl = URL.createObjectURL(blob);
					});
					return imageBytes;
				},
				// A read that was cleared must not start on the worker the next file is using.
				(bytes) =>
					current() ? recognize(bytes) : Promise.reject(new Error('This read was cancelled.')),
				() => {
					if (current()) cleanup();
				},
				{
					onPage: (n) => runs.commit(run, () => (currentPage = n)),
					isCancelled: () => !current()
				}
			);
			if (!current()) return;

			// Throws when no page could be read: that is a failure, not an empty PDF.
			const combined = combinePages(pages);
			const elapsed = Math.round(performance.now() - startTime);
			runs.commit(run, () => {
				resultText = combined.text;
				warnings = combined.warnings;
				processingTime = elapsed;
				reading = false;
				focusResult = true;
			});

			// Save to history (using first page as representative image). A PDF that
			// read as blank is shown as such and not kept.
			if (firstPageBlob && worthSaving(combined.text)) {
				const id = await saveRecord(
					{
						fileName: source.name,
						fileType: 'application/pdf',
						fileData: firstPageBlob,
						text: combined.text,
						processingTime: elapsed
					},
					'ocr-scan',
					false
				);
				if (current()) remember(id);
				await loadHistory();
			}
		} catch (err: unknown) {
			console.error('PDF Processing Error:', err);
			const detail = err instanceof Error ? err.message : 'Please check your file and try again.';
			runs.commit(run, () => {
				error = { kind: 'pdf', detail };
				reading = false;
			});
		}
	}

	async function processImage(source: File, run: number) {
		const current = () => runs.isCurrent(run);
		try {
			const start = performance.now();

			const buffer = await source.arrayBuffer();
			if (!current()) return;
			const bytes = new Uint8Array(buffer);

			// Run OCR. Throws when no line could be read; a page that was read and
			// found no text resolves with empty text, as before.
			const result = await recognize(bytes);
			if (!current()) return;
			const text = result.text;
			const elapsed = Math.round(performance.now() - start);
			runs.commit(run, () => {
				resultText = text;
				warnings = pageWarnings(result);
				processingTime = elapsed;
				reading = false;
				focusResult = true;
			});

			// Save to history. A page with no text is shown as such and not kept.
			if (worthSaving(text)) {
				const id = await saveRecord(
					{
						fileName: source.name,
						fileType: source.type,
						fileData: source,
						text,
						processingTime: elapsed
					},
					'ocr-scan',
					false
				);
				if (current()) remember(id);
				await loadHistory();
			}
		} catch (e: unknown) {
			console.error(e);
			// A timed-out read keeps running in the worker; stop it so the next
			// image does not queue behind it. The next recognize() starts afresh.
			// Not when the read was already cleared: the worker is the next file's.
			if (isTimeout(e) && current()) cleanup();
			const detail = e instanceof Error ? e.message : String(e);
			runs.commit(run, () => {
				error = { kind: 'ocr', detail };
				reading = false;
			});
		}
	}

	/** Clear, Cancel and Process Another: stop whatever is running and start over. */
	function reset() {
		runs.cancel();
		if (reading) cleanup();
		reading = false;
		file = null;
		lastFile = null;
		queuedFile = null;
		revokePreview();
		previewFailed = false;
		resultText = null;
		warnings = [];
		error = null;
		currentPage = 0;
		totalPages = 0;
		remember(null);
	}

	function retry() {
		if (!error) return;
		const target = retryTarget(error);
		if (target === 'engine') startEngine();
		else if (target === 'file' && lastFile) startRead(lastFile);
	}

	function downloadText() {
		if (!resultText) return;

		const blob = new Blob([resultText], { type: 'text/plain;charset=utf-8' });
		const url = URL.createObjectURL(blob);
		const link = document.createElement('a');

		// Create a filename from the current date/time to avoid overwrites
		const dateObj = new Date();
		const dateStr = dateObj.toISOString().split('T')[0];
		const timeStr = dateObj.toTimeString().split(' ')[0].replace(/:/g, '-');
		link.download = `monocr-extraction-${dateStr}-${timeStr}.txt`;

		link.href = url;
		document.body.appendChild(link);
		link.click();
		document.body.removeChild(link);
		URL.revokeObjectURL(url);
	}

	function copyText() {
		navigator.clipboard.writeText(resultText || '');
		copied = true;
		setTimeout(() => (copied = false), 2000);
	}

	async function reportError() {
		feedbackStore.set({
			text: resultText || '',
			previewUrl
		});
		await goto('/report');
	}

	// Smart focus for accessibility and efficiency: Copy takes focus when a read
	// finishes. `preventScroll`, because focusing it scrolled the card's header
	// under the fixed header; the card is then brought into view on its own terms.
	$effect(() => {
		if (focusResult && view === 'text' && copyButton) {
			focusResult = false;
			copyButton.focus({ preventScroll: true });
			tick().then(() => resultCard?.scrollIntoView({ block: 'nearest' }));
		}
	});

	function wordCount(text: string): number {
		return text.split(/\s+/).filter((w) => w.length > 0).length;
	}

	/** The translated headline for an error, and the raw message for Details. */
	function describe(err: AppError): { headline: string; details: string | null } {
		switch (err.kind) {
			case 'file_large':
				return { headline: m.error_file_large({ max: err.maxMb }), details: null };
			case 'file_type':
				return { headline: m.error_file_type(), details: null };
			default: {
				const { summary, full } = summariseDetail(err.detail);
				const headline =
					err.kind === 'engine'
						? m.error_engine_load({ msg: summary })
						: err.kind === 'pdf'
							? m.error_pdf_failed({ msg: summary })
							: m.error_ocr_failed({ msg: summary });
				return { headline, details: full };
			}
		}
	}

	const statusText = $derived.by(() => {
		switch (status) {
			case 'loading_model':
				return m.main_loading_model();
			case 'ready':
				return m.main_engine_ready();
			case 'engine_failed':
				return error ? describe(error).headline : '';
			case 'scanning':
				return m.main_scanning();
			case 'processing_page':
				return m.main_processing_page({ current: currentPage, total: totalPages });
			case 'no_text':
				return m.main_no_text();
			case 'done':
				return `${m.main_extracted_text()}: ${wordCount(resultText ?? '')} ${m.main_words()}`;
			default:
				return '';
		}
	});
</script>

{#snippet errorNotice(err: AppError)}
	{@const info = describe(err)}
	<div
		role="alert"
		class="rounded-[var(--radius-lg)] border border-red-200 bg-red-50 px-4 py-3 text-[13px] leading-snug text-red-700 dark:border-red-900/40 dark:bg-red-950/30 dark:text-red-300"
	>
		<p class="font-medium">{info.headline}</p>
		{#if info.details}
			<details class="mt-1.5">
				<summary class="cursor-pointer text-[12px] underline underline-offset-2">
					{m.error_details()}
				</summary>
				<p class="mt-1 text-[12px] break-words whitespace-pre-wrap opacity-90">{info.details}</p>
			</details>
		{/if}
		<div class="mt-2 flex flex-wrap items-center gap-3">
			{#if retryTarget(err)}
				<button
					type="button"
					onclick={retry}
					class="focus-ring min-h-11 rounded-md border border-current px-4 text-[12px] font-semibold"
				>
					{m.history_retry()}
				</button>
			{/if}
			{#if err.kind === 'file_large'}
				<a href="/docs#cli-reference" class="focus-ring text-[12px] underline underline-offset-2">
					{m.main_learn_more()}
				</a>
			{/if}
		</div>
	</div>
{/snippet}

<div class="mx-auto w-full max-w-3xl">
	<!-- One polite status line for screen readers: loading, scanning, done. -->
	<div class="sr-only" role="status" aria-live="polite">{statusText}</div>

	<!-- Intro Section -->
	<div class="mb-6 space-y-2 text-center md:mb-10 md:space-y-4">
		<h1
			class="text-fg-primary mx-auto mt-2 max-w-xl text-[18px] leading-snug font-medium tracking-tight text-balance sm:text-[20px] md:text-[var(--text-title)]"
		>
			{m.main_hero()}
		</h1>

		<div class="flex flex-col items-center gap-2 pt-2">
			{#if engineState === 'loading'}
				<div
					class="bg-canvas-subtle/60 text-fg-muted inline-flex flex-col items-center gap-1.5 rounded-md px-3 py-1.5 text-[11px] font-semibold tracking-wider uppercase"
				>
					<div class="flex items-center gap-2">
						<div class="bg-fg-muted h-1 w-1 animate-pulse rounded-full"></div>
						{m.main_loading_model()}
						{#if modelTotal > 0}
							<span class="normal-case tabular-nums"
								>{formatMegabytes(modelReceived)} / {formatMegabytes(modelTotal)} MB</span
							>
						{/if}
					</div>
					{#if modelTotal > 0}
						<!-- Determinate only when content-length was sent; otherwise the bar
						     would animate against an unknown total and mislead. -->
						<div class="bg-fg-muted/20 h-1 w-48 overflow-hidden rounded-full">
							<div
								class="bg-fg-muted h-full transition-[width] duration-200"
								style="width: {percent(modelReceived, modelTotal)}%"
							></div>
						</div>
					{/if}
				</div>
				<p class="text-fg-muted max-w-md text-[12px] leading-snug normal-case">
					{m.main_model_info()}
				</p>
			{:else if engineState === 'ready'}
				<div
					class="bg-canvas-subtle/60 text-fg-muted inline-flex items-center gap-1.5 rounded-md px-3 py-1 text-[11px] font-semibold tracking-wider uppercase"
					in:fade
				>
					<div
						class="animate-pulse-subtle h-1 w-1 rounded-full bg-emerald-500/60 dark:bg-emerald-500/40"
					></div>
					{m.main_engine_ready()}
				</div>
			{/if}
		</div>
	</div>

	<!-- Main Content -->
	<main id="main-content" class="space-y-12 sm:space-y-16">
		{#if !file}
			<div in:fade={{ duration: 150 }} class="space-y-4">
				{#if error}
					{@render errorNotice(error)}
				{/if}

				<div
					class="bg-canvas-subtle/40 group relative flex flex-col items-center justify-center rounded-[var(--radius-huge)] py-16 transition-all duration-300 ease-in-out {dropDisabled
						? 'opacity-50'
						: 'hover:bg-canvas-subtle'} {dragging ? 'dropzone-active' : ''}"
					ondragover={handleDragOver}
					ondragleave={handleDragLeave}
					ondrop={handleDrop}
					onclick={() => !dropDisabled && fileInput?.click()}
					role="button"
					tabindex="0"
					aria-disabled={dropDisabled}
					onkeydown={(e) => {
						if (e.key === 'Enter' || e.key === ' ') {
							e.preventDefault();
							if (!dropDisabled) fileInput?.click();
						}
					}}
				>
					<input
						type="file"
						bind:this={fileInput}
						onchange={handleFileSelect}
						accept="image/*,application/pdf"
						class="hidden"
						disabled={dropDisabled}
					/>
					<div
						class="bg-primary-action/0 group-hover:bg-primary-action/[0.02] pointer-events-none absolute inset-0 rounded-[var(--radius-huge)] transition-colors"
					></div>

					<div class="relative flex flex-col items-center space-y-4 px-4 text-center">
						<div
							class="bg-canvas-subtle flex h-16 w-16 items-center justify-center rounded-2xl shadow-sm transition-all duration-300 group-hover:scale-110"
						>
							<Icon
								name="upload_file"
								size={30}
								class="text-fg-muted group-hover:text-primary transition-colors duration-300"
							/>
						</div>

						<div class="space-y-1.5">
							<h2 class="text-fg-primary text-base font-semibold tracking-tight">
								{m.main_dropzone_title()}
							</h2>
							<p class="text-fg-secondary text-sm">{m.main_dropzone_hint()}</p>
							{#if dropDisabled}
								<p class="text-fg-secondary text-sm">{m.main_dropzone_blocked()}</p>
							{:else if queuedFile}
								<p class="text-fg-primary text-sm font-medium">
									{m.main_queued({ name: queuedFile.name })}
								</p>
							{/if}
						</div>
					</div>
				</div>
			</div>
		{:else}
			<div class="mx-auto flex w-full max-w-2xl flex-col gap-6" in:fly={{ y: 20, duration: 400 }}>
				<!-- Image Preview -->
				{#if previewUrl && !previewFailed}
					<div
						class="bg-canvas-subtle/30 relative flex min-h-[120px] items-center justify-center overflow-hidden rounded-lg p-3"
					>
						<img
							src={previewUrl}
							alt={m.main_preview_alt()}
							class="h-auto max-h-[25vh] w-full object-contain"
							onerror={() => (previewFailed = true)}
						/>
					</div>
				{/if}

				<!-- Result -->
				<div
					bind:this={resultCard}
					class="bg-canvas-subtle/20 animate-fade-in-up flex min-h-[250px] scroll-mt-20 flex-col overflow-hidden rounded-[var(--radius-huge)] transition-all duration-300"
				>
					<div
						class="bg-canvas-subtle/50 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-5 py-2"
					>
						<div class="flex items-center gap-1">
							<h2 class="section-label mb-0 tracking-[0.4em] text-[var(--text-meta)]">
								{m.main_extracted_text()}
							</h2>
							<button
								onclick={reset}
								class="text-fg-secondary hover:text-fg-primary focus-ring flex h-11 w-11 items-center justify-center rounded-full transition-colors"
								title={m.main_clear_result()}
								aria-label={m.main_clear_result()}
							>
								<Icon name="close" size={18} />
							</button>
						</div>
						{#if processingTime > 0 && view === 'text' && resultText}
							<div
								class="text-fg-secondary flex flex-wrap items-center gap-x-2.5 font-mono text-[var(--text-meta)]"
							>
								<span>{wordCount(resultText)} {m.main_words()}</span>
								<span class="opacity-30">•</span>
								<span>{resultText.length} {m.main_chars()}</span>
								<span class="opacity-30">•</span>
								<span>{processingTime}ms</span>
							</div>
						{/if}
					</div>

					{#if warnings.length > 0 && !reading}
						<!-- Part of the input could not be read. Shown with the text rather
						     than instead of it: what was read is still worth having, but
						     not as if it were all there was. -->
						<div
							role="status"
							class="border-b border-amber-200/60 bg-amber-50/40 px-5 py-2 text-[var(--text-meta)] text-amber-800 dark:border-amber-900/40 dark:bg-amber-950/20 dark:text-amber-300"
						>
							{#each warnings as warning (warning)}
								<p>{warning}</p>
							{/each}
						</div>
					{/if}

					<!-- Keyboard-focusable so the scrolling text can be read without a mouse. -->
					<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
					<div
						class="relative max-h-[40vh] min-h-[170px] flex-1 overflow-y-auto p-5"
						tabindex="0"
						role="region"
						aria-label={m.main_extracted_text()}
					>
						{#if view === 'loading'}
							<div
								class="bg-canvas/80 absolute inset-0 z-10 flex items-center justify-center backdrop-blur-sm"
							>
								<div class="flex w-full max-w-xs flex-col items-center gap-3 px-4">
									<div
										class="border-primary-500 h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
									></div>
									{#if isPdf && totalPages > 0}
										<span class="text-fg-secondary text-center font-medium text-[var(--text-body)]">
											{m.main_processing_page({ current: currentPage, total: totalPages })}
										</span>
										<div class="bg-fg-muted/20 h-1.5 w-full overflow-hidden rounded-full">
											<div
												class="bg-fg-primary h-full transition-[width] duration-300"
												style="width: {percent(currentPage, totalPages)}%"
											></div>
										</div>
									{:else}
										<span
											class="text-fg-secondary animate-pulse font-medium text-[var(--text-body)]"
										>
											{m.main_scanning()}
										</span>
									{/if}
									<button type="button" class="btn-secondary min-h-11 px-6" onclick={reset}>
										{m.main_cancel()}
									</button>
								</div>
							</div>
							<div class="flex flex-col gap-4 opacity-40">
								<div class="shimmer h-3 w-full rounded-full"></div>
								<div class="shimmer h-3 w-11/12 rounded-full"></div>
								<div class="shimmer h-3 w-4/5 rounded-full"></div>
								<div class="shimmer h-3 w-full rounded-full"></div>
								<div class="shimmer h-3 w-3/4 rounded-full"></div>
							</div>
						{:else if view === 'error' && error}
							{@render errorNotice(error)}
						{:else if view === 'empty'}
							<div class="flex flex-col items-center gap-2 py-6 text-center">
								<p class="text-fg-primary font-medium text-[var(--text-body)]">
									{m.main_no_text()}
								</p>
								<p class="text-fg-secondary max-w-xs text-[13px]">{m.main_no_text_hint()}</p>
								<button type="button" class="btn-primary mt-2 min-h-11" onclick={reset}>
									<Icon name="add" size={16} class="mr-1.5" />
									{m.main_process_another()}
								</button>
							</div>
						{:else if view === 'text'}
							<div class="prose max-w-none dark:prose-invert">
								<p
									class="font-mon text-fg-primary leading-relaxed break-words whitespace-pre-wrap text-[var(--text-body)]"
								>
									{resultText}
								</p>
							</div>
						{/if}
					</div>

					{#if view === 'text'}
						<div class="bg-canvas-subtle/50 flex flex-col gap-2 px-4 py-3 sm:px-6">
							<div class="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
								<button
									class="btn-secondary min-h-11 text-center leading-tight"
									onclick={downloadText}
									aria-label={m.main_save_text()}
								>
									{m.main_save_text()}
								</button>
								<button
									bind:this={copyButton}
									class="btn-secondary min-h-11 text-center leading-tight"
									onclick={copyText}
									aria-label={m.main_copy_clipboard()}
								>
									{copied ? m.main_copied() : m.main_copy_clipboard()}
								</button>
								<button
									class="btn-primary col-span-2 min-h-11 text-center leading-tight"
									onclick={reset}
									aria-label={m.main_process_another()}
								>
									<Icon name="add" size={16} class="mr-1.5" />
									{m.main_process_another()}
								</button>
							</div>
							<div class="flex items-center sm:justify-end">
								<button
									class="text-fg-secondary hover:text-fg-primary focus-ring group flex min-h-11 items-center gap-2 px-1 text-[11px] font-bold tracking-widest uppercase transition-all"
									onclick={reportError}
									aria-label={m.nav_feedback()}
								>
									<Icon
										name="flag"
										size={16}
										class="opacity-60 transition-opacity group-hover:opacity-100"
									/>
									<span>{m.nav_feedback()}</span>
								</button>
							</div>
						</div>
					{/if}
				</div>
			</div>
		{/if}

		<HistorySection bind:this={historySection} category="ocr-scan" />
	</main>
</div>

<style>
	/* `@font-face` and `.font-mon` moved to src/app.css on 2026-08-26. Scoped here,
	   they applied only to this route, so the contribute textarea and the history
	   list asked for a font nothing had declared. */

	@keyframes pulse-subtle {
		0%,
		100% {
			opacity: 1;
			transform: scale(1);
		}
		50% {
			opacity: 0.4;
			transform: scale(0.9);
		}
	}

	:global(.animate-pulse-subtle) {
		animation: pulse-subtle 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
	}
</style>
