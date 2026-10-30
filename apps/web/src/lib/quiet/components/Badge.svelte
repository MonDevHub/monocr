<script lang="ts">
	/*
	 * A short status word next to a title or in a row: "Draft", "Beta", "Archived". A word,
	 * never a pill, a chip or a box: no fill, no border, no radius. Without colour it is told
	 * from the text around it by a dotted underline in the control edge colour (--link-underline,
	 * 4.63:1 light, 3.66:1 dark, past 3:1). The dots are a different line from a link's solid
	 * underline, which is why a middle dot prefix was not used: MetaLine separates items with
	 * one. type-meta, fg-secondary on canvas 7.40:1 light and 6.91:1 dark. `emphasis` moves to
	 * type-label (weight 500) for the word that matters most; the role turns it fg-primary
	 * (16.97:1 and 13.96:1). Static: it does not change after load, so it is not a live region
	 * (announce changes through Status).
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLSpanElement> {
		/** The word: one or two words, in sentence case. */
		children: Snippet;
		/** Switches from `type-meta` to `type-label`: 14px, weight 500, `fg-primary`. Set it for the one badge that must not be missed. */
		emphasis?: boolean;
	}

	const { children, emphasis = false, class: className, ...rest }: Props = $props();
</script>

<span
	class={[
		emphasis ? 'type-label' : 'type-meta',
		'underline decoration-link-underline decoration-dotted underline-offset-4',
		className
	]}
	{...rest}>{@render children()}</span
>
