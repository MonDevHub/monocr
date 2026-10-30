<script lang="ts">
	/*
	 * One page's <main>: 64px above (96px from 640px), 96px below before the footer.
	 * With a `title` it renders the page head: an optional back link, the one h1 and an
	 * optional intro in the reading measure. The head is never animated: it holds the
	 * largest text on the page, and a transparent first paint loses the LCP candidate.
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import BackLink from './BackLink.svelte';

	interface Props extends HTMLAttributes<HTMLElement> {
		/** The page's one h1. Omit it only when the first block renders its own (Intro). */
		title?: string;
		/** One paragraph under the h1, in the 576px measure. Shown only with `title`. */
		intro?: string;
		/** A BackLink above the h1, for inner pages. Shown only with `title`. */
		back?: { href: string; label: string };
		/** The sections. */
		children: Snippet;
	}

	const { title, intro, back, children, class: className, ...rest }: Props = $props();
</script>

<main
	id="main-content"
	class={['pt-page-top pb-page-bottom sm:pt-page-top-wide', className]}
	{...rest}
>
	{#if title}
		<div class="space-y-8">
			{#if back}
				<BackLink href={back.href} label={back.label} />
			{/if}
			<div class="space-y-6">
				<h1 class="type-h1">{title}</h1>
				{#if intro}
					<p class="max-w-measure type-body">{intro}</p>
				{/if}
			</div>
		</div>
	{/if}
	{@render children()}
</main>
