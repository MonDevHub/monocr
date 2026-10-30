<script lang="ts">
	/*
	 * Where this page sits, for pages three or more levels deep: ancestor links in
	 * type-small, a "/" between them, and the current page last in type-label, marked
	 * aria-current="page" and not linked. The separators are aria-hidden: the ordered list
	 * already says how the items relate. It replaces PageLayout's back link on a page; never
	 * use both. data-qd-nav="breadcrumb" tells navCurrentMismatches that its ancestor links
	 * are not marked as site sections.
	 *
	 * Each ancestor is a 44px target, but the box the eye sees is only its text: the extra
	 * width is a ::before centred on the link that moves nothing, so the gap before and after
	 * every "/" is the same 8px whatever a crumb's length. Padding a short crumb out to 44px
	 * (min-w-target on the link) would leave its "/" further away than the next one.
	 */
	interface Props {
		/** The trail, root first and the current page last. The last item is never a link, even with an href; an ancestor without an href shows as plain text. */
		items: { label: string; href?: string }[];
		/** The name of the nav landmark. Change it when a page has two trails, so the landmarks have different names. */
		label?: string;
	}

	const { items, label = 'Breadcrumb' }: Props = $props();

	const LINK =
		'relative inline-flex min-h-target items-center type-small link before:absolute before:inset-y-0 before:left-1/2 before:w-full before:min-w-target before:-translate-x-1/2 focus-visible:rounded-sm';
</script>

{#if items.length > 0}
	<nav aria-label={label} data-qd-nav="breadcrumb">
		<ol role="list" class="flex flex-wrap items-center gap-x-2">
			{#each items as item, i (i)}
				{#if i < items.length - 1}
					<li class="flex items-center gap-x-2">
						{#if item.href}
							<a href={item.href} class={LINK}>{item.label}</a>
						{:else}
							<span class="type-small">{item.label}</span>
						{/if}
						<span aria-hidden="true" class="type-small">/</span>
					</li>
				{:else}
					<li class="flex min-h-target items-center">
						<span aria-current="page" class="type-label">{item.label}</span>
					</li>
				{/if}
			{/each}
		</ol>
	</nav>
{/if}
