<script lang="ts">
	import { fade, fly } from 'svelte/transition';

	interface Props {
		isOpen: boolean;
		title?: string;
		message?: string;
		onClose: () => void;
	}

	let {
		isOpen,
		title = 'Success!',
		message = 'Operation completed successfully.',
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
			class="bg-canvas border-border shadow-huge relative flex w-full max-w-[320px] flex-col overflow-hidden rounded-[var(--radius-huge)] border p-5 text-center"
			in:fly={{ y: 15, duration: 250, delay: 50 }}
		>
			<div class="mb-2 flex flex-col items-center">
				<div class="bg-primary/5 mb-3 flex h-11 w-11 items-center justify-center rounded-full">
					<span class="material-symbols-outlined text-primary text-[22px]">check_circle</span>
				</div>
				<h2 class="text-fg-primary text-base font-semibold tracking-tight">
					{title}
				</h2>
			</div>

			<p class="text-fg-secondary mb-5 text-[13px] leading-relaxed">
				{message}
			</p>

			<button class="btn-primary w-full py-2 text-[11px]" onclick={onClose}> Done </button>
		</div>
	</div>
{/if}
