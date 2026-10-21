import { json, error } from '@sveltejs/kit';
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { dev } from '$app/environment';
import { env } from '$env/dynamic/private';
import { CONFIG } from '$lib/config';
import type { RequestHandler } from './$types';

// Cache S3 client instance to reuse connections
let s3ClientInstance: S3Client | null = null;
let lastUsedAccountId: string | null = null;

/**
 * The only origin allowed in production. Exact match: the old check used
 * `includes('localhost')`, `includes('vercel.app')` and `includes('pages.dev')`,
 * so `https://localhost.attacker.example` passed.
 */
const PRODUCTION_ORIGIN = 'https://ocr.mondevhub.com';

/** Local development only, any port. Never allowed in a production build. */
const LOCAL_ORIGIN = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d{1,5})?$/;

function isAllowedOrigin(origin: string | null): boolean {
	if (!origin) return false;
	if (origin === PRODUCTION_ORIGIN) return true;
	return dev && LOCAL_ORIGIN.test(origin);
}

const ALLOWED_TYPES: readonly string[] = CONFIG.SYNC.ALLOWED_MIME_TYPES;
const MAX_BYTES = CONFIG.SYNC.MAX_UPLOAD_BYTES;

/**
 * Signs a 15-minute PUT URL into the dataset bucket.
 *
 * POST, not GET: a browser sends `Origin` on a same-origin POST but not on a
 * same-origin GET, and the check below refuses a request without one. A missing
 * Origin is what curl sends by default, so it is refused rather than waved
 * through. This is not authentication — a non-browser client can set any
 * Origin — so the endpoint also needs a rate limit at the edge (see the web
 * README, "Deploy").
 *
 * Body: `{ fileName, fileType, recordId, size }`, `size` in bytes. The size and
 * the type are signed into the URL, so the PUT has to carry exactly that
 * Content-Length and Content-Type.
 */
export const POST: RequestHandler = async ({ platform, request }) => {
	// 1. Security: Strict Origin Check, first, before anything else is read.
	if (!isAllowedOrigin(request.headers.get('origin'))) {
		throw error(403, 'Unauthorized cross-origin request');
	}

	let body: Record<string, unknown>;
	try {
		body = await request.json();
	} catch {
		throw error(400, 'Request body must be JSON');
	}
	const { fileName, fileType, recordId, size } = body ?? {};

	// 2. Basic Validation
	if (
		typeof fileName !== 'string' ||
		typeof fileType !== 'string' ||
		typeof recordId !== 'string' ||
		!fileName ||
		!fileType ||
		!recordId
	) {
		throw error(400, 'Missing required parameters');
	}

	// 3. Security: Validate recordId format (RFC 4122 UUID)
	const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
	if (!uuidRegex.test(recordId)) {
		throw error(400, 'Invalid record identifier format');
	}

	// 4. Security: Validate File Type
	if (!ALLOWED_TYPES.includes(fileType)) {
		throw error(400, 'Invalid file type prohibited for sync');
	}

	// 5. Security: Bound the size. Empty is refused as the feedback service
	// refuses it: a zero-byte object is a corpus row nothing can use.
	if (typeof size !== 'number' || !Number.isInteger(size) || size < 1) {
		throw error(400, 'Missing or invalid size');
	}
	if (size > MAX_BYTES) {
		throw error(413, `File exceeds the maximum upload size of ${MAX_BYTES / (1024 * 1024)} MB`);
	}

	// 6. Security: Sanitize Filename (No path traversal, limit length)
	const sanitizedFileName = fileName
		.replace(/\.\./g, '') // remove ".."
		.replace(/[^a-zA-Z0-9.-]/g, '_')
		.slice(0, 100);

	// Environment variables - Check platform.env (Cloudflare) or fallback to static env
	const platformEnv = (platform as { env?: Record<string, string> })?.env || {};

	const finalId = platformEnv.R2_ACCESS_KEY_ID || env.R2_ACCESS_KEY_ID;
	const finalSecret = platformEnv.R2_SECRET_ACCESS_KEY || env.R2_SECRET_ACCESS_KEY;
	const finalAccount = platformEnv.R2_ACCOUNT_ID || env.R2_ACCOUNT_ID;
	// No default bucket. A missing R2_BUCKET_NAME used to fall back to 'monocr',
	// while `wrangler.toml` binds `monocr-dataset` — so a deploy that lost the
	// variable would sign URLs for a DIFFERENT bucket and succeed, writing the
	// corpus somewhere nobody was reading. Failing here is the cheaper outcome.
	//
	// The `MONOCR_DATASET` r2_buckets binding in wrangler.toml is not what this
	// route uses; it signs with S3 credentials instead, so the bucket has to be
	// named explicitly.
	const finalBucket = platformEnv.R2_BUCKET_NAME || env.R2_BUCKET_NAME;

	// `finalBucket` is in this check because it no longer has a default. Without it
	// an undefined bucket reaches PutObjectCommand, and the SDK's error names the
	// signature rather than the missing variable.
	if (!finalId || !finalSecret || !finalAccount || !finalBucket) {
		console.error('R2 configuration missing in environment');
		throw error(500, 'Cloud storage not configured');
	}

	// 7. Performance: Connection Pooling / Client Reuse
	if (!s3ClientInstance || lastUsedAccountId !== finalAccount) {
		s3ClientInstance = new S3Client({
			region: 'auto',
			endpoint: `https://${finalAccount}.r2.cloudflarestorage.com`,
			credentials: {
				accessKeyId: finalId,
				secretAccessKey: finalSecret
			}
		});
		lastUsedAccountId = finalAccount;
	}

	const client = s3ClientInstance;
	if (!client) throw error(500, 'Failed to initialize storage client');

	try {
		// `contribution/`, singular, matching the two other clients and the written
		// contract.
		//
		// This was `contributions/` and nothing else in the system used that prefix.
		// Android and iOS upload through the Go service, which writes
		// `contribution/YYYY-MM/` (`services/feedback/internal/upload/handler.go`,
		// `UploadContribution`), and `shared/contract/README.md` documents the same
		// singular form in its example response. So web was one client writing to a
		// prefix of its own, and anything assembling the corpus by prefix silently
		// saw two of three platforms.
		//
		// Web contributions uploaded BEFORE 2026-08-28 are still under
		// `contributions/`. A reader covering the full history has to take both; a
		// reader of new data only needs this one. That split is the cost of the fix
		// and it is cheaper than leaving the prefixes diverged.
		const dateStr = new Date().toISOString().slice(0, 7); // YYYY-MM
		const key = `contribution/${dateStr}/${recordId}-${sanitizedFileName}`;

		const command = new PutObjectCommand({
			Bucket: finalBucket,
			Key: key,
			ContentType: fileType,
			// Signed as `content-length`, so the URL accepts this many bytes and no
			// other count. Without it the URL took any body up to the store's limit.
			ContentLength: size,
			Metadata: {
				'record-id': recordId,
				'original-name': fileName
			}
		});

		// URL expires in 15 minutes (900 seconds) to support slow connections.
		//
		// `signableHeaders`: the presigner adds `content-type` to its unsignable
		// set by default, so the type checked above would otherwise be advisory
		// and the PUT could carry any Content-Type. Naming it here signs it.
		const signedUrl = await getSignedUrl(client, command, {
			expiresIn: 900,
			signableHeaders: new Set(['content-type'])
		});

		return json({
			uploadUrl: signedUrl,
			key: key
		});
	} catch (err) {
		console.error('Error generating signed URL:', err);
		throw error(500, 'Failed to generate upload permission');
	}
};
