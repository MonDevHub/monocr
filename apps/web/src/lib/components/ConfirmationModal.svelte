<script lang="ts">
	import { Button, Dialog } from '$lib/quiet';
	import { m } from '$lib/paraglide/messages';

	interface Props {
		isOpen: boolean;
		title: string;
		message: string;
		confirmLabel?: string;
		cancelLabel?: string;
		onConfirm: () => void;
		onCancel: () => void;
	}

	let {
		isOpen,
		title,
		message,
		confirmLabel = m.modal_confirm_title(),
		cancelLabel = m.modal_cancel(),
		onConfirm,
		onCancel
	}: Props = $props();

	// The parent owns `isOpen`; the dialog keeps its own copy so that Escape and Close, which
	// the browser handles, can close it, and tells the parent through onCancel.
	let open = $derived(isOpen);
</script>

<!-- An urgent dialog: it answers a destructive choice, so the scrim does not dismiss it. The
     choice is told by its words (the message says what is lost), not by colour, and focus
     starts on Cancel, never on the destructive action. -->
<Dialog
	bind:open
	urgent
	{title}
	closeLabel={m.history_close_view()}
	description={message}
	onclose={() => {
		// Confirming sets isOpen false first; only a close from inside is a cancel.
		if (isOpen) onCancel();
	}}
>
	<!-- The message is the description; the body is empty. -->
	<div></div>
	{#snippet actions()}
		<Button data-autofocus onclick={onCancel}>{cancelLabel}</Button>
		<Button variant="primary" onclick={onConfirm}>{confirmLabel}</Button>
	{/snippet}
</Dialog>
