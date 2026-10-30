<script lang="ts" module>
	/** How long the pointer must rest on the trigger before the tip opens. Focus has no delay. */
	export const TOOLTIP_DELAY_MS = 400;
	/** How long the tip stays after the pointer leaves, so the pointer can cross onto it (WCAG 1.4.13). */
	export const TOOLTIP_GRACE_MS = 100;
	/** Space between the trigger and the tip, and the least space kept from a viewport edge. */
	export const TOOLTIP_GAP = 8;

	export type TooltipSide = 'top' | 'bottom';

	interface Box {
		top: number;
		bottom: number;
		left: number;
		width: number;
		height: number;
	}

	/**
	 * Where the tip goes: centred on the trigger, on the requested side, flipped to the other
	 * side when it would leave the viewport (and only then, and only if the other side fits
	 * better), and clamped sideways so it never leaves it. Pure, so it is tested without a browser.
	 */
	export function placeTip(
		anchor: Box,
		tip: { width: number; height: number },
		viewport: { width: number; height: number },
		side: TooltipSide,
		gap: number = TOOLTIP_GAP
	): { side: TooltipSide; top: number; left: number } {
		const above = anchor.top - gap - tip.height;
		const below = anchor.bottom + gap;
		const fitsAbove = above >= gap;
		const fitsBelow = below + tip.height <= viewport.height - gap;
		let placed = side;
		if (side === 'top' && !fitsAbove && fitsBelow) placed = 'bottom';
		if (side === 'bottom' && !fitsBelow && fitsAbove) placed = 'top';
		const centred = anchor.left + anchor.width / 2 - tip.width / 2;
		const left = Math.max(gap, Math.min(centred, viewport.width - tip.width - gap));
		return { side: placed, top: placed === 'top' ? above : below, left };
	}

	/**
	 * When a tooltip is open. It opens after `delay` of hover, at once on keyboard focus, and
	 * stays open while the pointer is on the trigger or on the tip. Escape closes it without
	 * touching focus, and it stays closed until the pointer and focus have both left.
	 * A touch pointer never opens it.
	 */
	export class TooltipState {
		open = $state(false);
		#hovered = false;
		#focused = false;
		#dismissed = false;
		#timer: ReturnType<typeof setTimeout> | undefined;
		readonly #delay: () => number;

		constructor(delay: () => number = () => TOOLTIP_DELAY_MS) {
			this.#delay = delay;
		}

		#clear() {
			clearTimeout(this.#timer);
			this.#timer = undefined;
		}

		/** The pointer is on the trigger or on the tip. */
		pointerEnter(pointerType: string = 'mouse') {
			if (pointerType === 'touch') return;
			this.#hovered = true;
			this.#clear();
			if (this.open || this.#dismissed) return;
			this.#timer = setTimeout(() => {
				this.#timer = undefined;
				this.open = true;
			}, this.#delay());
		}

		pointerLeave(pointerType: string = 'mouse') {
			if (pointerType === 'touch') return;
			this.#hovered = false;
			this.#clear();
			if (!this.#focused) this.#dismissed = false;
			if (!this.open || this.#focused) return;
			this.#timer = setTimeout(() => {
				this.#timer = undefined;
				if (!this.#hovered && !this.#focused) this.open = false;
			}, TOOLTIP_GRACE_MS);
		}

		focus() {
			this.#focused = true;
			this.#clear();
			if (!this.#dismissed) this.open = true;
		}

		blur() {
			this.#focused = false;
			this.#clear();
			if (!this.#hovered) this.#dismissed = false;
			if (!this.#hovered) this.open = false;
		}

		/** Escape: close now, leave focus alone. */
		escape() {
			this.#clear();
			if (!this.open && !this.#timer) return;
			this.#dismissed = true;
			this.open = false;
		}

		destroy() {
			this.#clear();
		}
	}
</script>

<script lang="ts">
	/*
	 * A short name or hint for one control, never anything the page needs to work without.
	 * It describes: the trigger keeps its own accessible name and gets aria-describedby.
	 * Opens on hover (after a delay) and on keyboard focus (at once); Escape dismisses it
	 * without moving focus; the pointer may cross onto it and it stays (WCAG 1.4.13). It is a
	 * popover="manual" in the top layer, positioned from getBoundingClientRect (no CSS anchor
	 * positioning, which Firefox lacks) and moved again on scroll and resize while open. It never
	 * takes focus and has no interactive content. On a device with no hover it never opens.
	 */
	import type { Snippet } from 'svelte';

	/** What the trigger must spread so the tip is wired to it. */
	export interface TooltipTrigger {
		'aria-describedby': string;
		onpointerenter: (e: PointerEvent) => void;
		onpointerleave: (e: PointerEvent) => void;
		onfocus: () => void;
		onblur: () => void;
	}

	interface Props {
		/** The tip: one short sentence of plain text. It wraps at max-w-xs. */
		text: string;
		/** The one trigger. It receives the props to spread onto it: {#snippet children(trigger)}. */
		children: Snippet<[TooltipTrigger]>;
		/** The preferred side. The tip flips when it would leave the viewport and the other side fits. */
		side?: TooltipSide;
		/** Milliseconds of hover before the tip opens. Focus opens it at once, whatever the delay. */
		delay?: number;
	}

	const { text, children, side = 'top', delay = TOOLTIP_DELAY_MS }: Props = $props();

	const uid = $props.id();
	const id = 'qd-tip-' + uid;
	const tipState = new TooltipState(() => delay);

	let anchor: HTMLElement | undefined = $state();
	let tip: HTMLElement | undefined = $state();

	const trigger: TooltipTrigger = {
		'aria-describedby': id,
		onpointerenter: (e) => tipState.pointerEnter(e.pointerType),
		onpointerleave: (e) => tipState.pointerLeave(e.pointerType),
		onfocus: () => tipState.focus(),
		onblur: () => tipState.blur()
	};

	$effect(() => {
		if (!tip || !anchor) return;
		const el = tip;
		const from = anchor;
		if (!tipState.open) {
			if (el.matches(':popover-open')) el.hidePopover();
			return;
		}
		if (!el.matches(':popover-open')) el.showPopover();
		const place = () => {
			const a = from.getBoundingClientRect();
			// The layout size, not the drawn box: the entrance scale would make the tip measure
			// smaller than it ends up, so it would overlap its trigger once it has grown.
			const spot = placeTip(
				a,
				{ width: el.offsetWidth, height: el.offsetHeight },
				{ width: document.documentElement.clientWidth, height: window.innerHeight },
				side
			);
			el.style.top = `${spot.top}px`;
			el.style.left = `${spot.left}px`;
			el.dataset.side = spot.side;
		};
		const onKey = (e: KeyboardEvent) => {
			if (e.key !== 'Escape') return;
			// One layer at a time: a tip over a dialog's control closes first, the dialog on the next press.
			e.preventDefault();
			tipState.escape();
		};
		place();
		window.addEventListener('scroll', place, true);
		window.addEventListener('resize', place);
		window.addEventListener('keydown', onKey);
		return () => {
			window.removeEventListener('scroll', place, true);
			window.removeEventListener('resize', place);
			window.removeEventListener('keydown', onKey);
		};
	});

	$effect(() => () => tipState.destroy());
</script>

<span bind:this={anchor} class="inline-flex">{@render children(trigger)}</span>
<!-- Closed, the tip is display: none, which aria-describedby still reads. It holds text only. -->
<span
	bind:this={tip}
	{id}
	role="tooltip"
	popover="manual"
	class="qd-tip max-w-xs overlay rounded-md hairline-box border-border bg-canvas px-2 py-1 type-small"
	onpointerenter={(e) => tipState.pointerEnter(e.pointerType)}
	onpointerleave={(e) => tipState.pointerLeave(e.pointerType)}>{text}</span
>

<style>
	/* A popover is centred by the browser; it is placed from the trigger's box instead. */
	.qd-tip {
		position: fixed;
		inset: auto;
		margin: 0;
		overflow: visible;
		width: max-content;
		overflow-wrap: break-word;
	}
	/* It grows out of its trigger: from its bottom edge when above, its top edge when below.
	   data-side is set from script, so Svelte cannot see it: the attribute part is global. */
	.qd-tip:global([data-side='top']) {
		transform-origin: bottom center;
	}
	.qd-tip:global([data-side='bottom']) {
		transform-origin: top center;
	}
	/* No hover, no tip: a touch screen has no way to reach one, so the label has to stand alone. */
	@media (hover: none) {
		.qd-tip {
			display: none !important;
		}
	}
</style>
