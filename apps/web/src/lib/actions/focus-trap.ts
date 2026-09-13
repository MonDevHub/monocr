/**
 * Svelte action to trap focus within an element.
 * Essential for Modal accessibility (WCAG).
 *
 * FEATURES:
 * - Focus Restoration: Returns focus to previous element on destroy.
 * - Dynamic Support: Uses MutationObserver to handle content changes.
 * - Safety: Guarded against empty sets.
 * - Escape-to-close: an optional `onEscape` callback, called on Escape.
 *
 * Escape handling lives here, not on each modal's backdrop element. Focus
 * moves inside this node as soon as the trap activates, so a keydown
 * listener on a backdrop `<div>` — a sibling, not an ancestor, of this node
 * — never receives the event once the trap is live. Every caller gets
 * correct Escape behavior from this one place instead of each modal
 * re-implementing (and mis-wiring) it.
 */
export function focusTrap(node: HTMLElement, params?: { onEscape?: () => void }) {
	let onEscape = params?.onEscape;
	let focusableElements: HTMLElement[] = [];
	let firstElement: HTMLElement | undefined;
	let lastElement: HTMLElement | undefined;
	const previouslyFocused = (document.activeElement as HTMLElement) || null;

	function updateFocusableElements() {
		const elements = node.querySelectorAll(
			'a[href], button, input, textarea, select, [tabindex]:not([tabindex="-1"])'
		);
		focusableElements = Array.from(elements) as HTMLElement[];
		firstElement = focusableElements[0];
		lastElement = focusableElements[focusableElements.length - 1];
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			onEscape?.();
			return;
		}

		if (e.key !== 'Tab' || focusableElements.length === 0) return;

		if (e.shiftKey) {
			if (document.activeElement === firstElement) {
				lastElement?.focus();
				e.preventDefault();
			}
		} else {
			if (document.activeElement === lastElement) {
				firstElement?.focus();
				e.preventDefault();
			}
		}
	}

	// 1. Initial State
	updateFocusableElements();

	// 2. Dynamic Content Monitoring
	const observer = new MutationObserver(() => {
		updateFocusableElements();
	});
	observer.observe(node, { childList: true, subtree: true, attributes: true });

	// 3. Events
	node.addEventListener('keydown', handleKeydown);

	// 4. Initial Focus
	const initialFocus = node.querySelector('[autofocus]') as HTMLElement;
	if (initialFocus) {
		initialFocus.focus();
	} else if (firstElement) {
		firstElement.focus();
	}

	return {
		update(newParams?: { onEscape?: () => void }) {
			onEscape = newParams?.onEscape;
		},
		destroy() {
			node.removeEventListener('keydown', handleKeydown);
			observer.disconnect();
			// Restore focus to where the user was before opening the modal
			if (previouslyFocused && typeof previouslyFocused.focus === 'function') {
				previouslyFocused.focus();
			}
		}
	};
}
