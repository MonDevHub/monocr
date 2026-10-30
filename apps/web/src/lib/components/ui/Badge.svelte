<script lang="ts">
	import { Badge } from '$lib/quiet';

	interface Props {
		/** The word: "Synced", "Failed", "Pending". */
		label: string;
	}

	const { label }: Props = $props();
</script>

<!-- quiet-ds Badge: a word with a dotted edge. The state is the word, never a colour or icon. -->
<Badge>{label}</Badge>
