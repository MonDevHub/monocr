<script lang="ts">
	/*
	 * One project, with no chrome: no border, background or shadow. Title with role and year
	 * on the right from 640px, a paragraph and points in the reading measure, the stack on a
	 * hairline as type-meta, then link rows. 12px between the parts.
	 */
	import type { Snippet } from 'svelte';
	import type { EntryLink } from '../types';
	import BulletList from './BulletList.svelte';
	import Link from './Link.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';

	interface Props {
		/** Anchor id, so other pages can link to /work#id. */
		id?: string;
		/** Project name (type-title). */
		title: string;
		/** Your role on it (type-meta). */
		role?: string;
		/** Year or range (type-meta). */
		year?: string;
		/** One paragraph: what it is and why it matters. */
		intro?: string;
		/** Points under the paragraph. */
		bullets?: string[];
		/** Comma-separated tools, after `stackLabel`. */
		stack?: string;
		/** Prefix for the stack line. Change it when the site is not in English. */
		stackLabel?: string;
		/** Link rows under the entry: "Visit project", "Read the write-up". Each gets a hidden ", <title>" suffix. */
		links?: EntryLink[];
		/** Anything else, after the stack line. */
		children?: Snippet;
	}

	const {
		id,
		title,
		role,
		year,
		intro,
		bullets = [],
		stack,
		stackLabel = 'Stack:',
		links = [],
		children
	}: Props = $props();
</script>

<article {id} class="space-y-3">
	<header class="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between">
		<h3 class="type-title">{title}</h3>
		{#if role || year}
			<div class="flex shrink-0 items-baseline gap-3">
				{#if role}
					<span class="type-meta">{role}</span>
				{/if}
				{#if year}
					<span class="type-meta">{year}</span>
				{/if}
			</div>
		{/if}
	</header>
	{#if intro || bullets.length > 0 || stack || children}
		<div class="max-w-measure space-y-4">
			{#if intro}
				<p class="type-body">{intro}</p>
			{/if}
			{#if bullets.length > 0}
				<BulletList items={bullets} />
			{/if}
			{#if stack}
				<p class="hairline-t border-border/50 pt-3 type-meta">{stackLabel} {stack}</p>
			{/if}
			{@render children?.()}
		</div>
	{/if}
	{#if links.length > 0}
		<div class="flex flex-wrap gap-x-6 gap-y-2">
			{#each links as link (link.href)}
				<Link href={link.href} standalone
					>{link.label}<VisuallyHidden>, {title}</VisuallyHidden></Link
				>
			{/each}
		</div>
	{/if}
</article>
