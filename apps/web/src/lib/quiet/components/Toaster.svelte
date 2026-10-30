<script lang="ts" module>
	export { toast, dismissToast } from './toast.svelte';
	export type { ToastOptions, ToastAction } from './toast.svelte';
</script>

<script lang="ts">
	/*
	 * The one place toasts appear. Mount it once, in the layout; call toast("Saved.") from
	 * anywhere. The region is a role="status" aria-live="polite" container that is in the DOM
	 * from the start and empty at rest, so each message is announced as it is added. It is a
	 * popover="manual" in the top layer, fixed to the bottom right: 24px from the viewport edges
	 * and 384px wide (max-w-sm) from sm up, and below sm the full width less a 16px inset on each
	 * side. Toasts take no focus. Nothing dismisses itself unless a confirmation with no action
	 * opts in with `duration` (5 seconds or more, paused while hovered or focused). Escape
	 * dismisses the focused toast. No status colour and no icon: the words carry the meaning.
	 */
	import { onMount, tick } from 'svelte';
	import TextButton from './TextButton.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';
	import { ToastTimer, toasts, visibleToasts, type ToastItem } from './toast.svelte';

	const visible = $derived(visibleToasts(toasts.items));

	let region: HTMLElement | undefined = $state();
	/** Where focus was before it entered the region: it goes back there if the focused toast is dismissed. */
	let before: Element | null = null;

	onMount(() => {
		// Shown before any toast exists, so the live region is rendered and watched from the start.
		region?.showPopover();
	});

	/** Runs the opt-in countdown for one toast, pausing it while the pointer or focus is inside. */
	function countdown(node: HTMLElement, item: ToastItem) {
		if (item.duration === undefined) return;
		const timer = new ToastTimer(item.duration, () => toasts.dismiss(item.id));
		const over = (v: boolean) => () => timer.setHovered(v);
		const inside = (v: boolean) => () => timer.setFocused(v);
		node.addEventListener('pointerenter', over(true));
		node.addEventListener('pointerleave', over(false));
		node.addEventListener('focusin', inside(true));
		node.addEventListener('focusout', inside(false));
		return () => timer.destroy();
	}

	async function dismiss(id: number) {
		const hadFocus = region?.contains(document.activeElement) ?? false;
		const list = visible.filter((t) => !t.leaving);
		const at = list.findIndex((t) => t.id === id);
		toasts.dismiss(id);
		if (!hadFocus) return;
		await tick();
		// Focus was on the toast that is going. Give it back to where it came from, or to the
		// toast that took its place, rather than let it fall to the top of the page.
		if (before?.isConnected && before instanceof HTMLElement && !region?.contains(before)) {
			before.focus();
			return;
		}
		const left = region?.querySelectorAll<HTMLElement>('li');
		const rest = left ? [...left].filter((li) => !li.hasAttribute('data-leaving')) : [];
		if (rest.length === 0) return;
		const buttons = rest[Math.min(Math.max(at, 0), rest.length - 1)].querySelectorAll('button');
		buttons[buttons.length - 1]?.focus();
	}

	function onkeydown(e: KeyboardEvent) {
		if (e.key !== 'Escape') return;
		const li = (e.target as Element | null)?.closest('li');
		const id = li?.getAttribute('data-toast');
		if (!li || !id) return;
		e.preventDefault();
		dismiss(Number(id));
	}

	function onfocusin(e: FocusEvent) {
		const from = e.relatedTarget as Element | null;
		if (from && !region?.contains(from)) before = from;
	}
</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
	bind:this={region}
	popover="manual"
	role="status"
	aria-live="polite"
	aria-atomic="false"
	class="qd-toaster inset-x-4 bottom-4 w-auto sm:right-6 sm:bottom-6 sm:left-auto sm:w-full sm:max-w-sm"
	{onkeydown}
	{onfocusin}
>
	{#if visible.length > 0}
		<ul role="list" class="flex flex-col gap-3">
			{#each visible as item (item.id)}
				<li
					data-toast={item.id}
					data-leaving={item.leaving || undefined}
					aria-hidden={item.leaving || undefined}
					inert={item.leaving || undefined}
					class="qd-toast pointer-events-auto flex flex-wrap items-center gap-x-4 rounded-lg hairline-box border-border bg-canvas px-4"
					{@attach (node) => countdown(node, item)}
				>
					<p class="min-w-0 flex-1 py-3 type-label break-words">{item.message}</p>
					<div class="flex items-center gap-x-4">
						{#if item.action}
							{@const action = item.action}
							<TextButton
								onclick={() => {
									action.onclick();
									dismiss(item.id);
								}}>{action.label}</TextButton
							>
						{/if}
						<TextButton onclick={() => dismiss(item.id)}
							>Dismiss<VisuallyHidden>{item.message}</VisuallyHidden></TextButton
						>
					</div>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	/* The popover is a column at the bottom right of the viewport; its classes set the insets
	   and the width. This block only undoes the browser's popover box (centred, bordered,
	   filled) and sets no left, right, bottom or width, so the classes win. The column itself
	   never catches a click: only a toast does, so the page under the gaps stays usable. */
	.qd-toaster {
		position: fixed;
		top: auto;
		margin: 0;
		padding: 0;
		border: 0;
		background: none;
		overflow: visible;
		height: auto;
		pointer-events: none;
	}
	/* Enter: fade and rise var(--motion-nudge) over var(--motion-medium), ease-out. */
	.qd-toast {
		opacity: 1;
		translate: 0 0;
		transition:
			opacity var(--motion-medium) var(--motion-ease-out),
			translate var(--motion-medium) var(--motion-ease-out);
		@starting-style {
			opacity: 0;
			translate: 0 var(--motion-nudge);
		}
	}
	/* Exit: opacity only, faster. */
	.qd-toast[data-leaving] {
		opacity: 0;
		transition: opacity var(--motion-fast) var(--motion-ease-standard);
	}
</style>
