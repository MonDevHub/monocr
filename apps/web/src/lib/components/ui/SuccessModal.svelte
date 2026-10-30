<script lang="ts">
	/*
	 * A confirmation in the vendored Dialog, for a flow that must interrupt. The two form pages
	 * report their result inline (a Status and a Note) because nothing here needs an answer.
	 */
	import { Dialog, Button } from '$lib/quiet';
	import { m } from '$lib/paraglide/messages';

	interface Props {
		isOpen: boolean;
		title?: string;
		message?: string;
		onClose: () => void;
	}

	let {
		isOpen,
		title = m.modal_success_title(),
		message = m.modal_success_msg(),
		onClose
	}: Props = $props();
</script>

<Dialog open={isOpen} {title} closeLabel={m.history_close_view()} onclose={onClose}>
	<p class="type-small">{message}</p>
	{#snippet actions()}
		<Button variant="primary" data-autofocus onclick={onClose}>{m.history_done()}</Button>
	{/snippet}
</Dialog>
