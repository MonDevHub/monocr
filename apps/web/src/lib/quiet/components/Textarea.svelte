<script lang="ts">
	/*
	 * Multi-line text: the same control as TextInput (one bottom edge, no box, the value at
	 * 16px fg-primary), four rows tall by default and resizable in height only, so it never
	 * pushes the column wider. Use it inside Field for the label, the hint and the error.
	 */
	import type { HTMLTextareaAttributes } from 'svelte/elements';

	interface Props extends HTMLTextareaAttributes {
		/** The text. Bindable: bind:value. */
		value?: string | null;
		/** Visible lines before it scrolls. Raise it when long answers are expected; the reader can still drag it taller. */
		rows?: number;
	}

	let { value = $bindable(), rows = 4, class: className, ...rest }: Props = $props();
</script>

<textarea bind:value {rows} class={['control resize-y', className]} {...rest}></textarea>
