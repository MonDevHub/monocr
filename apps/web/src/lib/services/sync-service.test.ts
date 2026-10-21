import { afterEach, describe, expect, it, vi } from 'vitest';
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
