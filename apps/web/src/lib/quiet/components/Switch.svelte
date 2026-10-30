<script lang="ts">
	/*
	 * An on-or-off setting that takes effect at once ("Email me about replies"), after the
	 * WAI-ARIA switch pattern and Primer's ToggleSwitch. A native <button role="switch"
	 * aria-checked>, so Space and Enter toggle it with no key handler of ours. The button is
	 * the whole 44px row: the label (type-label, as Field's), an optional description
	 * (type-small, as Field's hint) and the state written as a word, "On" or "Off", so colour
	 * is never the signal. The button is named by the label (aria-labelledby) and described by
	 * the description.
	 * The track is 36 x 20px with a 16px thumb 2px in from its outer edge, the Radix and Geist
	 * proportion. Its boundary is the --link-underline edge a control uses (4.63:1 light,
	 * 3.66:1 dark, over the 3:1 a control's boundary needs): off is an empty track with an
	 * fg-secondary thumb on the left, on is a fg-primary track with an on-primary thumb on the
	 * right. The thumb moves with transform for --motion-fast. Disabled, as in Radix and
	 * Primer, is quieter as a whole: the state word, track and thumb at 50% opacity with a
	 * dashed edge, and the label in fg-secondary (type-subtitle). A setting that needs a Save
	 * button is a Checkbox, not a Switch. No loading state: say "Saving" through a Status.
	 */
	import type { HTMLButtonAttributes } from 'svelte/elements';

	interface Props extends Omit<HTMLButtonAttributes, 'type' | 'role' | 'children' | 'onchange'> {
		/** Visible label, sentence case. It is the switch's accessible name. */
		label: string;
		/** One short line under the label: what turning it on does. Read after the label through aria-describedby. */
		description?: string;
		/** Whether it is on. Bindable: bind:checked. */
		checked?: boolean;
		/** Called with the new state after the user toggles it; save the setting here. Not called when disabled, or when an onclick you pass calls preventDefault. */
		onchange?: (checked: boolean) => void;
	}

	let {
		label,
		description,
		checked = $bindable(false),
		disabled,
		onchange,
		onclick,
		id,
		class: className,
		'aria-describedby': describedBy,
		...rest
	}: Props = $props();

	const uid = $props.id();
	const base = $derived(id ?? uid);
	const labelId = $derived(base + '-label');
	const descId = $derived(base + '-description');
	const describedByAll = $derived(
		[description ? descId : '', describedBy ?? ''].filter(Boolean).join(' ') || undefined
	);

	function toggle(event: MouseEvent & { currentTarget: EventTarget & HTMLButtonElement }) {
		onclick?.(event);
		if (event.defaultPrevented || disabled) return;
		checked = !checked;
		onchange?.(checked);
	}
</script>

<button
	{...rest}
	{id}
	type="button"
	role="switch"
	aria-checked={checked}
	aria-labelledby={labelId}
	aria-describedby={describedByAll}
	{disabled}
	onclick={toggle}
	class={[
		'qd-switch flex min-h-target w-full items-center gap-3 text-left',
		disabled ? 'cursor-not-allowed' : 'cursor-pointer',
		className
	]}
>
	<span class="min-w-0 flex-1 py-2">
		<span id={labelId} class={['block', disabled ? 'type-subtitle' : 'type-label']}>{label}</span>
		{#if description}<span id={descId} class="block type-small">{description}</span>{/if}
	</span>
	<span
		aria-hidden="true"
		class={['qd-switch-control flex shrink-0 items-center gap-3', disabled ? 'opacity-50' : '']}
	>
		<span class="w-6 text-right type-meta">{checked ? 'On' : 'Off'}</span>
		<span class="qd-switch-track relative block rounded-full">
			<span class="qd-switch-thumb absolute block size-4 rounded-full"></span>
		</span>
	</span>
</button>

<style>
	/* The track edge is the control edge (--link-underline), never --bg-border (1.21:1). */
	.qd-switch-track {
		width: 2.25rem; /* 36px */
		height: 1.25rem; /* 20px */
		border: var(--shape-underline) solid var(--link-underline);
		background-color: transparent;
		print-color-adjust: exact;
	}
	.qd-switch-thumb {
		/* Offsets start inside the 1px edge: 1px + 1px is the 2px inset. */
		top: 1px;
		left: 1px;
		background-color: var(--fg-secondary);
		transform: translateX(0);
		transition: transform var(--motion-fast) var(--motion-ease-standard);
		print-color-adjust: exact;
	}
	.qd-switch[aria-checked='true'] .qd-switch-track {
		background-color: var(--fg-primary);
		border-color: var(--fg-primary);
	}
	.qd-switch[aria-checked='true'] .qd-switch-thumb {
		background-color: var(--fg-on-primary);
		transform: translateX(16px);
	}
	/* Disabled is a dashed edge, as a disabled control is everywhere else. When on, the edge
	   stays --link-underline and the fill stops inside it, so the dashes still show. */
	.qd-switch:disabled .qd-switch-track {
		border-style: dashed;
	}
	.qd-switch[aria-checked='true']:disabled .qd-switch-track {
		border-color: var(--link-underline);
		background-clip: padding-box;
	}
	/* Forced colours repaint backgrounds and drop the tokens: set system colours so the track,
	   the thumb and its checked state all survive. Off is an empty track, a CanvasText thumb;
	   on is a CanvasText track and a Canvas thumb. Disabled is GrayText at full opacity: the
	   system colour already says disabled, and halving it would lose it. */
	@media (forced-colors: active) {
		.qd-switch-control {
			opacity: 1;
		}
		.qd-switch-track {
			border-color: ButtonText;
			background-color: Canvas;
		}
		.qd-switch-thumb {
			background-color: ButtonText;
		}
		.qd-switch[aria-checked='true'] .qd-switch-track {
			background-color: ButtonText;
			border-color: ButtonText;
		}
		.qd-switch[aria-checked='true'] .qd-switch-thumb {
			background-color: Canvas;
		}
		.qd-switch:disabled .qd-switch-track {
			border-color: GrayText;
		}
		.qd-switch:disabled .qd-switch-thumb {
			background-color: GrayText;
		}
		.qd-switch[aria-checked='true']:disabled .qd-switch-track {
			background-color: GrayText;
			border-color: GrayText;
		}
		.qd-switch[aria-checked='true']:disabled .qd-switch-thumb {
			background-color: Canvas;
		}
	}
</style>
