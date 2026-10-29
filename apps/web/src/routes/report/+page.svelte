<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { m } from '$lib/paraglide/messages';
	import { feedbackStore } from '$lib/stores/feedback';
	import { get } from 'svelte/store';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { SEO, HistorySection, SuccessModal, ActionBox, Icon } from '$lib/components';
	import { saveRecord } from '$lib/storage/db';

	let loading = $state(false);
	let showSuccessModal = $state(false);
	let originalText = $state('');
	let correctedText = $state('');
	let previewUrl = $state<string | null>(null);
	let selectedType = $state('Spelling');
	// The value is what is saved; the label is what is shown.
	const errorTypes = [
		{ type: 'Spelling', label: () => m.report_type_spelling() },
		{ type: 'Layout', label: () => m.report_type_layout() },
		{ type: 'Formatting', label: () => m.report_type_formatting() },
		{ type: 'Other', label: () => m.report_type_other() }
	];
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
				{m.report_sharing_desc()}
			</p>
		</section>
		<!-- Original Source Selection -->
		<section class="mb-12">
			<h2 class="zen-label mb-6">{m.action_upload_source()}</h2>
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
						<Icon name="upload_file" size={18} class="text-fg-muted" />
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
				<h2 class="zen-label mb-6">{m.report_original_output()}</h2>
				<div class="bg-canvas-subtle/20 overflow-hidden rounded-[var(--radius-huge)]">
					<div class="p-4">
						<div class="flex flex-col gap-4">
							{#if previewUrl}
								<div class="bg-canvas-subtle/40 relative h-[200px] w-full overflow-hidden rounded">
									<img
										src={previewUrl}
										alt={m.report_source_alt()}
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
								<Icon name="info" size={14} />
								<span>{m.report_quality_hint()}</span>
							</div>
						</div>
					</div>
				</div>
			</section>
		{/if}

		<!-- Corrected Text Section -->
		<section class="mb-12">
			<div class="mb-6 flex items-center justify-between">
				<h2 class="zen-label mb-0">{m.report_corrected_text()}</h2>
				<span class="text-fg-muted text-[11px] font-bold tracking-widest uppercase"
					>{m.report_human_verification()}</span
				>
			</div>
			<div class="flex flex-col gap-3">
				<label class="block">
					<textarea
						bind:value={correctedText}
						class="focus-ring bg-canvas-subtle/30 hover:bg-canvas-subtle/50 focus:bg-canvas font-mon text-fg-primary placeholder:text-fg-muted block w-full resize-y rounded-[var(--radius-huge)] px-5 py-4 leading-relaxed text-[var(--text-body)] transition-all duration-150 outline-none placeholder:text-[var(--text-meta)]"
						rows="5"
						placeholder={m.report_corrected_placeholder()}
					></textarea>
				</label>
			</div>
		</section>

		<!-- Error Categories -->
		<section class="mb-16">
			<h2 class="zen-label mb-6">{m.report_error_type()}</h2>
			<div class="flex flex-wrap gap-3">
				{#each errorTypes as { type, label } (type)}
					<button
						onclick={() => (selectedType = type)}
						class="hover:bg-canvas-subtle/60 focus-ring rounded-[var(--radius-lg)] px-5 py-2.5 text-[11px] font-bold tracking-widest uppercase transition-all {selectedType ===
						type
							? 'bg-primary-action text-fg-on-primary shadow-sm hover:brightness-110'
							: 'bg-canvas-subtle/30 text-fg-muted'}"
					>
						{label()}
					</button>
				{/each}
			</div>
		</section>

		<!-- Consent & Actions -->
		<section class="mb-20 space-y-6 pt-6">
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
						for="consent">{m.report_consent_label()}</label
					>
					<p class="text-fg-secondary leading-relaxed text-[var(--text-meta)]">
						{m.report_consent_desc()}
					</p>
				</div>
			</div>
			<div class="flex items-center gap-4">
				<button
					onclick={handleSubmit}
					disabled={!correctedText || !consent || loading}
					class="btn-primary min-w-[140px]"
				>
					{loading ? m.report_sharing() : m.report_share()}
				</button>
				<button
					onclick={handleCancel}
					class="text-fg-secondary hover:text-fg-primary focus-ring rounded-sm px-2 py-1 text-[11px] font-bold tracking-wider uppercase transition-colors"
				>
					{m.modal_cancel()}
				</button>
			</div>
		</section>

		<HistorySection
			bind:this={historySection}
			category="feedback"
			title={m.report_past_feedback()}
		/>
	</main>

	<SuccessModal
		isOpen={showSuccessModal}
		title={m.report_received_title()}
		message={m.report_received_msg()}
		onClose={() => {
			showSuccessModal = false;
		}}
	/>
</div>
