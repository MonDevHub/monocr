import adapter from '@sveltejs/adapter-cloudflare';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

const config = {
	preprocess: vitePreprocess(),
	kit: {
		adapter: adapter(),

		alias: {
			'@/*': './src/lib/*'
		},
		inlineStyleThreshold: 40960, // Inline CSS smaller than 40kb to avoid render blocking
		prerender: {
			handleHttpError: 'warn',
			handleMissingId: 'warn',
			entries: ['/', '/docs']
		}
	}
};

export default config;
