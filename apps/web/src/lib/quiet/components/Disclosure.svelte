<script lang="ts">
	/*
	 * A section the reader opens for detail: native <details>/<summary>, so it opens and closes
	 * with no JavaScript. The summary is a 44px row in type-label with a "+" or "−" at the end
	 * (a word glyph, not a rotating chevron). Stack several with a hairline between; each is
	 * independent, so any number can be open at once. The content fades in over 200ms (opacity
	 * only: the height snaps, because animating it would move the text under the reader's eye).
	 * Give it an `id` and a link to #id opens it on load.
	 */
	import { onMount, type Snippet } from 'svelte';
	import type { HTMLDetailsAttributes } from 'svelte/elements';

	interface Props extends Omit<HTMLDetailsAttributes, 'title' | 'children' | 'open' | 'name'> {
		/** The text of the summary row: what is inside, in sentence case. */
		title: string;
		/** Whether the content is shown. Bind it to read or set it from the page. */
		open?: boolean;
		/** The content shown when open. */
		children: Snippet;
	}

	let { title, open = $bindable(false), children, id, class: className, ...rest }: Props = $props();

	// The fade is armed after load, so a disclosure that starts open does not animate in.
	let armed = $state(false);

	onMount(() => {
		const fromHash = () => {
			if (id && decodeURIComponent(location.hash.slice(1)) === id) open = true;
		};
		fromHash();
		window.addEventListener('hashchange', fromHash);
		const frame = requestAnimationFrame(() => (armed = true));
		return () => {
			window.removeEventListener('hashchange', fromHash);
			cancelAnimationFrame(frame);
		};
	});
</script>

<details
	{id}
	bind:open
	class={['group hairline-t border-border/50 first:border-t-0', armed && 'armed', className]}
	{...rest}
>
	<summary class="flex min-h-target cursor-pointer items-center justify-between gap-4 type-label">
		<span>{title}</span>
		<span aria-hidden="true" class="shrink-0 type-small">
			<span class="group-open:hidden">+</span><span class="hidden group-open:inline">−</span>
		</span>
	</summary>
	<div class="content max-w-measure pb-4 type-small-wrapped">{@render children()}</div>
</details>

<style>
	summary {
		list-style: none;
	}
	summary::-webkit-details-marker {
		display: none;
	}
	.armed .content {
		transition: opacity var(--motion-medium) var(--motion-ease-out);
	}
	@starting-style {
		.armed .content {
			opacity: 0;
		}
	}
</style>
