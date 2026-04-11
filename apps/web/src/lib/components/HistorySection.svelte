<script lang="ts">
	import { onMount } from 'svelte';
	import { fade, fly } from 'svelte/transition';
	import { db, getRecords, deleteRecord, clearHistory, type OCRRecord } from '$lib/storage/db';
	import { syncService, syncEvents } from '$lib/services/sync-service';
	import { ConfirmationModal } from './index';

	interface Props {
		category?: string;
		title?: string;
	}

	let { category = 'ocr-scan', title = 'Recent Scans' }: Props = $props();

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

	function closeRecordView() {
		modalOpen = false;
		// Cleanup after transition
		setTimeout(() => {
			if (!modalOpen) {
				selectedRecord = null;
				if (modalPreviewUrl) {
					URL.revokeObjectURL(modalPreviewUrl);
					modalPreviewUrl = null;
				}
			}
		}, 300);
	}
</script>

{#if historyRecords.length > 0}
	<section class="mt-8 space-y-4" in:fade={{ duration: 150 }}>
		<div class="flex items-center justify-between">
			<h2 class="text-fg-primary font-bold tracking-tight text-[var(--text-title)]">
				{title}
				{#if loading}
					<span class="text-fg-muted ml-2 text-xs font-normal opacity-50">Updating...</span>
				{/if}
			</h2>
			<button
				onclick={() => {
					showClearModal = true;
				}}
				class="text-fg-secondary text-[11px] font-bold tracking-wider uppercase transition-colors hover:text-red-500"
			>
				Clear All
			</button>
		</div>

		<div class="space-y-2.5">
			{#each historyRecords as record (record.id)}
				<div
					class="border-border/60 bg-canvas-subtle hover:bg-canvas hover:border-border group relative flex items-center justify-between overflow-hidden rounded-[var(--radius-lg)] border px-4 py-3 transition-all duration-200 select-none hover:shadow-sm"
				>
					<div class="flex min-w-0 items-center gap-4">
						<!-- Icon Wrapper (Cleaner, no box) -->
						<div
							class="flex h-8 w-8 items-center justify-center opacity-40 transition-opacity group-hover:opacity-100"
						>
							<span class="material-symbols-outlined text-fg-primary text-[22px]">
								{record.fileType.includes('pdf') ? 'description' : 'image'}
							</span>
						</div>

						<div class="flex min-w-0 flex-col gap-0.5">
							<div class="flex items-center gap-2.5">
								<span class="text-fg-primary truncate text-[13px] font-semibold tracking-tight"
									>{record.fileName}</span
								>
								<span class="text-fg-muted text-[10px] whitespace-nowrap opacity-40"
									>{new Date(record.timestamp).toLocaleDateString()}</span
								>
							</div>

							<div class="flex items-center gap-2">
								{#if record.isSynced}
									<div class="flex items-center gap-1.5 rounded-full bg-emerald-500/5 px-2 py-0.5">
										<span class="material-symbols-outlined text-[13px] text-emerald-500/80"
											>check_circle</span
										>
										<span class="text-[9px] font-bold tracking-widest text-emerald-500/80 uppercase"
											>Synced</span
										>
									</div>
								{:else if record.syncError}
									<div class="flex items-center gap-1.5 rounded-full bg-red-500/5 px-2 py-0.5">
										<span class="material-symbols-outlined text-[13px] text-red-500/80">error</span>
										<span class="text-[9px] font-bold tracking-widest text-red-500/80 uppercase"
											>Failed</span
										>
									</div>
									<button
										onclick={async (e) => {
											e.stopPropagation();
											await db?.records.update(record.id, { syncAttempts: 0 });
											await syncService.syncAll();
											await loadHistory();
										}}
										class="text-primary hover:text-primary/80 ml-1 text-[9px] font-bold tracking-tighter uppercase underline underline-offset-2 opacity-60 transition-opacity hover:opacity-100"
									>
										Retry
									</button>
								{:else}
									<div class="bg-fg-muted/5 flex items-center gap-1.5 rounded-full px-2 py-0.5">
										<span
											class="material-symbols-outlined animate-spin-slow text-fg-muted/40 text-[13px]"
											>progress_activity</span
										>
										<span
											class="text-fg-muted/50 text-[9px] font-semibold tracking-widest uppercase"
											>Pending</span
										>
									</div>
								{/if}
							</div>
						</div>
					</div>

					<div class="flex items-center gap-1">
						<button
							onclick={() => viewRecord(record)}
							class="text-fg-muted hover:bg-fg-muted/5 hover:text-fg-primary flex h-9 w-9 items-center justify-center rounded-full opacity-40 transition-all group-hover:opacity-100"
							aria-label="View record"
						>
							<span class="material-symbols-outlined text-[20px]">visibility</span>
						</button>
						<button
							onclick={async (e) => {
								e.stopPropagation();
								await deleteRecord(record.id);
								await loadHistory();
							}}
							class="text-fg-muted flex h-9 w-9 items-center justify-center rounded-full opacity-40 transition-all group-hover:opacity-100 hover:bg-red-500/5 hover:text-red-500"
							aria-label="Delete record"
						>
							<span class="material-symbols-outlined text-[20px]">delete_outline</span>
						</button>
					</div>
				</div>
			{/each}
		</div>
	</section>
{:else if loading}
	<div class="mt-8 space-y-4">
		<div class="shimmer h-6 w-32 rounded-md opacity-20"></div>
		<div class="space-y-2">
			{#each [1, 2, 3] as i (i)}
				<div
					class="border-border bg-canvas-subtle flex h-[52px] items-center gap-3 rounded-[var(--radius-md)] border px-3 py-1.5"
				>
					<div class="shimmer h-4 w-4 rounded-full opacity-10"></div>
					<div class="flex flex-1 flex-col gap-2">
						<div class="shimmer h-3 w-1/3 rounded-full opacity-10"></div>
						<div class="shimmer h-2 w-1/4 rounded-full opacity-5"></div>
					</div>
				</div>
			{/each}
		</div>
	</div>
{:else if !loading}
	<!-- Reclaimed vertical space (collapsed empty state) -->
{/if}

<!-- Modal View -->
{#if modalOpen && selectedRecord}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
		in:fade={{ duration: 200 }}
		out:fade={{ duration: 200 }}
	>
		<div
			class="bg-canvas/60 absolute inset-0 backdrop-blur-md"
			onclick={closeRecordView}
			role="button"
			tabindex="-1"
			onkeydown={(e) => e.key === 'Escape' && closeRecordView()}
		></div>

		<div
			class="bg-canvas border-border shadow-huge relative flex h-full max-h-[82vh] w-full max-w-4xl flex-col overflow-hidden rounded-[var(--radius-huge)] border"
			in:fly={{
				y: 20,
				duration: 400,
				easing: (t) => 1 - Math.pow(1 - t, 4) /* cubic-out */,
				delay: 50
			}}
		>
			<div
				class="border-border bg-canvas-subtle flex items-center justify-between border-b px-5 py-3"
			>
				<div class="flex items-center gap-2">
					<span class="material-symbols-outlined text-fg-muted text-[18px]"
						>{selectedRecord.fileType.includes('pdf') ? 'picture_as_pdf' : 'image'}</span
					>
					<h2 class="text-fg-primary text-[13px] font-bold tracking-tight">
						{selectedRecord.fileName}
					</h2>
				</div>
				<button
					onclick={closeRecordView}
					class="text-fg-secondary hover:text-fg-primary flex h-7 w-7 items-center justify-center rounded-full transition-colors"
					aria-label="Close view"
				>
					<span class="material-symbols-outlined text-[18px]">close</span>
				</button>
			</div>

			<!-- Modal Body -->
			<div class="flex flex-1 flex-col overflow-hidden lg:flex-row">
				<!-- Preview Panel -->
				<div
					class="border-border bg-canvas-subtle flex items-center justify-center border-b p-4 lg:w-1/2 lg:border-r lg:border-b-0"
				>
					<div class="relative flex h-full w-full items-center justify-center overflow-hidden">
						<img
							src={modalPreviewUrl}
							alt="History Record Preview"
							class="h-auto max-h-full w-auto max-w-full rounded-[var(--radius-md)] object-contain shadow-sm"
						/>
					</div>
				</div>

				<!-- Content Panel -->
				<div class="bg-canvas flex flex-1 flex-col overflow-hidden p-5 lg:w-1/2">
					<div class="border-border/40 mb-4 flex items-center justify-between border-b pb-2">
						<span class="text-fg-secondary text-[10px] font-bold tracking-widest uppercase"
							>Content</span
						>
						<div class="text-fg-muted font-mono text-[10px]">
							{selectedRecord.processingTime}ms
						</div>
					</div>
					<div class="flex-1 overflow-y-auto">
						<p
							class="font-mon text-fg-primary selection:bg-primary/20 leading-relaxed break-words whitespace-pre-wrap text-[var(--text-body)]"
						>
							{selectedRecord.text}
						</p>
					</div>
				</div>
			</div>

			<!-- Modal Footer -->
			<div class="border-border bg-canvas-subtle flex justify-end gap-2 border-t px-5 py-3">
				<button
					class="btn-secondary px-4 py-1.5 text-[11px]"
					onclick={() => {
						navigator.clipboard.writeText(selectedRecord?.text || '');
						copied = true;
						setTimeout(() => (copied = false), 2000);
					}}
				>
					{copied ? 'Copied' : 'Copy Text'}
				</button>
				<button class="btn-primary px-4 py-1.5 text-[11px]" onclick={closeRecordView}>
					Done
				</button>
			</div>
		</div>
	</div>
{/if}

<ConfirmationModal
	isOpen={showClearModal}
	title="Clear History"
	message="Are you sure you want to permanently delete all scans in this category? This action cannot be undone."
	confirmLabel="Delete All"
	cancelLabel="Cancel"
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
	.font-mon {
		font-family: 'PyidaungSu', 'Myanmar Text', sans-serif;
	}
</style>
