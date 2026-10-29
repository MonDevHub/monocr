import { describe, expect, it } from 'vitest';
import { recallResult, rememberResult, RESULT_KEY } from './session-result';

function fakeStore(initial: Record<string, string> = {}) {
	const data = { ...initial };
	return {
		data,
		getItem: (k: string) => data[k] ?? null,
		setItem: (k: string, v: string) => void (data[k] = v),
		removeItem: (k: string) => void delete data[k]
	};
}

describe('session result', () => {
	it('survives a reload: what was remembered is recalled', () => {
		const store = fakeStore();
		rememberResult(store, 'rec-1');
		expect(recallResult(store)).toBe('rec-1');
	});

	it('forgets on Clear, so the next load starts empty', () => {
		const store = fakeStore({ [RESULT_KEY]: 'rec-1' });
		rememberResult(store, null);
		expect(recallResult(store)).toBeNull();
	});

	it('does not throw when storage is blocked or missing', () => {
		const blocked = {
			getItem: () => {
				throw new Error('denied');
			},
			setItem: () => {
				throw new Error('denied');
			},
			removeItem: () => {
				throw new Error('denied');
			}
		};
		expect(() => rememberResult(blocked, 'x')).not.toThrow();
		expect(recallResult(blocked)).toBeNull();
		expect(recallResult(undefined)).toBeNull();
	});
});
