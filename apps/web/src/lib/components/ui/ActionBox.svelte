<script lang="ts">
	/*
	 * A labelled file control: a Field whose control is a 1px hairline-box drop region holding a
	 * Choose file button, the chosen file's name and a way to remove it. The real <input type=file>
	 * stays out of the tab order and the button opens it, so the keyboard path is one stop. A file
	 * dragged over the region tints it (.dropzone-active); a dropped file is kept only when it
	 * matches `accept`, as the picker would.
	 */
	import { Button, Field, TextButton } from '$lib/quiet';
	import { m } from '$lib/paraglide/messages';
	import { acceptsFile } from '$lib/accept-file';

	interface Props {
		/** The button's id; the label, hint and error point at it. */
		id: string;
		label: string;
		hint?: string;
		error?: string;
		accept?: string;
		/** The chosen file. Bindable. */
		file?: File | null;
		/** Called after the file changes by choosing, dropping or removing (null). */
		onfile?: (file: File | null) => void;
	}

	let { id, label, hint, error, accept = '', file = $bindable(null), onfile }: Props = $props();

	let input: HTMLInputElement | undefined = $state();
	let dragging = $state(false);
	/** A dropped file the control does not take: said in words, as the picker would not have offered it. */
	let rejected = $state(false);
	const shownError = $derived(error ?? (rejected ? m.error_file_type() : undefined));

	function set(next: File | null) {
		rejected = false;
		file = next;
		onfile?.(next);
	}

	function onchange(e: Event) {
		const picked = (e.currentTarget as HTMLInputElement).files?.[0];
		if (picked) set(picked);
	}

	function remove() {
		if (input) input.value = '';
		set(null);
		document.getElementById(id)?.focus();
	}

	function ondrop(e: DragEvent) {
		e.preventDefault();
		dragging = false;
		const dropped = e.dataTransfer?.files?.[0];
		if (!dropped) return;
		if (acceptsFile(dropped, accept)) set(dropped);
		else rejected = true;
	}
</script>

<Field {id} {label} {hint} error={shownError} errorLabel={m.error_label()}>
	{#snippet children(control)}
		<div
			class={[
				'hairline-box flex flex-wrap items-center gap-x-4 gap-y-2 rounded-md p-6 transition-colors duration-150',
				shownError ? 'border-link-underline' : 'border-border',
				dragging && 'dropzone-active'
			]}
			role="group"
			aria-label={label}
			ondragover={(e) => {
				e.preventDefault();
				dragging = true;
			}}
			ondragleave={() => (dragging = false)}
			{ondrop}
		>
			<input
				bind:this={input}
				type="file"
				class="hidden"
				{accept}
				tabindex="-1"
				aria-hidden="true"
				{onchange}
			/>
			<Button {...control} onclick={() => input?.click()}>{m.form_choose_file()}</Button>
			{#if file}
				<span class="type-small min-w-0 break-words">{file.name}</span>
				<TextButton onclick={remove}>{m.form_remove_file()}</TextButton>
			{/if}
		</div>
	{/snippet}
</Field>
<!-- A drop gives no other cue, so a rejected file is also said politely. Present and empty first. -->
<p role="status" class="sr-only">{rejected ? m.error_file_type() : ''}</p>
