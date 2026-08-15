// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}

	/** Injected by vite.config.ts from package.json, so the UI cannot drift from it. */
	const __APP_VERSION__: string;
}

export {};
