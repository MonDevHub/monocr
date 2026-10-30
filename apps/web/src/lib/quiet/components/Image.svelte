<script lang="ts">
	/*
	 * Two image shapes and no others. `portrait`: a 1:1 circle, 64px (96px from 640px),
	 * grayscale at rest and colour under the pointer with a 1.01 scale, both over 150ms. `wide`:
	 * a 16:7 figure, full column width, 6px radius, cropped to fill. Always real alt text,
	 * or alt="" when the image only decorates.
	 * `caption` wraps the image in a <figure> with a type-meta <figcaption> 8px below it,
	 * left-aligned like everything else: a credit or a line of context, never a repeat of the
	 * alt text. Without a caption the markup is the bare <img>. Every other attribute, class
	 * included, still goes on the <img>.
	 */
	import type { HTMLImgAttributes } from 'svelte/elements';

	interface Props extends HTMLImgAttributes {
		/** Image URL. */
		src: string;
		/** What the image shows. "" only when it is decorative. */
		alt: string;
		/** Shape: `portrait` for a person, `wide` for a screenshot or figure. */
		variant?: 'portrait' | 'wide';
		/** A credit or a line of context, shown under the image in a figcaption. */
		caption?: string;
	}

	const { src, alt, variant = 'portrait', caption, class: className, ...rest }: Props = $props();

	const SHAPE = {
		portrait:
			'relative h-16 w-16 rounded-full object-cover grayscale transition-portrait duration-150 hover:scale-101 hover:grayscale-0 sm:h-24 sm:w-24',
		wide: 'aspect-wide w-full rounded-md object-cover'
	} as const;
</script>

{#snippet image()}
	<img {src} {alt} class={[SHAPE[variant], className]} {...rest} />
{/snippet}

{#if caption}
	<figure class="space-y-2">
		{@render image()}
		<figcaption class="type-meta">{caption}</figcaption>
	</figure>
{:else}
	{@render image()}
{/if}
