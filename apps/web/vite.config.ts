import tailwindcss from '@tailwindcss/vite';
import { playwright } from '@vitest/browser-playwright';
import { defineConfig } from 'vitest/config';
import { sveltekit } from '@sveltejs/kit/vite';
import { SvelteKitPWA } from '@vite-pwa/sveltekit';
import { paraglideVitePlugin } from '@inlang/paraglide-js';
import wasm from 'vite-plugin-wasm';
import topLevelAwait from 'vite-plugin-top-level-await';
import { readFileSync } from 'node:fs';

// One version, one source. The header used to carry a literal "Version 1.0.0"
// against a package version of 0.3.0, and nothing could notice the drift.
const { version: APP_VERSION } = JSON.parse(readFileSync('./package.json', 'utf-8'));

export default defineConfig({
	define: {
		__APP_VERSION__: JSON.stringify(APP_VERSION)
	},
	plugins: [
		paraglideVitePlugin({
			project: './project.inlang',
			outdir: './src/lib/paraglide'
		}),
		wasm(),
		topLevelAwait(),
		tailwindcss(),
		sveltekit(),
		SvelteKitPWA({
			strategies: 'generateSW',
			registerType: 'autoUpdate',
			manifest: {
				name: 'MonOCR',
				short_name: 'MonOCR',
				description:
					'Private Mon Language OCR. Optimized for high-accuracy archival digitization, running entirely in your browser.',
				// Matches src/app.html's own <meta name="theme-color">, and --bg-canvas in
				// app.css -- this manifest previously carried unrelated placeholder values
				// (an indigo theme_color, a white background_color, and literal starter-template
				// categories) that never matched the rest of the app.
				theme_color: '#7a1b1b',
				background_color: '#fcfbf9',
				display: 'standalone',
				orientation: 'portrait-primary',
				icons: [
					{
						src: '/favicon-96x96.png',
						sizes: '96x96',
						type: 'image/png'
					},
					{
						src: '/web-app-manifest-192x192.png',
						sizes: '192x192',
						type: 'image/png',
						purpose: 'any maskable'
					},
					{
						src: '/web-app-manifest-512x512.png',
						sizes: '512x512',
						type: 'image/png',
						purpose: 'any maskable'
					},
					{
						src: '/apple-touch-icon.png',
						sizes: '180x180',
						type: 'image/png'
					}
				],
				screenshots: [
					{
						src: '/og-image.jpg',
						sizes: '1200x630',
						type: 'image/jpeg',
						form_factor: 'wide'
					}
				],
				categories: ['ocr', 'mon', 'language-preservation'],
				lang: 'en',
				dir: 'ltr'
			},
			workbox: {
				globPatterns: [
					// `ttf` matters: the only Myanmar font ships as TTF, so without it an
					// offline PWA kept every Latin font and lost Mon rendering entirely.
					'client/**/*.{js,css,ico,png,svg,webp,avif,jpg,jpeg,json,woff,woff2,ttf}',
					'prerendered/**/*.{html,json}'
				],
				globIgnores: [
					'**/node_modules/**/*',
					'**/.git/**/*',
					// Committed but referenced by no CSS — a numerals-only Pyidaungsu variant.
					// Adding `ttf` to the globs above swept it into the offline bundle, costing
					// ~185 KiB for a face nothing can select. Regular and Bold are both used and
					// both stay. Delete the file and this line together if it is ever pruned.
					'**/Pyidaungsu-Numbers.ttf'
				],
				maximumFileSizeToCacheInBytes: 100 * 1024 * 1024, // 100 MB for WASM files
				runtimeCaching: [
					{
						urlPattern: ({ request }) => request.destination === 'document',
						handler: 'StaleWhileRevalidate',
						options: {
							cacheName: 'pages-cache',
							expiration: {
								maxEntries: 50,
								maxAgeSeconds: 60 * 60 * 24 * 10 // 10 days
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					},
					{
						// HuggingFace ONNX model (cross-origin full URL match)
						urlPattern: /huggingface\.co\/.*\.onnx/,
						handler: 'CacheFirst',
						options: {
							cacheName: 'monocr-models',
							expiration: {
								maxEntries: 5,
								maxAgeSeconds: 60 * 60 * 24 * 30, // 30 days
								purgeOnQuotaError: true
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					},
					{
						// Same-origin charset and any local .onnx
						urlPattern: ({ url }) =>
							url.origin === location.origin &&
							(url.pathname.endsWith('.onnx') || url.pathname.endsWith('charset.txt')),
						handler: 'CacheFirst',
						options: {
							cacheName: 'monocr-models',
							expiration: {
								maxEntries: 5,
								maxAgeSeconds: 60 * 60 * 24 * 30, // 30 days
								purgeOnQuotaError: true
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					},
					{
						// WASM runtime files loaded dynamically by ONNX Runtime
						urlPattern: /\/wasm\/.*\.wasm$/,
						handler: 'CacheFirst',
						options: {
							cacheName: 'ort-wasm',
							expiration: {
								maxEntries: 10,
								maxAgeSeconds: 60 * 60 * 24 * 30, // 30 days
								purgeOnQuotaError: true
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					},
					{
						urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
						handler: 'StaleWhileRevalidate',
						options: {
							cacheName: 'google-fonts-cache',
							expiration: {
								maxEntries: 10,
								maxAgeSeconds: 60 * 60 * 24 * 10 // 10 days
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					},
					{
						urlPattern: /^https:\/\/fonts\.gstatic\.com\/.*/i,
						handler: 'CacheFirst',
						options: {
							cacheName: 'gstatic-fonts-cache',
							expiration: {
								maxEntries: 10,
								maxAgeSeconds: 60 * 60 * 24 * 10 // 10 days
							},
							cacheableResponse: {
								statuses: [0, 200]
							}
						}
					}
				],
				navigateFallback: '/',
				navigateFallbackDenylist: [/^\/_/, /\/[^/?]+\.[^/?]+$/],
				skipWaiting: true,
				clientsClaim: true
			},
			devOptions: {
				enabled: false,
				type: 'module'
			}
		})
	],
	build: {
		minify: 'terser',
		target: 'es2022', // Modern target for modern template
		cssMinify: 'lightningcss',
		chunkSizeWarningLimit: 1000,
		terserOptions: {
			compress: {
				drop_console: true,
				drop_debugger: true
			},
			format: {
				comments: false
			}
		},
		modulePreload: {
			polyfill: false
		}
	},
	optimizeDeps: {
		include: ['svelte'],
		exclude: ['@sveltejs/kit'],
		force: false
	},
	ssr: {
		noExternal: []
	},
	test: {
		expect: { requireAssertions: true },
		projects: [
			{
				extends: './vite.config.ts',
				test: {
					name: 'client',
					// No `environment: 'browser'` here. Vitest 4 rejects it outright —
					// "use test.browser.enabled instead" — which the block below already
					// does, so the line only stopped the runner from starting. Nothing
					// caught it because the repository had no test files at all.
					browser: {
						enabled: true,
						// A factory, not the string 'playwright'. Vitest 4 changed this and
						// the string form now throws at startup rather than warning.
						provider: playwright(),
						instances: [{ browser: 'chromium' }]
					},
					include: ['src/**/*.svelte.{test,spec}.{js,ts}'],
					exclude: ['src/lib/server/**'],
					setupFiles: ['./vitest-setup-client.ts']
				}
			},
			{
				extends: './vite.config.ts',
				test: {
					name: 'server',
					environment: 'node',
					include: ['src/**/*.{test,spec}.{js,ts}'],
					exclude: ['src/**/*.svelte.{test,spec}.{js,ts}']
				}
			}
		]
	},
	server: {
		fs: {
			allow: ['.']
		},
		headers: {
			'Cross-Origin-Opener-Policy': 'same-origin',
			'Cross-Origin-Embedder-Policy': 'require-corp'
		}
	}
});
