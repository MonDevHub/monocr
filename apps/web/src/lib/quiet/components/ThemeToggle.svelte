<script lang="ts">
	/*
	 * Light / dark switch. Its name says the theme it switches to; the icon shows the theme
	 * showing (sun in light, moon in dark), swapped instantly with no rotation or crossfade.
	 *
	 * Both icons and both names are in the markup, and quiet.css shows the right ones from
	 * the html class (theme-light-only / theme-dark-only). So the toggle is right from first
	 * paint, before any JavaScript runs, in either theme. The name changes with the theme, so
	 * it must not also carry aria-pressed.
	 */
	import { applyTheme, currentTheme } from '../theme';
	import { ICON_BUTTON_CLASS, ICON_PRESS_CLASS } from './IconButton.svelte';
	import Icon from './Icon.svelte';

	interface Props {
		/** The accessible name while light is showing. Change it when the site is not in English. */
		darkLabel?: string;
		/** The accessible name while dark is showing. Change it when the site is not in English. */
		lightLabel?: string;
	}

	const { darkLabel = 'Toggle dark mode', lightLabel = 'Toggle light mode' }: Props = $props();

	function toggle(): void {
		const next = currentTheme() === 'dark' ? 'light' : 'dark';
		applyTheme(next);
	}
</script>

<button type="button" class={ICON_BUTTON_CLASS} onclick={toggle}>
	<span class="sr-only theme-light-only">{darkLabel}</span>
	<span class="sr-only theme-dark-only">{lightLabel}</span>
	<span class={ICON_PRESS_CLASS} aria-hidden="true">
		<span class="inline-flex theme-light-only"><Icon name="sun" size={20} /></span>
		<span class="inline-flex theme-dark-only"><Icon name="moon" size={20} /></span>
	</span>
</button>
