<script lang="ts">
	/*
	 * A keyboard key or chord inside a sentence: "Press Ctrl+K to search". It keeps its
	 * paragraph's role (size, weight, leading), because the base layer already sets kbd to
	 * inherit the face and size and turn fg-primary. There is no monospace in the system, so
	 * the key is told from the words around it by a 1px hairline edge and 6px of side padding:
	 * no fill, no shadow, no keycap. Decorative text, not a control, so no 44px target. A
	 * chord is one nowrap span: the keys never split across a line, and the "+" between them
	 * is hidden from assistive technology and replaced by a visually hidden "plus".
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLSpanElement> {
		/** The keys in order, as their keycaps read: `['Ctrl', 'K']`. Each gets its own `kbd`, joined by "+". When set and not empty, `children` is ignored. */
		keys?: string[];
		/** One key, written as content (`<Kbd>Esc</Kbd>`). Used only when `keys` is not set or is empty. */
		children?: Snippet;
	}

	const { keys, children, class: className, ...rest }: Props = $props();
</script>

<span class={['whitespace-nowrap', className]} {...rest}
	>{#if keys && keys.length > 0}{#each keys as key, i (i)}{#if i > 0}<span
					aria-hidden="true"
					class="mx-1">+</span
				><span class="sr-only">&nbsp;plus&nbsp;</span>{/if}<kbd
				class="rounded-sm hairline-box border-border px-1.5">{key}</kbd
			>{/each}{:else if children}<kbd class="rounded-sm hairline-box border-border px-1.5"
			>{@render children()}</kbd
		>{/if}</span
>
