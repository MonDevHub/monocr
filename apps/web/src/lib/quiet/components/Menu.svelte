<script lang="ts" module>
	export interface MenuItem {
		/** Stable id, passed back through onselect. */
		id: string;
		/** The row's text, and what typing a letter jumps to. */
		label: string;
		/** Runs when the item is activated, before the menu closes. */
		onselect?: () => void;
		/** Makes the item a link: activating it navigates here. */
		href?: string;
		/** Shown in fg-secondary, not activatable and skipped by the arrow keys. */
		disabled?: boolean;
	}

	export interface MenuSeparator {
		/** A hairline between two groups of items. */
		separator: true;
	}

	export type MenuEntry = MenuItem | MenuSeparator;

	/** What a custom trigger must spread onto its one button. */
	export interface MenuTrigger {
		id: string;
		'aria-haspopup': 'menu';
		'aria-expanded': boolean;
		'aria-controls': string;
		popovertarget: string;
		onkeydown: (e: KeyboardEvent) => void;
	}

	/** How long a typed letter keeps counting towards the prefix. */
	export const MENU_TYPEAHEAD_MS = 500;

	export function isSeparator(entry: MenuEntry): entry is MenuSeparator {
		return 'separator' in entry;
	}

	/**
	 * The row to focus after a key, as the WAI-ARIA menu pattern has it. `enabled` says, per
	 * row in order, whether the row can take focus. ArrowDown and ArrowUp skip disabled rows and
	 * wrap; Home and End go to the first and last enabled row. From no row (-1) ArrowDown goes
	 * to the first and ArrowUp to the last. Null when the key is not ours or no row is enabled.
	 */
	export function moveFocus(enabled: boolean[], current: number, key: string): number | null {
		const n = enabled.length;
		if (!enabled.some(Boolean)) return null;
		const step = (from: number, dir: 1 | -1) => {
			let at = from;
			for (let i = 0; i < n; i++) {
				at = (at + dir + n) % n;
				if (enabled[at]) return at;
			}
			return null;
		};
		switch (key) {
			case 'ArrowDown':
				return step(current < 0 ? -1 : current, 1);
			case 'ArrowUp':
				return step(current < 0 ? 0 : current, -1);
			case 'Home':
				return step(-1, 1);
			case 'End':
				return step(0, -1);
			default:
				return null;
		}
	}

	/**
	 * The typed prefix after a key. A printable character within `windowMs` of the last one
	 * extends the prefix; after the window it starts a new one. Null when the key is not a
	 * single printable character (Enter, ArrowDown and the like), or is Space, which activates.
	 */
	export function nextPrefix(
		prev: { text: string; at: number },
		key: string,
		now: number,
		windowMs: number = MENU_TYPEAHEAD_MS
	): { text: string; at: number } | null {
		if (key.length !== 1 || key === ' ') return null;
		const text = now - prev.at <= windowMs ? prev.text + key.toLowerCase() : key.toLowerCase();
		return { text, at: now };
	}

	/**
	 * The enabled row whose label starts with `prefix`, case-insensitive, searching on from the
	 * current row and wrapping. One repeated letter ("a", then "a" again) steps through the rows
	 * that start with it; a longer prefix may stay on the current row. Null when nothing matches.
	 */
	export function matchPrefix(
		labels: string[],
		enabled: boolean[],
		current: number,
		prefix: string
	): number | null {
		const n = labels.length;
		if (n === 0 || prefix === '') return null;
		const repeated = prefix.length > 1 && [...prefix].every((c) => c === prefix[0]);
		const needle = repeated ? prefix[0] : prefix;
		const start = current < 0 ? 0 : current + (needle.length === 1 ? 1 : 0);
		for (let i = 0; i < n; i++) {
			const at = (start + i) % n;
			if (enabled[at] && labels[at].toLowerCase().startsWith(needle)) return at;
		}
		return null;
	}
</script>

<script lang="ts">
	/*
	 * A menu of actions behind one button, after the Geist menu and Primer's ActionMenu, on
	 * the WAI-ARIA menu button pattern. It is for actions ("Rename", "Delete"), never for
	 * moving around the site: that is Nav. The popup is a popover="auto" in the top layer, so
	 * Escape and a press outside close it and the browser returns focus to the trigger. It sits
	 * 8px under the trigger through placeTip, flipped above when it does not fit, and is moved
	 * again on scroll and resize while open.
	 *
	 * Focus lives on the rows (a roving tabindex, no aria-activedescendant), so a screen reader
	 * reads each row as it is reached. Rows are 44px; the highlight is bg-canvas-subtle on a
	 * 6px-cornered row inside a 6px inset, so nothing is cut at the panel's edge. The panel has
	 * a hairline edge and no shadow.
	 */
	import type { Snippet } from 'svelte';
	import Icon from './Icon.svelte';
	import { placeTip, TOOLTIP_GAP } from './Tooltip.svelte';

	interface Props {
		/** The trigger's text. A verb or a noun that says what the menu holds: "Actions", "Sort by". */
		label: string;
		/** The rows, in order. A `{ separator: true }` entry draws a hairline between two groups. */
		items: MenuEntry[];
		/** Which edge of the trigger the menu lines up with. Use 'end' for a trigger at the right edge of a row. */
		align?: 'start' | 'end';
		/** Replaces the default button. It receives the props to spread onto its one button: {#snippet trigger(props)}. */
		trigger?: Snippet<[MenuTrigger]>;
		/** Called with the item after it is activated, once its own onselect has run. */
		onselect?: (item: MenuItem) => void;
	}

	const { label, items, align = 'start', trigger, onselect }: Props = $props();

	const uid = $props.id();
	const triggerId = 'qd-menu-trigger-' + uid;
	const menuId = 'qd-menu-' + uid;

	let anchor: HTMLElement | undefined = $state();
	let menu: HTMLElement | undefined = $state();
	let open = $state(false);
	let want: 'first' | 'last' = 'first';
	let typed = { text: '', at: -1e9 };

	const rows = $derived(items.filter((e): e is MenuItem => !isSeparator(e)));
	const enabled = $derived(rows.map((r) => !r.disabled));

	const itemEls = () => (menu ? [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')] : []);
	const triggerEl = () => document.getElementById(triggerId);

	function focusRow(i: number | null) {
		if (i !== null) itemEls()[i]?.focus();
	}

	// Focus the row the key asked for. A popup the browser has not finished displaying cannot take
	// focus yet, so try again for a few frames until it does. It is called from the toggle event,
	// not from a state change: a quick close and reopen is reported as one "open" event, and the
	// state would not change.
	let frame = 0;
	function focusWanted(tries = 0) {
		cancelAnimationFrame(frame);
		const target = moveFocus(enabled, -1, want === 'first' ? 'ArrowDown' : 'ArrowUp');
		focusRow(target);
		if (target !== null && document.activeElement !== itemEls()[target] && tries < 10)
			frame = requestAnimationFrame(() => focusWanted(tries + 1));
	}

	function show(which: 'first' | 'last') {
		want = which;
		if (menu && !menu.matches(':popover-open')) menu.showPopover();
		else focusWanted();
	}

	function close() {
		if (menu?.matches(':popover-open')) menu.hidePopover();
		triggerEl()?.focus();
	}

	function onTriggerKey(e: KeyboardEvent) {
		// Opened with a press, focus is still on the trigger: Tab or Shift+Tab walks away from it,
		// so the menu closes rather than stay open over a page the reader has left.
		if (e.key === 'Tab') {
			if (menu?.matches(':popover-open')) menu.hidePopover();
			return;
		}
		if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
		if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
			e.preventDefault();
			show(e.key === 'ArrowDown' ? 'first' : 'last');
		} else if (e.key === 'Enter' || e.key === ' ') {
			// The button's own click opens it through popovertarget; focus starts on the first row.
			want = 'first';
		}
	}

	const triggerProps = $derived<MenuTrigger>({
		id: triggerId,
		'aria-haspopup': 'menu',
		'aria-expanded': open,
		'aria-controls': menuId,
		popovertarget: menuId,
		onkeydown: onTriggerKey
	});

	function onToggle(e: Event) {
		open = (e as ToggleEvent).newState === 'open';
		if (open) focusWanted();
		if (!open) {
			cancelAnimationFrame(frame);
			// Escape and an outside press: focus goes back to the trigger unless it went to another control.
			const at = document.activeElement;
			if (!at || at === document.body || menu?.contains(at)) triggerEl()?.focus();
			want = 'first';
		}
	}

	function onMenuKey(e: KeyboardEvent) {
		if (e.altKey || e.ctrlKey || e.metaKey) return;
		const els = itemEls();
		const current = els.indexOf(document.activeElement as HTMLElement);
		if (e.key === 'Tab') {
			// Close and let Tab carry on from the trigger.
			close();
			return;
		}
		if (e.key === ' ' && current >= 0) {
			e.preventDefault();
			els[current].click();
			return;
		}
		const moved = moveFocus(enabled, current, e.key);
		if (moved !== null) {
			e.preventDefault();
			focusRow(moved);
			return;
		}
		const next = nextPrefix(typed, e.key, performance.now());
		if (!next) return;
		e.preventDefault();
		typed = next;
		focusRow(
			matchPrefix(
				rows.map((r) => r.label),
				enabled,
				current,
				next.text
			)
		);
	}

	function activate(e: MouseEvent, item: MenuItem) {
		if (item.disabled) {
			e.preventDefault();
			return;
		}
		item.onselect?.();
		onselect?.(item);
		close();
	}

	$effect(() => {
		if (!menu || !anchor || !open) return;
		const el = menu;
		const from = anchor;
		const place = () => {
			el.style.minWidth = `${from.offsetWidth}px`;
			const a = from.getBoundingClientRect();
			const size = { width: el.offsetWidth, height: el.offsetHeight };
			// placeTip centres the tip on its anchor. Giving it an anchor as wide as the menu, at the
			// edge to line up with, turns that centring into start or end alignment.
			const edge = align === 'end' ? a.right - size.width : a.left;
			const spot = placeTip(
				{ top: a.top, bottom: a.bottom, left: edge, width: size.width, height: a.height },
				size,
				{ width: document.documentElement.clientWidth, height: window.innerHeight },
				'bottom',
				TOOLTIP_GAP
			);
			el.style.top = `${spot.top}px`;
			el.style.left = `${spot.left}px`;
			el.dataset.side = spot.side;
		};
		place();
		window.addEventListener('scroll', place, true);
		window.addEventListener('resize', place);
		return () => {
			window.removeEventListener('scroll', place, true);
			window.removeEventListener('resize', place);
		};
	});
</script>

<span bind:this={anchor} class="inline-flex">
	{#if trigger}
		{@render trigger(triggerProps)}
	{:else}
		<button
			type="button"
			{...triggerProps}
			class="inline-flex min-h-target cursor-pointer items-center gap-2 rounded-md hairline-box border-link-underline bg-canvas px-4 type-label transition-colors duration-150 hover:bg-canvas-subtle"
		>
			{label}
			<Icon name="chevron-down" size={16} />
		</button>
	{/if}
</span>
<div
	bind:this={menu}
	id={menuId}
	role="menu"
	aria-label={label}
	popover="auto"
	tabindex="-1"
	class="qd-menu max-w-xs overlay rounded-lg hairline-box border-border bg-canvas p-1.5"
	ontoggle={onToggle}
	onkeydown={onMenuKey}
>
	{#each items as entry, i (isSeparator(entry) ? 'separator-' + i : entry.id)}
		{#if isSeparator(entry)}
			<div role="separator" class="my-1.5 hairline-t border-border/50"></div>
		{:else if entry.href}
			<a
				role="menuitem"
				href={entry.disabled ? undefined : entry.href}
				aria-disabled={entry.disabled || undefined}
				tabindex="-1"
				class={[
					'flex min-h-target items-center rounded-md px-3 transition-colors duration-150',
					entry.disabled
						? 'cursor-not-allowed type-subtitle opacity-50'
						: 'cursor-pointer type-label hover:bg-canvas-subtle focus-visible:bg-canvas-subtle'
				]}
				onclick={(e) => activate(e, entry)}>{entry.label}</a
			>
		{:else}
			<button
				type="button"
				role="menuitem"
				aria-disabled={entry.disabled || undefined}
				tabindex="-1"
				class={[
					'flex min-h-target w-full items-center rounded-md px-3 text-left transition-colors duration-150',
					entry.disabled
						? 'cursor-not-allowed type-subtitle opacity-50'
						: 'cursor-pointer type-label hover:bg-canvas-subtle focus-visible:bg-canvas-subtle'
				]}
				onclick={(e) => activate(e, entry)}>{entry.label}</button
			>
		{/if}
	{/each}
</div>

<style>
	/* A popover is centred by the browser; it is placed from the trigger's box instead. It grows
	   out of the trigger, and a long list scrolls inside the viewport. */
	.qd-menu {
		position: fixed;
		inset: auto;
		margin: 0;
		width: max-content;
		max-height: calc(100dvh - var(--space-unit) * 4);
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	.qd-menu:global([data-side='bottom']) {
		transform-origin: top center;
	}
	.qd-menu:global([data-side='top']) {
		transform-origin: bottom center;
	}
	/* On a narrow screen the menu keeps 8px from both edges. */
	@media (max-width: 22rem) {
		.qd-menu {
			max-width: calc(100vw - var(--space-unit) * 4);
		}
	}
</style>
