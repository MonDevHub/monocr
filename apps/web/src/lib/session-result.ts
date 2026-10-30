/**
 * Keeps the result on screen across a reload.
 *
 * Switching language reloads the page (Paraglide's setLocale default), which
 * threw away the text the person was reading. The result is already saved to
 * history, so only its record id needs to survive: it is kept in sessionStorage,
 * which is per tab and gone when the tab closes, and the page loads the record
 * again on mount.
 */
export const RESULT_KEY = 'monocr.result';

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** Storage can throw (private windows, blocked site data); none of this may. */
export function rememberResult(store: Store | undefined, recordId: string | null): void {
	try {
		if (!store) return;
		if (recordId) store.setItem(RESULT_KEY, recordId);
		else store.removeItem(RESULT_KEY);
	} catch {
		// Losing the restore is the failure mode this was written to reduce, not a new one.
	}
}

export function recallResult(store: Store | undefined): string | null {
	try {
		return store?.getItem(RESULT_KEY) || null;
	} catch {
		return null;
	}
}
