<script lang="ts" module>
	export interface SegmentOption {
		/** Stable value, written to `value` when the option is chosen. */
		value: string;
		/** The option's visible text, sentence case, one or two words. */
		label: string;
		/** Not choosable and skipped by the arrow keys. */
		disabled?: boolean;
	}

	/**
	 * The WAI-ARIA radio group keyboard model as a pure function. `values` are the enabled
	 * options in order; the result is the option to choose and focus, or null when the key is not
	 * ours. ArrowRight and ArrowDown go forward, ArrowLeft and ArrowUp back, both wrapping; Home
	 * and End go to the ends.
	 */
	export function nextSegment(values: string[], current: string, key: string): string | null {
		if (values.length === 0) return null;
		const at = values.indexOf(current);
		switch (key) {
			case 'ArrowRight':
			case 'ArrowDown':
				return values[(at + 1) % values.length];
			case 'ArrowLeft':
			case 'ArrowUp':
				return values[(at <= 0 ? values.length : at) - 1];
			case 'Home':
				return values[0];
			case 'End':
				return values[values.length - 1];
			default:
				return null;
		}
	}
</script>

<script lang="ts">
	/*
	 * Pick one of two to five closely related views or settings, applied at once: list or grid,
	 * day or week or month. After Primer's SegmentedControl, on the WAI-ARIA radio group pattern:
	 * the options are role="radio" buttons with a roving tabindex, and the arrow keys move and
	 * choose together, as native radios do. It is not Tabs (it shows no panels) and not Switch
	 * (it has more than two states).
	 *
	 * One hairline box holds the options, 2px in from its edge. Each option is the full 44px
	 * target, so the box is 50px tall and no ::before is needed (an overflow-scrolling box would
	 * clip an extended target and the focus outline). The chosen option is bg-canvas-subtle with
	 * the primary ink; the others are secondary ink, turning primary on hover. Only colour
	 * changes, over 150ms: the highlight never slides. In forced colours the tint is dropped, so
	 * the chosen option gets a 1px ButtonText edge. If the options do not fit, the box scrolls
	 * sideways inside a 4px gutter that keeps the focus outline whole; no label is cut.
	 */
	interface Props {
		/** The options, in order. Use two to five. */
		options: SegmentOption[];
		/** The chosen option's value; bind it to read or set the choice. An empty, unknown or disabled value shows the first enabled option without writing it back. */
		value?: string;
		/** The group's name for screen readers ("Layout"); not shown on screen. */
		label: string;
		/** Called with the new value after the user chooses a different option. */
		onchange?: (value: string) => void;
	}

	let { options, value = $bindable(''), label, onchange }: Props = $props();

	const uid = $props.id();
	const optionId = (v: string) => uid + '-option-' + v;

	const enabled = $derived(options.filter((o) => !o.disabled).map((o) => o.value));
	const current = $derived(enabled.includes(value) ? value : (enabled[0] ?? ''));

	function choose(next: string) {
		if (next === current) return;
		value = next;
		onchange?.(next);
	}

	function onkeydown(event: KeyboardEvent, v: string) {
		if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
		const next = nextSegment(enabled, v, event.key);
		if (next === null) return;
		event.preventDefault();
		choose(next);
		document.getElementById(optionId(next))?.focus();
	}
</script>

<div class="-m-1 max-w-full overflow-x-auto p-1">
	<div
		role="radiogroup"
		aria-label={label}
		class="flex w-max rounded-md hairline-box border-link-underline bg-canvas p-0.5"
	>
		{#each options as option (option.value)}
			{@const chosen = option.value === current}
			<button
				type="button"
				role="radio"
				id={optionId(option.value)}
				aria-checked={chosen}
				tabindex={chosen ? 0 : -1}
				disabled={option.disabled}
				onclick={() => choose(option.value)}
				onkeydown={(e) => onkeydown(e, option.value)}
				class={[
					'qd-segment min-h-target shrink-0 cursor-pointer rounded-sm px-4 whitespace-nowrap transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-50',
					chosen
						? 'bg-canvas-subtle type-label'
						: 'type-subtitle hover:text-fg-primary disabled:hover:text-fg-secondary'
				]}>{option.label}</button
			>
		{/each}
	</div>
</div>

<style>
	/* Forced colours drop the tint: the chosen option keeps a 1px edge of its own. */
	@media (forced-colors: active) {
		.qd-segment[aria-checked='true'] {
			border: 1px solid ButtonText;
		}
	}
</style>
