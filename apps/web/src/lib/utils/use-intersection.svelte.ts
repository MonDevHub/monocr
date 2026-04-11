/**
 * Intersection Observer Utility for Scroll Animations
 * Svelte 5 rune-based implementation with performance optimizations
 */

import { INTERSECTION_CONFIG } from './animation-config';

interface IntersectionOptions {
	/** Threshold percentage (0-1) of visibility to trigger */
	threshold?: number;
	/** Root margin for early/late triggering */
	rootMargin?: string;
	/** Only trigger once (prevents re-animation on scroll) */
	once?: boolean;
	/** Callback function when element intersects */
	onIntersect?: (isIntersecting: boolean) => void;
}

/**
 * Svelte action for intersection observer
 * Usage: use:intersection={{ onIntersect: (isIntersecting) => { ... } }}
 */
export function intersection(node: HTMLElement, options: IntersectionOptions = {}) {
	const {
		threshold = INTERSECTION_CONFIG.threshold,
		rootMargin = INTERSECTION_CONFIG.rootMargin,
		once = true,
		onIntersect
	} = options;

	// Check for browser support
	if (typeof IntersectionObserver === 'undefined') {
		// Fallback: immediately trigger for browsers without support
		if (onIntersect) {
			onIntersect(true);
		}
		return {
			destroy() {}
		};
	}

	let hasTriggered = false;

	const observer = new IntersectionObserver(
		(entries) => {
			entries.forEach((entry) => {
				if (entry.isIntersecting) {
					if (!hasTriggered || !once) {
						if (onIntersect) {
							onIntersect(true);
						}
						hasTriggered = true;
					}
				} else if (!once && onIntersect) {
					onIntersect(false);
				}
			});
		},
		{ threshold, rootMargin }
	);

	observer.observe(node);

	return {
		destroy() {
			observer.disconnect();
		}
	};
}

/**
 * Svelte 5 rune-based hook for intersection detection
 * @param options Configuration options
 * @returns Object with isIntersecting state
 */
export function createIntersection(options: Omit<IntersectionOptions, 'onIntersect'> = {}) {
	let isIntersecting = $state(false);

	return {
		get isIntersecting() {
			return isIntersecting;
		},
		setIntersecting(value: boolean) {
			isIntersecting = value;
		},
		options: {
			...options,
			onIntersect: (value: boolean) => {
				isIntersecting = value;
			}
		}
	};
}
