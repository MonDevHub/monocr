<script lang="ts">
	import { m } from '$lib/paraglide/messages';
	import { SEO, HistorySection, ActionBox } from '$lib/components';
	import { Button, Textarea, Field, Status, Note } from '$lib/quiet';
	import { saveRecord } from '$lib/storage/db';

	let transcription = $state('');
	let historySection: ReturnType<typeof HistorySection>;
	let loading = $state(false);
	let resultMessage = $state('');
	let saveError = $state('');
	let submitted = $state(false);
	let sourceFile = $state<File | null>(null);

	const emptyError = $derived(
		submitted && !transcription && !sourceFile ? m.contribute_error_empty() : undefined
	);

	async function handleSubmit() {
		resultMessage = '';
		saveError = '';
		submitted = true;
		if (!transcription && !sourceFile) {
			document.getElementById('transcription')?.focus();
			return;
		}

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
			resultMessage = m.contribute_received_title();
			historySection?.refresh();
		} catch (e) {
			console.error('Failed to submit contribution:', e);
			saveError = m.error_save_failed();
		} finally {
			loading = false;
		}
	}
</script>

<SEO
	title="Contribute - MonOCR Help Preserve Heritage"
	description="Contribute Mon documents and transcriptions to help improve OCR accuracy and preserve the Mon language."
/>

<main id="main-content" class="max-w-column pt-page-top pb-page-bottom sm:pt-page-top-wide">
	<div class="space-y-3">
		<h1 class="type-h1">{m.nav_contribute()}</h1>
		<p class="max-w-measure type-body">{m.about_effort()}</p>
	</div>

	<form
		class="mt-8 space-y-8"
		novalidate
		onsubmit={(e) => {
			e.preventDefault();
			handleSubmit();
		}}
	>
		<ActionBox
			id="source-file"
			label={m.action_upload_docs()}
			accept=".pdf,.docx,.txt,image/*"
			bind:file={sourceFile}
		/>

		<div class="max-w-measure flex items-center gap-4" role="separator">
			<span class="hairline-t border-border/50 flex-1"></span>
			<span class="type-small">{m.contribute_or()}</span>
			<span class="hairline-t border-border/50 flex-1"></span>
		</div>

		<Field
			id="transcription"
			label={m.contribute_type_script()}
			error={emptyError}
			errorLabel={m.error_label()}
		>
			{#snippet children(control)}
				<Textarea
					{...control}
					bind:value={transcription}
					rows={6}
					class="font-mon"
					lang="mnw"
					placeholder={m.contribute_placeholder()}
				/>
			{/snippet}
		</Field>

		<Button type="submit" variant="primary" disabled={loading}>
			{loading ? m.contribute_submitting() : m.contribute_submit()}
		</Button>

		<Status message={resultMessage || saveError} visible={false} />
		{#if saveError}
			<Note label={m.error_label()}>{saveError}</Note>
		{/if}
		{#if resultMessage}
			<Note label={m.contribute_received_title()}>{m.contribute_received_msg()}</Note>
		{/if}
	</form>

	<div class="mt-16">
		<HistorySection
			bind:this={historySection}
			category="contribution"
			title={m.contribute_history()}
		/>
	</div>
</main>
