<script lang="ts">
	/*
	 * Term and value pairs (role, location, stack): a real <dl>, the term in type-label and
	 * the detail in type-small. Term above detail on phones, beside it from 640px in a 1:2
	 * grid (grid-dl in quiet.css). No table lines, no boxes, no bold keys: the weight step
	 * from 500 to 400 and the colour step from primary to secondary tell term from detail.
	 */
	interface Props {
		/** The pairs, shown in the order given; each renders one `dt` and one `dd`. Keep terms short and in sentence case. */
		items: { term: string; detail: string }[];
	}

	const { items }: Props = $props();
</script>

<dl class="grid grid-dl gap-x-6 gap-y-3">
	{#each items as item, i (i)}
		<dt class="type-label">{item.term}</dt>
		<dd class="m-0 type-small">{item.detail}</dd>
	{/each}
</dl>
