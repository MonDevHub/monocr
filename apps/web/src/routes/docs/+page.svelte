<script lang="ts">
	import { SEO } from '$lib/components';
	import {
		CodeBlock,
		DescriptionList,
		Link,
		Rule,
		Section,
		Tabs,
		TableOfContents
	} from '$lib/quiet';
	import { onMount } from 'svelte';
	import * as m from '$lib/paraglide/messages';

	// The button words follow the interface language; "Copy failed" keeps its English default until it has a message.
	const copyLabels = $derived({ idle: m.main_copy_clipboard(), copied: m.main_copied() });

	const sdks = [
		{
			id: 'js',
			name: 'JavaScript',
			pkg: 'monocr',
			code: `// Node.js Implementation
import { MonOCR } from 'monocr';

const ocr = new MonOCR();
const lines = await ocr.predictPage('page.jpg');`
		},
		{
			id: 'python',
			name: 'Python',
			pkg: 'monocr-onnx',
			code: `# Python
from monocr_onnx import MonOCR

engine = MonOCR()
text = engine.predict("document.png")
print(text)`
		},
		{
			id: 'go',
			name: 'Go',
			pkg: 'monocr-onnx/go',
			code: `// Go binding
import monocr "github.com/MonDevHub/monocr-onnx/go"

text, _ := monocr.ReadImage("manuscript.jpg")`
		},
		{
			id: 'rust',
			name: 'Rust',
			pkg: 'monocr',
			code: `// Memory-safe Rust implementation
use monocr_onnx::MonOcr;

let mut ocr = MonOcr::builder().build().await?;
let text = ocr.read_image("scan.jpg").await?;`
		}
	];

	const installCode = 'pip install monocr';
	const quickStartCode = `# Initialize engine
from monocr import MonOCR

ocr = MonOCR()
text = ocr.predict('manuscript.jpg')
print(text)`;
	const cliCode = `# Process single file
monocr read manuscript_01.jpg

# Batch process archive directory
monocr batch ./scans/ --output results.txt`;

	let selectedSdk = $state('js');

	const sdkTabs = sdks.map((sdk) => ({ id: sdk.id, label: sdk.name }));

	const toc = $derived([
		{ href: '#installation', label: m.docs_install_title() },
		{ href: '#quick-start', label: m.docs_impl_title() },
		{ href: '#image-quality', label: m.docs_standards_title() },
		{ href: '#sdks', label: m.docs_usage_sdks() },
		{ href: '#cli-reference', label: m.docs_usage_cli() },
		{ href: '#privacy', label: m.docs_governance_privacy() },
		{ href: '#license', label: m.docs_governance_license() }
	]);

	onMount(() => {
		// Handle initial hash for SDK selection
		const hash = window.location.hash.replace('#', '');
		if (['js', 'python', 'go', 'rust', 'cli', 'nodejs'].includes(hash)) {
			if (hash === 'cli') selectedSdk = 'python';
			else if (hash === 'nodejs') selectedSdk = 'js';
			else selectedSdk = hash;
		}
	});
</script>

<SEO title={`${m.docs_title()} - MonOCR`} description={m.docs_hero_desc()} />

<main id="main-content" class="max-w-column pt-page-top pb-page-bottom sm:pt-page-top-wide">
	<div class="space-y-3">
		<h1 class="type-h1">{m.docs_hero_title()}</h1>
		<p class="max-w-measure type-body">{m.docs_hero_desc()}</p>
	</div>

	<div class="mt-8">
		<TableOfContents items={toc} title={m.docs_title()} />
	</div>

	<Section id="installation" title={m.docs_install_title()} intro={m.docs_install_desc()}>
		<CodeBlock caption="Shell" code={installCode} {copyLabels} />
	</Section>

	<Section id="quick-start" title={m.docs_impl_title()} intro={m.docs_impl_desc()}>
		<CodeBlock caption="example.py" code={quickStartCode} {copyLabels} />
	</Section>

	<Section id="image-quality" title={m.docs_standards_title()} intro={m.docs_standards_desc()}>
		<DescriptionList
			items={[
				{ term: m.docs_standards_dpi_title(), detail: m.docs_standards_dpi_desc() },
				{ term: m.docs_standards_light_title(), detail: m.docs_standards_light_desc() }
			]}
		/>
	</Section>

	<Section id="sdks" title={m.docs_usage_sdks()} intro={m.docs_sdks_desc()}>
		<Tabs
			label={m.docs_usage_sdks()}
			items={sdkTabs}
			bind:value={selectedSdk}
			panelFocusable={false}
		>
			{#snippet panel(id)}
				{@const sdk = sdks.find((s) => s.id === id) ?? sdks[0]}
				<CodeBlock caption={`${sdk.name} (${sdk.pkg})`} code={sdk.code} {copyLabels} />
			{/snippet}
		</Tabs>
	</Section>

	<Section id="cli-reference" title={m.docs_usage_cli()} intro={m.docs_cli_desc()}>
		<CodeBlock caption="Terminal" code={cliCode} {copyLabels} />
	</Section>

	<Section id="privacy" title={m.docs_governance_privacy()}>
		<p class="max-w-measure type-body">{m.docs_privacy_guarantee()}</p>
	</Section>

	<Section id="license" title={m.docs_governance_license()}>
		<p class="max-w-measure type-body">{m.docs_license_desc()}</p>
	</Section>

	<Rule />
	<div class="flex flex-wrap gap-x-6">
		<Link href="/" standalone>{m.docs_footer_prev()}</Link>
		<Link href="/report" standalone>{m.docs_footer_next()}</Link>
	</div>
</main>
