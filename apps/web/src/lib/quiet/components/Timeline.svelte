<script lang="ts">
	/*
	 * A career or history, newest first. From 768px three columns: the period right-aligned
	 * on the left, a 12px dot on a 1px line, the entry. Below 768px the period sits on top.
	 * The period is last in the DOM (read after the role it belongs to) and first on screen.
	 * `connected={false}` drops the dot and line for a single item (a degree): it keeps the
	 * columns so the text lines up with a timeline above it.
	 *
	 * The organisation link is the system's one 24px target (WCAG 2.5.8 AA), not 44px: a
	 * 44px hit area would cover the role title and the location line 4px away, so a tap on
	 * either would follow the link. It is marked data-qd-target="24" for the target check.
	 */
	import type { TimelineEntry } from '../types';
	import BulletList from './BulletList.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';
	import { isExternal } from '../external';

	interface Props {
		/** Entries, newest first. */
		items: TimelineEntry[];
		/** Draw the dot and the connecting line. Set false for a single item: the columns stay, so the text lines up with a timeline above. */
		connected?: boolean;
	}

	const { items, connected = true }: Props = $props();
</script>

{#snippet body(item: TimelineEntry)}
	<div class="flex flex-col gap-1">
		<h3 class="type-title">{item.title}</h3>
		{#if item.org && item.orgHref}
			<a
				href={item.orgHref}
				target={isExternal(item.orgHref) ? '_blank' : undefined}
				rel={isExternal(item.orgHref) ? 'noopener noreferrer' : undefined}
				data-qd-target="24"
				class="relative self-start type-label link before:absolute before:inset-x-0 before:-inset-y-0.5 focus-visible:rounded-sm"
				>{item.org}</a
			>
		{:else if item.org}
			<span class="type-subtitle">{item.org}</span>
		{/if}
		{#if item.location}
			<span class="type-small">{item.location}</span>
		{/if}
	</div>
	{#if item.intro || item.bullets?.length}
		<div class="max-w-measure space-y-4">
			{#if item.intro}
				<p class="type-body">{item.intro}</p>
			{/if}
			{#if item.bullets?.length}
				<BulletList items={item.bullets} />
			{/if}
		</div>
	{/if}
	{#if item.detailsHref}
		<a
			href={item.detailsHref}
			class="inline-flex min-h-target items-center type-label link focus-visible:rounded-sm"
			>{item.detailsLabel ?? 'Details'}<VisuallyHidden>, {item.title}</VisuallyHidden></a
		>
	{/if}
{/snippet}

{#if connected}
	<ol role="list" class="relative space-y-8">
		{#each items as item, i (item.period + '|' + item.title + '|' + i)}
			<li class="-mx-4 grid grid-timeline gap-3 p-4 md:gap-8">
				<div class="relative flex flex-col items-center">
					<div
						class="z-(--layer-raised) h-3 w-3 rounded-full bg-fg-secondary ring-4 ring-canvas"
					></div>
					{#if i !== items.length - 1}
						<div class="absolute top-3 -bottom-8 z-0 w-px bg-border"></div>
					{/if}
				</div>
				<div class="flex flex-col items-start gap-2 pb-8 md:pb-0">
					{@render body(item)}
				</div>
				<div
					class="order-first col-span-3 type-meta whitespace-nowrap md:col-span-1 md:pt-1 md:text-right"
				>
					{item.period}
				</div>
			</li>
		{/each}
	</ol>
{:else}
	<ol role="list" class="space-y-8">
		{#each items as item, i (item.period + '|' + item.title + '|' + i)}
			<li class="-mx-4 grid grid-timeline gap-3 px-4 py-3 md:gap-8">
				<div class="w-3" aria-hidden="true"></div>
				<div class="flex flex-col items-start gap-2">
					{@render body(item)}
				</div>
				<div
					class="order-first col-span-3 type-meta whitespace-nowrap md:col-span-1 md:pt-1 md:text-right"
				>
					{item.period}
				</div>
			</li>
		{/each}
	</ol>
{/if}
