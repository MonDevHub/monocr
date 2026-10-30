<script lang="ts" module>
	export type DiffKind = 'add' | 'remove' | 'context';

	export interface DiffLine {
		kind: DiffKind;
		text: string;
	}

	/** The marker drawn in the gutter. "−" is the real minus sign; the copied text uses "-". */
	export const MARKERS: Record<DiffKind, string> = { add: '+', remove: '−', context: ' ' };

	/** The word a screen reader hears before a changed line. Context lines say nothing. */
	export const WORDS: Record<DiffKind, string> = {
		add: 'Added: ',
		remove: 'Removed: ',
		context: ''
	};

	/** What Copy puts on the clipboard: a unified-diff body, "+", "-" or a space, then the line. No line numbers. */
	export function patchText(lines: readonly DiffLine[]): string {
		const prefix: Record<DiffKind, string> = { add: '+', remove: '-', context: ' ' };
		return lines.map((l) => prefix[l.kind] + l.text).join('\n');
	}
</script>

<script lang="ts">
	/*
	 * A diff in the system's own terms: Inter with code-literal (exact characters, no
	 * ligatures) and tabular figures, no red, no green. The kind of a line is carried by a
	 * marker ("+", "−"), a hidden word ("Added:", "Removed:") and its style: added lines are
	 * fg-primary with a hairline rule just before the text, removed lines fg-secondary and struck
	 * through, context lines fg-secondary. None of it depends on hue, so it survives forced
	 * colours and a black and white print. The gutter (number and marker) is aria-hidden and not
	 * selectable.
	 */
	import { CopyAction, COPY_WORDS } from './CodeBlock.svelte';
	import TextButton from './TextButton.svelte';
	import VisuallyHidden from './VisuallyHidden.svelte';

	interface Props {
		/** The lines in order, one entry per line. They are numbered 1, 2, 3 by their position in this list. */
		lines: DiffLine[];
		/** A file name ("src/lib/index.ts"). Shown as the figure's caption, it names the region and is added to the button's name. Without it the region is "Diff" and the button "Copy diff". */
		caption?: string;
		/** Shows the "Copy" button and its status region. It copies the patch: "+", "-" or a space, then the line. Set it to false for a diff nobody should apply. */
		copy?: boolean;
	}

	const { lines, caption, copy = true }: Props = $props();

	const suffix = $derived(' ' + (caption ?? 'diff'));

	const action = new CopyAction(async (text) => {
		if (!navigator.clipboard) throw new Error('Clipboard unavailable');
		await navigator.clipboard.writeText(text);
	});

	$effect(() => () => action.destroy());

	/*
	 * The rule sits on the code's own left edge, between the marker and the text, so it reads as
	 * part of the line it marks. Only an added line has a border at all: a transparent one would
	 * be painted in forced colours and rule every line. The others take the same 1px as a margin,
	 * so every line's text starts at the same x.
	 */
	const RULE: Record<DiffKind, string> = {
		add: 'border-l border-border',
		remove: 'ml-px',
		context: 'ml-px'
	};
	/* The line itself is a <code>, which the base layer inks fg-primary, so every kind states its ink. */
	const TEXT: Record<DiffKind, string> = {
		add: 'text-fg-primary',
		remove: 'text-fg-secondary line-through',
		context: 'text-fg-secondary'
	};
</script>

<figure class="flex flex-wrap items-center gap-x-4 gap-y-2">
	{#if caption}<figcaption class="type-meta">{caption}</figcaption>{/if}
	{#if copy}
		<TextButton
			onclick={() => action.press(patchText(lines))}
			class="ml-auto min-w-target justify-end"
			>{COPY_WORDS[action.outcome]}<VisuallyHidden>{suffix}</VisuallyHidden></TextButton
		>
		<p role="status" class="sr-only">{action.announcement}</p>
	{/if}
	<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
	<div
		tabindex="0"
		role="region"
		aria-label={caption ?? 'Diff'}
		class="w-full overflow-x-auto print:overflow-visible"
	>
		<ol role="list" class="w-max min-w-full print:w-full">
			{#each lines as line, i (i)}
				<li class="flex items-baseline gap-3 pr-4 code-literal type-small tabular-nums">
					<span aria-hidden="true" class="min-w-8 shrink-0 text-right select-none">{i + 1}</span>
					<span aria-hidden="true" class="w-4 shrink-0 text-center select-none"
						>{MARKERS[line.kind]}</span
					>
					<code
						class={[
							'pl-2 whitespace-pre print:whitespace-pre-wrap',
							RULE[line.kind],
							TEXT[line.kind]
						]}
						>{#if WORDS[line.kind]}<VisuallyHidden>{WORDS[line.kind]}</VisuallyHidden
							>{/if}{line.text}</code
					>
				</li>
			{/each}
		</ol>
	</div>
</figure>
