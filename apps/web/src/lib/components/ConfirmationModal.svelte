<script lang="ts">
	import { fade, fly } from 'svelte/transition';
	import { focusTrap } from '$lib/actions/focus-trap';
	import { m } from '$lib/paraglide/messages';
	import { Icon } from './index';

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
		<!-- Backdrop: mouse-only click-to-close. Escape is handled by focusTrap below,
		     once focus has moved inside -- a listener here would never see it. -->
		<div
			class="bg-canvas/60 absolute inset-0 backdrop-blur-md"
			onclick={onCancel}
			aria-hidden="true"
		></div>

		<!-- Modal Container -->
		<div
			use:focusTrap={{ onEscape: onCancel }}
			role="dialog"
			aria-modal="true"
			aria-labelledby="confirmation-modal-title"
			class="bg-canvas border-border focus-ring relative w-full max-w-sm overflow-hidden rounded-[var(--radius-huge)] border shadow-xl"
			in:fly={{ y: 20, duration: 400, easing: (t) => 1 - Math.pow(1 - t, 4) /* cubic-out */ }}
		>
			<div class="px-8 pt-10 pb-8 text-center">
				<div
					class="mx-auto mb-6 flex h-14 w-14 items-center justify-center rounded-full bg-red-500/10"
				>
					<Icon name="warning" size={28} class="text-red-500" />
				</div>

				<h2
					id="confirmation-modal-title"
					class="text-fg-primary mb-3 text-xl font-bold tracking-tight"
				>
					{title}
				</h2>
				<p class="text-fg-secondary leading-relaxed text-[var(--text-body)]">
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
