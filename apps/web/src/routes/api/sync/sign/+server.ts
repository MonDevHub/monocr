import { json, error } from '@sveltejs/kit';
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

// Cache S3 client instance to reuse connections
let s3ClientInstance: S3Client | null = null;
let lastUsedAccountId: string | null = null;

export const GET: RequestHandler = async ({ url, platform, request }) => {
	const fileName = url.searchParams.get('fileName');
	const fileType = url.searchParams.get('fileType');
	const recordId = url.searchParams.get('recordId');

	// 1. Basic Validation
	if (!fileName || !fileType || !recordId) {
		throw error(400, 'Missing required parameters');
	}

	// 2. Security: Validate recordId format (RFC 4122 UUID)
	const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
	if (!uuidRegex.test(recordId)) {
		throw error(400, 'Invalid record identifier format');
	}

	// 3. Security: Validate File Type
	const allowedTypes = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf', 'text/plain'];
	if (!allowedTypes.includes(fileType)) {
		throw error(400, 'Invalid file type prohibited for sync');
	}

	// 4. Security: Sanitize Filename (No path traversal, limit length)
	const sanitizedFileName = fileName
		.replace(/\.\./g, '') // remove ".."
		.replace(/[^a-zA-Z0-9.-]/g, '_')
		.slice(0, 100);

	// 5. Security: Strict Origin Check
	const origin = request.headers.get('origin');
	// In production, strictly match your TLD. Here we allow localhost for development.
	const isAllowedOrigin =
		origin === 'https://monocr.app' ||
		origin?.includes('localhost') ||
		origin?.includes('vercel.app') ||
		origin?.includes('pages.dev');

	if (origin && !isAllowedOrigin) {
		throw error(403, 'Unauthorized cross-origin request');
	}

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

	// 5. Performance: Connection Pooling / Client Reuse
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
			Metadata: {
				'record-id': recordId,
				'original-name': fileName
			}
		});

		// URL expires in 15 minutes (900 seconds) to support slow connections
		const signedUrl = await getSignedUrl(client, command, { expiresIn: 900 });

		return json({
			uploadUrl: signedUrl,
			key: key
		});
	} catch (err) {
		console.error('Error generating signed URL:', err);
		throw error(500, 'Failed to generate upload permission');
	}
};
