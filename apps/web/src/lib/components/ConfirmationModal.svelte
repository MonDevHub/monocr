<script lang="ts">
	import { fade, fly } from 'svelte/transition';
	import { focusTrap } from '$lib/actions/focus-trap';
	import { m } from '$lib/paraglide/messages';

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
		confirmLabel = m.modal_confirm_title(),
		cancelLabel = m.modal_cancel(),
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
			use:focusTrap
			class="bg-canvas border-border shadow-huge focus-ring relative w-full max-w-sm overflow-hidden rounded-[var(--radius-huge)] border"
			in:fly={{ y: 20, duration: 400, easing: (t) => 1 - Math.pow(1 - t, 4) /* cubic-out */ }}
		>
			<div class="px-8 pt-10 pb-8 text-center">
				<div
					class="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-full bg-red-500/10"
				>
					<span class="material-symbols-outlined text-[28px] text-red-500">warning</span>
				</div>

				<h2 class="text-fg-primary mb-3 text-xl font-bold tracking-tight">
					{title}
				</h2>
				<p class="text-fg-secondary leading-relaxed text-[var(--text-body)] opacity-80">
					{message}
				</p>
			</div>

			<div class="border-border bg-canvas-subtle/50 flex flex-col gap-3 border-t p-6 sm:flex-row">
				<button class="btn-secondary flex-1" onclick={onCancel}>
					{cancelLabel}
				</button>
				<button class="btn-primary flex-1 border-red-500 bg-red-500" onclick={onConfirm}>
					{confirmLabel}
				</button>
			</div>
		</div>
	</div>
{/if}
