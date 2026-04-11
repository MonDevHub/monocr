<script lang="ts">
	import { SEO, HistorySection, SuccessModal } from '$lib/components';
	import { saveRecord } from '$lib/storage/db';

	let transcription = $state('');
	let fileInput: HTMLInputElement;
	let historySection: ReturnType<typeof HistorySection>;
	let loading = $state(false);
	let showSuccessModal = $state(false);
	let sourceFile = $state<File | null>(null);

	function handleFileChange(e: Event) {
		const target = e.target as HTMLInputElement;
		if (target.files && target.files.length > 0) {
			sourceFile = target.files[0];
		}
	}

	async function handleSubmit() {
		if (!transcription && !sourceFile) return;

		loading = true;
		try {
			await saveRecord(
				{
					fileName: sourceFile ? `Contribute: ${sourceFile.name}` : 'Mon Text Contribution',
					fileType: sourceFile?.type || 'text/plain',
					fileData: sourceFile || new Blob([transcription], { type: 'text/plain' }),
					text: transcription,
					processingTime: 0
				},
				'contribution',
				true
			);

			// Success
			showSuccessModal = true;
			historySection?.refresh();
		} catch (e) {
			console.error('Failed to submit contribution:', e);
		} finally {
			loading = false;
		}
	}
</script>

<SEO
	title="Contribute - MonOCR Help Preserve Heritage"
	description="Contribute Mon documents and transcriptions to help improve OCR accuracy and preserve the Mon language."
/>

<div class="font-display flex flex-col">
	<!-- Header removed to use global Header -->

	<main id="main-content" class="flex-1 overflow-y-auto pb-6">
		<div class="mx-auto max-w-2xl px-6 py-0.5">
			<header class="mb-4 text-center">
				<h2 class="text-fg-primary mb-1 font-semibold tracking-tight text-[var(--text-section)]">
					Preserve Our Heritage
				</h2>
				<p class="text-fg-muted font-medium tracking-wide text-[var(--text-meta)]">
					Contribute Mon documents to improve recognition accuracy.
				</p>
			</header>

			<!-- Section 1: Upload -->
			<section class="mb-3">
				<div
					class="hover:bg-canvas-subtle group border-border relative flex cursor-pointer flex-col items-center justify-center rounded-[var(--radius-lg)] border py-4 transition-all duration-150"
					onclick={() => fileInput.click()}
					onkeydown={(e) => e.key === 'Enter' && fileInput.click()}
					role="button"
					tabindex="0"
				>
					<input
						type="file"
						bind:this={fileInput}
						onchange={handleFileChange}
						class="hidden"
						accept=".pdf,.docx,.txt,image/*"
					/>
					<div class="flex flex-col items-center space-y-2 text-center">
						<div
							class="bg-primary/5 group-hover:bg-primary/10 flex h-10 w-10 items-center justify-center rounded-md transition-all duration-150"
						>
							<span
								class="material-symbols-outlined text-primary text-lg font-light transition-transform duration-150 group-hover:scale-110"
							>
								upload_file
							</span>
						</div>
						<div class="space-y-1">
							<h3 class="text-fg-primary font-semibold tracking-tight text-[var(--text-secondary)]">
								{sourceFile ? sourceFile.name : 'Upload Mon Documents'}
							</h3>
						</div>
					</div>
				</div>
			</section>

			<!-- Divider -->
			<div class="relative mx-auto mb-1 flex max-w-xs items-center py-1 opacity-20">
				<div class="border-border flex-grow border-t"></div>
				<span class="text-fg-muted mx-3 flex-shrink text-[9px] font-bold tracking-[0.2em] uppercase"
					>OR</span
				>
				<div class="border-border flex-grow border-t"></div>
			</div>

			<!-- Section 2: Textarea -->
			<section class="mb-3">
				<h3 class="section-label mb-2">Type Mon Script</h3>
				<div class="relative">
					<textarea
						bind:value={transcription}
						class="focus:ring-primary/5 focus:border-primary/20 font-mon border-border bg-canvas placeholder:text-fg-muted/30 w-full resize-y rounded-md border p-3 leading-relaxed text-[var(--text-body)] transition-all duration-150 placeholder:text-[var(--text-meta)] focus:ring-4"
						placeholder="Example: မန်ဒိုင် (Type or paste the Mon script here)..."
					></textarea>
				</div>
			</section>

			<!-- Submit Action -->
			<div class="mx-auto mb-4 w-full max-w-md">
				<button
					onclick={handleSubmit}
					disabled={(!transcription && !sourceFile) || loading}
					class="btn-primary w-full shadow-sm"
				>
					{loading ? 'Submitting...' : 'Submit Contribution'}
				</button>
			</div>

			<HistorySection bind:this={historySection} category="contribution" title="Contributions" />
		</div>
	</main>

	<SuccessModal
		isOpen={showSuccessModal}
		title="Contribution Received"
		message="Thank you for contributing to the Mon language community! Your submission has been saved."
		onClose={() => {
			showSuccessModal = false;
		}}
	/>
</div>

<style>
	/* Any Mon-specific typography needs for the textarea */
</style>
