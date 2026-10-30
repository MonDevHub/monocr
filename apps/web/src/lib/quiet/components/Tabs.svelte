<script lang="ts" module>
	export interface TabItem {
		/** Stable id, used for `value` and to wire the tab to its panel. */
		id: string;
		/** The tab's visible name, sentence case, one or two words. */
		label: string;
		/** Not selectable and skipped by the arrow keys. */
		disabled?: boolean;
	}

	/**
	 * The WAI-ARIA tabs keyboard model as a pure function. `ids` are the enabled tabs in
	 * order; the result is the tab to select and focus, or null when the key is not ours.
	 * ArrowRight and ArrowLeft wrap; Home and End go to the ends.
	 */
	export function nextTab(ids: string[], current: string, key: string): string | null {
		if (ids.length === 0) return null;
		const at = ids.indexOf(current);
		switch (key) {
			case 'ArrowRight':
				return ids[(at + 1) % ids.length];
			case 'ArrowLeft':
				return ids[(at <= 0 ? ids.length : at) - 1];
			case 'Home':
				return ids[0];
			case 'End':
				return ids[ids.length - 1];
			default:
				return null;
		}
	}
</script>

<script lang="ts">
	/*
	 * Tabs: switch between panels of one page, in place. A row of buttons on a hairline; the
	 * selected one has a 1px underline in the text colour and the primary ink. Selection follows
	 * focus, instantly (the panels are already on the page, so nothing to wait for). Not a
	 * <nav>: tabs change a panel, not the page. Mirroring the selection in the URL (a ?tab=
	 * query or a hash) is the app's job: bind `value` and write it where your router wants it.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** The tabs, in order. */
		items: TabItem[];
		/** The selected tab's id; bind it to read or set the selection. An empty, unknown or disabled id shows the first enabled tab without writing it back. */
		value?: string;
		/** The tab list's name for screen readers ("Project sections"); not shown on screen. */
		label: string;
		/** Renders a panel's content from its tab id. It runs only for the selected tab. */
		panel: Snippet<[string]>;
		/** Puts the panel in the tab order, so Tab moves from the tabs to the panel. Set it to false when the panel holds a link or control of its own. */
		panelFocusable?: boolean;
	}

	let { items, value = $bindable(''), label, panel, panelFocusable = true }: Props = $props();

	const uid = $props.id();
	const tabId = (id: string) => uid + '-tab-' + id;
	const panelId = (id: string) => uid + '-panel-' + id;

	const enabled = $derived(items.filter((i) => !i.disabled).map((i) => i.id));
	const current = $derived(enabled.includes(value) ? value : (enabled[0] ?? ''));

	function select(id: string) {
		value = id;
	}

	function onkeydown(event: KeyboardEvent, id: string) {
		if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
		const next = nextTab(enabled, id, event.key);
		if (next === null) return;
		event.preventDefault();
		select(next);
		document.getElementById(tabId(next))?.focus();
	}
</script>

<div>
	<div role="tablist" aria-label={label} class="flex overflow-x-auto hairline-b border-border/50">
		{#each items as item (item.id)}
			{@const selected = item.id === current}
			<button
				type="button"
				role="tab"
				id={tabId(item.id)}
				aria-selected={selected}
				aria-controls={panelId(item.id)}
				tabindex={selected ? 0 : -1}
				disabled={item.disabled}
				onclick={() => select(item.id)}
				onkeydown={(e) => onkeydown(e, item.id)}
				class={[
					'relative min-h-target shrink-0 cursor-pointer px-4 whitespace-nowrap transition-colors duration-150 hover:text-fg-primary disabled:cursor-not-allowed disabled:hover:text-fg-secondary',
					selected ? 'type-label' : 'type-subtitle'
				]}
			>
				{item.label}
				<span
					aria-hidden="true"
					class={[
						'pointer-events-none absolute inset-x-0 bottom-0 border-b border-fg-primary transition-opacity duration-150',
						selected ? 'opacity-100' : 'opacity-0'
					]}
				></span>
			</button>
		{/each}
	</div>
	{#each items as item (item.id)}
		{@const selected = item.id === current}
		<div
			role="tabpanel"
			id={panelId(item.id)}
			aria-labelledby={tabId(item.id)}
			tabindex={panelFocusable ? 0 : undefined}
			hidden={!selected}
			class="pt-4"
		>
			{#if selected}
				{@render panel(item.id)}
			{/if}
		</div>
	{/each}
</div>
