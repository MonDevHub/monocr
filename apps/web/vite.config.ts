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
				name: 'MonOCR Web',
				short_name: 'MonOCR',
				description: 'Offline-capable Mon language OCR running entirely in the browser.',
				theme_color: '#4338ca',
				background_color: '#ffffff',
				display: 'standalone',
				orientation: 'portrait-primary',
				icons: [
					{
						src: '/android-chrome-192x192.png',
						sizes: '192x192',
						type: 'image/png',
						purpose: 'any maskable'
					},
					{
						src: '/android-chrome-512x512.png',
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
				categories: ['template', 'starter', 'sveltekit'],
				lang: 'en',
				dir: 'ltr'
			},
			workbox: {
				globPatterns: [
					'client/**/*.{js,css,ico,png,svg,webp,avif,jpg,jpeg,json,woff,woff2}',
					'prerendered/**/*.{html,json}'
				],
				globIgnores: ['**/node_modules/**/*', '**/.git/**/*'],
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
