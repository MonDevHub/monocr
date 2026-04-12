<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { m } from '$lib/paraglide/messages';

	interface Props {
		items: { label: string; href?: string }[];
	}

	const { items }: Props = $props();
</script>

<nav
	class="mb-12 flex items-center space-x-2 font-bold tracking-[0.2em] text-[var(--text-meta)] uppercase opacity-50"
	aria-label="Breadcrumb"
>
	<a href="/" class="hover:text-fg-primary focus-ring rounded-sm transition-colors"
		>{m.breadcrumb_home()}</a
	>
	{#each items as item (item.label)}
		<span class="text-fg-muted text-[10px]">/</span>
		{#if item.href}
			<a href={item.href} class="hover:text-fg-primary focus-ring rounded-sm transition-colors">
				{item.label}
			</a>
		{:else}
			<span class="text-fg-primary font-bold">{item.label}</span>
		{/if}
	{/each}
</nav>
