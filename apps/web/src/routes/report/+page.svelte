<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { m } from '$lib/paraglide/messages';
	import { feedbackStore } from '$lib/stores/feedback';
	import { get } from 'svelte/store';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { SEO, HistorySection, ActionBox } from '$lib/components';
	import {
		Button,
		TextButton,
		Textarea,
		Field,
		Checkbox,
		SegmentedControl,
		ErrorSummary,
		Status,
		Note
	} from '$lib/quiet';
	import type { ErrorEntry } from '$lib/quiet/components/ErrorSummary.svelte';
	import { saveRecord } from '$lib/storage/db';

	let loading = $state(false);
	let resultMessage = $state('');
	let saveError = $state('');
	let errors = $state<ErrorEntry[]>([]);
	let originalText = $state('');
	let correctedText = $state('');
	let previewUrl = $state<string | null>(null);
	let selectedType = $state('Spelling');
	// The value is what is saved; the label is what is shown.
	const errorTypes = $derived([
		{ value: 'Spelling', label: m.report_type_spelling() },
		{ value: 'Layout', label: m.report_type_layout() },
		{ value: 'Formatting', label: m.report_type_formatting() },
		{ value: 'Other', label: m.report_type_other() }
	]);
	let consent = $state(false);
	let sourceFile = $state<File | null>(null);

	let historySection: ReturnType<typeof HistorySection>;

	onMount(() => {
		const data = get(feedbackStore);
		if (data.text) {
			originalText = data.text;
			correctedText = data.text;
			previewUrl = data.previewUrl || null;
		}
	});

	function handleFileChange(file: File | null) {
		if (file) {
			// For preview if it's an image
			if (file.type.startsWith('image/')) {
				previewUrl = URL.createObjectURL(file);
			}
		} else {
			// Removed: fall back to the preview that came with the OCR result, if any.
			previewUrl = get(feedbackStore).previewUrl || null;
		}
	}

	async function handleSubmit() {
		resultMessage = '';
		saveError = '';
		const found: ErrorEntry[] = [];
		if (!correctedText) found.push({ id: 'corrected-text', text: m.report_error_text() });
		if (!consent) found.push({ id: 'consent', text: m.report_error_consent() });
		errors = found;
		if (found.length === 1) document.getElementById(found[0].id)?.focus();
		if (found.length > 0) return;

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

			resultMessage = m.report_received_title();

			// 3. Trigger immediate sync
			import('$lib/services/sync-service').then(({ syncService }) => {
				syncService.syncAll();
			});

			historySection?.refresh();
		} catch (e) {
			console.error('Failed to submit feedback:', e);
			saveError = m.error_save_failed();
		} finally {
			loading = false;
		}
	}

	const textError = $derived(
		correctedText ? undefined : errors.find((e) => e.id === 'corrected-text')?.text
	);
	const consentError = $derived(consent ? undefined : errors.find((e) => e.id === 'consent')?.text);

	async function handleCancel() {
		await goto('/');
	}
</script>

<SEO
	title="Improve Accuracy - MonOCR Community Correction"
	description="Help improve our Mon language model by submitting corrections for OCR results."
/>

<main id="main-content" class="max-w-column pt-page-top pb-page-bottom sm:pt-page-top-wide">
	<div class="space-y-3">
		<h1 class="type-h1">{m.nav_feedback()}</h1>
		<p class="max-w-measure type-body">{m.report_sharing_desc()}</p>
	</div>

	<form
		class="mt-8 space-y-8"
		novalidate
		onsubmit={(e) => {
			e.preventDefault();
			handleSubmit();
		}}
	>
		{#if errors.length > 1}
			<ErrorSummary {errors} title={m.form_error_title()} headingLevel={2} />
		{/if}

		<ActionBox
			id="source-file"
			label={m.action_upload_source()}
			hint={m.action_helper_image_pdf()}
			accept="image/*,application/pdf"
			bind:file={sourceFile}
			onfile={handleFileChange}
		/>

		{#if originalText}
			<div class="max-w-measure space-y-3">
				<h2 class="type-label">{m.report_original_output()}</h2>
				{#if previewUrl}
					<img
						src={previewUrl}
						alt={m.report_source_alt()}
						class="hairline-box max-h-52 w-full rounded-md object-contain"
						onerror={() => (previewUrl = null)}
					/>
				{/if}
				<p class="font-mon hairline-box type-body rounded-md p-4" lang="mnw">{originalText}</p>
				<p class="type-small">{m.report_quality_hint()}</p>
			</div>
		{/if}

		<Field
			id="corrected-text"
			label={m.report_corrected_text()}
			error={textError}
			errorLabel={m.error_label()}
		>
			{#snippet children(control)}
				<Textarea
					{...control}
					bind:value={correctedText}
					rows={5}
					class="font-mon"
					lang="mnw"
					placeholder={m.report_corrected_placeholder()}
				/>
			{/snippet}
		</Field>

		<div class="max-w-measure space-y-2">
			<p class="type-label">{m.report_error_type()}</p>
			<SegmentedControl
				label={m.report_error_type()}
				options={errorTypes}
				bind:value={selectedType}
			/>
		</div>

		<Checkbox
			id="consent"
			label={m.report_consent_label()}
			hint={m.report_consent_desc()}
			error={consentError}
			errorLabel={m.error_label()}
			bind:checked={consent}
		/>

		<div class="flex flex-wrap items-center gap-x-6 gap-y-2">
			<Button type="submit" variant="primary" disabled={loading}>
				{loading ? m.report_sharing() : m.report_share()}
			</Button>
			<TextButton onclick={handleCancel}>{m.modal_cancel()}</TextButton>
		</div>

		<Status message={resultMessage || saveError} visible={false} />
		{#if saveError}
			<Note label={m.error_label()}>{saveError}</Note>
		{/if}
		{#if resultMessage}
			<Note label={m.report_received_title()}>{m.report_received_msg()}</Note>
		{/if}
	</form>

	<div class="mt-16">
		<HistorySection
			bind:this={historySection}
			category="feedback"
			title={m.report_past_feedback()}
		/>
	</div>
</main>
