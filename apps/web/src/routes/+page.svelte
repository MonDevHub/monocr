<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { onMount, onDestroy } from 'svelte';
	import { fade, fly } from 'svelte/transition';
	import { initializeEngine, recognize, cleanup } from '$lib/monocr';
	import { CONFIG } from '$lib/config';
	import { renderPdfPage, loadPdf } from '$lib/utils/pdf-util';
	import { feedbackStore } from '$lib/stores/feedback';
	import { HistorySection } from '$lib/components';
	import { saveRecord } from '$lib/storage/db';
	import * as m from '$lib/paraglide/messages';
	import { goto } from '$app/navigation';

	let engineReady = $state(false);
	let loading = $state(false);
	let error = $state<string | null>(null);

	let file = $state<File | null>(null);
	let previewUrl = $state<string | null>(null);
	let resultText = $state<string | null>(null);
	let processingTime = $state(0);
	let copied = $state(false);
	let isPdf = $state(false);
	let totalPages = $state(0);
	let fileInput = $state<HTMLInputElement>();

	// History reference for refreshing
	let historySection: ReturnType<typeof HistorySection>;

	async function loadHistory() {
		historySection?.refresh();
	}

	function handleDragOver(e: DragEvent) {
		e.preventDefault();
		// Drag feedback removed for minimalist simplicity
	}

	function handleDragLeave() {
		// Drag feedback removed for minimalist simplicity
	}

	function handleDrop(e: DragEvent) {
		e.preventDefault();
		const droppedFile = e.dataTransfer?.files[0];
		if (droppedFile) {
			handleFile({ detail: droppedFile } as CustomEvent<File>);
		}
	}

	function handleFileSelect(e: Event) {
		const selectedFile = (e.target as HTMLInputElement).files?.[0];
		if (selectedFile) {
			handleFile({ detail: selectedFile } as CustomEvent<File>);
		}
	}

	onMount(async () => {
		try {
			loading = true;
			await Promise.all([initializeEngine(), loadHistory()]);
			engineReady = true;
		} catch (e: unknown) {
			const msg = e instanceof Error ? e.message : String(e);
			error = `Failed to load OCR engine: ${msg}`;
		} finally {
			loading = false;
		}
	});

	onDestroy(() => {
		cleanup();
		// Only revoke if we aren't heading to the report page
		if (previewUrl && !window.location.pathname.includes('/report')) {
			URL.revokeObjectURL(previewUrl);
		}
	});

	async function handleFile(e: CustomEvent<File>) {
		if (!engineReady) return;

		const newFile = e.detail;
		if (!newFile) return;

		// Validation
		if (newFile.size > CONFIG.UI.MAX_IMAGE_SIZE_MB * 1024 * 1024) {
			error = `File too large (Max ${CONFIG.UI.MAX_IMAGE_SIZE_MB}MB). Use CLI tools or desktop version for bigger file support.`;
			return;
		}
		if (!(CONFIG.UI.ALLOWED_FILE_TYPES as readonly string[]).includes(newFile.type)) {
			error = 'Invalid file type. Allowed: JPG, PNG, WEBP, PDF';
			return;
		}

		file = newFile;
		isPdf = file.type === 'application/pdf';

		// Reset
		resultText = null;
		error = null;
		if (previewUrl) {
			try {
				URL.revokeObjectURL(previewUrl);
			} catch {
				// Ignore cleanup errors
			}
		}

		if (isPdf) {
			processPdf();
		} else {
			previewUrl = URL.createObjectURL(file);
			processImage();
		}
	}

	async function processPdf() {
		if (!file) return;

		try {
			loading = true;
			resultText = '';
			const buffer = await file.arrayBuffer();

			// Load the PDF once to avoid detaching the buffer multiple times
			const pdf = await loadPdf(buffer);
			totalPages = pdf.numPages;

			const allTexts: string[] = [];
			const startTime = performance.now();
			let firstPageBlob: Blob | null = null;

			for (let i = 1; i <= totalPages; i++) {
				// 1. Render page using the loaded pdf proxy
				const { imageBytes } = await renderPdfPage(pdf, i);

				// 2. Set preview for first page only
				if (i === 1) {
					if (previewUrl) URL.revokeObjectURL(previewUrl);
					firstPageBlob = new Blob([imageBytes.buffer as ArrayBuffer], { type: 'image/jpeg' });
					previewUrl = URL.createObjectURL(firstPageBlob);
				}

				// 3. Run OCR
				const text = await recognize(imageBytes);
				if (text.trim()) {
					allTexts.push(`--- Page ${i} ---\n${text}`);
				}
			}

			resultText = allTexts.join('\n\n');
			const endTime = performance.now();
			processingTime = Math.round(endTime - startTime);

			// Save to history (using first page as representative image)
			if (firstPageBlob) {
				await saveRecord(
					{
						fileName: file.name,
						fileType: 'application/pdf',
						fileData: firstPageBlob,
						text: resultText,
						processingTime
					},
					'ocr-scan',
					false
				);

				await loadHistory();
			}
		} catch (err: unknown) {
			console.error('PDF Processing Error:', err);
			const msg = err instanceof Error ? err.message : 'Please check your file and try again.';
			error = `PDF Processing Failed: ${msg}`;
		} finally {
			loading = false;
		}
	}

	async function processImage() {
		if (!file) return;

		try {
			loading = true;
			const start = performance.now();

			const buffer = await file.arrayBuffer();
			const bytes = new Uint8Array(buffer);

			// Run OCR
			const text = await recognize(bytes);
			resultText = text;

			const end = performance.now();
			processingTime = Math.round(end - start);

			// Save to history
			await saveRecord(
				{
					fileName: file.name,
					fileType: file.type,
					fileData: file,
					text,
					processingTime
				},
				'ocr-scan',
				false
			);

			await loadHistory();
		} catch (e: unknown) {
			console.error(e);
			const msg = e instanceof Error ? e.message : String(e);
			error = `OCR Failed: ${msg}`;
		} finally {
			loading = false;
		}
	}

	function reset() {
		file = null;
		if (previewUrl) {
			try {
				URL.revokeObjectURL(previewUrl);
			} catch {
				// Ignore cleanup errors
			}
		}
		previewUrl = null;
		resultText = null;
		error = null;
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

	async function reportError() {
		feedbackStore.set({
			text: resultText || '',
			previewUrl
		});
		await goto('/report');
	}

	// Smart Focus for accessibility and efficiency
	let copyButton = $state<HTMLButtonElement>();
	$effect(() => {
		if (resultText && !loading && copyButton) {
			copyButton.focus();
		}
	});
</script>

<div class="mx-auto w-full max-w-3xl">
	<!-- Header -->
	<header class="mb-6 space-y-2 text-center md:mb-10 md:space-y-4">
		<p
			class="text-fg-primary mx-auto mt-2 max-w-xl text-[18px] leading-snug font-medium tracking-tight text-balance sm:text-[20px] md:text-[var(--text-title)]"
		>
			Digitize Mon texts effortlessly. High-precision OCR running right in your browser.
		</p>

		<div class="flex justify-center pt-2">
			{#if !engineReady && !error}
				<div
					class="border-border bg-canvas-subtle text-fg-muted inline-flex items-center gap-2 rounded-md border px-3 py-1 text-[11px] font-semibold tracking-wider uppercase"
				>
					<div class="bg-fg-muted h-1 w-1 animate-pulse rounded-full"></div>
					{m.main_loading_model()}
				</div>
			{:else if error}
				<div
					class="inline-flex items-center gap-2 rounded-md border border-red-100 bg-red-50/30 px-3 py-1 text-[11px] font-semibold tracking-wider text-red-600/80 uppercase dark:border-red-900/30 dark:bg-red-950/20 dark:text-red-400"
				>
					<div class="h-1 w-1 rounded-full bg-current"></div>
					{error}
					{#if error.includes('CLI')}
						<a
							href="/docs#cli-reference"
							class="ml-2 border-b border-red-200/50 pb-px font-bold lowercase transition-colors hover:text-red-700 dark:hover:text-red-300"
						>
							Learn more
						</a>
					{/if}
				</div>
			{:else}
				<div
					class="border-border bg-canvas-subtle text-fg-muted inline-flex items-center gap-1.5 rounded-md border px-3 py-1 text-[11px] font-semibold tracking-wider uppercase"
					in:fade
				>
					<div
						class="animate-pulse-subtle h-1 w-1 rounded-full bg-emerald-500/60 dark:bg-emerald-500/40"
					></div>
					System Ready
				</div>
			{/if}
		</div>
	</header>

	<!-- Main Content -->
	<main id="main-content" class="space-y-12 sm:space-y-16">
		{#if !file}
			<div in:fade={{ duration: 150 }}>
				<div
					class="border-border bg-canvas hover:bg-canvas-subtle group hover:border-primary/30 relative flex flex-col items-center justify-center rounded-[var(--radius-huge)] border-2 border-dashed py-12 transition-all duration-300 ease-in-out"
					ondragover={handleDragOver}
					ondragleave={handleDragLeave}
					ondrop={handleDrop}
					onclick={() => fileInput?.click()}
					role="button"
					tabindex="0"
					onkeydown={(e) => e.key === 'Enter' && fileInput?.click()}
				>
					<input
						type="file"
						bind:this={fileInput}
						onchange={handleFileSelect}
						accept="image/*,application/pdf"
						class="hidden"
					/>
					<div
						class="bg-primary-action/0 group-hover:bg-primary-action/[0.02] pointer-events-none absolute inset-0 rounded-[var(--radius-huge)] transition-colors"
					></div>

					<div class="relative flex flex-col items-center space-y-4 text-center">
						<div
							class="bg-canvas-subtle border-border group-hover:border-primary/20 flex h-16 w-16 items-center justify-center rounded-2xl border transition-all duration-300 group-hover:scale-110 group-hover:shadow-sm"
						>
							<span
								class="material-symbols-outlined text-fg-muted group-hover:text-primary text-3xl font-light transition-colors duration-300"
							>
								upload_file
							</span>
						</div>

						<div class="space-y-1.5">
							<h3 class="text-fg-primary text-base font-semibold tracking-tight">
								Drop an image or PDF here
							</h3>
							<p class="text-fg-secondary text-sm opacity-60">
								Or click to browse from your device
							</p>
						</div>
					</div>
				</div>
			</div>
		{:else}
			<div class="mx-auto flex w-full max-w-2xl flex-col gap-6" in:fly={{ y: 20, duration: 400 }}>
				<!-- Image Preview -->
				<div
					class="border-border bg-canvas-subtle relative flex min-h-[120px] items-center justify-center overflow-hidden rounded-lg border p-3 shadow-sm"
				>
					<img src={previewUrl} alt="Preview" class="h-auto max-h-[25vh] w-full object-contain" />
				</div>

				<!-- Result -->
				<div
					class="border-border bg-canvas animate-fade-in-up ring-border/20 flex min-h-[250px] flex-col overflow-hidden rounded-xl border shadow-sm ring-1 transition-all duration-300"
				>
					<div
						class="border-border bg-canvas-subtle flex items-center justify-between border-b px-5 py-2.5"
					>
						<div class="flex items-center gap-3">
							<h2 class="section-label mb-0 tracking-[0.4em] text-[var(--text-meta)]">
								Extracted Text
							</h2>
							<button
								onclick={reset}
								class="text-fg-secondary hover:text-fg-primary flex items-center justify-center rounded-full p-1 transition-colors"
								title="Clear result"
								aria-label="Clear result"
							>
								<span class="material-symbols-outlined text-[18px]">close</span>
							</button>
						</div>
						{#if processingTime > 0 && !loading && resultText}
							<div
								class="text-fg-secondary flex items-center gap-2.5 font-mono text-[var(--text-meta)] opacity-80"
							>
								<span
									>{resultText
										.trim()
										.split(/\s+/)
										.filter((w) => w.length > 0).length} w</span
								>
								<span class="opacity-30">•</span>
								<span>{resultText.length} ch</span>
								<span class="opacity-30">•</span>
								<span>{processingTime}ms</span>
							</div>
						{/if}
					</div>

					<div class="relative max-h-[40vh] flex-1 overflow-y-auto p-5">
						{#if loading}
							<div
								class="bg-canvas/80 absolute inset-0 z-10 flex items-center justify-center backdrop-blur-sm"
							>
								<div class="flex flex-col items-center gap-3">
									<div
										class="border-primary-500 h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
									></div>
									<span class="text-fg-secondary animate-pulse font-medium text-[var(--text-body)]">
										{m.main_scanning()}
									</span>
								</div>
							</div>
						{/if}

						{#if resultText}
							<div class="prose max-w-none dark:prose-invert">
								<p
									class="font-mon text-fg-primary leading-relaxed break-words whitespace-pre-wrap text-[var(--text-body)]"
								>
									{resultText}
								</p>
							</div>
						{:else if loading}
							<div class="flex flex-col gap-4 opacity-40">
								<div class="shimmer h-3 w-full rounded-full"></div>
								<div class="shimmer h-3 w-11/12 rounded-full"></div>
								<div class="shimmer h-3 w-4/5 rounded-full"></div>
								<div class="shimmer h-3 w-full rounded-full"></div>
								<div class="shimmer h-3 w-3/4 rounded-full"></div>
							</div>
						{/if}
					</div>

					{#if resultText}
						<div
							class="border-border bg-canvas-subtle flex items-center justify-between border-t px-6 py-2.5"
						>
							<div class="flex items-center gap-3">
								<button
									class="btn-secondary px-3 py-1"
									onclick={downloadText}
									aria-label="Save extracted text as a file"
								>
									{m.main_save_text()}
								</button>
								<button
									bind:this={copyButton}
									class="btn-secondary px-3 py-1"
									onclick={() => {
										navigator.clipboard.writeText(resultText || '');
										copied = true;
										setTimeout(() => (copied = false), 2000);
									}}
									aria-label="Copy extracted text to clipboard"
								>
									{copied ? 'Copied' : 'Copy'}
								</button>
								<button
									class="btn-primary px-3 py-1"
									onclick={reset}
									aria-label="Process another image or PDF"
								>
									<span class="material-symbols-outlined mr-1.5 text-[16px]">add</span>
									{m.main_process_another()}
								</button>
							</div>
							<div class="flex items-center">
								<button
									class="text-fg-secondary hover:text-primary group flex items-center gap-2 text-[10px] font-bold tracking-widest uppercase transition-all"
									onclick={reportError}
									aria-label="Report Error or feedback for this result"
								>
									<span
										class="material-symbols-outlined text-[16px] opacity-40 group-hover:opacity-100"
										>flag</span
									>
									<span>{m.nav_feedback()}</span>
								</button>
							</div>
						</div>
					{/if}
				</div>
			</div>
		{/if}

		<HistorySection bind:this={historySection} category="ocr-scan" title="Recent Scans" />
	</main>
</div>

<style>
	/* Add any custom font imports here if needed for Mon language */
	@font-face {
		font-family: 'PyidaungSu';
		src:
			local('PyidaungSu'),
			url('/fonts/PyidaungSu/Pyidaungsu-Regular.ttf') format('truetype');
		font-display: swap;
	}
	.font-mon {
		font-family: 'PyidaungSu', 'Myanmar Text', sans-serif;
	}

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
