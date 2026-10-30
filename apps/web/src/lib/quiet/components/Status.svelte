<script lang="ts">
	/*
	 * The live region for page messages, what happens after load: "Loading", "12 results",
	 * "Link copied", "Saved". Two components carry their own region for their own outcome
	 * instead: CodeBlock (its copy word) and ErrorSummary (the errors on submit). It is always
	 * rendered, empty if need be, because a screen reader only announces changes to a region it
	 * registered before the text arrived: mount it once with the page and change `message`.
	 * A message stays until the next one replaces it; nothing clears it on a timer. Plain
	 * type-small words, no spinner, no dots, no colour. `visible` off keeps it for screen
	 * readers only, when the change is already visible elsewhere.
	 */
	interface Props {
		/** The current message, in words. Empty until the first action; each change is announced. */
		message: string;
		/**
		 * Shows the message on screen. Set it to false when the result already shows on screen:
		 * the message is then sr-only, announced but not seen.
		 */
		visible?: boolean;
	}

	const { message, visible = true }: Props = $props();
</script>

<p role="status" class={visible ? 'type-small' : 'sr-only'}>{message}</p>
