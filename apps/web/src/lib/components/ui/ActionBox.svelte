<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		onclick?: () => void;
		label?: string;
		children: Snippet;
	}

	const { onclick, label = '', children }: Props = $props();

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			onclick?.();
		}
	}
</script>

<div
	class="bg-canvas-subtle/30 hover:bg-canvas-subtle/70 focus-ring group relative flex cursor-pointer flex-col items-center justify-center rounded-xl py-12 transition-all duration-300 ease-out active:scale-[0.98]"
	{onclick}
	onkeydown={handleKeydown}
	role="button"
	tabindex="0"
	aria-label={label}
>
	{@render children()}
</div>
