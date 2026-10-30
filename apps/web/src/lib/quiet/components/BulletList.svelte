<script lang="ts">
	/*
	 * Points under a paragraph: a hanging 4px dot in fg-secondary at 50%, 12px before the
	 * text, 8px between items, type-body. The dot is decorative; the list is a real list.
	 * `ordered` is for steps that happen in order: the number is plain text in the row
	 * ("1.", type-body, fg-secondary), not a list-style marker, because a ::marker takes its
	 * own size and colour and would not match the role. It sits in a 16px slot, so one to
	 * nine points line up; it is read aloud with the point, as it is seen.
	 */
	interface Props {
		/** The points as plain strings, one sentence each. */
		items: string[];
		/** Number the points (an ol) instead of dotting them (a ul). */
		ordered?: boolean;
	}

	const { items, ordered = false }: Props = $props();
</script>

{#if ordered}
	<ol role="list" class="space-y-2">
		{#each items as item, i (i)}
			<li class="flex items-start gap-3 type-body">
				<span class="min-w-4 shrink-0">{i + 1}.</span>
				<span>{item}</span>
			</li>
		{/each}
	</ol>
{:else}
	<ul role="list" class="space-y-2">
		{#each items as item, i (i)}
			<li class="flex items-start gap-3 type-body">
				<span class="mt-2.5 h-1 w-1 shrink-0 rounded-full bg-fg-secondary/50" aria-hidden="true"
				></span>
				<span>{item}</span>
			</li>
		{/each}
	</ul>
{/if}
