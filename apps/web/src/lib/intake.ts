/**
 * What happens to a file the person picks or drops, and what an error means.
 *
 * Pure decisions, kept out of the page so they can be tested: the page had them
 * inline, and three of them were wrong (a file picked while the model loaded was
 * dropped without a word, a blank page was shown as an empty box, and every
 * error offered the same Retry, which reloaded the engine rather than re-reading
 * the file).
 */

export type EngineState = 'loading' | 'ready' | 'failed';

export type IntakeAction =
	/** Read it now. */
	| 'start'
	/** Hold it and read it when the engine is ready. */
	| 'queue'
	/** The engine did not load; there is nothing to read it with. */
	| 'block';

export function intakeAction(engine: EngineState): IntakeAction {
	if (engine === 'ready') return 'start';
	if (engine === 'loading') return 'queue';
	return 'block';
}

export type FileProblem = { kind: 'file_large'; maxMb: number } | { kind: 'file_type' };

export function checkFile(
	file: { size: number; type: string },
	maxMb: number,
	allowedTypes: readonly string[]
): FileProblem | null {
	if (file.size > maxMb * 1024 * 1024) return { kind: 'file_large', maxMb };
	if (!allowedTypes.includes(file.type)) return { kind: 'file_type' };
	return null;
}

export type AppError =
	| { kind: 'engine'; detail: string }
	| { kind: 'file_large'; maxMb: number }
	| { kind: 'file_type' }
	| { kind: 'pdf'; detail: string }
	| { kind: 'ocr'; detail: string };

/**
 * What Retry should do. A load failure retries the load. A read failure retries
 * the read of the same file. A rejected file has nothing to retry: choosing
 * another file is the way forward.
 */
export type RetryTarget = 'engine' | 'file' | null;

export function retryTarget(error: AppError): RetryTarget {
	switch (error.kind) {
		case 'engine':
			return 'engine';
		case 'pdf':
		case 'ocr':
			return 'file';
		default:
			return null;
	}
}

const SUMMARY_MAX = 140;

/**
 * A raw error message cut to something that fits in a sentence: its first
 * sentence, at most SUMMARY_MAX characters. `full` is what a Details
 * disclosure shows, and is null when the summary already says all of it.
 */
export function summariseDetail(raw: string): { summary: string; full: string | null } {
	const text = raw.replace(/\s+/g, ' ').trim();
	const firstSentence = /^.*?[.!?](?=\s|$)/.exec(text)?.[0] ?? text;
	let summary = firstSentence;
	if (summary.length > SUMMARY_MAX) summary = summary.slice(0, SUMMARY_MAX - 1).trimEnd() + '…';
	return { summary, full: summary === text ? null : text };
}

/** What the result area shows. */
export type ResultView = 'idle' | 'loading' | 'error' | 'empty' | 'text';

export function resultView(state: {
	loading: boolean;
	error: boolean;
	text: string | null;
}): ResultView {
	if (state.error) return 'error';
	if (state.loading) return 'loading';
	if (state.text === null) return 'idle';
	return state.text.trim() === '' ? 'empty' : 'text';
}

/** A read that found no text is shown as such; it is not worth a history row. */
export function worthSaving(text: string): boolean {
	return text.trim() !== '';
}

/** Whole percent for a determinate bar; 0 when the total is unknown. */
export function percent(done: number, total: number): number {
	if (!(total > 0)) return 0;
	return Math.max(0, Math.min(100, Math.round((done / total) * 100)));
}

/** Decimal megabytes, one place: the model is advertised as 46.2MB, not MiB. */
export function formatMegabytes(bytes: number): string {
	return (bytes / 1_000_000).toFixed(1);
}

/**
 * Which sentence the screen-reader status line speaks. The page maps each kind
 * to a translated message; nothing here is wording.
 */
export type StatusKind =
	| 'loading_model'
	| 'ready'
	| 'engine_failed'
	| 'scanning'
	| 'processing_page'
	| 'no_text'
	| 'done'
	| 'none';

export function statusKind(s: {
	engine: EngineState;
	reading: boolean;
	pdf: boolean;
	view: ResultView;
}): StatusKind {
	if (s.engine === 'failed') return 'engine_failed';
	if (s.reading) return s.pdf ? 'processing_page' : 'scanning';
	if (s.view === 'empty') return 'no_text';
	if (s.view === 'text') return 'done';
	if (s.engine === 'loading') return 'loading_model';
	return s.view === 'idle' ? 'ready' : 'none';
}
