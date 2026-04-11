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
	const finalBucket = platformEnv.R2_BUCKET_NAME || env.R2_BUCKET_NAME || 'monocr';

	if (!finalId || !finalSecret || !finalAccount) {
		console.error('R2 Credentials missing in environment');
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
		// Generate an object key: contributions/YYYY-MM/recordId-fileName
		const dateStr = new Date().toISOString().slice(0, 7); // YYYY-MM
		const key = `contributions/${dateStr}/${recordId}-${sanitizedFileName}`;

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
