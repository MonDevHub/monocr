<script lang="ts">
	/*
	 * One choice from a short list, drawn as a text control: the same single bottom edge as
	 * an input (`control`), no box, no fill, no radius. The picker is the platform's own,
	 * so it works with every keyboard, screen reader and touch screen without our help. The
	 * arrow is a decorative chevron in fg-secondary at the right; the select keeps 32px of
	 * right padding so a long label never runs under it. A `class` goes on the wrapper, not
	 * the select: the select fills the wrapper and the chevron is placed against the
	 * wrapper's right edge, so a width class (`w-24`) narrows both together and the chevron
	 * stays at the select's end. It has no label of its own: put it in a Field, which names
	 * it and wires the hint and error.
	 */
	import type { HTMLSelectAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';

	interface Props extends HTMLSelectAttributes {
		/** The choices, in the order shown. `label` is the visible text, sentence case; a disabled option stays in the list but cannot be chosen. */
		options: { value: string; label: string; disabled?: boolean }[];
		/** The chosen option's value. Bindable: bind:value. */
		value?: string;
		/** Classes for the wrapper, not the select. The select fills the wrapper, so a width class (`w-24`) narrows both and the chevron stays at the select's right edge. */
		class?: HTMLSelectAttributes['class'];
	}

	let { options, value = $bindable(), class: className, ...rest }: Props = $props();
</script>

<div class={['relative', className]}>
	<select {...rest} bind:value class="control control-select">
		{#each options as option (option.value)}
			<option value={option.value} disabled={option.disabled}>{option.label}</option>
		{/each}
	</select>
	<Icon
		name="chevron-down"
		class="pointer-events-none absolute top-1/2 right-0 -translate-y-1/2 text-fg-secondary"
	/>
</div>
