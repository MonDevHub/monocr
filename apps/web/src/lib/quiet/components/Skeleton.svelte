<script lang="ts" module>
	export type SkeletonShape = 'text' | 'block';
	export type SkeletonSize = 'sm' | 'md' | 'lg' | 'wide';

	/** Whole class names, so Tailwind sees them and the checker can read them. */
	export const BLOCK_SIZES: Record<SkeletonSize, string> = {
		sm: 'h-8',
		md: 'h-16',
		lg: 'h-24',
		wide: 'aspect-wide'
	};
</script>

<script lang="ts">
	/*
	 * Reserved geometry for content that has not arrived: grey bars the size of the final
	 * text or block, so nothing shifts when it lands. Static: no shimmer, no pulse, because the
	 * only loading indicator in the system is the word "Loading" in a Status. Always pair
	 * it with that Status, and set aria-busy on the region the skeleton stands in for. The
	 * skeleton itself is aria-hidden: it says nothing a screen reader needs. Bars are
	 * bg-border/50 with rounded-sm and no border. Text lines are a 20px row (type-small's line
	 * height) holding a 12px bar, so they occupy the space type-small text would; the last of
	 * several lines is two thirds wide. A block takes its height from a fixed lookup of whole
	 * class names (never built from a template literal).
	 */
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLDivElement> {
		/** text: lines the height of type-small text, for a paragraph. block: one bar, for an image, a figure or a control. */
		shape?: SkeletonShape;
		/** Number of text lines, rounded and clamped to 1 to 6. Ignored for block. */
		lines?: number;
		/** Block height: sm 32px, md 64px, lg 96px, or wide for the 16:7 figure ratio. Ignored for text. */
		size?: SkeletonSize;
	}

	const { shape = 'text', lines = 3, size = 'md', class: className, ...rest }: Props = $props();

	const count = $derived(Math.min(6, Math.max(1, Math.round(lines))));
</script>

<div aria-hidden="true" class={['w-full', className]} {...rest}>
	{#if shape === 'block'}
		<div class={['qd-skeleton w-full rounded-sm bg-border/50', BLOCK_SIZES[size]]}></div>
	{:else}
		{#each { length: count } as _, i (i)}
			<div class="flex h-5 items-center">
				<div
					class={[
						'qd-skeleton h-3 rounded-sm bg-border/50',
						count > 1 && i === count - 1 ? 'w-2/3' : 'w-full'
					]}
				></div>
			</div>
		{/each}
	{/if}
</div>

<style>
	/* Forced colours drop the fill: keep the reserved shape as a system-colour outline. */
	@media (forced-colors: active) {
		.qd-skeleton {
			outline: var(--shape-underline) solid GrayText;
			outline-offset: calc(var(--shape-underline) * -1);
		}
	}
</style>
