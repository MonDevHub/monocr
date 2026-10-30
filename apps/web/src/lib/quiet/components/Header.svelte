<script lang="ts">
	/*
	 * The fixed header. No name or wordmark: navigation and controls only.
	 *
	 * At the top of the page it is transparent and lets taps through (pointer-events-none on
	 * the bar, auto on its controls), 32px padding. Once the page scrolls past 20px it tightens
	 * to 16px and frosts: canvas at 90% with an 8px backdrop blur. 300ms either way.
	 *
	 * Below 768px the links move into a drawer from the left behind a 44px menu button. The
	 * drawer is a labelled modal dialog: focus moves in on open, Tab is trapped, Escape and the
	 * scrim close it and focus returns to the menu button, the page behind goes inert (Shell
	 * does that), and the body stops scrolling.
	 */
	import { onMount, tick, type Snippet } from 'svelte';
	import type { NavItem } from '../types';
	import { drawerState } from '../state.svelte';
	import Nav from './Nav.svelte';
	import IconButton from './IconButton.svelte';
	import Icon from './Icon.svelte';
	import ThemeToggle from './ThemeToggle.svelte';

	interface Props {
		/** The nav entries, in order, labels in sentence case. Five or fewer. */
		items: NavItem[];
		/** The current path (page.url.pathname). Marks the matching entry. */
		current: string;
		/** Controls after the links, in the bar and in the drawer. Defaults to ThemeToggle. */
		controls?: Snippet;
	}

	const { items, current, controls }: Props = $props();

	let isMobile = $state(false);
	let isScrolled = $state(false);
	let triggerButton: HTMLButtonElement | undefined = $state();
	let drawer: HTMLElement | undefined = $state();

	onMount(() => {
		// The md breakpoint (48rem): the bar shows from 768px, the drawer below it.
		const mobile = window.matchMedia('(max-width: 767px)');
		const updateMobile = () => {
			isMobile = mobile.matches;
			if (!isMobile) drawerState.open = false;
		};
		const handleScroll = () => {
			isScrolled = window.scrollY > 20;
		};
		updateMobile();
		handleScroll();
		mobile.addEventListener('change', updateMobile);
		window.addEventListener('scroll', handleScroll, { passive: true });
		return () => {
			mobile.removeEventListener('change', updateMobile);
			window.removeEventListener('scroll', handleScroll);
			document.body.style.overflow = '';
			drawerState.open = false;
		};
	});

	$effect(() => {
		document.body.style.overflow = isMobile && drawerState.open ? 'hidden' : '';
	});

	function focusFirstInDrawer() {
		drawer?.querySelector<HTMLElement>('button, a[href]')?.focus({ preventScroll: true });
	}

	function openDrawer() {
		drawerState.open = true;
		// Wait for the drawer to drop `inert`: an inert element cannot take focus.
		void tick().then(focusFirstInDrawer);
	}

	function closeDrawer() {
		// Return focus before hiding, so focus never sits inside an aria-hidden subtree.
		triggerButton?.focus();
		drawerState.open = false;
	}

	function trapFocus(e: KeyboardEvent): void {
		if (e.key !== 'Tab' || !drawer) return;
		const focusable = [
			...drawer.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
		].filter((el) => el.tabIndex >= 0);
		if (focusable.length === 0) return;
		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		const active = document.activeElement;
		if (e.shiftKey && (active === first || !drawer.contains(active))) {
			e.preventDefault();
			last.focus();
		} else if (!e.shiftKey && (active === last || !drawer.contains(active))) {
			e.preventDefault();
			first.focus();
		}
	}

	function handleKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && drawerState.open) closeDrawer();
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#snippet defaultControls()}
	<ThemeToggle />
{/snippet}

<header
	class={[
		'fixed top-0 left-0 z-(--layer-header) w-full transition-all duration-300 print:hidden',
		isScrolled
			? 'bg-canvas/90 py-header-scrolled backdrop-blur-header'
			: drawerState.open
				? 'bg-canvas py-header'
				: 'pointer-events-none py-header'
	]}
>
	<div class="mx-auto flex max-w-column items-center justify-between px-gutter sm:px-gutter-wide">
		<IconButton
			bind:ref={triggerButton}
			label="Toggle navigation menu"
			aria-expanded={drawerState.open}
			aria-controls="mobile-menu"
			class="pointer-events-auto md:hidden"
			onclick={openDrawer}
		>
			<Icon name="menu" size={20} />
		</IconButton>

		<Nav {items} {current} variant="bar" controls={controls ?? defaultControls} />
	</div>
</header>

<!-- Pointer-only scrim: never a tab stop, hidden from assistive technology. -->
<button
	type="button"
	tabindex="-1"
	aria-hidden="true"
	aria-label="Close navigation menu"
	class={[
		'fixed inset-0 z-(--layer-scrim) w-full cursor-default bg-scrim transition-opacity duration-300 md:hidden',
		drawerState.open ? 'pointer-events-auto opacity-100' : 'pointer-events-none opacity-0'
	]}
	onclick={closeDrawer}
></button>

<div
	bind:this={drawer}
	id="mobile-menu"
	role="dialog"
	aria-modal="true"
	aria-label="Menu"
	aria-hidden={!drawerState.open}
	inert={!drawerState.open}
	tabindex="-1"
	onkeydown={trapFocus}
	class={[
		'fixed top-0 left-0 z-(--layer-drawer) h-full w-drawer border-r border-border bg-canvas transition-transform duration-300 md:hidden',
		drawerState.open ? 'translate-x-0' : '-translate-x-full'
	]}
>
	<div class="flex h-full flex-col p-6">
		<IconButton label="Close menu" class="mb-8 self-end" onclick={closeDrawer}>
			<Icon name="close" size={24} />
		</IconButton>
		<Nav
			{items}
			{current}
			variant="drawer"
			onnavigate={closeDrawer}
			controls={controls ?? defaultControls}
		/>
	</div>
</div>
