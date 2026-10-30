<script lang="ts">
	/*
	 * A page section: the hairline Rule above it (48px either side), then a 32px stack of
	 * heading and content. `titleHidden` keeps the h2 for the outline only, for a list that
	 * says what it is. `enter` is the one entrance (fade up 16px over 300ms, delayed N x 50ms):
	 * only for a section below the fold, never the first block of a page.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import Rule from './Rule.svelte';

	interface Props extends HTMLAttributes<HTMLElement> {
		/** Section heading, sentence case. Rendered as an h2. */
		title?: string;
		/** Hide the h2 from view but keep it for screen readers. Set it when the content already says what it is. */
		titleHidden?: boolean;
		/** One paragraph under the heading, in the reading measure. */
		intro?: string;
		/** Draw the Rule above. Set false for a first section that has nothing above it to separate from, such as on a page without a page head. */
		rule?: boolean;
		/** Fade the section up 16px over 300ms, delayed by enter x 50ms. Below the fold only. */
		enter?: 1 | 2 | 3 | 4 | 5 | 6;
		/** The content. */
		children: Snippet;
	}

	const {
		title,
		titleHidden = false,
		intro,
		rule = true,
		enter,
		children,
		class: className,
		...rest
	}: Props = $props();

	// Whole class names, so Tailwind sees each one. Never build `stagger-${n}`.
	const STAGGER = {
		1: 'stagger-1',
		2: 'stagger-2',
		3: 'stagger-3',
		4: 'stagger-4',
		5: 'stagger-5',
		6: 'stagger-6'
	} as const;
</script>

{#if rule}
	<Rule />
{/if}
<section
	class={['space-y-8', enter && ['animate-fade-in-up', STAGGER[enter]], className]}
	{...rest}
>
	{#if title && intro && !titleHidden}
		<div class="space-y-3">
			<h2 class="type-h2">{title}</h2>
			<p class="max-w-measure type-body">{intro}</p>
		</div>
	{:else}
		{#if title}
			<h2 class={titleHidden ? 'sr-only' : 'type-h2'}>{title}</h2>
		{/if}
		{#if intro}
			<p class="max-w-measure type-body">{intro}</p>
		{/if}
	{/if}
	{@render children()}
</section>
