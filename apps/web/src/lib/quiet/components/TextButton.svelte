<script lang="ts">
	/*
	 * The quiet action: a button that reads like the text link ("Load more", "Show all").
	 * No fill, no border, no chrome. A 44px row, left-aligned under the thing it acts on.
	 * Disabled drops the underline and goes to fg-secondary, still readable (7.4:1).
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';

	interface Props extends HTMLButtonAttributes {
		/** The label, verb first: "Load more", not "More". */
		children: Snippet;
		/** Sets aria-busy. Set it while the action runs, and change the label to match ("Loading"). */
		busy?: boolean;
	}

	const { children, busy = false, type = 'button', class: className, ...rest }: Props = $props();
</script>

<button
	{type}
	aria-busy={busy || undefined}
	class={[
		'inline-flex min-h-target cursor-pointer items-center type-label link focus-visible:rounded-sm disabled:cursor-not-allowed disabled:text-fg-secondary disabled:no-underline',
		className
	]}
	{...rest}>{@render children()}</button
>
