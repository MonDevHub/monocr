<script lang="ts">
	/*
	 * Header navigation in two shapes. `bar`: a row of 14px capitals from 768px, the current
	 * page underlined 8px below in fg-primary. `drawer`: 18px capitals in 44px rows with a
	 * 1px left marker on the current page. Labels are stored in sentence case; CSS sets the
	 * capitals. External links open in a new tab. Header renders both; you rarely need this
	 * on its own.
	 */
	import type { Snippet } from 'svelte';
	import type { NavItem } from '../types';
	import { isExternal } from '../external';

	interface Props {
		/** The entries, in order. Keep it to five or fewer. */
		items: NavItem[];
		/** The current path (page.url.pathname). Marks the matching entry, and the entry for its parent section. */
		current: string;
		/** `bar` for the header row from 768px, `drawer` for the mobile panel. */
		variant?: 'bar' | 'drawer';
		/** Accessible name of the <nav> landmark. Change it when the site is not in English. */
		label?: string;
		/** Called on every link click. Header uses it to close the drawer. */
		onnavigate?: () => void;
		/** Controls after the links, past a divider (border-l in the bar, border-t in the drawer). Without controls, no divider is drawn. */
		controls?: Snippet;
	}

	const { items, current, variant = 'bar', label, onnavigate, controls }: Props = $props();

	function state(href: string): 'page' | 'section' | undefined {
		if (current === href) return 'page';
		if (href !== '/' && current.startsWith(`${href}/`)) return 'section';
		return undefined;
	}
</script>

{#if variant === 'bar'}
	<nav
		class="pointer-events-auto ml-auto hidden items-center space-x-6 md:flex"
		aria-label={label ?? 'Main navigation'}
	>
		{#each items as item (item.href)}
			{@const at = state(item.href)}
			{@const external = isExternal(item.href)}
			<a
				href={item.href}
				target={external ? '_blank' : undefined}
				rel={external ? 'noopener noreferrer' : undefined}
				aria-current={at === 'page' ? 'page' : at === 'section' ? 'true' : undefined}
				onclick={onnavigate}
				class={[
					'py-3 type-nav transition-colors focus-visible:rounded-sm',
					at
						? 'text-fg-primary underline underline-offset-8'
						: 'text-fg-secondary hover:text-fg-primary'
				]}>{item.label}</a
			>
		{/each}
		{#if controls}
			<div class="flex items-center space-x-3 border-l border-border pl-6">
				{@render controls()}
			</div>
		{/if}
	</nav>
{:else}
	<nav
		class="-mt-2 flex flex-col space-y-2 type-nav-drawer text-fg-secondary"
		aria-label={label ?? 'Mobile navigation'}
	>
		{#each items as item (item.href)}
			{@const at = state(item.href)}
			{@const external = isExternal(item.href)}
			<a
				href={item.href}
				target={external ? '_blank' : undefined}
				rel={external ? 'noopener noreferrer' : undefined}
				aria-current={at === 'page' ? 'page' : at === 'section' ? 'true' : undefined}
				onclick={onnavigate}
				class={[
					'flex min-h-target items-center border-l pl-4 transition-colors focus-visible:rounded-sm',
					at
						? 'border-fg-accent text-fg-primary'
						: 'border-transparent hover:border-border hover:text-fg-primary'
				]}>{item.label}</a
			>
		{/each}
		{#if controls}
			<div class="mt-2 border-t border-border pt-6">
				<div class="flex items-center justify-start gap-6">
					{@render controls()}
				</div>
			</div>
		{/if}
	</nav>
{/if}
