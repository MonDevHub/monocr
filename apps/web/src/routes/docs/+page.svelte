<script lang="ts">
	/* eslint-disable svelte/no-navigation-without-resolve */
	import { SEO } from '$lib/components';
	import { onMount } from 'svelte';
	import * as m from '$lib/paraglide/messages';

	let activeSection = $state('introduction');
	let selectedSdk = $state('js');

	const sdks = [
		{ id: 'js', name: 'JavaScript', pkg: 'monocr' },
		{ id: 'python', name: 'Python', pkg: 'monocr-onnx' },
		{ id: 'go', name: 'Go', pkg: 'monocr-onnx/go' },
		{ id: 'rust', name: 'Rust', pkg: 'monocr-onnx' }
	];

	interface DocLink {
		id: string;
		label: () => string;
	}

	const sections: { title: () => string; links: DocLink[] }[] = [
		{
			title: () => m.docs_foundation(),
			links: [
				{ id: 'introduction', label: () => m.docs_foundation_overview() },
				{ id: 'getting-started', label: () => m.docs_foundation_get_started() },
				{ id: 'image-quality', label: () => m.docs_foundation_image_quality() }
			]
		},
		{
			title: () => m.docs_usage_title(),
			links: [
				{ id: 'sdks', label: () => m.docs_usage_sdks() },
				{ id: 'cli-reference', label: () => m.docs_usage_cli() }
			]
		},
		{
			title: () => m.docs_governance(),
			links: [
				{ id: 'privacy', label: () => m.docs_governance_privacy() },
				{ id: 'license', label: () => m.docs_governance_license() },
				{ id: 'contributors', label: () => m.docs_governance_contributors() }
			]
		}
	];

	const toc = [
		{ id: 'introduction', label: () => m.docs_foundation_overview() },
		{ id: 'getting-started', label: () => m.docs_foundation_get_started() },
		{ id: 'installation', label: () => m.docs_install_title(), sub: true },
		{ id: 'quick-start', label: () => m.docs_impl_title(), sub: true },
		{ id: 'image-quality', label: () => m.docs_foundation_image_quality() },
		{ id: 'sdks', label: () => m.docs_usage_sdks() },
		{ id: 'cli-reference', label: () => m.docs_usage_cli() },
		{ id: 'privacy', label: () => m.docs_governance_privacy() },
		{ id: 'license', label: () => m.docs_governance_license() },
		{ id: 'contributors', label: () => m.docs_governance_contributors() }
	];

	onMount(() => {
		const observer = new IntersectionObserver(
			(entries) => {
				entries.forEach((entry) => {
					if (entry.isIntersecting) {
						activeSection = entry.target.id;
					}
				});
			},
			{ threshold: 0.1, rootMargin: '-10% 0% -80% 0%' }
		);

		document.querySelectorAll('section[id], h2[id], div[id]').forEach((el) => {
			if (toc.some((item) => item.id === el.id)) {
				observer.observe(el);
			}
		});

		// Handle initial hash for SDK selection
		const hash = window.location.hash.replace('#', '');
		if (['js', 'python', 'go', 'rust', 'cli', 'nodejs'].includes(hash)) {
			if (hash === 'cli') selectedSdk = 'python';
			else if (hash === 'nodejs') selectedSdk = 'js';
			else selectedSdk = hash;
		}

		return () => observer.disconnect();
	});

	function copyToClipboard(text: string) {
		navigator.clipboard.writeText(text);
	}
</script>

<SEO
	title={`${m.docs_title()} - MonOCR Professional Academic OCR`}
	description={m.docs_hero_desc()}
/>

<div class="selection:bg-primary-tint/20">
	<div class="mx-auto max-w-[1400px] px-6 lg:px-8">
		<div class="flex flex-col gap-8 lg:flex-row lg:gap-2">
			<!-- VERCEL STYLE: LEFT NAVIGATION -->
			<aside
				class="no-scrollbar glass sticky top-0 hidden h-screen w-64 shrink-0 overflow-y-auto py-16 lg:block border-r border-border/50"
			>
				<div class="space-y-8 pr-6">
					{#each sections as section (section.title())}
						<div class="space-y-3">
							<span class="text-fg-primary/40 px-3 text-[10px] font-bold tracking-[0.2em] uppercase">
								{section.title()}
							</span>
							<nav class="flex flex-col gap-0.5">
								{#each section.links as link (link.id)}
									<a
										href="#{link.id}"
										class="group relative flex items-center gap-2 rounded-md px-3 py-2 text-[13px] transition-all duration-200
											{activeSection === link.id
											? 'bg-canvas text-primary font-medium shadow-sm ring-1 ring-border/30'
											: 'text-fg-muted hover:text-fg-primary hover:bg-canvas-subtle'}"
									>
										{#if activeSection === link.id}
											<span class="absolute left-0 h-4 w-0.5 rounded-full bg-primary-tint"></span>
										{/if}
										<span>{link.label()}</span>
									</a>
								{/each}
							</nav>
						</div>
					{/each}
				</div>
			</aside>

			<!-- MAIN CONTENT PANEL -->
			<main class=" w-full min-w-0 flex-1 py-16 lg:px-12 xl:px-20">
				<!-- Breadcrumbs -->
				<nav class="text-fg-muted/60 mb-12 flex items-center gap-2 text-[12px] font-medium">
					<a href="/docs" class="hover:text-fg-primary">{m.nav_docs()}</a>
					<span class="opacity-30">/</span>
					<span class="text-fg-primary capitalize">{activeSection.replace('-', ' ')}</span>
				</nav>

				<article class="flex max-w-[750px] flex-col gap-24 pb-32">
					<!-- Header Section -->
					<section id="introduction" class="scroll-mt-32">
						<div class="space-y-6">
							<h1
								class="text-fg-primary text-4xl leading-tight font-extrabold tracking-tight lg:text-5xl"
							>
								{m.docs_hero_title()}
							</h1>
							<p class="text-fg-secondary text-lg leading-relaxed font-light">
								{m.docs_hero_desc()}
							</p>
						</div>
					</section>

					<!-- Getting Started Section -->
					<section id="getting-started" class="scroll-mt-32 pt-8">
						<div class="space-y-20">
							<div id="installation" class="scroll-mt-32 space-y-8">
								<div class="space-y-4">
									<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_install_title()}</h2>
									<p class="text-fg-muted text-[15px] leading-relaxed">
										{m.docs_install_desc()}
									</p>
								</div>

								<div
									class="group relative overflow-hidden rounded-xl border border-border/50 bg-canvas-subtle/40 shadow-sm"
								>
									<!-- Mac Traffic Lights -->
									<div class="flex items-center justify-between border-b border-border/30 bg-canvas-subtle/50 px-4 py-3">
										<div class="flex gap-2">
											<div class="h-2.5 w-2.5 rounded-full bg-red-500/30"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-yellow-500/30"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-green-500/30"></div>
										</div>
										<span class="font-mono text-[9px] font-bold tracking-widest uppercase opacity-40">Bash</span>
									</div>
									<div class="relative">
										<pre class="p-6 font-mono text-[13px] leading-relaxed"><span class="text-primary-tint/30 select-none">$ </span>pip install monocr</pre>
										<button
											onclick={() => copyToClipboard('pip install monocr')}
											class="focus-ring absolute top-1/2 -translate-y-1/2 right-4 flex h-8 w-8 items-center justify-center rounded-lg bg-canvas border border-border/50 opacity-0 transition-opacity hover:bg-canvas-subtle group-hover:opacity-100"
											aria-label="Copy code"
										>
											<span class="material-symbols-outlined text-[16px] opacity-60">content_copy</span>
										</button>
									</div>
								</div>
							</div>

							<div id="quick-start" class="scroll-mt-32 space-y-8">
								<div class="space-y-4">
									<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_impl_title()}</h2>
									<p class="text-fg-muted text-[15px] leading-relaxed">
										{m.docs_impl_desc()}
									</p>
								</div>
								<div
									class="group relative overflow-hidden rounded-xl border border-border/50 bg-canvas-subtle/40 shadow-sm"
								>
									<div class="flex items-center justify-between border-b border-border/30 bg-canvas-subtle/50 px-4 py-3">
										<div class="flex gap-2">
											<div class="h-2.5 w-2.5 rounded-full bg-red-400/20"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-yellow-400/20"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-green-400/20"></div>
										</div>
										<span class="font-mono text-[9px] font-bold tracking-widest uppercase opacity-40">example.py</span>
									</div>
									<div class="relative">
										<pre class="p-6 font-mono text-[13px] leading-[1.8]"><span class="zen-code-comment"># Initialize engine</span>
<span class="zen-code-keyword">from</span> monocr <span class="zen-code-keyword">import</span> MonOCR

ocr = MonOCR()
text = ocr.predict(<span class="zen-code-string">'manuscript.jpg'</span>)
<span class="zen-code-keyword">print</span>(text)</pre>
										<button
											onclick={() =>
												copyToClipboard(
													"from monocr import MonOCR\n\nocr = MonOCR()\ntext = ocr.predict('manuscript.jpg')\nprint(text)"
												)}
											class="focus-ring absolute top-1/2 -translate-y-1/2 right-4 flex h-8 w-8 items-center justify-center rounded-lg bg-canvas border border-border/50 opacity-0 transition-opacity hover:bg-canvas-subtle group-hover:opacity-100"
											aria-label="Copy code"
										>
											<span class="material-symbols-outlined text-[16px] opacity-60">content_copy</span>
										</button>
									</div>
								</div>
							</div>
						</div>
					</section>

					<!-- Image Quality Section -->
					<section id="image-quality" class="scroll-mt-32 pt-8">
						<div class="space-y-12">
							<div class="space-y-4">
								<h2 class="text-fg-primary text-2xl font-bold tracking-tight">
									{m.docs_standards_title()}
								</h2>
								<p class="text-fg-muted text-[15px] leading-relaxed">
									{m.docs_standards_desc()}
								</p>
							</div>

							<div class="grid gap-6 sm:grid-cols-2">
								<div
									class="bg-canvas-subtle/40 hover:bg-canvas-subtle/60 rounded-2xl border border-border/30 p-8 transition-all hover:shadow-sm"
								>
									<h3 class="mb-4 text-[11px] font-bold tracking-[0.2em] uppercase opacity-40">
										{m.docs_standards_dpi_title()}
									</h3>
									<p class="text-fg-muted text-[13px] leading-relaxed font-light">
										{m.docs_standards_dpi_desc()}
									</p>
								</div>
								<div
									class="bg-canvas-subtle/40 hover:bg-canvas-subtle/60 rounded-2xl border border-border/30 p-8 transition-all hover:shadow-sm"
								>
									<h3 class="mb-4 text-[11px] font-bold tracking-[0.2em] uppercase opacity-40">
										{m.docs_standards_light_title()}
									</h3>
									<p class="text-fg-muted text-[13px] leading-relaxed font-light">
										{m.docs_standards_light_desc()}
									</p>
								</div>
							</div>
						</div>
					</section>

					<!-- SDKs Section -->
					<section id="sdks" class="scroll-mt-32 pt-8">
						<div class="space-y-12">
							<div class="space-y-4">
								<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_usage_sdks()}</h2>
								<p class="text-fg-muted text-[15px] leading-relaxed">
									{m.docs_sdks_desc()}
								</p>
							</div>

							<div class="space-y-6">
								<div class="bg-canvas-subtle/30 flex w-fit gap-1 rounded-lg border border-border/30 p-1 font-display">
									{#each sdks as sdk (sdk.id)}
										<button
											onclick={() => (selectedSdk = sdk.id)}
											class="rounded-md px-4 py-1.5 text-[11px] font-semibold transition-all
												{selectedSdk === sdk.id
												? 'bg-canvas text-primary shadow-sm'
												: 'text-fg-muted hover:text-fg-primary'}"
										>
											{sdk.name}
										</button>
									{/each}
								</div>

								<div
									class="group relative overflow-hidden rounded-xl border border-border/50 bg-canvas-subtle/40 shadow-sm"
								>
									<div class="flex items-center justify-between border-b border-border/30 bg-canvas-subtle/50 px-4 py-3">
										<div class="flex gap-2">
											<div class="h-2.5 w-2.5 rounded-full bg-red-400/20"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-yellow-400/20"></div>
											<div class="h-2.5 w-2.5 rounded-full bg-green-400/20"></div>
										</div>
										<span class="font-mono text-[9px] font-bold tracking-widest uppercase opacity-40">{sdks.find((s) => s.id === selectedSdk)?.pkg}</span>
									</div>
									<div class="relative">
										<pre
											class="min-h-[160px] p-6 font-mono text-[13px] leading-[1.8]">{#if selectedSdk === 'js'}
<span class="zen-code-comment">// Node.js Implementation</span>
<span class="zen-code-keyword">import</span> &#123; MonOCR &#125; <span class="zen-code-keyword">from</span> <span class="zen-code-string">'monocr'</span>;

<span class="zen-code-keyword">const</span> ocr = <span class="zen-code-keyword">new</span> MonOCR();
<span class="zen-code-keyword">const</span> text = <span class="zen-code-keyword">await</span> ocr.predict(<span class="zen-code-string">'page.jpg'</span>);
										{:else if selectedSdk === 'python'}
<span class="zen-code-comment"># Python / Academic Research</span>
<span class="zen-code-keyword">from</span> monocr <span class="zen-code-keyword">import</span> MonOCR

ocr = MonOCR()
res = ocr.predict_with_confidence(<span class="zen-code-string">"sample.png"</span>)
<span class="zen-code-keyword">print</span>(<span class="zen-code-string">f"Confidence: &#123;res['confidence']:.2%&#125;"</span>)
										{:else if selectedSdk === 'go'}
<span class="zen-code-comment">// High-performance Go implementation</span>
<span class="zen-code-keyword">import</span> <span class="zen-code-string">"github.com/MonDevHub/monocr-onnx/go/pkg/ocr"</span>

engine, _ := ocr.NewMonOCR(<span class="zen-code-string">""</span>)
text, _ := engine.Predict(<span class="zen-code-string">"manuscript.jpg"</span>)
										{:else if selectedSdk === 'rust'}
<span class="zen-code-comment">// Memory-safe Rust implementation</span>
<span class="zen-code-keyword">use</span> monocr_onnx::MonOCR;

<span class="zen-code-keyword">let</span> ocr = MonOCR::new(<span class="zen-code-string">"monocr.onnx"</span>)?;
<span class="zen-code-keyword">let</span> text = ocr.predict(<span class="zen-code-string">"scan.jpg"</span>)?;
										{/if}</pre>
									</div>
								</div>
							</div>
						</div>
					</section>

					<!-- CLI Reference Section -->
					<section id="cli-reference" class="scroll-mt-32 pt-8">
						<div class="space-y-8">
							<div class="space-y-4">
								<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_usage_cli()}</h2>
								<p class="text-fg-muted text-[15px] leading-relaxed">
									{m.docs_cli_desc()}
								</p>
							</div>

							<div class="relative overflow-hidden rounded-xl border border-border/50 bg-canvas-subtle/40 shadow-sm">
								<div class="flex items-center justify-between border-b border-border/30 bg-canvas-subtle/50 px-4 py-3">
									<div class="flex gap-2">
										<div class="h-2.5 w-2.5 rounded-full bg-red-400/20"></div>
										<div class="h-2.5 w-2.5 rounded-full bg-yellow-400/20"></div>
										<div class="h-2.5 w-2.5 rounded-full bg-green-400/20"></div>
									</div>
									<span class="font-mono text-[9px] font-bold tracking-widest uppercase opacity-40">Terminal</span>
								</div>
								<pre class="p-6 font-mono text-[13px] leading-[1.8]"><span class="zen-code-comment"># Process single file</span>
monocr read manuscript_01.jpg

<span class="zen-code-comment"># Batch process archive directory</span>
monocr batch ./scans/ --output results.txt</pre>
							</div>
						</div>
					</section>

					<!-- Privacy Section -->
					<section id="privacy" class="scroll-mt-32 pt-8">
						<div class="space-y-4">
							<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_governance_privacy()}</h2>
							<p class="text-fg-muted text-justify text-[15px] leading-relaxed font-light">
								{m.docs_privacy_guarantee()}
							</p>
						</div>
					</section>

					<!-- License Section -->
					<section id="license" class="scroll-mt-32 pt-8">
						<div class="space-y-4">
							<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_governance_license()}</h2>
							<p class="text-fg-muted text-justify text-[15px] leading-relaxed font-light">
								{m.docs_license_desc()}
							</p>
						</div>
					</section>

					<!-- Contributors Section -->
					<section id="contributors" class="scroll-mt-32 pt-8">
						<div class="space-y-6">
							<h2 class="text-fg-primary text-2xl font-bold tracking-tight">{m.docs_contributors_title()}</h2>
							<div class="border border-border/50 overflow-hidden shadow-sm rounded-xl">
								<a href="https://github.com/janakhpon" target="_blank" rel="noopener noreferrer" class="group flex w-full items-center justify-between p-4 px-6 transition-colors hover:bg-canvas-subtle/50">
									<div class="flex flex-col">
										<span class="text-fg-primary text-[14px] font-semibold">Janakh Pon</span>
										<span class="text-fg-muted text-[12px]">Project Lead & Architecture</span>
									</div>
									<span class="material-symbols-outlined text-[18px] opacity-20 transition-all group-hover:scale-110 group-hover:opacity-100">open_in_new</span>
								</a>
								<div class="bg-border/30 h-[1px] w-full"></div>
								<a href="https://github.com/Oungseik" target="_blank" rel="noopener noreferrer" class="group flex w-full items-center justify-between p-4 px-6 transition-colors hover:bg-canvas-subtle/50">
									<div class="flex flex-col">
										<span class="text-fg-primary text-[14px] font-semibold">Oung Seik Nyan</span>
										<span class="text-fg-muted text-[12px]">Language Model Specialist</span>
									</div>
									<span class="material-symbols-outlined text-[18px] opacity-20 transition-all group-hover:scale-110 group-hover:opacity-100">open_in_new</span>
								</a>
								<div class="bg-border/30 h-[1px] w-full"></div>
								<a href="https://www.facebook.com/RJOMDK10" target="_blank" rel="noopener noreferrer" class="group flex w-full items-center justify-between p-4 px-6 transition-colors hover:bg-canvas-subtle/50">
									<div class="flex flex-col">
										<span class="text-fg-primary text-[14px] font-semibold">Rajel Da Key</span>
										<span class="text-fg-muted text-[12px]">Dataset & Research</span>
									</div>
									<span class="material-symbols-outlined text-[18px] opacity-20 transition-all group-hover:scale-110 group-hover:opacity-100">open_in_new</span>
								</a>
							</div>
						</div>
					</section>

					<!-- Footer Navigation -->
					<footer class="pt-24 pb-12 border-t border-border/30">
						<div
							class=" flex flex-col justify-between gap-8  pt-12 sm:flex-row"
						>
							<a href="/" class="group space-y-3">
								<span
									class="text-fg-muted group-hover:text-primary-tint text-[10px] font-bold tracking-widest uppercase transition-colors"
									>Previous</span
								>
								<div class="text-fg-primary text-lg font-bold">{m.docs_footer_prev()}</div>
							</a>
							<a href="/report" class="group space-y-3 text-right">
								<span
									class="text-fg-muted group-hover:text-primary-tint text-[10px] font-bold tracking-widest uppercase transition-colors"
									>Next</span
								>
								<div class="text-fg-primary text-lg font-bold">{m.docs_footer_next()}</div>
							</a>
						</div>
					</footer>
				</article>
			</main>

			<!-- VERCEL STYLE: RIGHT TOC -->
			<aside
				class="no-scrollbar sticky top-0 hidden h-screen w-64 shrink-0 overflow-y-auto px-6 py-16 xl:block"
			>
				<div class="space-y-6">
					<span class="text-fg-primary/40 px-3 text-[10px] font-bold tracking-[0.2em] uppercase">
						On this page
					</span>
					<nav class=" ml-3 flex flex-col gap-0.5 border-l border-border/50">
						{#each toc as item (item.id)}
							<a
								href="#{item.id}"
								class="-ml-px  px-4 py-1.5 text-[12.5px] transition-all duration-200 border-l-2
									{activeSection === item.id
									? 'text-primary-tint border-primary-tint bg-primary-tint/5 font-semibold'
									: 'text-fg-muted border-transparent hover:text-fg-primary hover:border-border'}
									{item.sub ? 'pl-8 text-[11.5px]' : ''}"
							>
								{item.label()}
							</a>
						{/each}
					</nav>
				</div>
			</aside>
		</div>
	</div>
</div>

<style>
	:global(html) {
		scroll-behavior: smooth;
	}

	.no-scrollbar::-webkit-scrollbar {
		display: none;
	}
	.no-scrollbar {
		-ms-overflow-style: none;
		scrollbar-width: none;
	}

	pre {
		margin: 0;
	}

	.material-symbols-outlined {
		font-variation-settings:
			'wght' 300,
			'opsz' 20;
	}

	@media (max-width: 1023px) {
		.scroll-mt-32 {
			scroll-margin-top: 6rem;
		}
	}
</style>
