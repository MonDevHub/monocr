<script lang="ts">
	import { page } from '$app/state';
	import { m } from '$lib/paraglide/messages';
	import { Button, EmptyState, Link } from '$lib/quiet';

	const status = $derived(page.status);
	const title = $derived(
		status === 404 ? m.error_page_not_found_title() : m.error_page_generic_title()
	);
	const text = $derived(
		status === 404 ? m.error_page_not_found_text() : m.error_page_generic_text()
	);
</script>

<svelte:head>
	<title>{status} - MonOCR</title>
</svelte:head>

<main id="main-content" class="max-w-column pt-page-top pb-page-bottom sm:pt-page-top-wide">
	<h1 class="type-h1">{status}</h1>
	<EmptyState level={2} {title} {text}>
		{#snippet action()}
			<div class="flex flex-wrap items-center gap-x-6 gap-y-2">
				<Button variant="primary" href="/">{m.error_page_home()}</Button>
				<Link href="/docs" standalone>{m.error_page_docs()}</Link>
			</div>
		{/snippet}
	</EmptyState>
</main>
