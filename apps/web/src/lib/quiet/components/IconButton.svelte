<script lang="ts" module>
	/** The button and icon-wrapper classes, shared with ThemeToggle so the two cannot drift. */
	export const ICON_BUTTON_CLASS =
		'group flex min-h-target min-w-target cursor-pointer touch-manipulation items-center justify-center rounded-md p-2 text-fg-secondary transition-colors duration-150 hover:bg-canvas-subtle hover:text-fg-primary active:bg-canvas-subtle active:text-fg-primary';
	export const ICON_PRESS_CLASS =
		'inline-flex transition-transform duration-150 ease-out motion-safe:group-active:scale-97';
</script>

<script lang="ts">
	/*
	 * A 44 x 44 control holding one icon: theme toggle, menu, close. No fill at rest;
	 * canvas-subtle under the pointer and while pressed. The icon gives a 0.97 press,
	 * only when motion is allowed. `label` is required: an icon has no name of its own.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';

	interface Props extends HTMLButtonAttributes {
		/** The accessible name: what pressing does ("Close menu"). */
		label: string;
		/** The icon. Decorative: aria-hidden is set on the wrapper. */
		children: Snippet;
		/** The rendered <button>. Bind it (bind:ref) to return focus to the button. */
		ref?: HTMLButtonElement;
	}

	let {
		label,
		children,
		ref = $bindable(),
		type = 'button',
		class: className,
		...rest
	}: Props = $props();
</script>

<button bind:this={ref} {type} aria-label={label} class={[ICON_BUTTON_CLASS, className]} {...rest}>
	<span class={ICON_PRESS_CLASS} aria-hidden="true">
		{@render children()}
	</span>
</button>
