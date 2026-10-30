<script lang="ts">
	/*
	 * Dense data, kept flat: a real <table> with a caption, column headers in type-label,
	 * values in type-small, and one 50% hairline under each body row. No zebra stripes, no
	 * fills, no boxed cells, no sticky header: space and the hairline separate the rows, as
	 * they separate sections. Numbers align to the end so their digits line up.
	 *
	 * The table sits in a scroll region that is always focusable (tabindex 0) and named by
	 * the caption, so a keyboard user can scroll a table wider than the 768px column and a
	 * screen reader announces what the region holds (axe: scrollable-region-focusable).
	 */
	interface Props {
		/** What the table holds, in sentence case. Names both the table and its scroll region. */
		caption: string;
		/** Hides the caption visually and keeps it for screen readers. Set it when a heading right above says the same thing. */
		captionHidden?: boolean;
		/** Column order and header text. `key` picks the value from each row; `numeric` aligns the column to the end and keeps its cells on one line. */
		columns: { key: string; label: string; numeric?: boolean }[];
		/** One record per row, keyed by column key. A missing key shows an empty cell. */
		rows: Record<string, string | number>[];
		/** The key of the column whose cells name their row; those cells become `<th scope="row">`. */
		rowHeader?: string;
	}

	const { caption, captionHidden = false, columns, rows, rowHeader }: Props = $props();

	const captionId = $props.id();
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- A scroll region must take focus so a keyboard can scroll it (WCAG 2.1.1, axe scrollable-region-focusable). -->
<div role="region" aria-labelledby={captionId} tabindex="0" class="overflow-x-auto">
	<table class="w-full border-collapse">
		<caption id={captionId} class={captionHidden ? 'sr-only' : 'pb-3 text-start type-label'}
			>{caption}</caption
		>
		<thead>
			<tr>
				{#each columns as col (col.key)}
					<th
						scope="col"
						class={[
							'py-3 pr-6 align-bottom type-label last:pr-0',
							col.numeric ? 'text-end' : 'text-start'
						]}>{col.label}</th
					>
				{/each}
			</tr>
		</thead>
		<tbody>
			{#each rows as row, i (i)}
				<tr class="hairline-b border-border/50">
					{#each columns as col (col.key)}
						{#if col.key === rowHeader}
							<th
								scope="row"
								class={[
									'py-3 pr-6 align-top type-label last:pr-0',
									col.numeric ? 'text-end whitespace-nowrap' : 'text-start'
								]}>{row[col.key] ?? ''}</th
							>
						{:else}
							<td
								class={[
									'py-3 pr-6 align-top type-small last:pr-0',
									col.numeric ? 'text-end whitespace-nowrap' : 'text-start'
								]}>{row[col.key] ?? ''}</td
							>
						{/if}
					{/each}
				</tr>
			{/each}
		</tbody>
	</table>
</div>
