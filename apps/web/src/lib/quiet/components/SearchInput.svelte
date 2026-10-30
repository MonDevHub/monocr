<script lang="ts" module>
	/** How long typing must pause before the result count is announced, in milliseconds. */
	export const SEARCH_ANNOUNCE_DELAY_MS = 300;

	/**
	 * The words a screen reader gets after typing pauses: "No results", "1 result" or "3 results".
	 * Empty when the app has not reported a count (null or undefined) or the field is empty, so
	 * the region says nothing then.
	 */
	export function resultsAnnouncement(
		results: number | null | undefined,
		value: string | null | undefined
	): string {
		if (typeof results !== 'number' || !Number.isFinite(results)) return '';
		if (!value || value.trim() === '') return '';
		if (results <= 0) return 'No results';
		return results === 1 ? '1 result' : `${results} results`;
	}
</script>

<script lang="ts">
	/*
	 * A search field: one text control with a search icon at the left and a "Clear" control at
	 * the right once there is text, after Primer's TextInput with a leading visual and the
	 * Geist search fields. It keeps the `control` utility's single bottom edge: no box, no fill.
	 * The icon sits 8px inside the field and "Clear" is set in it (its text 8px from the edge), so the global
	 * focus outline (2px away from the field) never touches either. The value stays 16px, so iOS
	 * does not zoom. The field is a <form role="search"> holding an <input type="search">; it
	 * has its own label, so it does not go inside Field. The result count is the app's to give:
	 * with `results` set, a polite status region says "3 results" or "No results" after typing
	 * pauses; the region is on the page, empty, from the start so screen readers watch it. The form
	 * is named by the same label, so two searches on one page need two different labels.
	 */
	import type { HTMLInputAttributes } from 'svelte/elements';
	import Icon from './Icon.svelte';
	import TextButton from './TextButton.svelte';

	interface Props extends Omit<HTMLInputAttributes, 'value' | 'type' | 'role' | 'onsubmit'> {
		/** The text. Bindable: bind:value. */
		value?: string;
		/** The field's accessible name ("Search articles"). Visually hidden unless labelVisible is set. */
		label: string;
		/** Shows the label above the field, as Field does. Leave it off when the icon and placeholder already say what the field searches. */
		labelVisible?: boolean;
		/** Hint text in the empty field. It never replaces the label. */
		placeholder?: string;
		/** Called with the text when the reader presses Enter. */
		onsubmit?: (value: string) => void;
		/** Called after "Clear" or Escape empties the field. */
		onclear?: () => void;
		/** How many results the app found for the current text. A number makes a polite status region announce it after typing pauses 300ms; null or undefined announces nothing. */
		results?: number | null;
		/** Stops input and hides "Clear". */
		disabled?: boolean;
	}

	let {
		value = $bindable(''),
		label,
		labelVisible = false,
		placeholder,
		onsubmit,
		onclear,
		results = null,
		disabled = false,
		class: className,
		id,
		onkeydown: userKeydown,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const inputId = $derived(id ?? uid + '-input');

	let input: HTMLInputElement | undefined = $state();
	let announcement = $state('');

	// After typing pauses, say the count. Every change to the text or the count restarts the wait.
	$effect(() => {
		const text = resultsAnnouncement(results, value);
		if (text === '') {
			announcement = '';
			return;
		}
		const timer = setTimeout(() => (announcement = text), SEARCH_ANNOUNCE_DELAY_MS);
		return () => clearTimeout(timer);
	});

	function clear() {
		value = '';
		input?.focus();
		onclear?.();
	}

	function submit(e: SubmitEvent) {
		e.preventDefault();
		onsubmit?.(value);
	}

	function onkeydown(e: KeyboardEvent & { currentTarget: EventTarget & HTMLInputElement }) {
		userKeydown?.(e);
		if (e.defaultPrevented || e.key !== 'Escape' || !value) return;
		if (e.isComposing || e.keyCode === 229) return;
		// The press is spent on clearing: it does not also close a dialog around the field.
		e.preventDefault();
		e.stopPropagation();
		value = '';
		onclear?.();
	}
</script>

<form role="search" aria-label={label} class={className} onsubmit={submit}>
	<label for={inputId} class={labelVisible ? 'mb-2 block type-label' : 'sr-only'}>{label}</label>
	<div class="relative">
		<span
			aria-hidden="true"
			class="pointer-events-none absolute top-0 bottom-0 left-2 flex items-center text-fg-secondary"
		>
			<Icon name="search" size={20} />
		</span>
		<input
			autocomplete="off"
			spellcheck="false"
			{...rest}
			bind:this={input}
			bind:value
			id={inputId}
			type="search"
			{placeholder}
			{disabled}
			class="qd-search-input control pr-16 pl-10"
			{onkeydown}
		/>
		{#if value && !disabled}
			<TextButton aria-label="Clear search" class="absolute top-0 right-0 px-2" onclick={clear}>
				Clear
			</TextButton>
		{/if}
	</div>
	<p role="status" class="sr-only">{announcement}</p>
</form>

<style>
	/* The browser's own clear cross and rounded search styling would double ours. */
	.qd-search-input {
		appearance: none;
	}
	.qd-search-input::-webkit-search-cancel-button,
	.qd-search-input::-webkit-search-decoration {
		appearance: none;
		display: none;
	}
</style>
