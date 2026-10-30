<script module lang="ts">
	export interface CommandItem {
		/** Stable identity, passed back through onselect. */
		id: string;
		/** The row's text, and what typing filters on. */
		label: string;
		/** A group heading. Rows keep the order they are given; groups appear where they first occur. */
		group?: string;
		/** A trailing word in type-meta: a shortcut, a section, a date. Not searched. */
		hint?: string;
		/** Choosing the row (Enter or a click) navigates here after onselect, as a full page load. */
		href?: string;
	}

	export interface CommandSection {
		group: string | undefined;
		rows: { item: CommandItem; index: number }[];
	}

	/** Case-insensitive match on the label. An empty or blank query keeps every item. */
	export function filterItems(items: CommandItem[], query: string): CommandItem[] {
		const q = query.trim().toLowerCase();
		if (q === '') return items;
		return items.filter((item) => item.label.toLowerCase().includes(q));
	}

	/**
	 * Gathers rows under their group, in order of first appearance, and numbers them in the order
	 * they are shown, so the keyboard walks the screen from top to bottom.
	 */
	export function groupItems(items: CommandItem[]): CommandSection[] {
		const sections: CommandSection[] = [];
		for (const item of items) {
			let section = sections.find((s) => s.group === item.group);
			if (!section) {
				section = { group: item.group, rows: [] };
				sections.push(section);
			}
			section.rows.push({ item, index: 0 });
		}
		let index = 0;
		for (const section of sections) for (const row of section.rows) row.index = index++;
		return sections;
	}

	/** The highlighted row after a key: the arrows wrap. Home and End stay with the text caret (APG, editable combobox). Null when the key is not ours. */
	export function moveHighlight(current: number, key: string, count: number): number | null {
		if (count <= 0) return null;
		switch (key) {
			case 'ArrowDown':
				return current < 0 || current >= count - 1 ? 0 : current + 1;
			case 'ArrowUp':
				return current <= 0 ? count - 1 : current - 1;
			default:
				return null;
		}
	}

	/**
	 * Ctrl or Cmd plus K, and not while typing in some other field (the menu's own input does not
	 * count: the shortcut closes the menu from there).
	 */
	export function isShortcut(
		e: {
			key: string;
			ctrlKey: boolean;
			metaKey: boolean;
			altKey: boolean;
			shiftKey: boolean;
			defaultPrevented?: boolean;
		},
		typingElsewhere: boolean
	): boolean {
		if (e.defaultPrevented || typingElsewhere) return false;
		if (e.altKey || e.shiftKey || !(e.ctrlKey || e.metaKey)) return false;
		return e.key.toLowerCase() === 'k';
	}

	/** The count announced after the list changes. Empty for zero: "No results" says that. */
	export function countAnnouncement(count: number): string {
		if (count === 0) return '';
		return count === 1 ? '1 result' : `${count} results`;
	}
</script>

<script lang="ts">
	/*
	 * A command menu: a Dialog holding a text combobox and a listbox of rows, after the Geist
	 * command menu and the WAI-ARIA combobox pattern with a listbox popup. Focus stays in the
	 * input; the highlighted row is named by aria-activedescendant. Typing filters by label.
	 *
	 * Ctrl or Cmd plus K toggles it. An empty input shows every item: a "recent" or "suggested"
	 * order is the app's job, so pass items in that order. The highlight is a fill step on the
	 * row (bg-canvas-subtle, 6px corners, inset 8px from the panel): rows never move or scale.
	 *
	 * The panel leads with the input, after Linear, Raycast and cmdk: the label stays the
	 * dialog's heading and name but is hidden from view, and the input fills Dialog's head row
	 * beside "Close". That row's hairline is the one line under the input, so the input drops the
	 * control utility's bottom edge here; focus never leaves it while the menu is open, so the
	 * global focus outline marks it, with 8px of row around it on every side.
	 */
	import { onMount, tick } from 'svelte';
	import Dialog from './Dialog.svelte';

	interface Props {
		/** Whether the menu is open. Bindable: bind:open. Closing clears the query. */
		open?: boolean;
		/** The rows, in the order to show them when the input is empty. Each id must be unique. */
		items: CommandItem[];
		/** Called when a row is chosen (Enter or click), before the menu closes. Return false to stop the navigation to item.href. */
		onselect?: (item: CommandItem) => void | boolean;
		/** The menu's title, and the name of its input and listbox: "Search the docs". */
		label: string;
		/** Hint text in the empty input: say what can be found. */
		placeholder?: string;
		/** Whether Ctrl+K or Cmd+K toggles the menu. Turn it off when the app already uses that key. */
		shortcut?: boolean;
	}

	let {
		open = $bindable(false),
		items,
		onselect,
		label,
		placeholder = 'Type to filter',
		shortcut = true
	}: Props = $props();

	const uid = $props.id();
	const listboxId = [uid, 'listbox'].join('-');

	let query = $state('');
	let highlighted = $state(0);
	let input: HTMLInputElement | undefined = $state();

	const results = $derived(filterItems(items, query));
	const sections = $derived(groupItems(results));
	const active = $derived(results.length === 0 ? -1 : Math.min(highlighted, results.length - 1));
	// The count is said after typing, not on open: opening already announces the dialog.
	const countText = $derived(query.trim() === '' ? '' : countAnnouncement(results.length));
	const optionId = (index: number) => [uid, 'option', index].join('-');

	// A fresh menu each time: no stale query, first row highlighted.
	$effect(() => {
		if (!open) {
			query = '';
			highlighted = 0;
		}
	});

	// Keep the highlighted row in view inside the list's own scroll.
	$effect(() => {
		if (!open || active < 0) return;
		void tick().then(() =>
			document.getElementById(optionId(active))?.scrollIntoView({ block: 'nearest' })
		);
	});

	onMount(() => {
		if (!shortcut) return;
		const onKeydown = (e: KeyboardEvent) => {
			const t = e.target;
			const typingElsewhere =
				t instanceof HTMLElement &&
				t !== input &&
				(t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
			if (!isShortcut(e, typingElsewhere)) return;
			// Only now is the key ours: everything else keeps its default.
			e.preventDefault();
			open = !open;
		};
		window.addEventListener('keydown', onKeydown);
		return () => window.removeEventListener('keydown', onKeydown);
	});

	function choose(item: CommandItem) {
		const handled = onselect?.(item);
		open = false;
		if (item.href && handled !== false) window.location.assign(item.href);
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.ctrlKey || e.metaKey || e.altKey) return;
		// A key that commits or moves through an IME candidate is the IME's, not the menu's.
		if (e.isComposing || e.keyCode === 229) return;
		const next = moveHighlight(active, e.key, results.length);
		if (next !== null) {
			e.preventDefault();
			highlighted = next;
		} else if (e.key === 'Enter' && active >= 0) {
			e.preventDefault();
			choose(results[active]);
		}
	}
</script>

<Dialog bind:open title={label} titleHidden>
	{#snippet head()}
		<!-- The control utility's input, with its bottom edge handed to the row's hairline. -->
		<input
			bind:this={input}
			bind:value={query}
			type="text"
			role="combobox"
			aria-label={label}
			aria-expanded={results.length > 0}
			aria-controls={listboxId}
			aria-autocomplete="list"
			aria-activedescendant={active >= 0 ? optionId(active) : undefined}
			autocomplete="off"
			autocapitalize="off"
			spellcheck="false"
			{placeholder}
			data-autofocus
			class="control border-b-0 px-2"
			oninput={() => (highlighted = 0)}
			onkeydown={handleKeydown}
		/>
	{/snippet}

	<!-- Pointer presses must not take focus from the input. -->
	<div
		id={listboxId}
		role="listbox"
		tabindex="-1"
		aria-label={label}
		class="qd-command-list"
		onmousedown={(e) => e.preventDefault()}
	>
		{#each sections as section, s (s)}
			{#if section.group}
				<div role="group" aria-labelledby="{uid}-group-{s}" class="pt-2 first:pt-0">
					<div id="{uid}-group-{s}" class="px-3 pt-2 pb-1 type-meta">{section.group}</div>
					{@render rows(section)}
				</div>
			{:else}
				{@render rows(section)}
			{/if}
		{/each}
	</div>

	<p role="status" class={['type-small', results.length === 0 && 'px-3 py-3']}>
		{results.length === 0 ? 'No results' : ''}
	</p>
	<p role="status" class="sr-only">{countText}</p>
</Dialog>

{#snippet rows(section: CommandSection)}
	{#each section.rows as row (row.item.id)}
		<!-- Keyboard users act through the input; the row's click is the pointer path. -->
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<div
			id={optionId(row.index)}
			role="option"
			tabindex="-1"
			aria-selected={row.index === active}
			class="qd-command-option flex min-h-target cursor-pointer items-center justify-between gap-4 rounded-md px-3 type-label aria-selected:bg-canvas-subtle"
			onclick={() => choose(row.item)}
			onpointermove={() => {
				if (highlighted !== row.index) highlighted = row.index;
			}}
		>
			<span>{row.item.label}</span>
			{#if row.item.hint}<span class="shrink-0 type-meta">{row.item.hint}</span>{/if}
		</div>
	{/each}
{/snippet}

<style>
	.qd-command-list {
		max-height: calc(var(--space-unit) * 80);
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	/* The highlight is a fill, which forced colours erase: give it the system highlight pair. */
	@media (forced-colors: active) {
		.qd-command-option[aria-selected='true'] {
			background-color: Highlight;
			color: HighlightText;
		}
	}
</style>
