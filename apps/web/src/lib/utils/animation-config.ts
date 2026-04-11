/**
 * Animation Configuration
 * Centralized animation settings for consistent UX across the portfolio
 */

/**
 * Animation Durations (in milliseconds)
 * Based on research: 150-400ms for optimal perceived performance
 */
export const DURATIONS = {
	/** Quick micro-interactions (hover, focus states) */
	fast: 200,
	/** Standard entrance/exit animations */
	medium: 350,
	/** Larger, more prominent animations */
	slow: 500
} as const;

/**
 * Easing Functions
 * Based on Josh Collinsworth's guidance:
 * - Entrance animations (ins): use ease-out (starts fast, ends smooth)
 * - Exit animations (outs): use ease-in (starts smooth, ends fast)
 * - Both: ease-in-out for reversible transitions
 */
export const EASINGS = {
	/** Default smooth easing for most interactions */
	smooth: 'cubic-bezier(0.4, 0.0, 0.2, 1)',
	/** Entrance animations - starts fast, decelerates to rest */
	easeOut: 'cubic-bezier(0.0, 0.0, 0.2, 1)',
	/** Exit animations - starts slow, accelerates out */
	easeIn: 'cubic-bezier(0.4, 0.0, 1, 1)',
	/** Reversible animations - smooth both ways */
	easeInOut: 'cubic-bezier(0.4, 0.0, 0.2, 1)',
	/** Snappy, bouncy feel for playful interactions */
	bounce: 'cubic-bezier(0.68, -0.55, 0.265, 1.55)'
} as const;

/**
 * Stagger Delays (in milliseconds)
 * For sequential animations of related items
 */
export const STAGGER = {
	/** Very subtle, almost imperceptible */
	subtle: 30,
	/** Noticeable but quick */
	normal: 50,
	/** More pronounced, for emphasis */
	emphasized: 80
} as const;

/**
 * Animation Distance Values (in pixels)
 * Maximum translate values for subtle movement
 */
export const DISTANCE = {
	/** Barely-there movement */
	minimal: 10,
	/** Subtle but noticeable */
	subtle: 20,
	/** More prominent for key elements */
	normal: 40
} as const;

/**
 * Intersection Observer Configuration
 * For scroll-triggered animations
 */
export const INTERSECTION_CONFIG = {
	/** Trigger when 10% of element is visible */
	threshold: 0.1,
	/** Start detecting 50px before element enters viewport */
	rootMargin: '0px 0px -50px 0px'
} as const;
