<script lang="ts" module>
	/** One entry: a folder has `children`, a file may have an `href`. */
	export interface TreeItem {
		name: string;
		href?: string;
		children?: TreeItem[];
	}

	/** One visible row of the flattened tree. `path` is the names joined with "/", unique per item. */
	export interface TreeRow {
		path: string;
		name: string;
		href?: string;
		folder: boolean;
		open: boolean;
		/** 0 for the top level. */
		depth: number;
		/** Index of the parent row in the same list, or -1 at the top. */
		parent: number;
		/** 1-based position among its siblings, and how many siblings there are. */
		pos: number;
		size: number;
	}

	/** The rows a reader can see: a folder's children only while it is in `open`. */
	export function visibleRows(items: TreeItem[], open: readonly string[]): TreeRow[] {
		const out: TreeRow[] = [];
		const isOpen = new Set(open);
		const walk = (list: TreeItem[], depth: number, parent: number, prefix: string) => {
			list.forEach((item, i) => {
				const path = prefix ? prefix + '/' + item.name : item.name;
				const folder = item.children !== undefined;
				const shown = folder && isOpen.has(path);
				const at = out.length;
				out.push({
					path,
					name: item.name,
					href: item.href,
					folder,
					open: shown,
					depth,
					parent,
					pos: i + 1,
					size: list.length
				});
				if (shown) walk(item.children ?? [], depth + 1, at, path);
			});
		};
		walk(items, 0, -1, '');
		return out;
	}

	export type TreeAction =
		| { type: 'focus'; index: number }
		| { type: 'open'; path: string }
		| { type: 'close'; path: string }
		| { type: 'openSiblings'; paths: string[] }
		| { type: 'activate' };

	/**
	 * The WAI-ARIA treeview keyboard map, as a pure function of the visible rows. `index` is
	 * the focused row. Returns what to do, or null when the key means nothing here (the
	 * caller then leaves the event alone). Type-ahead is separate: see typeAheadIndex.
	 *   Down / Up       next / previous visible row (no wrap)
	 *   Right           closed folder: open it; open folder: first child; file: nothing
	 *   Left            open folder: close it; otherwise: the parent; top level: nothing
	 *   Home / End      first / last visible row
	 *   Enter / Space   activate (a folder toggles, a link follows)
	 *   *               open every folder that shares the focused row's parent
	 */
	export function treeKeyAction(
		rows: readonly TreeRow[],
		index: number,
		key: string
	): TreeAction | null {
		const row = rows[index];
		if (!row) return null;
		switch (key) {
			case 'ArrowDown':
				return index < rows.length - 1 ? { type: 'focus', index: index + 1 } : null;
			case 'ArrowUp':
				return index > 0 ? { type: 'focus', index: index - 1 } : null;
			case 'Home':
				return { type: 'focus', index: 0 };
			case 'End':
				return { type: 'focus', index: rows.length - 1 };
			case 'ArrowRight':
				if (!row.folder) return null;
				if (!row.open) return { type: 'open', path: row.path };
				return rows[index + 1] && rows[index + 1].depth > row.depth
					? { type: 'focus', index: index + 1 }
					: null;
			case 'ArrowLeft':
				if (row.folder && row.open) return { type: 'close', path: row.path };
				return row.parent >= 0 ? { type: 'focus', index: row.parent } : null;
			case 'Enter':
			case ' ':
				return { type: 'activate' };
			case '*':
				return {
					type: 'openSiblings',
					paths: rows
						.filter((r) => r.folder && r.parent === row.parent && r.depth === row.depth)
						.map((r) => r.path)
				};
			default:
				return null;
		}
	}

	/**
	 * Type-ahead: the index of the next row whose name starts with `query` (case-insensitive),
	 * searching from the row after `from` and wrapping, or -1. One repeated letter ("ss") cycles
	 * through the rows that start with it; a longer query matches from the focused row itself,
	 * so typing "sr" while on "src" stays put.
	 */
	export function typeAheadIndex(names: readonly string[], from: number, query: string): number {
		const q = query.toLowerCase();
		if (!q || names.length === 0) return -1;
		const repeated = [...q].every((c) => c === q[0]);
		const needle = repeated ? q[0] : q;
		const start = repeated ? from + 1 : from;
		for (let step = 0; step < names.length; step++) {
			const i = (((start + step) % names.length) + names.length) % names.length;
			if (names[i].toLowerCase().startsWith(needle)) return i;
		}
		return -1;
	}

	/** How long a typed prefix stays alive between keystrokes. Not an animation. */
	export const TYPE_AHEAD_MS = 500;

	/** Indentation by depth, as whole class names (rules in the style block below). Depth 6 and deeper share the last. */
	export const DEPTH_CLASSES = [
		'qd-tree-depth-0',
		'qd-tree-depth-1',
		'qd-tree-depth-2',
		'qd-tree-depth-3',
		'qd-tree-depth-4',
		'qd-tree-depth-5',
		'qd-tree-depth-6'
	] as const;
</script>

<script lang="ts">
	/*
	 * A file listing as one tab stop: the WAI-ARIA treeview pattern with a roving tabindex.
	 * Rows are flat (aria-level, aria-posinset, aria-setsize) rather than nested role="group"
	 * lists, so the focus outline wraps one row and not a folder's whole subtree. A folder is
	 * its name, a "/" and a "+" or "−" word glyph; files are plain. No icons, no colour.
	 */
	import { tick } from 'svelte';

	interface Props {
		/** The tree's accessible name, saying what it lists ("Project files"). */
		label: string;
		/** The entries. An item with `children` (even `[]`) is a folder; one with an `href` and no `children` is a link. */
		items: TreeItem[];
		/** Paths of the open folders: names joined with "/" ("src", "src/lib"). Sets which start open; bind it to read the reader's changes. */
		open?: string[];
		/** Path of the current item. That row gets `aria-current="page"` and `type-label`, and is the tab stop until the reader focuses another row. */
		current?: string;
	}

	let { label, items, open = $bindable([]), current }: Props = $props();

	const rows = $derived(visibleRows(items, open));
	let focused = $state<string | undefined>(undefined);
	/** Rows animate in only after the first interaction, so nothing moves at page load. */
	let interacted = $state(false);
	let tree: HTMLDivElement | undefined = $state();

	/** The one tab stop: the focused row while it is visible, else the current one, else the first. */
	const stop = $derived(
		rows.find((r) => r.path === focused)?.path ??
			rows.find((r) => r.path === current)?.path ??
			rows[0]?.path
	);

	let typed = '';
	let typedTimer: ReturnType<typeof setTimeout> | undefined;
	$effect(() => () => clearTimeout(typedTimer));

	async function focusRow(index: number) {
		const row = rows[index];
		if (!row) return;
		focused = row.path;
		await tick();
		tree
			?.querySelectorAll<HTMLElement>('[role="treeitem"]')
			.forEach((el) => el.dataset.path === row.path && el.focus());
	}

	function setOpen(path: string, on: boolean) {
		interacted = true;
		open = on ? [...open.filter((p) => p !== path), path] : open.filter((p) => p !== path);
	}

	function onkeydown(event: KeyboardEvent, index: number) {
		if (event.ctrlKey || event.metaKey || event.altKey) return;
		const row = rows[index];
		const action = treeKeyAction(rows, index, event.key);
		if (action) {
			if (action.type === 'activate') {
				// A link keeps its native Enter; a folder toggles; a plain file does nothing.
				if (row.folder) {
					event.preventDefault();
					setOpen(row.path, !row.open);
				} else if (!row.href && event.key === ' ') event.preventDefault();
				return;
			}
			event.preventDefault();
			if (action.type === 'focus') focusRow(action.index);
			else if (action.type === 'open') setOpen(action.path, true);
			else if (action.type === 'close') setOpen(action.path, false);
			else {
				interacted = true;
				open = [...new Set([...open, ...action.paths])];
			}
			return;
		}
		if (event.key.length === 1 && event.key !== ' ') {
			typed += event.key;
			clearTimeout(typedTimer);
			typedTimer = setTimeout(() => (typed = ''), TYPE_AHEAD_MS);
			const hit = typeAheadIndex(
				rows.map((r) => r.name),
				index,
				typed
			);
			if (hit >= 0) {
				event.preventDefault();
				focusRow(hit);
			}
		}
	}

	function onclick(event: MouseEvent, row: TreeRow) {
		focused = row.path;
		if (row.folder) {
			event.preventDefault();
			setOpen(row.path, !row.open);
		}
	}
</script>

<!-- The tree is one named widget; its rows are the tab stop and the keyboard handlers. -->
<div role="tree" aria-label={label} bind:this={tree}>
	{#each rows as row, index (row.path)}
		<svelte:element
			this={row.href && !row.folder ? 'a' : 'div'}
			role="treeitem"
			href={row.href && !row.folder ? row.href : undefined}
			tabindex={row.path === stop ? 0 : -1}
			aria-level={row.depth + 1}
			aria-posinset={row.pos}
			aria-setsize={row.size}
			aria-expanded={row.folder ? row.open : undefined}
			aria-current={row.path === current ? 'page' : undefined}
			data-path={row.path}
			class={[
				'flex min-h-target w-full items-center gap-2 hover:bg-canvas-subtle/50',
				row.path === current ? 'type-label' : 'type-small',
				row.folder && 'cursor-pointer',
				DEPTH_CLASSES[Math.min(row.depth, DEPTH_CLASSES.length - 1)],
				interacted && row.depth > 0 && 'qd-tree-enter'
			]}
			onfocus={() => (focused = row.path)}
			onclick={(e: MouseEvent) => onclick(e, row)}
			onkeydown={(e: KeyboardEvent) => onkeydown(e, index)}
		>
			<span aria-hidden="true" class="inline-block w-4 shrink-0"
				>{row.folder ? (row.open ? '−' : '+') : ''}</span
			>
			<span class="wrap-anywhere"
				>{row.name}{#if row.folder}<span aria-hidden="true">/</span>{/if}</span
			>
		</svelte:element>
	{/each}
</div>

<style>
	/* 16px per level, from an 8px start. Whole class names are picked in the script. */
	.qd-tree-depth-0 {
		padding-inline: calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-1 {
		padding-inline: calc(var(--space-unit) * 6) calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-2 {
		padding-inline: calc(var(--space-unit) * 10) calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-3 {
		padding-inline: calc(var(--space-unit) * 14) calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-4 {
		padding-inline: calc(var(--space-unit) * 18) calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-5 {
		padding-inline: calc(var(--space-unit) * 22) calc(var(--space-unit) * 2);
	}
	.qd-tree-depth-6 {
		padding-inline: calc(var(--space-unit) * 26) calc(var(--space-unit) * 2);
	}

	/* A row opened by the reader fades in: opacity only, over --motion-fast, no height. It
	   starts only on rows inserted after the first interaction, so a page load animates nothing. */
	.qd-tree-enter {
		transition: opacity var(--motion-fast) var(--motion-ease-out);
	}
	@starting-style {
		.qd-tree-enter {
			opacity: 0;
		}
	}
</style>
