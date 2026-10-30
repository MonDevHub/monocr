<script lang="ts" module>
	export type ErrorEntry = { id: string; text: string };

	/**
	 * Decides when the summary takes focus: once for every new errors array that has
	 * something in it. The decision is by identity, not by text, so a second failed submit
	 * with the same problems (a new array, the same words) takes focus again; the same array
	 * read twice does not. An empty array forgets the last one.
	 */
	export function focusTracker() {
		let last: readonly ErrorEntry[] | undefined;
		return (errors: readonly ErrorEntry[]): boolean => {
			if (errors.length === 0) {
				last = undefined;
				return false;
			}
			if (errors === last) return false;
			last = errors;
			return true;
		};
	}
</script>

<script lang="ts">
	/*
	 * What went wrong with a form, said once at the top in words: a heading and one link per
	 * problem, each link taking the reader to the field it names. No box, no tint, no icon,
	 * no red: the words and the move of focus carry it (DESIGN.md sections 2 and 8). It
	 * renders nothing while there is nothing to say. Every new errors array it receives takes
	 * focus once (each submit passes a new array), so a keyboard or screen reader user lands
	 * on the list instead of hunting for it; the outline it shows is the global one. The links
	 * are rows on their own lines, so they are type-label, as Link standalone is.
	 */
	interface Props {
		/** One entry per problem, in the order of the fields. `id` is the id of the control to focus; `text` is what is wrong and the next step ("Enter an email address"). Pass a new array on each submit: every new array takes focus once. Empty renders nothing. */
		errors: ErrorEntry[];
		/** The heading above the list, sentence case. Change it when the form needs a more specific heading. */
		title?: string;
		/** The heading level, so the summary sits in the page outline: 2 at the top of a page's form, 3 inside a Section, 4 under an h3. */
		headingLevel?: 2 | 3 | 4;
	}

	const { errors, title = 'There is a problem', headingLevel = 2 }: Props = $props();

	let container: HTMLDivElement | undefined = $state();
	// Plain, not $state: what it remembers must not re-run the effect.
	const shouldFocus = focusTracker();

	$effect(() => {
		// Reading `errors` itself tracks the array's identity: a new array re-runs this.
		const current = errors;
		if (!container) {
			// Nothing rendered yet; let an empty set reset the tracker all the same.
			if (current.length === 0) shouldFocus(current);
			return;
		}
		if (shouldFocus(current)) container.focus();
	});

	function jump(event: MouseEvent, id: string) {
		const field = document.getElementById(id);
		// No such field: let the browser follow the fragment as a plain link.
		if (!field) return;
		event.preventDefault();
		field.focus({ preventScroll: true });
		field.scrollIntoView({ block: 'center' });
	}
</script>

{#if errors.length > 0}
	<div bind:this={container} role="alert" tabindex="-1" class="space-y-2">
		<svelte:element this={`h${headingLevel}`} class="type-label">{title}</svelte:element>
		<ul role="list" class="space-y-1">
			{#each errors as error, i (i)}
				<li>
					<a
						href="#{error.id}"
						class="inline-flex min-h-target items-center type-label link focus-visible:rounded-sm"
						onclick={(event) => jump(event, error.id)}>{error.text}</a
					>
				</li>
			{/each}
		</ul>
	</div>
{/if}
