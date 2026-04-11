<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { onMount } from 'svelte';
	import { browser } from '$app/environment';
	import { page } from '$app/stores';
	import { m } from '$lib/paraglide/messages';
	import ThemeToggle from '../ui/ThemeToggle.svelte';
	import LanguageToggle from '../ui/LanguageToggle.svelte';

	let isSidebarOpen = $state(false);
	let isMobile = $state(false);
	let isScrolled = $state(false);

	let mediaQuery: MediaQueryList | null = null;
	let rafId: number | null = null;

	$effect(() => {
		if (!browser) return;

		if (rafId !== null) {
			cancelAnimationFrame(rafId);
		}

		if (isMobile && isSidebarOpen) {
			rafId = requestAnimationFrame(() => {
				document.body.style.overflow = 'hidden';
				rafId = null;
			});
		} else {
			rafId = requestAnimationFrame(() => {
				document.body.style.overflow = '';
				rafId = null;
			});
		}

		return () => {
			if (rafId !== null) {
				cancelAnimationFrame(rafId);
				rafId = null;
			}
		};
	});

	onMount(() => {
		if (!browser) return;

		mediaQuery = window.matchMedia('(max-width: 767px)');

		const updateMobile = (m: MediaQueryList | MediaQueryListEvent) => {
			isMobile = m.matches;
			if (!isMobile) {
				isSidebarOpen = false;
			}
		};

		const handleScroll = () => {
			isScrolled = window.scrollY > 20;
		};

		updateMobile(mediaQuery);
		mediaQuery.addEventListener('change', updateMobile);
		window.addEventListener('scroll', handleScroll);

		return () => {
			if (mediaQuery) {
				mediaQuery.removeEventListener('change', updateMobile);
				mediaQuery = null;
			}
			window.removeEventListener('scroll', handleScroll);
			if (rafId !== null) {
				cancelAnimationFrame(rafId);
				rafId = null;
			}
			document.body.style.overflow = '';
		};
	});

	let triggerButton: HTMLButtonElement | undefined = $state();

	function toggleSidebar() {
		isSidebarOpen = !isSidebarOpen;
	}

	function closeSidebar() {
		triggerButton?.focus();
		isSidebarOpen = false;
	}

	function handleKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && isSidebarOpen) {
			closeSidebar();
		}
	}
</script>

<svelte:window onkeydown={handleKeydown} />

<header
	class="fixed top-0 left-0 z-50 w-full transition-all duration-300 print:hidden"
	class:bg-canvas={isScrolled || isSidebarOpen}
	class:bg-opacity-90={isScrolled}
	class:dark:bg-opacity-90={isScrolled}
	class:backdrop-blur-md={isScrolled}
	class:py-2={isScrolled}
	class:py-3={!isScrolled}
>
	<div class="mx-auto flex items-baseline justify-between px-6 sm:px-8 lg:px-8">
		<!-- Mobile Toggle -->
		<button
			bind:this={triggerButton}
			onclick={toggleSidebar}
			class="text-fg-secondary hover:bg-canvas-subtle hover:text-fg-primary flex min-h-[40px] min-w-[40px] touch-manipulation items-center justify-center rounded-md p-2 transition-all focus:outline-none md:hidden"
			aria-label="Toggle navigation menu"
			aria-expanded={isSidebarOpen}
			type="button"
		>
			<img
				src="/icons/bars.svg"
				alt=""
				class="h-5 w-5 opacity-70 transition-all hover:opacity-100 dark:invert"
				width="20"
				height="20"
				fetchpriority="high"
				aria-hidden="true"
			/>
		</button>

		<!-- Desktop Nav -->
		<nav class="ml-auto hidden items-center space-x-4 md:flex" aria-label="Main navigation">
			<a
				class="text-[14px] font-medium transition-all duration-200 focus:outline-none active:scale-95 {$page
					.url.pathname === '/'
					? 'text-fg-primary'
					: 'text-fg-secondary hover:text-fg-primary'}"
				href="/"
			>
				{m.nav_home()}
			</a>
			<a
				class="text-[14px] font-medium transition-all duration-200 focus:outline-none active:scale-95 {$page
					.url.pathname === '/docs'
					? 'text-fg-primary'
					: 'text-fg-secondary hover:text-fg-primary'}"
				href="/docs"
			>
				{m.nav_docs()}
			</a>
			<a
				class="text-[14px] font-medium transition-all duration-200 focus:outline-none active:scale-95 {$page
					.url.pathname === '/report'
					? 'text-fg-primary'
					: 'text-fg-secondary hover:text-fg-primary'}"
				href="/report"
			>
				{m.nav_feedback()}
			</a>
			<a
				class="text-[14px] font-medium transition-all duration-200 focus:outline-none active:scale-95 {$page
					.url.pathname === '/contribute'
					? 'text-fg-primary'
					: 'text-fg-secondary hover:text-fg-primary'}"
				href="/contribute"
			>
				{m.nav_contribute()}
			</a>
			<a
				class="text-[14px] font-medium transition-all duration-200 focus:outline-none active:scale-95 {$page
					.url.pathname === '/privacy'
					? 'text-fg-primary'
					: 'text-fg-secondary hover:text-fg-primary'}"
				href="/privacy"
			>
				{m.nav_privacy()}
			</a>

			<div class="border-border flex items-center space-x-3 border-l pl-4">
				<LanguageToggle />
				<ThemeToggle />
			</div>
		</nav>
	</div>
</header>

<!-- Mobile Overlay -->
<div
	class="pointer-events-none fixed inset-0 z-[60] bg-black/50 transition-opacity duration-300 md:hidden"
	class:opacity-100={isSidebarOpen}
	class:opacity-0={!isSidebarOpen}
	class:pointer-events-auto={isSidebarOpen}
	onclick={closeSidebar}
	role="button"
	tabindex={isSidebarOpen ? 0 : -1}
	aria-hidden={!isSidebarOpen}
	onkeydown={(e) => e.key === 'Enter' && closeSidebar()}
></div>

<!-- Mobile Sidebar -->
<aside
	class="bg-canvas border-border fixed top-0 left-0 z-[70] h-full w-64 transform border-r shadow-md transition-transform duration-500 ease-[cubic-bezier(0.16,1,0.3,1)] md:hidden"
	class:translate-x-0={isSidebarOpen}
	class:-translate-x-full={!isSidebarOpen}
	aria-hidden={!isSidebarOpen}
	inert={!isSidebarOpen}
>
	<div class="flex h-full flex-col p-6">
		<div class="mb-4 flex flex-col px-4 pt-4">
			<span class="text-fg-primary text-xl font-bold tracking-tight">MonOCR</span>
			<span class="text-fg-secondary text-[10px] font-medium opacity-60">Version 1.0.0</span>
		</div>

		<button
			onclick={closeSidebar}
			class="text-fg-secondary hover:text-fg-primary absolute top-4 right-4 flex min-h-[44px] min-w-[44px] items-center justify-center p-2"
			aria-label="Close menu"
			type="button"
		>
			<svg
				xmlns="http://www.w3.org/2000/svg"
				fill="none"
				viewBox="0 0 24 24"
				stroke-width="1.5"
				stroke="currentColor"
				class="h-6 w-6"
			>
				<path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
			</svg>
		</button>

		<nav class="text-fg-secondary flex flex-col space-y-6 text-lg font-medium">
			<a
				onclick={closeSidebar}
				class="block pl-4 transition-all duration-200 {$page.url.pathname === '/'
					? 'border-primary text-fg-primary border-l-2 font-bold'
					: 'hover:border-fg-muted/30 hover:text-fg-primary border-l-2 border-transparent'}"
				href="/"
			>
				{m.nav_home()}
			</a>
			<a
				onclick={closeSidebar}
				class="block pl-4 {$page.url.pathname === '/docs'
					? 'border-fg-accent text-fg-primary border-l-2 font-semibold'
					: 'hover:border-fg-border hover:text-fg-primary border-l-2 border-transparent'}"
				href="/docs"
			>
				{m.nav_docs()}
			</a>
			<a
				onclick={closeSidebar}
				class="block pl-4 {$page.url.pathname === '/report'
					? 'border-fg-accent text-fg-primary border-l-2 font-semibold'
					: 'hover:border-fg-border hover:text-fg-primary border-l-2 border-transparent'}"
				href="/report"
			>
				{m.nav_feedback()}
			</a>
			<a
				onclick={closeSidebar}
				class="block pl-4 {$page.url.pathname === '/contribute'
					? 'border-fg-accent text-fg-primary border-l-2 font-semibold'
					: 'hover:border-fg-border hover:text-fg-primary border-l-2 border-transparent'}"
				href="/contribute"
			>
				{m.nav_contribute()}
			</a>
			<a
				onclick={closeSidebar}
				class="block pl-4 {$page.url.pathname === '/privacy'
					? 'border-fg-accent text-fg-primary border-l-2 font-semibold'
					: 'hover:border-fg-border hover:text-fg-primary border-l-2 border-transparent'}"
				href="/privacy"
			>
				{m.nav_privacy()}
			</a>

			<div class="border-border mt-auto border-t pt-6">
				<div class="flex items-center justify-start gap-6">
					<LanguageToggle disabled={!isSidebarOpen} />
					<ThemeToggle />
				</div>
			</div>
		</nav>
	</div>
</aside>
