<script lang="ts">
	/*
	 * Running text: paragraphs in type-body (16/26, fg-secondary), 16px apart, inside the
	 * 576px reading measure. Links inside use Link (inline).
	 */
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	interface Props extends HTMLAttributes<HTMLDivElement> {
		/** Paragraphs. */
		children: Snippet;
		/** 12px between paragraphs instead of 16px. Set it inside an entry. */
		tight?: boolean;
		/** Cap the width at the 576px measure. Turn it off only inside a narrower grid cell. */
		measure?: boolean;
	}

	const { children, tight = false, measure = true, class: className, ...rest }: Props = $props();
</script>

<div
	class={[measure && 'max-w-measure', tight ? 'space-y-3' : 'space-y-4', 'type-body', className]}
	{...rest}
>
	{@render children()}
</div>
