<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { m } from '$lib/paraglide/messages';
	import { feedbackStore } from '$lib/stores/feedback';
	import { get } from 'svelte/store';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { SEO, HistorySection, SuccessModal, ActionBox } from '$lib/components';
	import { saveRecord } from '$lib/storage/db';

	let loading = $state(false);
	let showSuccessModal = $state(false);
	let originalText = $state('');
	let correctedText = $state('');
	let previewUrl = $state<string | null>(null);
	let selectedType = $state('Spelling');
	let consent = $state(false);
	let sourceFile = $state<File | null>(null);
	let sourceFileInput: HTMLInputElement;

	let historySection: ReturnType<typeof HistorySection>;

	onMount(() => {
		const data = get(feedbackStore);
		if (data.text) {
			originalText = data.text;
			correctedText = data.text;
			previewUrl = data.previewUrl || null;
		}
	});

	function handleFileChange(e: Event) {
		const target = e.target as HTMLInputElement;
		if (target.files && target.files.length > 0) {
			sourceFile = target.files[0];
			// For preview if it's an image
			if (sourceFile.type.startsWith('image/')) {
				previewUrl = URL.createObjectURL(sourceFile);
			}
		}
	}

	async function handleSubmit() {
		if (!correctedText || !consent) return;

		loading = true;
		try {
			// 1. Data Contribution Model: Save to local IndexedDB for background sync
			// We no longer use mailto as the primary bridge; R2 Sync is now the official path.

			// 2. Local Logging: Save to history for user record
			const timestamp = Date.now();
			const fileToSave = sourceFile || new Blob([originalText], { type: 'text/plain' });
			const defaultFileName = `feedback-${timestamp}.txt`;

			await saveRecord(
				{
					fileName: sourceFile ? `Feedback: ${sourceFile.name}` : defaultFileName,
					fileType: sourceFile ? sourceFile.type : 'text/plain',
					fileData: fileToSave,
					text: `[${selectedType}] ${correctedText}`,
					processingTime: 0
				},

				'feedback',
				consent
			);

			showSuccessModal = true;

			// 3. Trigger immediate sync
			import('$lib/services/sync-service').then(({ syncService }) => {
				syncService.syncAll();
			});

			historySection?.refresh();
		} catch (e) {
			console.error('Failed to submit feedback:', e);
		} finally {
			loading = false;
		}
	}

	async function handleCancel() {
		await goto('/');
	}
</script>

<SEO
	title="Improve Accuracy - MonOCR Community Correction"
	description="Help improve our Mon language model by submitting corrections for OCR results."
/>

<div class="font-display flex flex-col">
	<main id="main-content" class="mx-auto w-full max-w-2xl px-6 py-12 md:py-20">
		<section class="mb-16 space-y-3">
			<h1 class="text-fg-primary text-3xl font-bold tracking-tight md:text-4xl">
				{m.nav_feedback()}
			</h1>
			<p class="text-fg-secondary max-w-lg leading-relaxed text-[var(--text-body)]">
				{m.docs_privacy_desc()}
			</p>
		</section>
		<!-- Original Source Selection -->
		<section class="mb-12">
			<h3 class="zen-label mb-6">{m.action_upload_source()}</h3>
			<ActionBox onclick={() => sourceFileInput.click()} label={m.action_upload_source()}>
				<input
					bind:this={sourceFileInput}
					type="file"
					class="hidden"
					accept="image/*,application/pdf"
					onchange={handleFileChange}
				/>
				<div class="flex items-center gap-3">
					<div class="bg-canvas-subtle flex h-8 w-8 items-center justify-center rounded-md">
						<span class="material-symbols-outlined text-fg-muted text-lg">upload_file</span>
					</div>
					<div class="text-left">
						<p class="text-fg-primary font-semibold text-[var(--text-secondary)]">
							{sourceFile ? sourceFile.name : m.action_upload_source()}
						</p>
						<p class="text-fg-muted tracking-wider text-[var(--text-meta)] uppercase">
							{m.action_helper_image_pdf()}
						</p>
					</div>
				</div>
			</ActionBox>
		</section>

		{#if originalText}
			<!-- Original Output Section -->
			<section class="mb-12">
				<h3 class="zen-label mb-6">Original Output</h3>
				<div class="border-border bg-canvas-subtle/30 overflow-hidden rounded-xl border">
					<div class="p-4">
						<div class="flex flex-col gap-4">
							{#if previewUrl}
								<div
									class="border-border bg-canvas-subtle relative h-[200px] w-full overflow-hidden rounded border"
								>
									<img
										src={previewUrl}
										alt="Source Scan"
										class="absolute inset-0 h-full w-full object-contain"
										onerror={() => (previewUrl = null)}
									/>
								</div>
							{/if}
							<p
								class="border-primary/40 bg-canvas-subtle/50 text-fg-secondary border-l-2 py-1.5 pl-4 leading-relaxed text-[var(--text-body)] italic"
							>
								"{originalText}"
							</p>
							<div class="text-fg-muted flex items-center gap-2 text-[var(--text-meta)]">
								<span class="material-symbols-outlined text-sm">info</span>
								<span>Report quality issues to help improve our model</span>
							</div>
						</div>
					</div>
				</div>
			</section>
		{/if}

		<!-- Corrected Text Section -->
		<section class="mb-12">
			<div class="mb-6 flex items-center justify-between">
				<h3 class="zen-label mb-0">Corrected Text</h3>
				<span class="text-fg-muted text-[8px] font-bold tracking-widest uppercase"
					>Human Verification</span
				>
			</div>
			<div class="flex flex-col gap-3">
				<label class="block">
					<textarea
						bind:value={correctedText}
						class="focus-ring border-border bg-canvas text-fg-primary placeholder:text-fg-muted/40 block w-full resize-y rounded-xl border px-5 py-4 leading-relaxed text-[var(--text-body)] transition-all duration-150 placeholder:text-[var(--text-meta)]"
						rows="5"
						placeholder="Corrected Mon script..."
					></textarea>
				</label>
			</div>
		</section>

		<!-- Error Categories -->
		<section class="mb-16">
			<h3 class="zen-label mb-6">Error Type</h3>
			<div class="flex flex-wrap gap-3">
				{#each ['Spelling', 'Layout', 'Formatting', 'Other'] as type (type)}
					<button
						onclick={() => (selectedType = type)}
						class="border-border hover:border-fg-secondary hover:bg-canvas-subtle focus-ring rounded-lg border px-5 py-2.5 text-[11px] font-bold tracking-widest uppercase transition-all {selectedType ===
						type
							? 'bg-primary border-primary text-white hover:brightness-110'
							: 'text-fg-muted'}"
					>
						{type}
					</button>
				{/each}
			</div>
		</section>

		<!-- Consent & Actions -->
		<section class="border-border mb-20 space-y-10 border-t pt-10">
			<div class="flex items-start gap-4">
				<div class="flex h-6 items-center">
					<input
						type="checkbox"
						id="consent"
						bind:checked={consent}
						class="text-primary focus:ring-primary border-border bg-canvas h-5 w-5 cursor-pointer rounded-sm"
					/>
				</div>
				<div class="space-y-1.5">
					<label
						class="text-fg-primary cursor-pointer font-bold text-[var(--text-body)]"
						for="consent">I want to help improve MonOCR</label
					>
					<p class="text-fg-secondary leading-relaxed text-[var(--text-meta)] opacity-70">
						Allow this correction to be used for future model training and verification. We respect
						your privacy according to our policies.
					</p>
				</div>
			</div>
			<div class="mx-auto flex w-full max-w-md flex-col gap-4">
				<button
					onclick={handleSubmit}
					disabled={!correctedText || !consent || loading}
					class="btn-primary w-full"
				>
					{loading ? 'Sharing...' : 'Share Correction'}
				</button>
				<button onclick={handleCancel} class="btn-secondary w-full"> Cancel Feedback </button>
			</div>
		</section>

		<HistorySection bind:this={historySection} category="feedback" title="Past Feedback" />
	</main>

	<SuccessModal
		isOpen={showSuccessModal}
		title="Feedback Received"
		message="Thanks for helping us improve MonOCR! Your correction has been saved."
		onClose={() => {
			showSuccessModal = false;
		}}
	/>
</div>
