import { describe, expect, it } from 'vitest';
import { applyDocumentLang } from './doc-lang';

describe('applyDocumentLang', () => {
	it('sets lang on the root element', () => {
		const root = { lang: 'en' };
		expect(applyDocumentLang(root, 'mnw')).toBe(true);
		expect(root.lang).toBe('mnw');
	});
	it('ignores a missing root or an empty language', () => {
		expect(applyDocumentLang(undefined, 'my')).toBe(false);
		const root = { lang: 'en' };
		expect(applyDocumentLang(root, '')).toBe(false);
		expect(root.lang).toBe('en');
	});
});
