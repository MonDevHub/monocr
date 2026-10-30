/**
 * A token that says whether a read is still the one the person is waiting for.
 *
 * Every read is async and takes seconds (a PDF takes minutes). Clearing the
 * file, or choosing another, does not stop the old read by itself: it keeps
 * going and, when it finishes, writes its text under the new file's preview and
 * saves its history record under the new file's name. Each read takes a token
 * when it starts and checks it before every write; `cancel()` and `begin()`
 * both invalidate every token handed out before.
 */
export interface RunToken {
	/** Start a new run. Everything started earlier is now stale. */
	begin(): number;
	/** Invalidate the current run without starting another (Clear, Cancel). */
	cancel(): void;
	/** True only for the run most recently begun and not cancelled since. */
	isCurrent(run: number): boolean;
	/** Run `write` only if `run` is still current. Returns whether it ran. */
	commit(run: number, write: () => void): boolean;
}

export function createRunToken(): RunToken {
	let current = 0;
	return {
		begin: () => ++current,
		cancel: () => {
			current++;
		},
		isCurrent: (run) => run === current,
		commit(run, write) {
			if (run !== current) return false;
			write();
			return true;
		}
	};
}
