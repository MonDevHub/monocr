<script lang="ts">
	/* The quiet-ds footer in its product shape: the name over one fact, then two text links. */
	import QuietFooter from '$lib/quiet/components/Footer.svelte';
	import { m } from '$lib/paraglide/messages';
</script>

<QuietFooter
	name="MonOCR"
	note={m.footer_tagline()}
	textLabel={m.footer_links()}
	textLinks={[
		{ href: '/privacy', label: m.footer_privacy() },
		{ href: 'https://github.com/MonDevHub/monocr', label: m.footer_github() }
	]}
/>
