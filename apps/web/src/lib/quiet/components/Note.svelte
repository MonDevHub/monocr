<script lang="ts">
	/*
	 * A static message that is on the page when it loads: "Archived", "Note". A type-label
	 * word above a type-small message, in the reading measure. No box, no icon, no colour:
	 * the label says what kind of message it is. No live role, because nothing changes after
	 * load (announce later changes through Status). No dismiss: a note that can be closed was
	 * not needed on the page.
	 */
	import type { Snippet } from 'svelte';

	interface Props {
		/** The kind of message, one or two words in sentence case: "Note", "Archived". */
		label: string;
		/** The message: one or two short sentences that say what it means and what to do. */
		children: Snippet;
	}

	const { label, children }: Props = $props();
</script>

<div class="max-w-measure space-y-1">
	<p class="type-label">{label}</p>
	<div class="type-small">{@render children()}</div>
</div>
