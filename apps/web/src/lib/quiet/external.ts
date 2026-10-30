/** True for an absolute http(s) URL: another site, opened in a new tab. */
export function isExternal(href: string | null | undefined): boolean {
	return typeof href === 'string' && /^https?:\/\//.test(href);
}
