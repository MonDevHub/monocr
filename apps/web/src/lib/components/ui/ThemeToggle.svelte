<script lang="ts">
	import { onMount } from 'svelte';
	import { browser } from '$app/environment';

	let isDark = $state(false);

	onMount(() => {
		if (!browser) return;
		isDark = document.documentElement.classList.contains('dark');

		const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
		const handleChange = (e: MediaQueryListEvent) => {
			if (!localStorage.getItem('theme')) {
				isDark = e.matches;
				if (isDark) {
					document.documentElement.classList.add('dark');
				} else {
					document.documentElement.classList.remove('dark');
				}
			}
		};

		mediaQuery.addEventListener('change', handleChange);
		return () => mediaQuery.removeEventListener('change', handleChange);
	});

	function toggleTheme(): void {
		if (!browser) return;

		isDark = !isDark;

		if (isDark) {
			document.documentElement.classList.add('dark');
			try {
				localStorage.setItem('theme', 'dark');
			} catch {
				// localStorage unavailable
			}
		} else {
			document.documentElement.classList.remove('dark');
			try {
				localStorage.setItem('theme', 'light');
			} catch {
				// localStorage unavailable
			}
		}
	}
</script>

<button
	onclick={toggleTheme}
	class="text-fg-secondary hover:bg-canvas-subtle hover:text-fg-primary flex h-10 w-10 cursor-pointer touch-manipulation items-center justify-center rounded-[var(--radius-lg)] p-2 transition-all duration-250 ease-in-out focus:outline-none active:scale-[0.95]"
	aria-label="Toggle {isDark ? 'light' : 'dark'} mode"
	type="button"
>
	{#if isDark}
		<!-- Moon Icon -->
		<svg
			xmlns="http://www.w3.org/2000/svg"
			width="20"
			height="20"
			viewBox="0 0 24 24"
			fill="none"
			stroke="currentColor"
			stroke-width="1.5"
			stroke-linecap="round"
			stroke-linejoin="round"
			class="opacity-80 hover:opacity-100"
		>
			<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
		</svg>
	{:else}
		<!-- Sun Icon -->
		<svg
			xmlns="http://www.w3.org/2000/svg"
			width="20"
			height="20"
			viewBox="0 0 24 24"
			fill="none"
			stroke="currentColor"
			stroke-width="1.5"
			stroke-linecap="round"
			stroke-linejoin="round"
			class="opacity-80 hover:opacity-100"
		>
			<circle cx="12" cy="12" r="5"></circle>
			<line x1="12" y1="1" x2="12" y2="3"></line>
			<line x1="12" y1="21" x2="12" y2="23"></line>
			<line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
			<line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
			<line x1="1" y1="12" x2="3" y2="12"></line>
			<line x1="21" y1="12" x2="23" y2="12"></line>
			<line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
			<line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
		</svg>
	{/if}
</button>
