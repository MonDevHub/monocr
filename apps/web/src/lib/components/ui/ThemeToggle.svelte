<script lang="ts">
	/*
	 * The quiet-ds toggle with this app's translated names. quiet-ds's theme.ts uses the same
	 * localStorage key ('theme') and `dark` class this app always did, so the behaviour is the
	 * same; the toggle is right from first paint through CSS, not through state.
	 */
	import QuietThemeToggle from '$lib/quiet/components/ThemeToggle.svelte';
	import { m } from '$lib/paraglide/messages';
</script>

<QuietThemeToggle darkLabel={m.theme_to_dark()} lightLabel={m.theme_to_light()} />
