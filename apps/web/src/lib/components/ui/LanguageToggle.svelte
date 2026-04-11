<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { currentLanguage, switchLanguage, sourceLanguageTag } from '$lib/i18n';
	import { m } from '$lib/paraglide/messages';
	import { logger } from '$lib/logger';
	import { browser } from '$app/environment';
	import { fly } from 'svelte/transition';

	interface Props {
		disabled?: boolean;
	}

	const { disabled = false }: Props = $props();

	let lang = $state(sourceLanguageTag);
	let isOpen = $state(false);
	let dropdownElement: HTMLDivElement | undefined = $state();

	let unsubscribe: (() => void) | null = null;
	let languageChangeHandler: ((e: Event) => void) | null = null;

	const languages = [
		{ id: 'en', label: 'EN', full: 'English' },
		{ id: 'my', label: 'ဗမာ', full: 'Burmese' },
		{ id: 'mnw', label: 'မန်', full: 'Mon' }
	] as const;

	onMount(() => {
		if (!browser) return;

		unsubscribe = currentLanguage.subscribe((value) => {
			lang = value;
		});

		languageChangeHandler = (e: Event) => {
			const customEvent = e as CustomEvent<{ lang: string }>;
			if (customEvent.detail?.lang) {
				lang = customEvent.detail.lang;
			}
		};

		window.addEventListener('languagechange', languageChangeHandler);

		const handleClickOutside = (event: MouseEvent) => {
			if (isOpen && dropdownElement && !dropdownElement.contains(event.target as Node)) {
				isOpen = false;
			}
		};

		window.addEventListener('click', handleClickOutside);

		return () => {
			window.removeEventListener('click', handleClickOutside);
		};
	});

	onDestroy(() => {
		if (unsubscribe) {
			unsubscribe();
			unsubscribe = null;
		}
		if (languageChangeHandler) {
			window.removeEventListener('languagechange', languageChangeHandler);
			languageChangeHandler = null;
		}
	});

	async function selectLanguage(newLang: 'en' | 'my' | 'mnw'): Promise<void> {
		if (!browser || disabled || newLang === lang) {
			isOpen = false;
			return;
		}

		try {
			await switchLanguage(newLang);
			isOpen = false;
		} catch (error) {
			logger.error('Language selection failed:', error);
		}
	}

	const currentLangInfo = $derived(languages.find((l) => l.id === lang) || languages[0]);
</script>

<div class="relative" bind:this={dropdownElement}>
	<button
		onclick={() => !disabled && (isOpen = !isOpen)}
		class="text-fg-secondary hover:bg-canvas-subtle hover:text-fg-primary focus:bg-canvas-subtle flex h-10 min-w-[48px] cursor-pointer items-center justify-center gap-1.5 rounded-md px-2.5 text-[13px] font-bold transition-all focus:outline-none"
		aria-label="Select language (Current: {currentLangInfo.full})"
		aria-expanded={isOpen}
		aria-haspopup="listbox"
		type="button"
		tabindex={disabled ? -1 : 0}
	>
		{currentLangInfo.label}
		<svg
			xmlns="http://www.w3.org/2000/svg"
			viewBox="0 0 20 20"
			fill="currentColor"
			class="h-4 w-4 opacity-50 transition-transform duration-200"
			class:rotate-180={isOpen}
		>
			<path
				fill-rule="evenodd"
				d="M5.22 8.22a.75.75 0 0 1 1.06 0L10 11.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 9.28a.75.75 0 0 1 0-1.06Z"
				clip-rule="evenodd"
			/>
		</svg>
	</button>

	{#if isOpen}
		<div
			transition:fly={{ y: 5, duration: 150 }}
			class="bg-canvas border-border shadow-huge absolute right-0 z-50 mt-2 min-w-[120px] overflow-hidden rounded-[var(--radius-lg)] border"
			role="listbox"
		>
			<div class="flex flex-col p-1.5">
				{#each languages as language (language.id)}
					<button
						onclick={() => selectLanguage(language.id)}
						class="hover:bg-canvas-subtle group flex w-full items-center justify-between rounded-[var(--radius-md)] px-3 py-2 text-left text-[13px] transition-colors duration-150"
						class:text-fg-primary={lang === language.id}
						class:bg-canvas-subtle={lang === language.id}
						class:text-fg-secondary={lang !== language.id}
						role="option"
						aria-selected={lang === language.id}
					>
						<div class="flex flex-col">
							<span class="font-medium">{language.full}</span>
							{#if language.id === 'mnw'}
								<span
									class="text-fg-accent text-[9px] font-semibold tracking-wide uppercase opacity-70"
								>
									{m.nav_mon_heritage()}
								</span>
							{/if}
						</div>
						{#if lang === language.id}
							<svg
								xmlns="http://www.w3.org/2000/svg"
								viewBox="0 0 20 20"
								fill="currentColor"
								class="text-fg-accent h-4 w-4"
							>
								<path
									fill-rule="evenodd"
									d="M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z"
									clip-rule="evenodd"
								/>
							</svg>
						{/if}
					</button>
				{/each}
			</div>
		</div>
	{/if}
</div>
