<script lang="ts">
	/*
	 * A testimonial: the quote upright in type-body with typographic quotation marks, then
	 * the author (type-label) and their role (type-meta). No italics (Inter ships none), no
	 * large quotation glyph, no avatar, no carousel. Stack several 48px apart in the measure.
	 */
	interface Props {
		/** The words, without quotation marks: the component adds them. */
		quote: string;
		/** Who said it. */
		author: string;
		/** Their role and organisation: "Role, Organisation". */
		role?: string;
	}

	const { quote, author, role }: Props = $props();
</script>

<figure class="flex flex-col gap-3">
	<blockquote class="type-body">“{quote}”</blockquote>
	<figcaption class="flex flex-col gap-0.5">
		<div class="type-label">{author}</div>
		{#if role}
			<div class="type-meta">{role}</div>
		{/if}
	</figcaption>
</figure>
