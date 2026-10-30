<script lang="ts">
	import { m } from '$lib/paraglide/messages';
	import { BulletList, DescriptionList, MetaLine, PageLayout, Prose, Section } from '$lib/quiet';

	// Same __APP_VERSION__ vite `define` Header.svelte already uses -- injected from
	// package.json at build time, so it can't drift the way this page's own hardcoded
	// "Version 0.2.0" string had (real version: 0.4.0, two minors stale).
	const APP_VERSION = __APP_VERSION__;

	const shortVersion = [
		'Recognition runs on your own device, and your images are never uploaded for processing.',
		'Nothing is shared unless you choose to share a correction or a contribution.',
		'There is no telemetry, no analytics and no tracking.',
		'Local history can be cleared at any time.'
	];

	const permissions = [
		{
			term: 'Camera access',
			detail:
				'Required to capture photos of physical documents or text for live scanning. Images captured via the camera are processed strictly locally on your device and are never uploaded to our servers unless explicitly submitted by you as a Voluntary Contribution.'
		},
		{
			term: 'Photo library and file access',
			detail:
				'Required to allow you to select existing images or PDF documents from your device for text extraction. We only access the specific files you select.'
		}
	];
</script>

<svelte:head>
	<title>Privacy Policy - MonOCR</title>
	<meta name="description" content="MonOCR Privacy - 100% Local, 100% Private" />
</svelte:head>

<PageLayout
	title={m.nav_privacy()}
	intro="MonOCR is built on a foundation of local-first privacy. We reject cloud-based surveillance and ensure your data remains under your absolute control."
	class="max-w-column"
>
	<div class="mt-4">
		<MetaLine items={['Effective date: March 22, 2026', `MonOCR version ${APP_VERSION}`]} />
	</div>

	<Section title="The short version">
		<BulletList items={shortVersion} />
	</Section>

	<Section title="Local processing">
		<Prose>
			<p>
				All OCR recognition runs entirely on your local hardware via WASM or WebGPU. Your images are
				processed in the browser's memory and are never uploaded to our servers for processing.
			</p>
		</Prose>
	</Section>

	<Section title="Device permissions and data access">
		<Prose>
			<p>
				To provide core OCR functionality, our applications (iOS and Android) may request the
				following device permissions:
			</p>
		</Prose>
		<DescriptionList items={permissions} />
	</Section>

	<Section title="Zero default collection">
		<Prose>
			<p>
				We do not use telemetry, analytics, or trackers. We do not know who you are, and we do not
				track how you use the application.
			</p>
		</Prose>
	</Section>

	<Section title="Voluntary contributions">
		<Prose>
			<p>
				You may choose to share corrected text (Feedback) or new documents (Contributions) to help
				improve the Mon language model. This is strictly opt-in and handled anonymously via secure
				synchronization.
			</p>
		</Prose>
	</Section>

	<Section title="Data retention">
		<Prose>
			<p>
				Local history is stored on your device and can be cleared at any time. Anonymized
				contributions shared with our dataset are retained for model training and cannot be
				individually identified or removed once pooled.
			</p>
		</Prose>
	</Section>

	<Section title="Dataset framework">
		<Prose>
			<p>
				Contributions are synchronized anonymously via pre-signed, short-lived URLs directly to our
				secure Cloudflare R2 storage. No personal identifiers (IPs, names, accounts) are associated
				with these uploads.
			</p>
			<p>
				Consent is managed per-contribution. Revoking consent stops all future synchronizations but
				does not affect data already contributed to the anonymized research pool.
			</p>
		</Prose>
	</Section>

	<Section title="Global compliance">
		<Prose>
			<p>
				Our architecture aligns with the General Data Protection Regulation (GDPR) and the
				California Consumer Privacy Act (CCPA) by providing users with full access and erasure
				rights over their local data.
			</p>
		</Prose>
	</Section>
</PageLayout>
