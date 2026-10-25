import { afterEach, describe, expect, it, vi } from 'vitest';
import type { OCRRecord } from '$lib/storage/db';

// `db` is null outside a browser, which would skip the write that marks a record
// synced. A stub that records the write lets the tests assert on it.
const update = vi.hoisted(() => vi.fn());
vi.mock('$lib/storage/db', () => ({ db: { records: { update } } }));

import { SyncService } from './sync-service';
import { CONFIG } from '$lib/config';

/**
 * The client half of `/api/sync/sign`. The endpoint refuses a request without an
 * Origin header, and a browser sends one on a same-origin POST but not on a
 * same-origin GET, so the method is part of the contract. So is `size`: it is
 * signed into the URL, and the PUT has to carry exactly that many bytes.
 */

const UUID = '550e8400-e29b-41d4-a716-446655440000';

type Upload = (name: string, type: string, id: string, data: Blob) => Promise<void>;
const uploadToR2 = (s: SyncService): Upload =>
	(s as unknown as { uploadToR2: Upload }).uploadToR2.bind(s);

afterEach(() => {
	vi.unstubAllGlobals();
	update.mockReset();
});

describe('signing an upload', () => {
	it('asks for the URL with a POST that declares the size, then PUTs the same bytes', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValueOnce(
				new Response(JSON.stringify({ uploadUrl: 'https://r2.example/put', key: 'k' }))
			)
			.mockResolvedValueOnce(new Response(null, { status: 200 }));
		vi.stubGlobal('fetch', fetchMock);

		const data = new Blob(['hello'], { type: 'text/plain' });
		await uploadToR2(new SyncService())('a.txt', 'text/plain', UUID, data);

		const [signUrl, signInit] = fetchMock.mock.calls[0];
		expect(signUrl).toBe('/api/sync/sign');
		expect(signInit.method).toBe('POST');
		expect(JSON.parse(signInit.body)).toEqual({
			fileName: 'a.txt',
			fileType: 'text/plain',
			recordId: UUID,
			size: 5
		});

		const [putUrl, putInit] = fetchMock.mock.calls[1];
		expect(putUrl).toBe('https://r2.example/put');
		expect(putInit.method).toBe('PUT');
		expect(putInit.body).toBe(data);
		expect(putInit.headers['Content-Type']).toBe('text/plain');
	});

	it('does not ask to sign a file over the upload limit', async () => {
		const fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);

		const tooBig = { size: CONFIG.SYNC.MAX_UPLOAD_BYTES + 1 } as Blob;
		await expect(
			uploadToR2(new SyncService())('big.png', 'image/png', UUID, tooBig)
		).rejects.toThrow(/upload limit/);
		expect(fetchMock).not.toHaveBeenCalled();
	});
});

type SyncRecord = (r: OCRRecord) => Promise<void>;
const syncRecord = (s: SyncService): SyncRecord =>
	(s as unknown as { syncRecord: SyncRecord }).syncRecord.bind(s);

const record = (over: Partial<OCRRecord>): OCRRecord => ({
	id: UUID,
	timestamp: 0,
	category: 'feedback',
	fileName: 'feedback-123.txt',
	fileType: 'text/plain',
	fileData: new Blob([], { type: 'text/plain' }),
	text: '[ocr-error] corrected line',
	processingTime: 0,
	isSynced: false,
	consentGiven: true,
	syncError: null,
	syncAttempts: 0,
	...over
});

/** Answers every sign request with a URL and every PUT with 200. */
function okFetch() {
	const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(async (url) =>
		url === '/api/sync/sign'
			? new Response(JSON.stringify({ uploadUrl: 'https://r2.example/put', key: 'k' }))
			: new Response(null, { status: 200 })
	);
	vi.stubGlobal('fetch', fetchMock);
	return fetchMock;
}

const signedNames = (fetchMock: ReturnType<typeof okFetch>) =>
	fetchMock.mock.calls
		.filter(([url]) => url === '/api/sync/sign')
		.map(([, init]) => JSON.parse(init!.body as string).fileName);

describe('syncing a record', () => {
	/**
	 * /report opened with no prior scan saves an empty primary Blob next to the
	 * typed correction. The endpoint refuses size 0, so signing the primary would
	 * fail every attempt and the correction would never be sent.
	 */
	it('skips an empty primary file and still uploads the transcription', async () => {
		const fetchMock = okFetch();

		await syncRecord(new SyncService())(record({}));

		expect(signedNames(fetchMock)).toEqual(['feedback-123-transcription.txt']);
		const puts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT');
		expect(puts).toHaveLength(1);
		expect(await (puts[0][1]!.body as Blob).text()).toBe('[ocr-error] corrected line');
		expect(update).toHaveBeenCalledWith(UUID, {
			isSynced: true,
			syncError: null,
			syncAttempts: 1
		});
	});

	it('uploads a non-empty primary and then its transcription, as before', async () => {
		const fetchMock = okFetch();

		await syncRecord(new SyncService())(
			record({
				fileName: 'page.png',
				fileType: 'image/png',
				fileData: new Blob(['png-bytes'], { type: 'image/png' })
			})
		);

		expect(signedNames(fetchMock)).toEqual(['page.png', 'page-transcription.txt']);
		expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(2);
		expect(update).toHaveBeenCalledWith(UUID, expect.objectContaining({ isSynced: true }));
	});

	it('marks a record with nothing to send as failed, not synced, without a request', async () => {
		const fetchMock = okFetch();

		await syncRecord(new SyncService())(record({ text: '   ' }));

		expect(fetchMock).not.toHaveBeenCalled();
		expect(update).toHaveBeenCalledWith(UUID, {
			syncError: expect.stringMatching(/Nothing to upload/),
			syncAttempts: 1
		});
		expect(update).not.toHaveBeenCalledWith(UUID, expect.objectContaining({ isSynced: true }));
	});
});
