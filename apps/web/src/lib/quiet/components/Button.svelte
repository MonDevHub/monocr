<script lang="ts">
	/*
	 * A flat button for the one step a view exists for. Primary is the inverse (fg-primary
	 * fill, on-primary text); secondary is transparent with a hairline edge in
	 * --link-underline, which clears the 3:1 a control's boundary needs. No shadow, no icon.
	 * With href it is an <a> that looks the same. Disabled is opacity-50, not-allowed, and out
	 * of the tab order. Forced colours keep an edge on both variants.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';

	interface Props
		extends HTMLButtonAttributes, Pick<HTMLAnchorAttributes, 'target' | 'rel' | 'download'> {
		/** The label, verb first: "Save changes". It wraps if it must and is never cut. */
		children: Snippet;
		/** "primary" is the one main action of a view; "secondary" is every other button. */
		variant?: 'primary' | 'secondary';
		/** Renders an <a> with the same look, for a step that goes to another page. */
		href?: string;
	}

	const {
		children,
		variant = 'secondary',
		href,
		disabled = false,
		type = 'button',
		class: className,
		...rest
	}: Props = $props();

	const LOOK = {
		primary: 'primary bg-fg-primary text-on-primary',
		secondary: 'secondary hairline-box text-fg-primary'
	};
	const LIVE = {
		primary: 'cursor-pointer hover:bg-fg-primary/90 motion-safe:active:scale-97',
		secondary: 'cursor-pointer hover:bg-canvas-subtle/50 motion-safe:active:scale-97'
	};
	const BASE =
		'inline-flex min-h-target max-w-full items-center justify-center rounded-md px-4 py-2.5 text-center type-label transition duration-150';
	const OFF = 'cursor-not-allowed opacity-50';

	const classes = $derived([BASE, LOOK[variant], disabled ? OFF : LIVE[variant], className]);
</script>

{#if href !== undefined}
	<a
		href={disabled ? undefined : href}
		role={disabled ? 'link' : undefined}
		aria-disabled={disabled || undefined}
		tabindex={disabled ? -1 : undefined}
		class={classes}
		{...rest as HTMLAnchorAttributes}>{@render children()}</a
	>
{:else}
	<button {type} {disabled} class={classes} {...rest}>{@render children()}</button>
{/if}

<style>
	.secondary {
		border-color: var(--link-underline);
	}
	@media (forced-colors: active) {
		.primary {
			border: var(--shape-hairline) solid ButtonText;
		}
		.secondary {
			border-color: ButtonText;
		}
	}
</style>
