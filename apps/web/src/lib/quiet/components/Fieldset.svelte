<script lang="ts">
	/*
	 * A group of choices that answer one question: the legend asks it, then an optional hint,
	 * then the error in words, then the checkboxes or radios. It is Field for a group, with
	 * the same order and the same voice: no box, no border, no tint, and an error is the word
	 * "Error:" plus aria-invalid, never a colour. The native fieldset's border, padding and
	 * minimum width are removed (preflight already zeroes its margin), so the group sits on
	 * the column like any other block and takes the spacing of the stack around it: an m-0
	 * here would cancel a parent's space-y. Hint and error keep to the reading measure.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** The question the group answers, sentence case. It is the group's accessible name. */
		legend: string;
		/** The fieldset's id. The hint and error take {id}-hint and {id}-error. */
		id: string;
		/** One short line that helps answer: "Choose all that apply." Read after the legend through aria-describedby. */
		hint?: string;
		/** What is wrong and the next step. Shown after "Error: "; sets aria-invalid="true" on the fieldset. Empty means no error. */
		error?: string;
		/** Appends " (optional)" to the legend. Set it on each optional one when most questions are required. When most are optional, leave it unset and write "required" in the few legends that need it. */
		optional?: boolean;
		/** The word appended in brackets when optional is set. Translate it when the site is not in English. */
		optionalLabel?: string;
		/** The word that starts the error, shown before it with a colon. Translate it when the site is not in English. */
		errorLabel?: string;
		/** The choices: Checkbox or Radio, one per row. */
		children: Snippet;
	}

	const {
		legend,
		id,
		hint,
		error,
		optional = false,
		optionalLabel = 'optional',
		errorLabel = 'Error',
		children
	}: Props = $props();

	const text = $derived(optional ? legend + ' (' + optionalLabel + ')' : legend);

	const hintId = $derived(id + '-hint');
	const errorId = $derived(id + '-error');
	const describedBy = $derived(
		[hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined
	);
</script>

<!-- Svelte's ARIA table does not list aria-invalid for the group role, so it warns; axe-core
     treats aria-invalid as a global attribute and accepts it here. Screen reader support on a
     group varies, so the words "Error: ..." in the description carry the meaning. -->
<!-- svelte-ignore a11y_role_supports_aria_props_implicit -->
<fieldset
	{id}
	class="min-w-0 space-y-2 border-0 p-0"
	aria-describedby={describedBy}
	aria-invalid={error ? 'true' : undefined}
>
	<legend class="type-label">{text}</legend>
	{#if hint}
		<p id={hintId} class="max-w-measure type-small">{hint}</p>
	{/if}
	{#if error}
		<p id={errorId} class="max-w-measure type-label">{errorLabel}: {error}</p>
	{/if}
	<div>
		{@render children()}
	</div>
</fieldset>
