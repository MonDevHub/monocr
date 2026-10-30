<script lang="ts" module>
	/**
	 * What Field hands its control. Spread all of it onto the input, textarea or select, so
	 * the label, the hint and the error are wired to the element that takes focus.
	 */
	export type ControlAttributes = {
		id: string;
		'aria-describedby'?: string;
		'aria-invalid'?: 'true';
	};
</script>

<script lang="ts">
	/*
	 * One form field: a visible label, then an optional hint, then the error in words, then
	 * the control. No box, no colour: an error is marked by the word "Error:" and by the
	 * control's heavier bottom edge, so it reads the same in greyscale, forced colours and a
	 * screen reader. Optional fields say "(optional)" in the label; there are no asterisks.
	 * The hint and error sit above the control so they are read before the value, and a
	 * phone keyboard never covers them.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** The control's id. The label points at it; the hint and error take {id}-hint and {id}-error. */
		id: string;
		/** Visible label, sentence case. It is the control's accessible name. */
		label: string;
		/** One short line that helps answer: a format or a limit. Read after the label through aria-describedby. */
		hint?: string;
		/** What is wrong and the next step. Shown after "Error: "; sets aria-invalid="true" on the control. Empty means no error. */
		error?: string;
		/** Appends " (optional)" to the label. Set it on each optional one when most fields are required. When most are optional, leave it unset and write "required" in the few labels that need it. */
		optional?: boolean;
		/** The word appended in brackets when optional is set. Translate it when the site is not in English. */
		optionalLabel?: string;
		/** The word that starts the error, shown before it with a colon. Translate it when the site is not in English. */
		errorLabel?: string;
		/** The control. Receives ControlAttributes; spread all of them onto the TextInput, Textarea or Select. */
		children: Snippet<[ControlAttributes]>;
	}

	const {
		id,
		label,
		hint,
		error,
		optional = false,
		optionalLabel = 'optional',
		errorLabel = 'Error',
		children
	}: Props = $props();

	const text = $derived(optional ? label + ' (' + optionalLabel + ')' : label);
	const hintId = $derived(id + '-hint');
	const errorId = $derived(id + '-error');
	const control = $derived<ControlAttributes>({
		id,
		'aria-describedby':
			[hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined,
		'aria-invalid': error ? 'true' : undefined
	});
</script>

<div class="max-w-measure space-y-2">
	<label for={id} class="block type-label">{text}</label>
	{#if hint}
		<p id={hintId} class="type-small">{hint}</p>
	{/if}
	{#if error}
		<p id={errorId} class="type-label">{errorLabel}: {error}</p>
	{/if}
	{@render children(control)}
</div>
