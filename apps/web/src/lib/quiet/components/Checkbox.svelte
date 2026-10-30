<script lang="ts">
	/*
	 * A yes-or-no choice: the platform's checkbox, tinted fg-primary (`choice`), beside its
	 * label in one 44px row, and the whole row is the target. No drawn box, no fill, no icon
	 * of our own: the platform draws the mark, and forced colours redraws it. The label is
	 * type-small, a value rather than a heading. A hint sits under the label text, aligned
	 * with it, and is wired by aria-describedby. Disabled keeps the label readable (type-small,
	 * 7.40:1) and shows a not-allowed cursor; the box greys itself. A checkbox that must be
	 * checked on its own (consent to terms) takes `error`: the word "Error:" and the next step
	 * in type-label above the row, wired by aria-describedby after the hint, and aria-invalid
	 * on the input, as Field does for a text control. Checkboxes in a group take the error on
	 * their Fieldset instead.
	 */
	import type { HTMLInputAttributes } from 'svelte/elements';

	interface Props extends HTMLInputAttributes {
		/** Visible label, sentence case. It is the checkbox's accessible name. */
		label: string;
		/** One short line under the label: what checking it means or costs. Read after the label through aria-describedby. */
		hint?: string;
		/** What is wrong and the next step, for a lone checkbox that must be checked. Shown after "Error: "; sets aria-invalid="true" on the input. Empty means no error. In a group, set it on the Fieldset. */
		error?: string;
		/** The word that starts the error, shown before it with a colon. Translate it when the site is not in English. */
		errorLabel?: string;
		/** Whether it is checked. Bindable: bind:checked. */
		checked?: boolean;
	}

	let {
		label,
		hint,
		error,
		errorLabel = 'Error',
		checked = $bindable(false),
		id,
		disabled,
		class: className,
		'aria-describedby': describedBy,
		'aria-invalid': invalid,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const inputId = $derived(id ?? uid);
	const hintId = $derived(inputId + '-hint');
	const errorId = $derived(inputId + '-error');
	const describedByAll = $derived(
		[hint ? hintId : '', error ? errorId : '', describedBy ?? ''].filter(Boolean).join(' ') ||
			undefined
	);
</script>

<div>
	{#if error}
		<p id={errorId} class="max-w-measure type-label">{errorLabel}: {error}</p>
	{/if}
	<label
		class={[
			'flex min-h-target items-center gap-3',
			disabled ? 'cursor-not-allowed' : 'cursor-pointer'
		]}
	>
		<input
			{...rest}
			bind:checked
			type="checkbox"
			id={inputId}
			{disabled}
			aria-describedby={describedByAll}
			aria-invalid={error ? 'true' : invalid}
			class={['choice', className]}
		/>
		<span class="type-small">{label}</span>
	</label>
	{#if hint}
		<div class="flex gap-3">
			<span class="w-4 shrink-0" aria-hidden="true"></span>
			<p id={hintId} class="type-small">{hint}</p>
		</div>
	{/if}
</div>
