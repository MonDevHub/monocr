<script lang="ts">
	/*
	 * Previous and next through a paged list, with "Page 3 of 12" between them. Two text
	 * links, no numbered page list, no boxes. At an end the link is left out, not shown
	 * disabled: a link that goes nowhere is not a link. The row is left-aligned in reading
	 * order, never spread or centred (DESIGN.md section 8). Each link is a 44px row;
	 * a VisuallyHidden " page" makes the names "Previous page" and "Next page".
	 * data-qd-nav="pagination" tells navCurrentMismatches these are not site sections.
	 * Fewer than two pages renders nothing: a landmark with no links is noise.
	 */
	import VisuallyHidden from './VisuallyHidden.svelte';

	interface Props {
		/** The current page, counted from 1. */
		page: number;
		/** The total number of pages. Under 2, nothing renders. */
		pages: number;
		/** Builds the address of a page from its number: (p) => '/writing?page=' + p. */
		href: (page: number) => string;
		/** The name of the nav landmark. Change it when a page has two paginated lists, so the landmarks have different names. */
		label?: string;
	}

	const { page, pages, href, label = 'Pagination' }: Props = $props();

	// Kept in an expression: Svelte trims the leading space of literal component children.
	const SUFFIX = ' page';
	const LINK =
		'inline-flex min-h-target min-w-target items-center type-label link focus-visible:rounded-sm';
</script>

{#if pages > 1}
	<!-- Left-aligned in reading order (DESIGN.md section 8: never centre pagination). An end
	     omits its link rather than showing a dead one. -->
	<nav aria-label={label} data-qd-nav="pagination" class="flex flex-wrap items-center gap-x-6">
		{#if page > 1}
			<a href={href(page - 1)} rel="prev" class={LINK}
				>Previous<VisuallyHidden>{SUFFIX}</VisuallyHidden></a
			>
		{/if}
		<span class="type-small">Page {page} of {pages}</span>
		{#if page < pages}
			<a href={href(page + 1)} rel="next" class={LINK}
				>Next<VisuallyHidden>{SUFFIX}</VisuallyHidden></a
			>
		{/if}
	</nav>
{/if}
