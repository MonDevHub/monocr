<script lang="ts">
	/*
	 * A home page's first block: the name as the page's h1, one level line under it, an
	 * optional portrait on the right, then a few paragraphs in the reading measure. Below
	 * 640px the name and a 64px portrait share the first row and the paragraphs run full
	 * width underneath. Never animated.
	 */
	import type { Snippet } from 'svelte';
	import Image from './Image.svelte';

	interface Props {
		/** The person's name, rendered as the page's h1 (an h2 at level 2). */
		name: string;
		/** One line under the name (type-subtitle): a role or level, such as "Software engineer". */
		subtitle?: string;
		/** The portrait, drawn as Image variant="portrait". Omit it for no portrait. */
		portrait?: { src: string; alt: string };
		/** Two or three short paragraphs. */
		children: Snippet;
		/** Heading level of the name. Set 2 only when showing an Intro inside another page. */
		level?: 1 | 2;
	}

	const { name, subtitle, portrait, children, level = 1 }: Props = $props();
</script>

<section class="grid grid-intro items-start gap-x-6 gap-y-6 sm:gap-x-16">
	<div class="space-y-1">
		<svelte:element this={level === 1 ? 'h1' : 'h2'} class="type-h1">{name}</svelte:element>
		{#if subtitle}
			<p class="type-subtitle">{subtitle}</p>
		{/if}
	</div>
	{#if portrait}
		<div class="relative shrink-0 sm:row-span-2 sm:pt-2">
			<Image
				src={portrait.src}
				alt={portrait.alt}
				variant="portrait"
				loading="eager"
				fetchpriority="high"
			/>
		</div>
	{/if}
	<div class="col-span-2 max-w-measure space-y-4 type-body sm:col-span-1">
		{@render children()}
	</div>
</section>
