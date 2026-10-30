<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { onMount, onDestroy, tick } from 'svelte';
	import { initializeEngine, recognize, cleanup, onModelProgress } from '$lib/monocr';
	import { CONFIG } from '$lib/config';
	import { renderPdfPage, loadPdf } from '$lib/utils/pdf-util';
	import { feedbackStore } from '$lib/stores/feedback';
	import { CropRotate, HistorySection } from '$lib/components';
	import {
		Button,
		Disclosure,
		EmptyState,
		Link,
		Note,
		Progress,
		Status,
		TextButton
	} from '$lib/quiet';
	import { getRecord, saveRecord } from '$lib/storage/db';
	import { combinePages, isTimeout, pageWarnings, readPdfPages } from '$lib/reading';
	import { createRunToken } from '$lib/run-token';
	import {
		checkFile,
		formatMegabytes,
		intakeAction,
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
	// The rotate-and-crop panel is open. Images only: a PDF page is not adjustable.
	let adjusting = $state(false);
	let fileInput = $state<HTMLInputElement>();
	let resultCard = $state<HTMLElement>();
	let focusResult = false;

	// One token per read. Clearing or choosing another file invalidates it, and a
	// read checks it before every write: see run-token.ts for the failure this stops.
	const runs = createRunToken();

	const view = $derived(resultView({ loading: reading, error: error !== null, text: resultText }));
	const status = $derived(statusKind({ engine: engineState, reading, pdf: isPdf, view }));
	const dropDisabled = $derived(engineState === 'failed');
	const canAdjust = $derived(file !== null && !isPdf && !reading && !adjusting);

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
		adjusting = false;
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
		adjusting = false;
		revokePreview();
		previewFailed = false;
		resultText = null;
		warnings = [];
		error = null;
		currentPage = 0;
		totalPages = 0;
		remember(null);
		// The control that had focus (Cancel, Read another file) is gone: land on the next step.
		void tick().then(() => document.getElementById('choose-file')?.focus());
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
	// finishes. `preventScroll`, because focusing it scrolled the result's heading
	// under the fixed header; the result is then brought into view on its own terms.
	$effect(() => {
		// Read first, so the effect re-runs when the view changes: `focusResult` is not state,
		// and a short-circuit on it would leave nothing tracked.
		const showing = view === 'text';
		const copyButton = resultCard?.querySelector<HTMLButtonElement>('[data-copy]');
		if (focusResult && showing && copyButton) {
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

	// The engine's own words, shown on the page: Loading, then ready.
	const engineText = $derived(
		engineState === 'loading'
			? m.main_loading_model()
			: engineState === 'ready'
				? m.main_engine_ready()
				: ''
	);

	const statusText = $derived.by(() => {
		switch (status) {
			// 'loading_model' and 'ready' are the words of the visible engine Status.
			// 'engine_failed' is announced by the error notice itself (role="alert").
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
	<div role="alert" class="space-y-4">
		<Note label={m.error_label()}>
			<p class="wrap-anywhere">{info.headline}</p>
		</Note>
		{#if info.details}
			<Disclosure title={m.error_details()} class="max-w-measure">
				<p class="type-small-wrapped pb-4 wrap-anywhere whitespace-pre-wrap">{info.details}</p>
			</Disclosure>
		{/if}
		{#if retryTarget(err) || err.kind === 'file_large'}
			<div class="flex flex-wrap items-center gap-x-6 gap-y-2">
				{#if retryTarget(err)}
					<Button onclick={retry}>{m.history_retry()}</Button>
				{/if}
				{#if err.kind === 'file_large'}
					<Link href="/docs#cli-reference" standalone>{m.main_learn_more()}</Link>
				{/if}
			</div>
		{/if}
	</div>
{/snippet}

<main
	id="main-content"
	class="max-w-column pt-page-top pb-page-bottom sm:pt-page-top-wide mx-auto w-full space-y-10"
>
	<!-- Polite status lines, present before their text changes. This one carries the read
	     itself (scanning, page progress, done); the engine and Copy have their own. -->
	<Status message={statusText} visible={false} />
	<Status message={copied ? m.main_copied() : ''} visible={false} />

	<div class="space-y-6">
		<h1 class="type-h1">{m.main_title()}</h1>
		<p class="max-w-measure type-body">{m.main_hero()}</p>
	</div>

	<!-- The engine: the word Loading, a determinate bar while the model arrives, then ready. -->
	<div class="max-w-measure space-y-4">
		<Status message={engineText} />
		{#if engineState === 'loading'}
			{#if modelTotal > 0}
				<!-- Determinate only when content-length was sent; without a total the word
				     Loading says all there is to say. -->
				<Progress
					id="model-progress"
					value={modelReceived / 1_000_000}
					max={modelTotal / 1_000_000}
					label="{formatMegabytes(modelReceived)} / {formatMegabytes(modelTotal)} MB"
				/>
			{/if}
			<p class="type-meta">{m.main_model_info()}</p>
		{/if}
	</div>

	{#if !file}
		<div class="space-y-6">
			{#if error}
				{@render errorNotice(error)}
			{/if}

			<!-- A drop target. The control inside it is the keyboard-operable one. -->
			<div
				role="group"
				aria-label={m.main_dropzone_title()}
				class="hairline-box border-border rounded-lg bg-transparent p-6 transition-colors duration-150 {dragging
					? 'dropzone-active'
					: ''}"
				ondragover={handleDragOver}
				ondragleave={handleDragLeave}
				ondrop={handleDrop}
			>
				<input
					type="file"
					bind:this={fileInput}
					onchange={handleFileSelect}
					accept="image/*,application/pdf"
					class="hidden"
					disabled={dropDisabled}
					tabindex="-1"
					aria-hidden="true"
				/>
				<div class="flex flex-wrap items-center gap-x-6 gap-y-3">
					<Button id="choose-file" disabled={dropDisabled} onclick={() => fileInput?.click()}>
						{m.main_choose_file()}
					</Button>
					<p class="type-small">{m.main_dropzone_title()}</p>
				</div>
				{#if dropDisabled}
					<p class="max-w-measure type-small mt-4">{m.main_dropzone_blocked()}</p>
				{:else if queuedFile}
					<p class="max-w-measure type-small text-fg-primary mt-4 wrap-anywhere">
						{m.main_queued({ name: queuedFile.name })}
					</p>
				{/if}
			</div>
		</div>
	{:else}
		<div class="space-y-8">
			<!-- The image being read, or the rotate-and-crop panel in its place -->
			{#if adjusting && file && !isPdf}
				<CropRotate
					{file}
					oncancel={() => {
						adjusting = false;
						// The Rotate and crop button that opened the panel comes back: focus it.
						void tick().then(() => document.querySelector<HTMLElement>('[data-adjust]')?.focus());
					}}
					onapply={(adjusted) => {
						adjusting = false;
						startRead(adjusted);
					}}
				/>
			{:else if previewUrl && !previewFailed}
				<img
					src={previewUrl}
					alt={m.main_preview_alt()}
					class="hairline-box border-border/50 max-h-64 max-w-full rounded-md object-contain"
					onerror={() => (previewFailed = true)}
				/>
			{/if}

			<div bind:this={resultCard} class="space-y-6">
				<div class="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
					<h2 class="type-h2">{m.main_extracted_text()}</h2>
					{#if processingTime > 0 && view === 'text' && resultText}
						<p class="type-meta">
							{wordCount(resultText)}
							{m.main_words()} · {resultText.length}
							{m.main_chars()} · {processingTime}ms
						</p>
					{/if}
				</div>

				{#if warnings.length > 0 && !reading}
					<!-- Part of the input could not be read. Shown with the text rather
					     than instead of it: what was read is still worth having, but
					     not as if it were all there was. -->
					<div role="status">
						<Note label={m.note_label()}>
							{#each warnings as warning (warning)}
								<p class="wrap-anywhere">{warning}</p>
							{/each}
						</Note>
					</div>
				{/if}

				{#if view === 'loading'}
					<div class="max-w-measure space-y-4">
						{#if isPdf && totalPages > 0}
							<Progress
								id="read-progress"
								value={currentPage}
								max={totalPages}
								label={m.main_processing_page({ current: currentPage, total: totalPages })}
							/>
						{:else}
							<p class="type-small">{m.main_scanning()}</p>
						{/if}
						<Button onclick={reset}>{m.main_cancel()}</Button>
					</div>
				{:else if view === 'error' && error}
					{@render errorNotice(error)}
					<TextButton onclick={reset}>{m.main_process_another()}</TextButton>
				{:else if view === 'empty'}
					<EmptyState title={m.main_no_text()} level={3} text={m.main_no_text_hint()}>
						{#snippet action()}
							<div class="flex flex-wrap items-center gap-2">
								{#if canAdjust}
									<Button data-adjust onclick={() => (adjusting = true)}>{m.adjust_open()}</Button>
								{/if}
								<Button variant="primary" onclick={reset}>{m.main_process_another()}</Button>
							</div>
						{/snippet}
					</EmptyState>
				{:else if view === 'text'}
					<!-- Keyboard-focusable so the scrolling text can be read without a mouse. -->
					<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
					<div
						class="hairline-box border-border/50 max-h-96 overflow-y-auto rounded-lg p-6"
						tabindex="0"
						role="region"
						aria-label={m.main_extracted_text()}
					>
						<p
							lang="mnw"
							class="recognised font-mon type-body text-fg-primary wrap-anywhere whitespace-pre-wrap"
						>
							{resultText}
						</p>
					</div>

					<div class="space-y-2">
						<div class="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
							<Button variant="primary" class="col-span-2 sm:col-auto" data-copy onclick={copyText}>
								{copied ? m.main_copied() : m.main_copy_clipboard()}
							</Button>
							<Button onclick={downloadText}>{m.main_save_text()}</Button>
							{#if canAdjust}
								<Button data-adjust onclick={() => (adjusting = true)}>{m.adjust_open()}</Button>
							{/if}
							<Button class="col-span-2 sm:col-auto" onclick={reset}>
								{m.main_process_another()}
							</Button>
						</div>
						<TextButton onclick={reportError}>{m.main_report_problem()}</TextButton>
					</div>
				{/if}
			</div>
		</div>
	{/if}

	<HistorySection bind:this={historySection} category="ocr-scan" />
</main>

<style>
	/* Recognised Mon text keeps its own spacing: the type role tracks Latin text slightly
	   tight, and stacked consonants and medials must not move. `.font-mon` (app.css) is
	   the family. */
	.recognised {
		letter-spacing: normal;
	}
</style>
