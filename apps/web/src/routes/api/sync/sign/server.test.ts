import { afterEach, describe, expect, it, vi } from 'vitest';

// The endpoint reads `platform.env` first and `$env/dynamic/private` second. Mock
// the latter to empty so these tests control the whole configuration through
// `platform.env`; without it, a real R2_BUCKET_NAME in the ambient environment
// satisfies the fallback and the "not configured" case cannot be reached.
vi.mock('$env/dynamic/private', () => ({ env: {} }));

// `dev` decides whether localhost is an allowed origin. A getter, so each test
// can flip it; production (`false`) is the default.
const mode = vi.hoisted(() => ({ dev: false }));
vi.mock('$app/environment', () => ({
	get dev() {
		return mode.dev;
	}
}));

import { POST } from './+server';

/**
 * Tests for the signing endpoint, the one route in this app that hands out a
 * write capability into the dataset bucket. They cover the parts a client can
 * steer: the origin, the object key, the type, the size, and the configuration
 * guard.
 *
 * `getSignedUrl` is real here. Signing is HMAC over the request, so it needs
 * credentials but no network, and fake ones sign fine.
 */

const CREDS = {
	R2_ACCESS_KEY_ID: 'test-key-id',
	R2_SECRET_ACCESS_KEY: 'test-secret',
	R2_ACCOUNT_ID: 'test-account',
	R2_BUCKET_NAME: 'monocr-dataset'
};

const UUID = '550e8400-e29b-41d4-a716-446655440000';
const SITE = 'https://ocr.mondevhub.com';
const MiB = 1024 * 1024;

const OK = { fileName: 'page.png', fileType: 'image/png', recordId: UUID, size: 1234 };

async function call(
	body: Record<string, unknown>,
	opts: { env?: Record<string, string>; origin?: string | null } = {}
) {
	const url = new URL(`${SITE}/api/sync/sign`);
	const headers = new Headers({ 'content-type': 'application/json' });
	// `undefined` means the default, the production origin; `null` means none.
	const origin = opts.origin === undefined ? SITE : opts.origin;
	if (origin !== null) headers.set('origin', origin);
	return POST({
		url,
		platform: { env: opts.env ?? CREDS },
		request: new Request(url, { method: 'POST', headers, body: JSON.stringify(body) })
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
	} as any);
}

async function signedUrl(body: Record<string, unknown> = OK): Promise<URL> {
	const res = await call(body);
	return new URL((await res.json()).uploadUrl);
}

afterEach(() => {
	mode.dev = false;
});

describe('the signed object key', () => {
	it('is contribution/ singular, matching the service and the contract', async () => {
		const res = await call(OK);
		const body = await res.json();
		const month = new Date().toISOString().slice(0, 7);

		expect(body.key).toBe(`contribution/${month}/${UUID}-page.png`);
		// The plural form is what this wrote until 2026-08-28, and it is what made
		// the corpus see two of three platforms.
		expect(body.key.startsWith('contributions/')).toBe(false);
	});

	it('cannot be steered out of its prefix by the filename', async () => {
		const res = await call({ ...OK, fileName: '../../../../etc/passwd' });
		const { key } = await res.json();

		expect(key).not.toContain('..');
		// Two separators are the prefix's own; a third would mean the client chose
		// part of the path.
		expect(key.split('/').length - 1).toBe(2);
	});

	it('signs a URL for the configured bucket', async () => {
		expect((await signedUrl()).href).toContain('monocr-dataset');
	});
});

describe('the signature binds the declared size and type', () => {
	/**
	 * A presigned PUT re-derives its signature from the headers named here. With
	 * `content-length` in the set, a body of any other length fails the signature
	 * at the store; with `content-type`, so does any other type. The presigner
	 * leaves `content-type` out unless asked, so both are asserted.
	 */
	it('signs content-length and content-type', async () => {
		const signed = (await signedUrl()).searchParams.get('X-Amz-SignedHeaders')?.split(';');
		expect(signed).toEqual(expect.arrayContaining(['content-length', 'content-type', 'host']));
	});

	it('signs the size the client declared, not some other size', async () => {
		// Same request, two sizes: if the size were not in the signature, the two
		// signatures would match.
		const a = (await signedUrl({ ...OK, size: 1000 })).searchParams.get('X-Amz-Signature');
		const b = (await signedUrl({ ...OK, size: 1001 })).searchParams.get('X-Amz-Signature');
		expect(a).not.toBe(b);
	});
});

describe('validation', () => {
	it('refuses a record id that is not a UUID', async () => {
		await expect(call({ ...OK, recordId: 'not-a-uuid' })).rejects.toMatchObject({ status: 400 });
	});

	it('refuses a file type outside the allowlist', async () => {
		for (const fileType of ['application/x-msdownload', 'image/svg+xml', 'text/html']) {
			await expect(call({ ...OK, fileName: 'a.bin', fileType })).rejects.toMatchObject({
				status: 400,
				body: { message: 'Invalid file type prohibited for sync' }
			});
		}
	});

	it('accepts each type the web client uploads', async () => {
		for (const fileType of [
			'image/jpeg',
			'image/png',
			'image/webp',
			'application/pdf',
			'text/plain'
		]) {
			const res = await call({ ...OK, fileType });
			expect(res.status).toBe(200);
		}
	});

	it('refuses a missing parameter', async () => {
		const withoutRecordId: Record<string, unknown> = { ...OK };
		delete withoutRecordId.recordId;
		await expect(call(withoutRecordId)).rejects.toMatchObject({ status: 400 });
	});

	it('refuses a body that is not JSON', async () => {
		const url = new URL(`${SITE}/api/sync/sign`);
		const request = new Request(url, {
			method: 'POST',
			headers: { origin: SITE },
			body: 'fileName=a.png'
		});
		await expect(
			// eslint-disable-next-line @typescript-eslint/no-explicit-any
			POST({ url, platform: { env: CREDS }, request } as any)
		).rejects.toMatchObject({ status: 400 });
	});
});

describe('the size bound', () => {
	it('refuses an upload over 20 MiB, the feedback service limit', async () => {
		await expect(call({ ...OK, size: 20 * MiB + 1 })).rejects.toMatchObject({ status: 413 });
	});

	it('accepts exactly 20 MiB', async () => {
		const res = await call({ ...OK, size: 20 * MiB });
		expect(res.status).toBe(200);
	});

	it('refuses a missing, empty, fractional or non-numeric size', async () => {
		const withoutSize: Record<string, unknown> = { ...OK };
		delete withoutSize.size;
		for (const body of [
			withoutSize,
			{ ...OK, size: 0 },
			{ ...OK, size: -1 },
			{ ...OK, size: 1.5 },
			{ ...OK, size: '1234' }
		]) {
			await expect(call(body)).rejects.toMatchObject({
				status: 400,
				body: { message: 'Missing or invalid size' }
			});
		}
	});
});

describe('configuration', () => {
	/**
	 * The bucket name has no default any more. It fell back to 'monocr' while
	 * `wrangler.toml` binds `monocr-dataset`, so a deploy that lost the variable
	 * would sign URLs for a different bucket and succeed.
	 */
	it('refuses to sign when the bucket is not configured', async () => {
		const withoutBucket: Record<string, string> = { ...CREDS };
		delete withoutBucket.R2_BUCKET_NAME;
		// The MESSAGE, not just the status. Both 500 paths in this route return 500,
		// and without asserting which one fired this test passed with the guard
		// removed: the SDK failed on `Bucket: undefined` and the outer catch turned
		// that into a 500 as well. Right outcome, wrong reason.
		await expect(call(OK, { env: withoutBucket })).rejects.toMatchObject({
			status: 500,
			body: { message: 'Cloud storage not configured' }
		});
	});
});

describe('the origin check', () => {
	it('allows the production site', async () => {
		const res = await call(OK, { origin: SITE });
		expect(res.status).toBe(200);
	});

	/**
	 * This used to pass: the check was `if (origin && !isAllowedOrigin)`, so no
	 * Origin header at all, which is curl's default, skipped it. The client now
	 * signs with a POST, which a browser always sends Origin on.
	 */
	it('refuses a request with no Origin header', async () => {
		await expect(call(OK, { origin: null })).rejects.toMatchObject({ status: 403 });
	});

	it('refuses a disallowed origin', async () => {
		await expect(call(OK, { origin: 'https://attacker.example' })).rejects.toMatchObject({
			status: 403
		});
	});

	/**
	 * The allowlist used `includes('localhost' | 'vercel.app' | 'pages.dev')`, so
	 * every one of these passed. It is an exact match now.
	 */
	it('refuses look-alikes of the allowed hosts', async () => {
		for (const origin of [
			'https://evil-pages.dev',
			'https://monocr.pages.dev',
			'https://evil-vercel.app',
			'https://localhost.attacker.com',
			'https://ocr.mondevhub.com.attacker.com',
			'http://ocr.mondevhub.com',
			'https://monocr.app'
		]) {
			await expect(call(OK, { origin })).rejects.toMatchObject({ status: 403 });
		}
	});

	it('refuses localhost in production', async () => {
		for (const origin of ['http://localhost:5173', 'http://127.0.0.1:4173']) {
			await expect(call(OK, { origin })).rejects.toMatchObject({ status: 403 });
		}
	});

	it('allows localhost and 127.0.0.1 on any port in development', async () => {
		mode.dev = true;
		for (const origin of ['http://localhost:5173', 'http://127.0.0.1:4173', 'http://localhost']) {
			const res = await call(OK, { origin });
			expect(res.status).toBe(200);
		}
	});

	it('still refuses a localhost look-alike in development', async () => {
		mode.dev = true;
		await expect(call(OK, { origin: 'http://localhost.attacker.com:5173' })).rejects.toMatchObject({
			status: 403
		});
	});

	it('checks the origin before it reads the body', async () => {
		await expect(
			call({ fileName: 'a', fileType: 'nope', recordId: 'x' }, { origin: null })
		).rejects.toMatchObject({ status: 403 });
	});
});
