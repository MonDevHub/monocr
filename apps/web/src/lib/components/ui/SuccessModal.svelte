<script lang="ts">
	import { fade, fly } from 'svelte/transition';
	import { focusTrap } from '$lib/actions/focus-trap';
	import { m } from '$lib/paraglide/messages';

	interface Props {
		isOpen: boolean;
		title?: string;
		message?: string;
		onClose: () => void;
	}

	let {
		isOpen,
		title = m.modal_success_title(),
		message = m.modal_success_msg(),
		onClose
	}: Props = $props();
</script>

{#if isOpen}
	<div
		class="fixed inset-0 z-[60] flex items-center justify-center p-4 sm:p-6"
		in:fade={{ duration: 150 }}
		out:fade={{ duration: 100 }}
	>
		<div
			class="bg-canvas/60 absolute inset-0 backdrop-blur-md"
			onclick={onClose}
			role="button"
			tabindex="-1"
			onkeydown={(e) => e.key === 'Escape' && onClose()}
		></div>

		<div
			use:focusTrap
			class="bg-canvas border-border shadow-huge focus-ring relative flex w-full max-w-[320px] flex-col overflow-hidden rounded-[var(--radius-huge)] border p-8 text-center"
			in:fly={{ y: 15, duration: 250, delay: 50 }}
		>
			<div class="mb-4 flex flex-col items-center">
				<div class="bg-primary/5 mb-4 flex h-12 w-12 items-center justify-center rounded-full">
					<span class="material-symbols-outlined text-primary text-[24px]">check_circle</span>
				</div>
				<h2 class="text-fg-primary text-lg font-bold tracking-tight">
					{title}
				</h2>
			</div>

			<p class="text-fg-secondary mb-8 leading-relaxed text-[var(--text-body)]">
				{message}
			</p>

			<button class="btn-primary w-full" onclick={onClose}>
				{m.history_done()}
			</button>
		</div>
	</div>
{/if}
