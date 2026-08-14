export async function onRequest(context) {
	// Pinned to a revision, not `main`. The four SDKs in monocr-onnx all pin; this
	// proxy and src/lib/config.ts were the only consumers tracking a moving branch,
	// which also made the `immutable` header below a promise this could not keep.
	// A revision here means a new model reaches users when someone changes this
	// line, not when someone pushes to the model repository.
	const MODEL_URL = 'https://huggingface.co/janakhpon/monocr/resolve/a51be11/onnx/monocr.onnx';

	try {
		// Fetch with redirect following (default behavior)
		const response = await fetch(MODEL_URL, {
			headers: {
				'User-Agent': 'MonOCR-Web-Proxy'
			},
			redirect: 'follow' // Explicitly follow redirects
		});

		if (!response.ok) {
			return new Response(`Failed to fetch model: ${response.status} ${response.statusText}`, {
				status: 502,
				headers: { 'Content-Type': 'text/plain' }
			});
		}

		// Stream the response body directly (don't buffer the 55MB file)
		return new Response(response.body, {
			status: 200,
			headers: {
				'Content-Type': 'application/octet-stream',
				'Access-Control-Allow-Origin': '*',
				'Cache-Control': 'public, max-age=31536000, immutable',
				'Content-Length': response.headers.get('Content-Length') || ''
			}
		});
	} catch (error) {
		return new Response(`Proxy error: ${error.message}`, {
			status: 500,
			headers: { 'Content-Type': 'text/plain' }
		});
	}
}
