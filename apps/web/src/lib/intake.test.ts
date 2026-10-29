import { describe, expect, it } from 'vitest';
import {
	checkFile,
	formatMegabytes,
	intakeAction,
	percent,
	resultView,
	retryTarget,
	statusKind,
	summariseDetail,
	worthSaving
} from './intake';

const TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

describe('intakeAction', () => {
	it('queues a file picked while the model is still loading instead of dropping it', () => {
		expect(intakeAction('loading')).toBe('queue');
	});
	it('starts at once when ready and blocks when the engine failed', () => {
		expect(intakeAction('ready')).toBe('start');
		expect(intakeAction('failed')).toBe('block');
	});
});

describe('checkFile', () => {
	it('rejects an oversized file with the limit, and a wrong type', () => {
		expect(checkFile({ size: 51 * 1024 * 1024, type: 'image/png' }, 50, TYPES)).toEqual({
			kind: 'file_large',
			maxMb: 50
		});
		expect(checkFile({ size: 10, type: 'text/plain' }, 50, TYPES)).toEqual({ kind: 'file_type' });
		expect(checkFile({ size: 10, type: 'image/png' }, 50, TYPES)).toBeNull();
	});
});

describe('retryTarget', () => {
	it('retries the file for a read failure, the engine for a load failure, nothing otherwise', () => {
		expect(retryTarget({ kind: 'ocr', detail: 'x' })).toBe('file');
		expect(retryTarget({ kind: 'pdf', detail: 'x' })).toBe('file');
		expect(retryTarget({ kind: 'engine', detail: 'x' })).toBe('engine');
		expect(retryTarget({ kind: 'file_large', maxMb: 50 })).toBeNull();
		expect(retryTarget({ kind: 'file_type' })).toBeNull();
	});
});

describe('summariseDetail', () => {
	it('keeps the first sentence as the summary and the rest for Details', () => {
		const out = summariseDetail('The source image could not be decoded. Cause: bad header.');
		expect(out.summary).toBe('The source image could not be decoded.');
		expect(out.full).toBe('The source image could not be decoded. Cause: bad header.');
	});
	it('offers no Details when the summary says it all, and truncates a long one', () => {
		expect(summariseDetail('Short.').full).toBeNull();
		const long = summariseDetail('x'.repeat(400));
		expect(long.summary.length).toBeLessThanOrEqual(140);
		expect(long.full).not.toBeNull();
	});
});

describe('resultView', () => {
	it('shows an empty result as empty, not as a blank box', () => {
		expect(resultView({ loading: false, error: false, text: '' })).toBe('empty');
		expect(resultView({ loading: false, error: false, text: '  \n' })).toBe('empty');
	});
	it('orders error, loading, idle and text', () => {
		expect(resultView({ loading: true, error: true, text: null })).toBe('error');
		expect(resultView({ loading: true, error: false, text: '' })).toBe('loading');
		expect(resultView({ loading: false, error: false, text: null })).toBe('idle');
		expect(resultView({ loading: false, error: false, text: 'မန်' })).toBe('text');
	});
});

describe('worthSaving and percent', () => {
	it('does not save a blank read to history', () => {
		expect(worthSaving('')).toBe(false);
		expect(worthSaving(' \n')).toBe(false);
		expect(worthSaving('a')).toBe(true);
	});
	it('computes a bounded percentage and treats an unknown total as zero', () => {
		expect(percent(3, 14)).toBe(21);
		expect(percent(20, 14)).toBe(100);
		expect(percent(1, 0)).toBe(0);
	});
});

describe('formatMegabytes and statusKind', () => {
	it('reports decimal megabytes so the readout matches the advertised model size', () => {
		expect(formatMegabytes(46_200_000)).toBe('46.2');
	});
	it('speaks one status at a time, in priority order', () => {
		const base = { engine: 'ready', reading: false, pdf: false, view: 'idle' } as const;
		expect(statusKind({ ...base, engine: 'loading' })).toBe('loading_model');
		expect(statusKind({ ...base, engine: 'failed' })).toBe('engine_failed');
		expect(statusKind({ ...base, reading: true })).toBe('scanning');
		expect(statusKind({ ...base, reading: true, pdf: true })).toBe('processing_page');
		expect(statusKind({ ...base, view: 'empty' })).toBe('no_text');
		expect(statusKind({ ...base, view: 'text' })).toBe('done');
		expect(statusKind(base)).toBe('ready');
	});
});
