<script lang="ts">
	/*
	 * Language switch: a quiet-ds Menu. The trigger shows the current language's short label
	 * and names it for assistive technology; the rows switch it. Persistence, <html lang> and
	 * the currentLang store are $lib/i18n's.
	 */
	import Menu from '$lib/quiet/components/Menu.svelte';
	import Icon from '$lib/quiet/components/Icon.svelte';
	import { currentLang, switchLanguage } from '$lib/i18n';
	import { m } from '$lib/paraglide/messages';

	const languages = [
		{ id: 'en', label: 'EN', full: 'English' },
		{ id: 'my', label: 'ဗမာ', full: 'Burmese' },
		{ id: 'mnw', label: 'မန်', full: 'Mon' }
	] as const;

	const current = $derived(languages.find((l) => l.id === $currentLang) ?? languages[0]);

	const items = $derived(
		languages.map((l) => ({
			id: l.id,
			label: l.full,
			onselect: () => {
				if (l.id !== $currentLang) void switchLanguage(l.id);
			}
		}))
	);
</script>

<Menu label={m.lang_label()} {items} align="end">
	{#snippet trigger(props)}
		<button
			type="button"
			{...props}
			aria-label="{current.label}: {m.lang_label()}, {current.full}"
			class="min-h-target min-w-target type-label text-fg-secondary hover:bg-canvas-subtle hover:text-fg-primary inline-flex cursor-pointer items-center justify-center gap-1 rounded-md px-2 transition-colors duration-150"
		>
			{current.label}
			<Icon name="chevron-down" size={16} />
		</button>
	{/snippet}
</Menu>
