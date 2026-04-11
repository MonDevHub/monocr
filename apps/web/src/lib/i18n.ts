import * as runtime from '$lib/paraglide/runtime.js';
import * as m from '$lib/paraglide/messages.js';
import { writable } from 'svelte/store';
import { logger } from '$lib/logger';
import { browser } from '$app/environment';

// Map runtime exports to consistent names
export const sourceLanguageTag = runtime.baseLocale;
export const availableLanguageTags = runtime.locales;

export { m };

export type AvailableLanguageTag = (typeof runtime.locales)[number];
export type Locale = AvailableLanguageTag;

// Create a Svelte store to track the current language
// Initialize with sourceLanguageTag, but update it potentially in init
export const currentLang = writable<AvailableLanguageTag>(sourceLanguageTag);
// Alias for backward compatibility
export const currentLanguage = currentLang;

// Initialize functionality
export const initLanguage = () => {
	if (!browser) return;

	try {
		// 1. Try localStorage
		const saved = localStorage.getItem('PARAGLIDE_LOCALE') as AvailableLanguageTag;
		if (saved && availableLanguageTags.includes(saved)) {
			runtime.setLocale(saved);
			currentLang.set(saved);
			return;
		}

		// 2. Try browser detection or Paraglide default
		const detected = runtime.getLocale() as AvailableLanguageTag;
		if (availableLanguageTags.includes(detected)) {
			currentLang.set(detected);
		} else {
			currentLang.set(sourceLanguageTag);
		}
	} catch (error) {
		logger.error('lang init failed:', error);
	}
};

// Function to switch language manually
export const switchLanguage = async (lang: string) => {
	try {
		if (!availableLanguageTags.includes(lang as AvailableLanguageTag)) {
			logger.warn(`invalid locale: ${lang}, using ${sourceLanguageTag}`);
			return;
		}

		const newLang = lang as AvailableLanguageTag;

		// Update runtime
		await runtime.setLocale(newLang);

		// Update store
		currentLang.set(newLang);

		// Persist
		if (browser) {
			localStorage.setItem('PARAGLIDE_LOCALE', newLang);
			window.dispatchEvent(new CustomEvent('languagechange', { detail: { lang: newLang } }));
		}
	} catch (error) {
		logger.error('lang switch failed:', error);
	}
};
