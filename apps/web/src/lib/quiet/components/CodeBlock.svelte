<script lang="ts" module>
	import { tick } from 'svelte';

	/** How long "Copied" stays before the button reads "Copy" again. A failure has no timer. */
	export const COPIED_MS = 2000;

	export type CopyOutcome = 'idle' | 'copied' | 'failed';

	/** The button word, and the word the status region announces, for each outcome. */
	export const COPY_WORDS: Record<CopyOutcome, string> = {
		idle: 'Copy',
		copied: 'Copied',
		failed: 'Copy failed'
	};

	/**
	 * One copy action's state, kept apart from the markup so its timing can be tested without
	 * a browser. "Copied" is the one timed word in the system: it reads for COPIED_MS, then the
	 * button says "Copy" again and the region empties. "Copy failed" has no timer: it stays
	 * until the next press, because nothing dismisses itself.
	 */
	export class CopyAction {
		outcome = $state<CopyOutcome>('idle');
		/** What the status region holds. Empty at rest. */
		announcement = $state('');
		#timer: ReturnType<typeof setTimeout> | undefined;
		/** Counts presses, so a slow write that finishes after a newer press is ignored. */
		#press = 0;
		readonly #write: (text: string) => Promise<void>;

		constructor(write: (text: string) => Promise<void>) {
			this.#write = write;
		}

		async press(text: string) {
			const press = ++this.#press;
			this.cancel();
			// Empty the region first, so the same word is announced again on a second press.
			this.announcement = '';
			await tick();
			let outcome: CopyOutcome;
			try {
				await this.#write(text);
				outcome = 'copied';
			} catch {
				outcome = 'failed';
			}
			// A newer press owns the button now: a stale result must not overwrite it or leave a
			// timer that would later clear the newer word, a "Copy failed" included.
			if (press !== this.#press) return;
			this.cancel();
			this.outcome = outcome;
			this.announcement = COPY_WORDS[this.outcome];
			if (this.outcome === 'copied') {
				this.#timer = setTimeout(() => {
					this.outcome = 'idle';
					this.announcement = '';
				}, COPIED_MS);
			}
		}

		/**
		 * Stops a pending return to "Copy": on the next press, and when the block unmounts.
		 * Unmounting also makes a write still in flight a stale press, so it arms no timer.
		 */
		cancel() {
			clearTimeout(this.#timer);
		}

		destroy() {
			this.#press += 1;
			this.cancel();
		}
	}
</script>

<script lang="ts">
	/*
	 * A code sample set apart by space, not by a frame: Inter like every other text (monospace
	 * is not in the system), type-small, fg-primary through the base code style, indented 16px
	 * from the column edge. No panel, no border, no fill. code-literal turns off ligatures and
	 * contextual alternates, so the characters on screen are the characters that get copied.
	 * A long line scrolls inside the block, which is a focusable named region so a keyboard can
	 * scroll it too. The copy action is a TextButton that says what happened in words: "Copied"
	 * for 2 seconds, then "Copy" again; "Copy failed" until the next press. Its own status
	 * region, in the DOM from the start, announces the same word.
	 */
	import TextButton from './TextButton.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';

	interface Props {
		/** The code, shown exactly as given: whitespace and line breaks are kept and ligatures are off. "Copy" copies this string. */
		code: string;
		/** A file name or language ("install.sh", "Svelte"). Shown as the figure's caption, it names the region and is added to the button's name. Without it the region is "Code" and the button "Copy code". */
		caption?: string;
		/** Shows the "Copy" button and its status region. Set it to false for output nobody should paste. */
		copy?: boolean;
	}

	const { code, caption, copy = true }: Props = $props();

	/** Makes each button's name unique on the page in every state: "Copy install.sh", "Copy failed code". */
	const suffix = $derived(' ' + (caption ?? 'code'));

	const action = new CopyAction(async (text) => {
		if (!navigator.clipboard) throw new Error('Clipboard unavailable');
		await navigator.clipboard.writeText(text);
	});

	$effect(() => () => action.destroy());
</script>

<!-- figcaption must be a direct child of figure, so the figure is the header row itself: it
     wraps, the caption and the copy action share the first line, the code takes a full line. -->
<figure class="flex flex-wrap items-center gap-x-4 gap-y-2">
	{#if caption}<figcaption class="type-meta">{caption}</figcaption>{/if}
	{#if copy}
		<!-- min-w-target: "Copy" alone is 35px wide, under the 44px target. justify-end sets the
		     word at the right end of that box and ml-auto pushes the box to the right of the row,
		     with or without a caption, so the word's last letter meets the column's right edge. -->
		<TextButton onclick={() => action.press(code)} class="ml-auto min-w-target justify-end"
			>{COPY_WORDS[action.outcome]}<VisuallyHidden>{suffix}</VisuallyHidden></TextButton
		>
		<p role="status" class="sr-only">{action.announcement}</p>
	{/if}
	<!-- A scrollable region must be focusable so a keyboard can scroll it (axe scrollable-region-focusable). -->
	<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
	<pre
		tabindex="0"
		role="region"
		aria-label={caption ?? 'Code'}
		class="w-full overflow-x-auto pl-4 code-literal type-small whitespace-pre"><code
			class="code-literal">{code}</code
		></pre>
</figure>
