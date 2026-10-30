<script lang="ts" module>
	export interface Step {
		/** The step's name, sentence case. */
		title: string;
		/** One short line of detail. */
		text?: string;
	}
</script>

<script lang="ts">
	/*
	 * Where the reader is in a short sequence: a marker, a title, optional text, and a 1px
	 * hairline joining each step to the next. It is an ordered list, not a <nav>, and it does
	 * not move: nothing animates. Each step says its state in words for a screen reader
	 * ("Completed:", "Current:", "Upcoming:"). On screen, as in Stripe's and Linear's
	 * onboarding steps but with no circle: a completed step's marker is the check icon in
	 * fg-primary, in the same 24 x 20px box as a numeral so nothing shifts; the current step's
	 * numeral and title are type-label; an upcoming step's are type-small (fg-secondary); a
	 * completed title is type-subtitle. Ink, weight and the check differ, never the hue.
	 * The current step carries aria-current="step".
	 */
	import Icon from './Icon.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';

	type State = 'completed' | 'current' | 'upcoming';

	interface Props {
		/** The steps, first to last. */
		items: Step[];
		/** Zero-based index of the current step; steps before it are completed. Past the last index, every step is completed; below 0, every step is upcoming. */
		current: number;
		/** The list's name for screen readers ("Checkout steps"). Set it when a page has more than one Steps list. */
		label?: string;
	}

	const { items, current, label }: Props = $props();

	const WORDS: Record<State, string> = {
		completed: 'Completed: ',
		current: 'Current: ',
		upcoming: 'Upcoming: '
	};
	const ROLE: Record<State, string> = {
		completed: 'type-subtitle',
		current: 'type-label',
		upcoming: 'type-small'
	};
	const stateOf = (i: number): State =>
		i < current ? 'completed' : i === current ? 'current' : 'upcoming';
</script>

<ol role="list" aria-label={label}>
	{#each items as item, i (item.title + '|' + i)}
		{@const s = stateOf(i)}
		<li
			aria-current={s === 'current' ? 'step' : undefined}
			class="relative flex gap-4 pb-6 last:pb-0"
		>
			{#if s === 'completed'}
				<span
					aria-hidden="true"
					class="flex h-5 w-6 shrink-0 items-center justify-center text-fg-primary"
				>
					<Icon name="check" size={16} />
				</span>
			{:else}
				<span
					aria-hidden="true"
					class={['flex h-5 w-6 shrink-0 items-center justify-center', ROLE[s]]}>{i + 1}</span
				>
			{/if}
			{#if i !== items.length - 1}
				<span aria-hidden="true" class="absolute top-6 bottom-1 left-3 w-px bg-border"></span>
			{/if}
			<div class="flex flex-col">
				<span class={ROLE[s]}><VisuallyHidden>{WORDS[s]}</VisuallyHidden>{item.title}</span>
				{#if item.text}
					<span class="max-w-measure type-small-wrapped">{item.text}</span>
				{/if}
			</div>
		</li>
	{/each}
</ol>
