<script lang="ts" module>
	import { moveHighlight } from './CommandMenu.svelte';

	export interface ComboboxItem {
		/** Stable identity: what `value` holds and `onchange` receives. Unique within the list. */
		value: string;
		/** The row's text, what the field shows once chosen, and what typing filters on. */
		label: string;
		/** Stays in the list, dimmed, but cannot be highlighted or chosen. */
		disabled?: boolean;
	}

	/** The tallest the popup grows: eight 44px rows plus its 8px padding top and bottom. */
	export const COMBOBOX_MAX_HEIGHT = 368;
	/** Space between the field and the popup, and the least space kept from a viewport edge. */
	export const COMBOBOX_GAP = 4;
	export const COMBOBOX_MARGIN = 8;

	/** Case-insensitive match on the label. An empty or blank query keeps every item. */
	export function filterOptions(items: ComboboxItem[], query: string): ComboboxItem[] {
		const q = query.trim().toLowerCase();
		if (q === '') return items;
		return items.filter((item) => item.label.toLowerCase().includes(q));
	}

	/**
	 * The row to highlight after ArrowDown or ArrowUp, as an index into `items`. The arrows wrap
	 * and skip disabled rows. Home and End stay with the text caret, so they are not ours. Null
	 * when the key is not ours or no row can be highlighted.
	 */
	export function nextOption(
		items: { disabled?: boolean }[],
		current: number,
		key: string
	): number | null {
		const enabled = items.flatMap((item, i) => (item.disabled ? [] : [i]));
		const next = moveHighlight(enabled.indexOf(current), key, enabled.length);
		return next === null ? null : enabled[next];
	}

	/** The first row that is not disabled, or -1. */
	export function firstEnabled(items: { disabled?: boolean }[]): number {
		return items.findIndex((item) => !item.disabled);
	}

	interface Box {
		top: number;
		bottom: number;
		left: number;
		width: number;
	}

	/**
	 * Where the popup goes: under the field and as wide as it, flipped above when the rows do
	 * not fit below and there is more room above, shortened to the room that is left, and
	 * clamped so it stays inside the viewport. `content` is the height the rows would take.
	 * Pure, so it is tested without a browser.
	 */
	export function placeListbox(
		anchor: Box,
		viewport: { width: number; height: number },
		content: number
	): { side: 'top' | 'bottom'; top: number; left: number; width: number; maxHeight: number } {
		const width = Math.min(anchor.width, viewport.width - 2 * COMBOBOX_MARGIN);
		const left = Math.max(
			COMBOBOX_MARGIN,
			Math.min(anchor.left, viewport.width - width - COMBOBOX_MARGIN)
		);
		const below = viewport.height - anchor.bottom - COMBOBOX_GAP - COMBOBOX_MARGIN;
		const above = anchor.top - COMBOBOX_GAP - COMBOBOX_MARGIN;
		const wanted = Math.min(content, COMBOBOX_MAX_HEIGHT);
		const side = wanted <= below || below >= above ? 'bottom' : 'top';
		const room = Math.max(0, side === 'bottom' ? below : above);
		const maxHeight = Math.min(COMBOBOX_MAX_HEIGHT, room);
		const shown = Math.min(wanted, maxHeight);
		const top =
			side === 'bottom' ? anchor.bottom + COMBOBOX_GAP : anchor.top - COMBOBOX_GAP - shown;
		return { side, top, left, width, maxHeight };
	}
</script>

<script lang="ts">
	/*
	 * A text field with a list of choices under it, for a list too long to scroll and too long
	 * for the native Select to filter: the WAI-ARIA editable combobox with list autocomplete
	 * (APG), after Primer's Autocomplete. The input is the combobox; focus never leaves it, and
	 * the highlighted row is named by aria-activedescendant. The list is a listbox in a
	 * popover="manual" in the top layer, opened and closed from script, placed under the input
	 * at its width and kept inside the viewport. Typing filters by label. The input shows the
	 * chosen item's label whenever it is closed; the typed text is only a filter until a row is
	 * chosen, so leaving the field accepts nothing. It sits inside a Field like TextInput: the
	 * attributes Field hands out (id, aria-describedby, aria-invalid) go onto the input.
	 */
	import { tick } from 'svelte';
	import type { HTMLInputAttributes } from 'svelte/elements';
	import { countAnnouncement } from './CommandMenu.svelte';
	import Icon from './Icon.svelte';

	interface Props extends Omit<
		HTMLInputAttributes,
		'value' | 'type' | 'role' | 'onchange' | 'placeholder' | 'list'
	> {
		/** The choices, in the order shown. Typing keeps the rows whose label contains the text. */
		items: ComboboxItem[];
		/** The chosen item's value, or null for none. Bindable: bind:value. It changes only when a row is chosen or the app sets it. */
		value?: string | null;
		/** The field's accessible name. Use the same text as the Field label that wraps it. */
		label: string;
		/** Hint text in the empty field. It never replaces the label. */
		placeholder?: string;
		/** Called with the new value when the reader chooses a different row. */
		onchange?: (value: string) => void;
		/** The words shown, and announced, when typing matches no row. */
		empty?: string;
		/** Classes for the wrapper, not the input: a width class narrows the field and its popup together. */
		class?: HTMLInputAttributes['class'];
	}

	let {
		items,
		value = $bindable(null),
		label,
		placeholder,
		onchange,
		empty = 'No matches',
		class: className,
		name,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const listboxId = uid + '-listbox';
	const optionId = (index: number) => [uid, 'option', index].join('-');

	let open = $state(false);
	// The text the reader typed, or null while the field shows the chosen label.
	let typedText = $state<string | null>(null);
	let highlighted = $state(-1);
	let input: HTMLInputElement | undefined = $state();
	let popup: HTMLElement | undefined = $state();

	const chosen = $derived(items.find((item) => item.value === value));
	const text = $derived(typedText ?? chosen?.label ?? '');
	const rows = $derived(typedText === null ? items : filterOptions(items, typedText));
	const active = $derived(
		rows[highlighted] && !rows[highlighted].disabled ? highlighted : firstEnabled(rows)
	);
	// The count is said while the list is open; the empty words when nothing matches.
	const status = $derived(!open ? '' : rows.length === 0 ? empty : countAnnouncement(rows.length));

	function show() {
		if (open) return;
		const at = rows.findIndex((row) => row.value === value && !row.disabled);
		highlighted = at >= 0 ? at : firstEnabled(rows);
		open = true;
	}

	function close() {
		open = false;
	}

	function choose(item: ComboboxItem) {
		if (item.disabled) return;
		const changed = item.value !== value;
		value = item.value;
		typedText = null;
		open = false;
		if (changed) onchange?.(item.value);
	}

	// Show and hide the popup, and keep it under the input and inside the viewport.
	$effect(() => {
		if (!popup || !input) return;
		const el = popup;
		const anchor = input;
		if (!open) {
			if (el.matches(':popover-open')) el.hidePopover();
			return;
		}
		if (!el.matches(':popover-open')) el.showPopover();
		void rows.length;
		const place = () => {
			const a = anchor.getBoundingClientRect();
			// The layout height of the rows, not the drawn box, which the entrance scale shrinks.
			const spot = placeListbox(
				a,
				{ width: document.documentElement.clientWidth, height: window.innerHeight },
				el.scrollHeight
			);
			el.style.top = `${spot.top}px`;
			el.style.left = `${spot.left}px`;
			el.style.width = `${spot.width}px`;
			el.style.maxHeight = `${spot.maxHeight}px`;
			el.dataset.side = spot.side;
		};
		const onScroll = (e: Event) => {
			if (e.target instanceof Node && el.contains(e.target)) return;
			place();
		};
		place();
		window.addEventListener('scroll', onScroll, true);
		window.addEventListener('resize', place);
		return () => {
			window.removeEventListener('scroll', onScroll, true);
			window.removeEventListener('resize', place);
		};
	});

	// Keep the highlighted row in view inside the list's own scroll.
	$effect(() => {
		if (!open || active < 0) return;
		void tick().then(() =>
			document.getElementById(optionId(active))?.scrollIntoView({ block: 'nearest' })
		);
	});

	function oninput(e: Event & { currentTarget: HTMLInputElement }) {
		typedText = e.currentTarget.value;
		highlighted = firstEnabled(filterOptions(items, typedText));
		open = true;
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.ctrlKey || e.metaKey) return;
		// A key that commits or moves through an IME candidate is the IME's, not the list's.
		if (e.isComposing || e.keyCode === 229) return;
		if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
			e.preventDefault();
			if (!open) {
				show();
				if (e.key === 'ArrowUp' && !chosen) {
					highlighted = nextOption(rows, -1, 'ArrowUp') ?? highlighted;
				}
				return;
			}
			if (e.altKey) return;
			const next = nextOption(rows, active, e.key);
			if (next !== null) highlighted = next;
		} else if (e.key === 'Enter') {
			if (!open) return;
			e.preventDefault();
			if (active >= 0) choose(rows[active]);
		} else if (e.key === 'Escape') {
			if (open) {
				// One layer at a time: this press closes the list, not a dialog around the field.
				e.preventDefault();
				e.stopPropagation();
				close();
			} else if (typedText !== null) {
				e.preventDefault();
				e.stopPropagation();
				typedText = null;
			}
		} else if (e.key === 'Tab') {
			close();
		}
	}

	function onblur() {
		close();
		typedText = null;
	}
</script>

<div class={['relative', className]}>
	<input
		autocomplete="off"
		autocapitalize="off"
		spellcheck="false"
		{...rest}
		bind:this={input}
		type="text"
		role="combobox"
		aria-label={label}
		aria-expanded={open}
		aria-controls={listboxId}
		aria-autocomplete="list"
		aria-activedescendant={open && active >= 0 ? optionId(active) : undefined}
		{placeholder}
		value={text}
		class="control pr-8"
		{oninput}
		{onkeydown}
		{onblur}
		onclick={show}
	/>
	<Icon
		name="chevron-down"
		class="pointer-events-none absolute top-1/2 right-0 -translate-y-1/2 text-fg-secondary"
	/>
	{#if name}
		<input type="hidden" {name} value={value ?? ''} />
	{/if}
	<p role="status" class="sr-only">{status}</p>

	<!-- Pointer presses must not take focus from the input. -->
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		bind:this={popup}
		popover="manual"
		class="qd-combobox-popup overlay rounded-md hairline-box border-border bg-canvas p-2 text-fg-primary"
		onmousedown={(e) => e.preventDefault()}
	>
		<div id={listboxId} role="listbox" tabindex="-1" aria-label={label}>
			{#each rows as row, index (row.value)}
				<!-- Keyboard users act through the input; the row's click is the pointer path. -->
				<!-- svelte-ignore a11y_click_events_have_key_events -->
				<div
					id={optionId(index)}
					role="option"
					tabindex="-1"
					aria-selected={row.value === value}
					aria-disabled={row.disabled || undefined}
					data-active={index === active}
					class={[
						'qd-combobox-option relative flex min-h-target items-center rounded-md px-3 type-label',
						row.disabled ? 'cursor-not-allowed text-fg-secondary' : 'cursor-pointer',
						index === active && 'bg-canvas-subtle'
					]}
					onclick={() => choose(row)}
					onpointermove={() => {
						if (!row.disabled && highlighted !== index) highlighted = index;
					}}
				>
					{#if row.value === value}
						<span
							aria-hidden="true"
							class="pointer-events-none absolute inset-y-2 left-0 border-l border-fg-primary"
						></span>
					{/if}
					{row.label}
				</div>
			{/each}
		</div>
		{#if rows.length === 0}
			<p aria-hidden="true" class="px-3 py-3 type-small">{empty}</p>
		{/if}
	</div>
</div>

<style>
	/* A popover is centred by the browser; it is placed from the input's box instead. */
	.qd-combobox-popup {
		position: fixed;
		inset: auto;
		margin: 0;
		max-height: calc(var(--space-unit) * 92);
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	/* It grows out of the input: from its top edge when below, its bottom edge when above.
	   data-side is set from script, so Svelte cannot see it: the attribute part is global. */
	.qd-combobox-popup:global([data-side='bottom']) {
		transform-origin: top center;
	}
	.qd-combobox-popup:global([data-side='top']) {
		transform-origin: bottom center;
	}
	/* The highlight is a fill, which forced colours erase: give it the system highlight pair. */
	@media (forced-colors: active) {
		.qd-combobox-option[data-active='true'] {
			background-color: Highlight;
			color: HighlightText;
		}
	}
</style>
