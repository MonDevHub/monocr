<script lang="ts">
	import { fade, fly } from 'svelte/transition';

	interface Props {
		isOpen: boolean;
		title: string;
		message: string;
		confirmLabel?: string;
		cancelLabel?: string;
		onConfirm: () => void;
		onCancel: () => void;
	}

	let {
		isOpen,
		title,
		message,
		confirmLabel = 'Confirm',
		cancelLabel = 'Cancel',
		onConfirm,
		onCancel
	}: Props = $props();
</script>

{#if isOpen}
	<div
		class="fixed inset-0 z-[100] flex items-center justify-center p-4 sm:p-6"
		in:fade={{ duration: 200 }}
		out:fade={{ duration: 150 }}
	>
		<!-- Backdrop -->
		<div
			class="bg-canvas/60 absolute inset-0 backdrop-blur-md"
			onclick={onCancel}
			onkeydown={(e) => e.key === 'Escape' && onCancel()}
			role="button"
			tabindex="-1"
		></div>

		<!-- Modal Container -->
		<div
			class="bg-canvas border-border shadow-huge relative w-full max-w-sm overflow-hidden rounded-[var(--radius-huge)] border"
			in:fly={{ y: 20, duration: 400, easing: (t) => 1 - Math.pow(1 - t, 4) /* cubic-out */ }}
		>
			<div class="p-6 text-center">
				<div
					class="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-500/10"
				>
					<span class="material-symbols-outlined text-red-500">warning</span>
				</div>

				<h2 class="text-fg-primary mb-2 text-lg font-bold tracking-tight">
					{title}
				</h2>
				<p class="text-fg-secondary text-sm leading-relaxed opacity-80">
					{message}
				</p>
			</div>

			<div class="border-border bg-canvas-subtle/50 flex flex-col gap-2 border-t p-4 sm:flex-row">
				<button class="btn-secondary flex-1 py-1.5 text-xs" onclick={onCancel}>
					{cancelLabel}
				</button>
				<button
					class="btn-primary flex-1 border-red-500 bg-red-500 py-1.5 text-xs"
					onclick={onConfirm}
				>
					{confirmLabel}
				</button>
			</div>
		</div>
	</div>
{/if}
