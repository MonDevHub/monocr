<script lang="ts">
	/*
	 * One answer to a question with a few answers: the platform's radio, tinted fg-primary
	 * (`choice`), beside its label in one 44px row that is the whole target. Radios come in a
	 * group that shares a `name` and a bound `group` value, inside a Fieldset whose legend
	 * asks the question. The platform draws the dot and handles the arrow keys. A hint sits
	 * under the label text, wired by aria-describedby. Disabled keeps the label readable and
	 * shows a not-allowed cursor.
	 */
	import type { HTMLInputAttributes } from 'svelte/elements';

	interface Props extends HTMLInputAttributes {
		/** Visible label, sentence case. It is the radio's accessible name. */
		label: string;
		/** One short line under the label: what choosing it means. Read after the label through aria-describedby. */
		hint?: string;
		/** The value the group takes when this radio is chosen. */
		value: string;
		/** The chosen value of the whole group. Bindable: bind:group, the same variable on every radio in the group. */
		group?: string;
		/** The group name, the same on every radio in it. The browser groups radios by name. */
		name: string;
	}

	let {
		label,
		hint,
		value,
		group = $bindable(),
		name,
		id,
		disabled,
		class: className,
		'aria-describedby': describedBy,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const inputId = $derived(id ?? uid);
	const hintId = $derived(inputId + '-hint');
	const describedByAll = $derived(
		[hint ? hintId : '', describedBy ?? ''].filter(Boolean).join(' ') || undefined
	);
</script>

<div>
	<label
		class={[
			'flex min-h-target items-center gap-3',
			disabled ? 'cursor-not-allowed' : 'cursor-pointer'
		]}
	>
		<input
			{...rest}
			bind:group
			type="radio"
			{value}
			{name}
			id={inputId}
			{disabled}
			aria-describedby={describedByAll}
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
