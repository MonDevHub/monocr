/**
 * Put the interface language on <html lang>.
 *
 * It stayed "en" whatever language was chosen, and the layout set `lang` only on
 * the div below the header, so the header was read and shaped as English. The
 * `:lang(mnw)` and `:lang(my)` rules in app.css (the Myanmar-script UI font)
 * match on this attribute.
 */
export function applyDocumentLang(
	root: { lang: string } | undefined | null,
	lang: string
): boolean {
	if (!root || !lang) return false;
	if (root.lang !== lang) root.lang = lang;
	return true;
}
