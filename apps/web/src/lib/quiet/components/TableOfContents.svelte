<script lang="ts">
	/*
	 * A list of same-page anchors above a long page: one level, in reading order, each link a
	 * 44px row in type-label, as every link alone on its line is (Link standalone). It sits in the one column like everything else: no sticky
	 * sidebar and no scroll-spy, so nothing moves or changes while the page scrolls. The
	 * title is a label, not a heading, so it adds nothing to the page outline. The landmark
	 * carries data-qd-nav="toc": its links share the page's path, and they are not site
	 * sections, so navCurrentMismatches must not ask them for aria-current.
	 */
	interface Props {
		/** The sections, in page order. Each href is a same-page anchor ("#setup"); each label is that section's heading. */
		items: { href: string; label: string }[];
		/** The visible label above the links, in sentence case. It also names the nav landmark; give two lists on one page different titles. */
		title?: string;
	}

	const { items, title = 'On this page' }: Props = $props();

	const id = $props.id();
</script>

{#if items.length > 0}
	<nav aria-labelledby={id} data-qd-nav="toc" class="space-y-2">
		<p {id} class="type-label">{title}</p>
		<ul role="list">
			{#each items as item (item.href)}
				<li>
					<a
						href={item.href}
						class="inline-flex min-h-target min-w-target items-center type-label link focus-visible:rounded-sm"
						>{item.label}</a
					>
				</li>
			{/each}
		</ul>
	</nav>
{/if}
