<script lang="ts">
	import { onMount } from 'svelte';
	import { db, getRecords, deleteRecord, clearHistory, type OCRRecord } from '$lib/storage/db';
	import { syncService, syncEvents } from '$lib/services/sync-service';
	import { ConfirmationModal, Badge } from './index';
	import { Button, Dialog, List, ListRow, Section, Status, TextButton } from '$lib/quiet';
	import { syncBadge } from '$lib/sync-badge';
	import { m } from '$lib/paraglide/messages';

	interface Props {
		category?: string;
		title?: string;
	}

	let { category = 'ocr-scan', title = m.history_recent_scans() }: Props = $props();

	let historyRecords = $state<OCRRecord[]>([]);
	let selectedRecord = $state<OCRRecord | null>(null);
	let modalOpen = $state(false);
	let modalPreviewUrl = $state<string | null>(null);
	let loading = $state(false);
	let copied = $state(false);
	let showClearModal = $state(false);

	async function loadHistory() {
		try {
			loading = true;
			historyRecords = await getRecords(category);
		} catch (e) {
			console.error(`Failed to load history for ${category}:`, e);
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		loadHistory();

		// Reactive sync: Refresh whenever background synchronization completes
		const listener = () => loadHistory();
		syncEvents.addEventListener('synced', listener);

		return () => syncEvents.removeEventListener('synced', listener);
	});

	// Expose a method to refresh from outside if needed
	export function refresh() {
		loadHistory();
	}

	function viewRecord(record: OCRRecord) {
		selectedRecord = record;
		if (modalPreviewUrl) URL.revokeObjectURL(modalPreviewUrl);
		modalPreviewUrl = URL.createObjectURL(record.fileData);
		modalOpen = true;
	}

	// The Dialog has closed (Done, Close, Escape): let go of the record and its preview URL.
	function closeRecordView() {
		selectedRecord = null;
		if (modalPreviewUrl) {
			URL.revokeObjectURL(modalPreviewUrl);
			modalPreviewUrl = null;
		}
	}

	async function retrySync(record: OCRRecord) {
		await db?.records.update(record.id, { syncAttempts: 0 });
		await syncService.syncAll();
		await loadHistory();
	}

	async function removeRecord(record: OCRRecord) {
		await deleteRecord(record.id);
		await loadHistory();
	}

	function copyRecord() {
		navigator.clipboard.writeText(selectedRecord?.text || '');
		copied = true;
		setTimeout(() => (copied = false), 2000);
	}
</script>

<!-- Present before its text changes, for screen readers. -->
<Status message={loading ? m.history_updating() : copied ? m.main_copied() : ''} visible={false} />

{#if historyRecords.length > 0}
	<Section {title}>
		<List>
			{#each historyRecords as record (record.id)}
				<ListRow title={record.fileName}>
					{#snippet secondary()}
						<span class="flex flex-wrap items-center gap-x-6 gap-y-1">
							<span class="type-meta">{new Date(record.timestamp).toLocaleDateString()}</span>
							<!-- A badge only where there is consent to sync: a local scan has no sync state. -->
							{#if syncBadge(record) === 'synced'}
								<Badge label={m.history_synced()} />
							{:else if syncBadge(record) === 'failed'}
								<Badge label={m.history_failed()} />
								<TextButton onclick={() => retrySync(record)}>{m.history_retry()}</TextButton>
							{:else if syncBadge(record) === 'pending'}
								<Badge label={m.history_pending()} />
							{/if}
							<TextButton onclick={() => viewRecord(record)}>
								{m.history_view_record()}<span class="sr-only">: {record.fileName}</span>
							</TextButton>
							<TextButton onclick={() => removeRecord(record)}>
								{m.history_delete_record()}<span class="sr-only">: {record.fileName}</span>
							</TextButton>
						</span>
					{/snippet}
				</ListRow>
			{/each}
		</List>
		<TextButton class="mt-4" onclick={() => (showClearModal = true)}>
			{m.history_clear_all()}
		</TextButton>
	</Section>
{/if}

<!-- A saved scan, read-only, in a dialog. -->
{#if selectedRecord}
	<Dialog
		bind:open={modalOpen}
		title={selectedRecord.fileName}
		closeLabel={m.history_close_view()}
		onclose={closeRecordView}
	>
		<div class="space-y-6">
			{#if modalPreviewUrl}
				<img
					src={modalPreviewUrl}
					alt={m.main_preview_alt()}
					class="hairline-box border-border/50 max-h-64 max-w-full rounded-md object-contain"
				/>
			{/if}
			<div class="space-y-2">
				<div class="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
					<h3 class="type-label">{m.history_content_label()}</h3>
					<p class="type-meta">{selectedRecord.processingTime}ms</p>
				</div>
				<p
					lang="mnw"
					class="recorded font-mon type-body text-fg-primary wrap-anywhere whitespace-pre-wrap"
				>
					{selectedRecord.text}
				</p>
			</div>
		</div>
		{#snippet actions()}
			<Button onclick={copyRecord}>
				{copied ? m.main_copied() : m.history_copy_text()}
			</Button>
			<Button variant="primary" onclick={() => (modalOpen = false)}>{m.history_done()}</Button>
		{/snippet}
	</Dialog>
{/if}

<ConfirmationModal
	isOpen={showClearModal}
	title={m.history_clear_all()}
	message={m.modal_confirm_delete_msg()}
	confirmLabel={m.history_clear_all()}
	cancelLabel={m.modal_cancel()}
	onConfirm={async () => {
		showClearModal = false;
		await clearHistory(category);
		await loadHistory();
	}}
	onCancel={() => {
		showClearModal = false;
	}}
/>

<style>
	/* Recognised Mon text keeps its own spacing: see routes/+page.svelte. `.font-mon` is in
	   src/app.css. */
	.recorded {
		letter-spacing: normal;
	}
</style>
