<script lang="ts">
	import { m } from '$lib/paraglide/messages';
	import { SEO, HistorySection, SuccessModal, ActionBox, Icon } from '$lib/components';
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
	<main id="main-content" class="flex-1 overflow-y-auto">
		<div class="mx-auto max-w-2xl px-6 py-12 md:py-20">
			<section class="mb-16 space-y-3">
				<h1 class="text-fg-primary text-3xl font-bold tracking-tight md:text-4xl">
					{m.nav_contribute()}
				</h1>
				<p class="text-fg-secondary max-w-lg leading-relaxed text-[var(--text-body)]">
					{m.about_effort()}
				</p>
			</section>
			<!-- Section 1: Upload -->
			<section class="mb-12">
				<ActionBox onclick={() => fileInput.click()} label={m.action_upload_docs()}>
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
							<Icon
								name="upload_file"
								size={18}
								class="text-primary transition-transform duration-150 group-hover:scale-110"
							/>
						</div>
						<div class="space-y-1">
							<p class="text-fg-primary font-semibold tracking-tight text-[var(--text-secondary)]">
								{sourceFile ? sourceFile.name : m.action_upload_docs()}
							</p>
						</div>
					</div>
				</ActionBox>
			</section>

			<!-- Divider -->
			<div class="relative mx-auto mb-10 flex max-w-xs items-center py-4">
				<div class="border-border flex-grow border-t opacity-30"></div>
				<span
					class="text-fg-muted mx-4 flex-shrink text-[10px] font-bold tracking-[0.3em] uppercase"
					>{m.contribute_or()}</span
				>
				<div class="border-border flex-grow border-t opacity-30"></div>
			</div>

			<!-- Section 2: Textarea -->
			<section class="mb-16">
				<h2 class="zen-label mb-6">{m.contribute_type_script()}</h2>
				<div class="relative">
					<textarea
						bind:value={transcription}
						class="focus-ring font-mon border-border bg-canvas placeholder:text-fg-muted w-full resize-y rounded-xl border p-5 leading-relaxed text-[var(--text-body)] transition-all duration-150 placeholder:text-[var(--text-meta)]"
						rows="6"
						placeholder={m.contribute_placeholder()}
					></textarea>
				</div>
			</section>

			<!-- Submit Action -->
			<div class="mb-20">
				<button
					onclick={handleSubmit}
					disabled={(!transcription && !sourceFile) || loading}
					class="btn-primary min-w-[160px]"
				>
					{loading ? m.contribute_submitting() : m.contribute_submit()}
				</button>
			</div>

			<HistorySection
				bind:this={historySection}
				category="contribution"
				title={m.contribute_history()}
			/>
		</div>
	</main>

	<SuccessModal
		isOpen={showSuccessModal}
		title={m.contribute_received_title()}
		message={m.contribute_received_msg()}
		onClose={() => {
			showSuccessModal = false;
		}}
	/>
</div>

<style>
	/* Any Mon-specific typography needs for the textarea */
</style>
