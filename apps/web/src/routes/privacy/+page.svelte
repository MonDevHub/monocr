<script lang="ts">
</script>

<svelte:head>
	<title>Privacy Policy - MonOCR</title>
	<meta name="description" content="MonOCR Privacy - 100% Local, 100% Private" />
</svelte:head>

<div class="mx-auto max-w-3xl px-4 py-8">
	<!-- Hero Section -->
	<header class="mb-8">
		<h1 class="text-fg-primary mb-2 text-2xl font-bold tracking-tight">Privacy Policy</h1>
		<p class="text-fg-secondary text-[13px] leading-relaxed">
			MonOCR is built on a foundation of local-first privacy. We reject cloud-based surveillance and
			ensure your data remains under your absolute control.
		</p>
	</header>

	<!-- Privacy Principles -->
	<div class="space-y-8">
		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">1. Local Processing</h2>
			<p class="text-fg-secondary text-[13px] leading-relaxed">
				All OCR recognition runs entirely on your local hardware via WASM or WebGPU. Your images are
				processed in the browser's memory and are never uploaded to our servers for processing.
			</p>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">2. Device Permissions and Data Access</h2>
			<div class="text-fg-secondary space-y-3 text-[13px] leading-relaxed">
				<p>
					To provide core OCR functionality, our applications (iOS and Android) may request the following device permissions:
				</p>
				<ul class="list-disc pl-5 space-y-2">
					<li>
						<strong>Camera Access:</strong> Required to capture photos of physical documents or text for live scanning. Images captured via the camera are processed strictly locally on your device and are never uploaded to our servers unless explicitly submitted by you as a Voluntary Contribution.
					</li>
					<li>
						<strong>Photo Library and File Access:</strong> Required to allow you to select existing images or PDF documents from your device for text extraction. We only access the specific files you select.
					</li>
				</ul>
			</div>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">3. Zero Default Collection</h2>
			<p class="text-fg-secondary text-[13px] leading-relaxed">
				We do not use telemetry, analytics, or trackers. We do not know who you are, and we do not
				track how you use the application.
			</p>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">4. Voluntary Contributions</h2>
			<p class="text-fg-secondary text-[13px] leading-relaxed">
				You may choose to share corrected text (Feedback) or new documents (Contributions) to help
				improve the Mon language model. This is strictly opt-in and handled anonymously via secure
				synchronization.
			</p>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">5. Data Retention</h2>
			<p class="text-fg-secondary text-[13px] leading-relaxed">
				Local history is stored on your device and can be cleared at any time. Anonymized
				contributions shared with our dataset are retained for model training and cannot be
				individually identified or removed once pooled.
			</p>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">6. Dataset Framework</h2>
			<div class="text-fg-secondary space-y-3 text-[13px] leading-relaxed">
				<p>
					Contributions are synchronized anonymously via pre-signed, short-lived URLs directly to
					our secure Cloudflare R2 storage. No personal identifiers (IPs, names, accounts) are
					associated with these uploads.
				</p>
				<p>
					Consent is managed per-contribution. Revoking consent stops all future synchronizations
					but does not affect data already contributed to the anonymized research pool.
				</p>
			</div>
		</section>

		<section>
			<h2 class="text-fg-primary mb-1 text-base font-bold">7. Global Compliance</h2>
			<div class="text-fg-secondary text-[13px] leading-relaxed">
				<p>
					Our architecture aligns with the **General Data Protection Regulation (GDPR)** and the
					**California Consumer Privacy Act (CCPA)** by providing users with full access and erasure
					rights over their local data.
				</p>
			</div>
		</section>
	</div>

	<footer class="border-border/50 mt-12 border-t pt-4 pb-2">
		<p class="text-fg-muted font-medium tracking-wide text-[var(--text-meta)]">
			Effective Date: March 22, 2026 • MonOCR Version 0.2.0
		</p>
	</footer>
</div>
