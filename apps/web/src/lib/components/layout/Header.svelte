<script lang="ts">
	/*
	 * The quiet-ds header with this app's five links and its controls (language, theme). The
	 * links are translated through paraglide; the current page is marked by quiet-ds's Nav
	 * (aria-current). On a phone the links and the controls sit in quiet-ds's drawer.
	 */
	import { page } from '$app/stores';
	import QuietHeader from '$lib/quiet/components/Header.svelte';
	import { currentLang } from '$lib/i18n';
	import { m } from '$lib/paraglide/messages';
	import ThemeToggle from '../ui/ThemeToggle.svelte';
	import LanguageToggle from '../ui/LanguageToggle.svelte';

	const items = $derived.by(() => {
		void $currentLang; // labels follow the language
		return [
			{ href: '/', label: m.nav_home() },
			{ href: '/docs', label: m.nav_docs() },
			{ href: '/report', label: m.nav_feedback() },
			{ href: '/contribute', label: m.nav_contribute() },
			{ href: '/privacy', label: m.nav_privacy() }
		];
	});
</script>

{#snippet controls()}
	<LanguageToggle />
	<ThemeToggle />
{/snippet}

<QuietHeader
	{items}
	current={$page.url.pathname}
	{controls}
	name="MonOCR"
	menuLabel={m.nav_toggle_menu()}
	closeLabel={m.nav_close_menu()}
	drawerLabel={m.nav_menu()}
	navLabel={m.nav_main()}
	drawerNavLabel={m.nav_menu()}
/>
