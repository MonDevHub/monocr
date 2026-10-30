<script lang="ts">
	/*
	 * A single-line text control: no box, no fill, one bottom edge in --link-underline (3:1
	 * against the canvas, the boundary WCAG 1.4.11 asks for), 44px tall, the value at 16px so
	 * iOS does not zoom. Invalid and disabled are drawn by the `control` utility in quiet.css.
	 * Use it inside Field, which gives it a label, a hint and an error. Numbers that are not
	 * quantities (codes, PINs, card numbers) take inputmode="numeric", not type="number",
	 * which rounds, scrolls on the wheel and drops leading zeros; so `type` stops at text kinds.
	 */
	import type { HTMLInputAttributes } from 'svelte/elements';

	interface Props extends HTMLInputAttributes {
		/** The text. Bindable: bind:value. */
		value?: string | null;
		/** The kind of text: it picks the on-screen keyboard on phones and what browsers offer to fill. Not number: see above. */
		type?: 'text' | 'email' | 'password' | 'search' | 'tel' | 'url';
	}

	let { value = $bindable(), type = 'text', class: className, ...rest }: Props = $props();
</script>

<input bind:value {type} class={['control', className]} {...rest} />
