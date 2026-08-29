import { describe, expect, it, vi } from 'vitest';

// The endpoint reads `platform.env` first and `$env/dynamic/private` second. Mock
// the latter to empty so these tests control the whole configuration through
// `platform.env`; without it, a real R2_BUCKET_NAME in the ambient environment
// satisfies the fallback and the "not configured" case cannot be reached.
vi.mock('$env/dynamic/private', () => ({ env: {} }));

import { GET } from './+server';

/**
 * First tests for the signing endpoint.
 *
 * It had none, and it is the one route in this app that hands out a write
 * capability into the dataset bucket. What follows covers the parts a client can
 * steer — the object key, the validation, and the configuration guard — and pins
 * the origin check's current behaviour including the hole in it, so the hole is
 * visible in a test rather than only in a comment.
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

async function call(
	params: Record<string, string>,
	opts: { env?: Record<string, string>; origin?: string } = {}
) {
	const url = new URL('https://monocr.app/api/sync/sign');
	for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
	const headers = new Headers();
	if (opts.origin) headers.set('origin', opts.origin);
	return GET({
		url,
		platform: { env: opts.env ?? CREDS },
		request: new Request(url, { headers })
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
	} as any);
}

describe('the signed object key', () => {
	it('is contribution/ singular, matching the service and the contract', async () => {
		const res = await call({ fileName: 'page.png', fileType: 'image/png', recordId: UUID });
		const body = await res.json();
		const month = new Date().toISOString().slice(0, 7);

		expect(body.key).toBe(`contribution/${month}/${UUID}-page.png`);
		// The plural form is what this wrote until 2026-08-28, and it is what made
		// the corpus see two of three platforms.
		expect(body.key.startsWith('contributions/')).toBe(false);
	});

	it('cannot be steered out of its prefix by the filename', async () => {
		const res = await call({
			fileName: '../../../../etc/passwd',
			fileType: 'image/png',
			recordId: UUID
		});
		const { key } = await res.json();

		expect(key).not.toContain('..');
		// Two separators are the prefix's own; a third would mean the client chose
		// part of the path.
		expect(key.split('/').length - 1).toBe(2);
	});

	it('signs a URL for the configured bucket', async () => {
		const res = await call({ fileName: 'a.png', fileType: 'image/png', recordId: UUID });
		const { uploadUrl } = await res.json();
		expect(uploadUrl).toContain('monocr-dataset');
	});
});

describe('validation', () => {
	it('refuses a record id that is not a UUID', async () => {
		await expect(
			call({ fileName: 'a.png', fileType: 'image/png', recordId: 'not-a-uuid' })
		).rejects.toMatchObject({ status: 400 });
	});

	it('refuses a file type outside the allowlist', async () => {
		await expect(
			call({ fileName: 'a.exe', fileType: 'application/x-msdownload', recordId: UUID })
		).rejects.toMatchObject({ status: 400 });
	});

	it('refuses a missing parameter', async () => {
		await expect(call({ fileName: 'a.png', fileType: 'image/png' })).rejects.toMatchObject({
			status: 400
		});
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
		await expect(
			call({ fileName: 'a.png', fileType: 'image/png', recordId: UUID }, { env: withoutBucket })
		).rejects.toMatchObject({ status: 500, body: { message: 'Cloud storage not configured' } });
	});
});

describe('the origin check, including what it does not do', () => {
	it('rejects a disallowed origin', async () => {
		await expect(
			call(
				{ fileName: 'a.png', fileType: 'image/png', recordId: UUID },
				{ origin: 'https://attacker.example' }
			)
		).rejects.toMatchObject({ status: 403 });
	});

	/**
	 * PINS A KNOWN HOLE, deliberately.
	 *
	 * The check is `if (origin && !isAllowedOrigin)`, so a request with NO Origin
	 * header passes — which is curl's default, and there is no authentication
	 * behind it. This is not tightened here because browsers omit Origin on
	 * same-origin GETs, so requiring it would break the web app; closing it needs
	 * real authentication, which is a design decision rather than a patch.
	 *
	 * The test exists so the hole is a visible, failing-on-change fact. When auth
	 * arrives, this test should start failing and be replaced.
	 */
	it('currently allows a request with no Origin header at all', async () => {
		const res = await call({ fileName: 'a.png', fileType: 'image/png', recordId: UUID });
		expect(res.status).toBe(200);
	});

	/**
	 * The allowlist uses `includes`, so these attacker-controlled hosts pass.
	 * Pinned for the same reason as above: visible rather than silent.
	 */
	it('currently allows substring matches on the allowlisted hosts', async () => {
		for (const origin of [
			'https://localhost.attacker.example',
			'https://evil-vercel.app.attacker.example',
			'https://pages.dev.attacker.example'
		]) {
			const res = await call(
				{ fileName: 'a.png', fileType: 'image/png', recordId: UUID },
				{ origin }
			);
			expect(res.status).toBe(200);
		}
	});
});
