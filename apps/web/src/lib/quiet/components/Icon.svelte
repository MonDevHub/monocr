<script lang="ts" module>
	/** Every icon the system ships, in showcase order. The showcase draws the set from this list. */
	export const ICON_NAMES = [
		'sun',
		'moon',
		'menu',
		'close',
		'arrow-left',
		'mail',
		'code',
		'link',
		'chevron-down',
		'copy',
		'check',
		'search'
	] as const;

	export type IconName = (typeof ICON_NAMES)[number];
</script>

<script lang="ts">
	/*
	 * The system's icons: 24px viewBox, 1.5px round strokes, currentColor, decorative.
	 * Paths: sun and moon from Feather (MIT); the others from Heroicons outline (MIT).
	 * An icon never carries meaning on its own: the control around it has the name.
	 */
	import type { SVGAttributes } from 'svelte/elements';

	interface Props extends SVGAttributes<SVGSVGElement> {
		/** Which icon. */
		name: IconName;
		/** Width and height in px: 20 in controls, 16 beside text, 24 in the drawer. */
		size?: number;
		/** Stroke width. Keep 1.5; only the back arrow uses 2. */
		strokeWidth?: number;
	}

	const { name, size = 20, strokeWidth = 1.5, ...rest }: Props = $props();
</script>

<svg
	xmlns="http://www.w3.org/2000/svg"
	width={size}
	height={size}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width={strokeWidth}
	stroke-linecap="round"
	stroke-linejoin="round"
	aria-hidden="true"
	focusable="false"
	{...rest}
>
	{#if name === 'sun'}
		<circle cx="12" cy="12" r="5"></circle>
		<line x1="12" y1="1" x2="12" y2="3"></line>
		<line x1="12" y1="21" x2="12" y2="23"></line>
		<line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
		<line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
		<line x1="1" y1="12" x2="3" y2="12"></line>
		<line x1="21" y1="12" x2="23" y2="12"></line>
		<line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
		<line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
	{:else if name === 'moon'}
		<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
	{:else if name === 'menu'}
		<path d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5"></path>
	{:else if name === 'close'}
		<path d="M6 18L18 6M6 6l12 12"></path>
	{:else if name === 'arrow-left'}
		<path d="M10 19l-7-7m0 0l7-7m-7 7h18"></path>
	{:else if name === 'mail'}
		<path
			d="M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.243a2.25 2.25 0 0 1-1.07 1.916l-7.5 4.615a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.916V6.75"
		></path>
	{:else if name === 'code'}
		<path d="M17.25 6.75 22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3-4.5 16.5"></path>
	{:else if name === 'link'}
		<path
			d="M13.19 8.688a4.5 4.5 0 0 1 1.242 7.244l-4.5 4.5a4.5 4.5 0 0 1-6.364-6.364l1.757-1.757m13.35-.622 1.757-1.757a4.5 4.5 0 0 0-6.364-6.364l-4.5 4.5a4.5 4.5 0 0 0 1.242 7.244"
		></path>
	{:else if name === 'chevron-down'}
		<path d="m19.5 8.25-7.5 7.5-7.5-7.5"></path>
	{:else if name === 'copy'}
		<path
			d="M15.75 17.25v3.375c0 .621-.504 1.125-1.125 1.125h-9.75a1.125 1.125 0 0 1-1.125-1.125V7.875c0-.621.504-1.125 1.125-1.125H6.75a9.06 9.06 0 0 1 1.5.124m7.5 10.376h3.375c.621 0 1.125-.504 1.125-1.125V11.25c0-4.46-3.243-8.161-7.5-8.876a9.06 9.06 0 0 0-1.5-.124H9.375c-.621 0-1.125.504-1.125 1.125v3.5m7.5 10.375H9.375a1.125 1.125 0 0 1-1.125-1.125v-9.25m12 6.625v-1.875a3.375 3.375 0 0 0-3.375-3.375h-1.5a1.125 1.125 0 0 1-1.125-1.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H9.75"
		></path>
	{:else if name === 'check'}
		<path d="m4.5 12.75 6 6 9-13.5"></path>
	{:else if name === 'search'}
		<path d="m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z"></path>
	{/if}
</svg>
