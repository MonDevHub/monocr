<script lang="ts">
	/*
	 * A link that is only an icon (footer profile links). 20px glyph in a 44 x 44 box,
	 * fg-secondary at rest, fg-accent on hover. `label` is the accessible name: write it out
	 * ("Email", "Source on GitHub"), never derive it from the icon key.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAnchorAttributes } from 'svelte/elements';
	import Icon, { type IconName } from './Icon.svelte';
	import { isExternal } from '../external';

	interface Props extends HTMLAnchorAttributes {
		/** Destination. An absolute http(s) URL opens in a new tab. */
		href: string;
		/** The accessible name, written out ("Source on GitHub"). Never the icon key. */
		label: string;
		/** A system icon, drawn at 20px. Omit it when you pass `children`. */
		icon?: IconName;
		/** A brand mark: a 20px SVG in currentColor. Drawn instead of `icon`. */
		children?: Snippet;
	}

	const { href, label, icon, children, class: className, ...rest }: Props = $props();

	const external = $derived(isExternal(href));
</script>

<a
	{href}
	aria-label={label}
	target={external ? '_blank' : undefined}
	rel={external ? 'noopener noreferrer' : undefined}
	class={[
		'inline-flex min-h-target min-w-target cursor-pointer touch-manipulation items-center justify-center text-fg-secondary transition-colors duration-150 hover:text-fg-accent focus-visible:rounded-sm',
		className
	]}
	{...rest}
>
	{#if children}
		<span class="inline-flex h-5 w-5" aria-hidden="true">{@render children()}</span>
	{:else if icon}
		<Icon name={icon} size={20} />
	{/if}
</a>
