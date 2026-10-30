import type { IconName } from './components/Icon.svelte';

/** One header navigation entry. Write `label` in sentence case; CSS sets the capitals. */
export interface NavItem {
	href: string;
	label: string;
}

/** One footer icon link. */
export interface FooterLink {
	href: string;
	/** Accessible name, written out: "Email", "Source on GitHub". */
	label: string;
	icon: IconName;
}

/** A text link in the footer of a documentation or product site. */
export interface FooterTextLink {
	href: string;
	/** The link text, which is its accessible name: "Source", "Changelog". */
	label: string;
}

/** One entry in a Timeline. */
export interface TimelineEntry {
	/** "Sep 2023 – Present". Rendered as type-meta, never wrapped. */
	period: string;
	/** Role or degree. */
	title: string;
	/** Organisation. Linked when `orgHref` is set. */
	org?: string;
	orgHref?: string;
	location?: string;
	/** One paragraph under the title. */
	intro?: string;
	/** Hanging-dot list under the paragraph. */
	bullets?: string[];
	/** An in-site link to more detail. */
	detailsHref?: string;
	detailsLabel?: string;
}

/** One link under a ProjectEntry. */
export interface EntryLink {
	href: string;
	label: string;
}
