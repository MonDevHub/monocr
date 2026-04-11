/**
 * Simple logger abstraction to allow for centralized logging control.
 * In production, you might want to send these to a service like Sentry or LogRocket.
 */
export const logger = {
	log: (...args: unknown[]) => {
		if (import.meta.env.DEV) {
			console.log(...args);
		}
	},
	error: (...args: unknown[]) => {
		if (import.meta.env.DEV) {
			console.error(...args);
		}
	},
	warn: (...args: unknown[]) => {
		if (import.meta.env.DEV) {
			console.warn(...args);
		}
	},
	debug: (...args: unknown[]) => {
		if (import.meta.env.DEV) {
			console.debug(...args);
		}
	}
};
