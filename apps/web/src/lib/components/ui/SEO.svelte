<script lang="ts">
	import { page } from '$app/stores';

	interface Props {
		title?: string;
		description?: string;
		keywords?: string;
		image?: string;
		type?: 'website' | 'article' | 'profile';
		// eslint-disable-next-line @typescript-eslint/no-explicit-any
		jsonLd?: Record<string, any>;
	}

	const {
		title = 'Mon OCR - Mon (mnw) Language Recognition',
		description = 'OCR model for digitalizing Mon (mnw) language. Fast, private, and high-accuracy recognition running entirely in your browser.',
		keywords = 'Mon, Mon (mnw), Mon OCR, MonOCR, monocr, mon ocr, mon language, ocr model, language preservation',
		image = '/og-image.jpg',
		type = 'website',
		jsonLd = undefined
	}: Props = $props();

	const siteUrl = 'https://ocr.mondevhub.com';
	const canonicalUrl = $derived(`${siteUrl}${$page.url.pathname}`);
	const imageUrl = $derived(image.startsWith('http') ? image : `${siteUrl}${image}`);

	const defaultJsonLd = {
		'@context': 'https://schema.org',
		'@graph': [
			{
				'@type': 'WebSite',
				'@id': 'https://ocr.mondevhub.com/#website',
				url: 'https://ocr.mondevhub.com',
				name: 'Mon OCR',
				description: 'OCR model for digitalizing Mon (mnw) language.',
				publisher: {
					'@id': 'https://ocr.mondevhub.com/#organization'
				},
				inLanguage: 'mnw'
			},
			{
				'@type': 'Organization',
				'@id': 'https://ocr.mondevhub.com/#organization',
				name: 'MonOCR Project',
				url: siteUrl,
				logo: {
					'@type': 'ImageObject',
					url: `${siteUrl}/favicon-512x512.png`,
					width: 512,
					height: 512
				}
			}
		]
	};

	const finalJsonLd = $derived(jsonLd || defaultJsonLd);
</script>

<svelte:head>
	<title>{title}</title>
	<meta name="description" content={description} />
	<meta name="keywords" content={keywords} />
	<meta name="author" content="MonOCR Project" />
	<meta
		name="robots"
		content="index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1"
	/>
	<link rel="canonical" href={canonicalUrl} />

	<!-- Open Graph / Facebook -->
	<meta property="og:type" content={type} />
	<meta property="og:url" content={canonicalUrl} />
	<meta property="og:title" content={title} />
	<meta property="og:description" content={description} />
	<meta property="og:image" content={imageUrl} />
	<meta property="og:image:width" content="1200" />
	<meta property="og:image:height" content="630" />
	<meta property="og:image:alt" content={title} />
	<meta property="og:site_name" content="Mon OCR" />
	<meta property="og:locale" content="en_US" />

	<!-- Twitter -->
	<meta name="twitter:card" content="summary_large_image" />
	<meta name="twitter:url" content={canonicalUrl} />
	<meta name="twitter:title" content={title} />
	<meta name="twitter:description" content={description} />
	<meta name="twitter:image" content={imageUrl} />

	<!-- Structured Data -->
	<!-- eslint-disable-next-line svelte/no-at-html-tags -->
	{@html `<script type="application/ld+json">${JSON.stringify(finalJsonLd)}</` + `script>`}
</svelte:head>
