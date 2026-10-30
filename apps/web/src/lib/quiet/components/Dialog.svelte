<script module lang="ts">
	/* Dialogs open at once (a confirmation over a dialog) share one scroll lock. */
	let locks = 0;

	/** Takes one hold on the page's scroll lock. */
	export function acquireScrollLock(root: { classList: Pick<DOMTokenList, 'add'> }) {
		locks += 1;
		root.classList.add(SCROLL_LOCK_CLASS);
	}

	/** Gives one hold back; the page scrolls again only when the last one is given back. */
	export function releaseScrollLock(root: { classList: Pick<DOMTokenList, 'remove'> }) {
		locks = Math.max(0, locks - 1);
		if (locks === 0) root.classList.remove(SCROLL_LOCK_CLASS);
	}

	/** The class Dialog puts on <html> while any dialog is open. */
	export const SCROLL_LOCK_CLASS = 'qd-scroll-lock';

	const TABBABLE =
		'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

	/**
	 * Where Tab or Shift+Tab goes next so focus never leaves the dialog. The browser keeps the page
	 * behind inert but lets Tab step out to its own chrome after the last stop; this wraps it
	 * instead. Returns the stop to focus, or null when the browser's own move stays inside.
	 */
	export function wrapTab<T>(stops: T[], active: T | null, shift: boolean, host: T): T | null {
		if (stops.length === 0) return null;
		const first = stops[0];
		const last = stops[stops.length - 1];
		if (shift) return active === first || active === host ? last : null;
		return active === last ? first : null;
	}
</script>

<script lang="ts">
	/*
	 * A modal dialog on the native <dialog> and showModal(): the browser makes the page behind
	 * inert, keeps Tab inside, and closes on Escape. The panel is bg-canvas with one hairline,
	 * 8px corners, no shadow and no fill panel; the scrim and the 200ms rise come from the
	 * overlay and overlay-backdrop utilities.
	 *
	 * The title is always rendered, as the heading and the accessible name; titleHidden keeps it
	 * for assistive technology only. The Close control sits in the header row, outside the
	 * scrolling body, so a long dialog never hides it. The panel keeps a 16px gutter at 320px
	 * and at 200% zoom, and scrolls inside when it is taller than the viewport. Closing returns
	 * focus to the opener and restores page scroll in the same tick: nothing waits for the exit
	 * animation.
	 *
	 * Spacing: 24px of panel on every side and 16px between title, description, body and
	 * actions, measured to the text. Close is a 44px target on a 28px title line, so -my-2 lets
	 * its target overhang the padding instead of pushing the title down; the actions row's 44px
	 * targets carry 12px above and below their 20px label, so its padding is 12px less.
	 *
	 * With head (CommandMenu), the header row holds that field and Close instead of the title,
	 * with 12px around the field so its focus outline clears the panel edge and the one
	 * hairline under the row, and the body keeps an 8px inset for list rows.
	 */
	import { onMount, type Snippet } from 'svelte';
	import TextButton from './TextButton.svelte';

	interface Props {
		/** The text of the Close button. Translate it when the site is not in English. */
		closeLabel?: string;
		/** Whether the dialog is open. Bindable: bind:open. It follows every close, so Escape, Close and the scrim set it back to false. */
		open?: boolean;
		/** The heading and the accessible name, in sentence case. Required. */
		title: string;
		/**
		 * Keeps the title as the heading and the accessible name but hides it from view (sr-only).
		 * For a panel whose first visible thing names it already, such as a search field.
		 */
		titleHidden?: boolean;
		/**
		 * A field that leads the panel, such as a search input: it fills the header row before
		 * Close, the title is hidden from view as with titleHidden, one hairline separates the row
		 * from the body, and the body keeps an 8px inset instead of 24px.
		 */
		head?: Snippet;
		/** One sentence of context under the title, wired as aria-describedby. */
		description?: string;
		/**
		 * Makes it an alert dialog (role="alertdialog") that a click on the scrim does not close;
		 * Escape and Close still do. Set it for a question that needs an answer, and never put
		 * data-autofocus on the destructive action.
		 */
		urgent?: boolean;
		/** Called after the dialog closes, whichever way: Close, Escape, the scrim, or open set to false. */
		onclose?: () => void;
		/** The body. It scrolls inside the panel when it is taller than the space left. */
		children: Snippet;
		/** Buttons after the body, outside the scroll area, so they stay in view. */
		actions?: Snippet;
	}

	let {
		open = $bindable(false),
		closeLabel = 'Close',
		title,
		titleHidden = false,
		head,
		description,
		urgent = false,
		onclose,
		children,
		actions
	}: Props = $props();

	const uid = $props.id();
	const titleId = `${uid}-title`;
	const descriptionId = `${uid}-description`;

	let dialog: HTMLDialogElement | undefined = $state();
	let opener: HTMLElement | null = null;
	let locked = false;
	let gone = false;
	let pressedOnBackdrop = false;

	function lock() {
		if (locked) return;
		locked = true;
		acquireScrollLock(document.documentElement);
	}

	function unlock() {
		if (!locked) return;
		locked = false;
		releaseScrollLock(document.documentElement);
	}

	function show(el: HTMLDialogElement) {
		const active = document.activeElement;
		opener = active instanceof HTMLElement && active !== document.body ? active : null;
		lock();
		el.showModal();
		// Svelte consumes `autofocus`, so a target inside a closed dialog is not focused on its own.
		el.querySelector<HTMLElement>('[data-autofocus], [autofocus]')?.focus({ preventScroll: true });
	}

	// Follow `open`. The effect only opens: closing goes through close() below, so the native
	// close event is the one place that cleans up.
	$effect(() => {
		const el = dialog;
		if (!el) return;
		if (open && !el.open) show(el);
		else if (!open && el.open) el.close();
	});

	function handleClose() {
		// A queued close event from a dialog that was reopened in between is stale.
		if (dialog?.open) return;
		unlock();
		// The component is already destroyed: its parent's binding and callback are not ours to touch.
		if (gone) return;
		open = false;
		// Focus goes back now, before any exit animation. The browser does it too; this covers
		// an opener that was focused programmatically or removed from and re-added to the page.
		if (opener?.isConnected) opener.focus({ preventScroll: true });
		opener = null;
		onclose?.();
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key !== 'Tab' || !dialog) return;
		const stops = [...dialog.querySelectorAll<HTMLElement>(TABBABLE)].filter((el) =>
			el.checkVisibility()
		);
		const target = wrapTab(stops, document.activeElement as HTMLElement | null, e.shiftKey, dialog);
		if (target) {
			e.preventDefault();
			target.focus();
		}
	}

	function handlePointerDown(e: PointerEvent) {
		pressedOnBackdrop = e.target === dialog;
	}

	function handleClick(e: MouseEvent) {
		// Only a press and a release both on the scrim dismiss: dragging a text selection out of
		// the panel must not close it.
		if (!urgent && pressedOnBackdrop && e.target === dialog) dialog?.close();
		pressedOnBackdrop = false;
	}

	onMount(() => () => {
		gone = true;
		unlock();
		if (dialog?.open) dialog.close();
	});
</script>

<dialog
	bind:this={dialog}
	role={urgent ? 'alertdialog' : undefined}
	aria-labelledby={titleId}
	aria-describedby={description ? descriptionId : undefined}
	class="qd-dialog overlay hairline-box border-border overlay-backdrop bg-canvas text-fg-primary"
	onclose={handleClose}
	onkeydown={handleKeydown}
	onpointerdown={handlePointerDown}
	onclick={handleClick}
>
	<div class="qd-dialog-panel">
		{#if head}
			<div class="flex items-center gap-3 hairline-b border-border/50 py-3 pr-5 pl-3">
				<h2 id={titleId} class="sr-only">{title}</h2>
				<div class="min-w-0 flex-1">{@render head()}</div>
				<TextButton class="shrink-0" onclick={() => dialog?.close()}>{closeLabel}</TextButton>
			</div>
		{:else}
			<div class="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-6 pt-6">
				<h2 id={titleId} class={titleHidden ? 'sr-only' : 'type-title'}>{title}</h2>
				<TextButton class="-my-2 ml-auto shrink-0" onclick={() => dialog?.close()}
					>{closeLabel}</TextButton
				>
			</div>
		{/if}
		{#if description}
			<p id={descriptionId} class="mt-4 px-6 type-small-wrapped">{description}</p>
		{/if}
		<div class={['qd-dialog-body', head ? 'p-2' : ['px-6', actions ? 'pt-4 pb-1' : 'pt-4 pb-6']]}>
			{@render children()}
		</div>
		{#if actions}
			<div class="flex flex-wrap items-center justify-end gap-x-6 gap-y-2 px-6 pb-3">
				{@render actions()}
			</div>
		{/if}
	</div>
</dialog>

<style>
	/* Sized in the 4px unit: 512px wide, never closer than 16px to any viewport edge, and never
	   taller than the viewport minus that gutter. At 200% zoom the viewport is half as wide, so
	   the same rules keep the panel inside it. */
	.qd-dialog {
		margin: auto;
		padding: 0;
		border-radius: var(--shape-radius-lg);
		width: calc(100% - var(--space-unit) * 8);
		max-width: calc(var(--space-unit) * 128);
		max-height: calc(100dvh - var(--space-unit) * 8);
		overflow: hidden;
	}
	.qd-dialog-panel {
		display: flex;
		flex-direction: column;
		max-height: calc(100dvh - var(--space-unit) * 8 - 2px);
	}
	/* The body is the only part that scrolls. min-height 0 lets a flex child shrink below its
	   content, overscroll-behavior keeps the page behind from scrolling at the end. */
	.qd-dialog-body {
		min-height: 0;
		overflow-y: auto;
		overscroll-behavior: contain;
	}
	:global(html.qd-scroll-lock) {
		overflow: hidden;
	}
</style>
