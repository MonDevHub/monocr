<script lang="ts">
	/*
	 * Determinate progress: the value in words ("3 of 8 uploaded") above a 2px native
	 * <progress> bar. The words carry the value; the bar is the glance. The words also name
	 * the bar (aria-labelledby), so the name and the visible text cannot disagree. Screen
	 * readers do not announce a progress value as it changes, so milestones and completion
	 * go through Status. Determinate only: an unknown amount is the word "Loading" in Status.
	 */
	interface Props {
		/** Work done, from 0 to max. 0 shows an empty bar, not an indeterminate one. */
		value: number;
		/** Total work, in the same unit as value. */
		max: number;
		/** The value as words, shown above the bar and used as its name: "3 of 8 uploaded". */
		label: string;
		/** A unique id for the bar. The label paragraph gets `{id}-label`. */
		id: string;
	}

	const { value, max, label, id }: Props = $props();
</script>

<div class="space-y-2">
	<p id="{id}-label" class="type-small">{label}</p>
	<progress {id} class="progress" {value} {max} aria-labelledby="{id}-label"></progress>
</div>
