<script lang="ts">
	/*
	 * The footer, inside the content column after <main>: a hairline (border at 50%), 32px
	 * above and below, left-aligned, never centred. Two shapes. A personal site: icon links
	 * on the left and one quiet meta line on the right. A documentation or product site: a
	 * name over its one fact on the left and a row of text links on the right (icon links, if
	 * any, follow them). Below 640px everything stacks, left-aligned; below 360px the icons
	 * sit in a 3-column grid. No copyright line and nothing the header already links to.
	 */
	import type { FooterLink, FooterTextLink } from '../types';
	import IconLink from './IconLink.svelte';
	import Link from './Link.svelte';

	interface Props {
		/** The icon links, in order: contact and profile links not already in the header. Each label is the accessible name. */
		links?: FooterLink[];
		/** Accessible name of the icon list. Change it when the links are not profile links. */
		label?: string;
		/** One fact, set as type-meta: a location, a licence, where the values come from. */
		note?: string;
		/** The name of a documentation or product site, in type-label above the note. Leave it out on a personal site. */
		name?: string;
		/** Text links for a documentation or product site, in order: source, changelog, rules. */
		textLinks?: FooterTextLink[];
		/** Accessible name of the text-link list. */
		textLabel?: string;
	}

	const {
		links = [],
		label = 'Profile links',
		note,
		name,
		textLinks = [],
		textLabel = 'Project links'
	}: Props = $props();

	const identity = $derived(Boolean(name) || textLinks.length > 0);
</script>

{#snippet icons()}
	<ul
		role="list"
		class="grid list-none grid-cols-3 items-center gap-x-4 gap-y-1 p-0 xs:flex xs:flex-wrap xs:gap-2"
		aria-label={label}
	>
		{#each links as link (link.href)}
			<li><IconLink href={link.href} label={link.label} icon={link.icon} /></li>
		{/each}
	</ul>
{/snippet}

{#if identity}
	<footer
		class="flex flex-col items-start justify-between gap-6 hairline-t border-border/50 pt-8 pb-8 sm:flex-row sm:gap-8"
	>
		<div class="flex flex-col gap-1 py-2.5">
			{#if name}<p class="type-label">{name}</p>{/if}
			{#if note}<p class="max-w-measure type-meta">{note}</p>{/if}
		</div>
		<div class="flex flex-col items-start gap-x-8 gap-y-2 sm:flex-row sm:items-center">
			{#if textLinks.length > 0}
				<ul role="list" class="flex list-none flex-wrap gap-x-6 gap-y-0 p-0" aria-label={textLabel}>
					{#each textLinks as link (link.href)}
						<li>
							<Link href={link.href} class="inline-flex min-h-target items-center type-small"
								>{link.label}</Link
							>
						</li>
					{/each}
				</ul>
			{/if}
			{#if links.length > 0}{@render icons()}{/if}
		</div>
	</footer>
{:else}
	<footer
		class="flex flex-col items-start justify-between gap-4 hairline-t border-border/50 pt-8 pb-8 type-small sm:flex-row sm:items-center"
	>
		{#if links.length > 0}{@render icons()}{/if}
		{#if note}
			<span class="pl-3 type-meta sm:pl-0 sm:text-right">{note}</span>
		{/if}
	</footer>
{/if}
