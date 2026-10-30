/*
 * The toast store: what is queued, what shows, and the one optional timer. The Toaster
 * component renders it; toast() is the only way in. Kept apart from the markup so the rules
 * (a queue of three, nothing dismisses itself, a timer that pauses) are tested without a browser.
 */

/** Toasts on screen at once. More wait their turn, in order, and show as room is made. */
export const MAX_VISIBLE = 3;
/** The shortest `duration`: long enough to read and reach (WCAG 2.2.1). Shorter values are raised to it. */
export const MIN_DURATION_MS = 5000;
/** How long a dismissed toast takes to fade out before it is removed from the DOM. */
export const LEAVE_MS = 150;

export interface ToastAction {
	/** Verb first: "Undo", "Try again". */
	label: string;
	/** Runs when the action is pressed; the toast is then dismissed. */
	onclick: () => void;
}

export interface ToastOptions {
	/** One text action beside "Dismiss". A toast with an action never dismisses itself. */
	action?: ToastAction;
	/**
	 * Milliseconds before the toast dismisses itself. Leave it out and the toast stays until
	 * dismissed. Use it only for a confirmation with no action: it is ignored when there is an
	 * action, and values below 5000 are raised to 5000. It pauses while the toast is hovered or
	 * holds focus.
	 */
	duration?: number;
}

export interface ToastItem {
	id: number;
	message: string;
	action?: ToastAction;
	/** Already clamped, and absent when the toast has an action. */
	duration?: number;
	/** Dismissed and fading out: hidden from assistive technology, gone after LEAVE_MS. */
	leaving: boolean;
}

/** The duration a toast really gets: undefined (stays), or at least MIN_DURATION_MS, and never with an action. */
export function effectiveDuration(options: ToastOptions = {}): number | undefined {
	if (options.action || options.duration === undefined || !Number.isFinite(options.duration))
		return undefined;
	return Math.max(MIN_DURATION_MS, options.duration);
}

/**
 * What shows now: the oldest `max` toasts that are not leaving, in order (newest last, at the
 * bottom), plus any leaving toast that was among them so it can finish fading. Nothing is
 * dropped: toasts past `max` wait, and appear when one above is dismissed.
 */
export function visibleToasts(items: readonly ToastItem[], max: number = MAX_VISIBLE): ToastItem[] {
	let live = 0;
	return items.filter((item) => {
		if (item.leaving) return live <= max;
		live += 1;
		return live <= max;
	});
}

/**
 * A pausable countdown. It runs only while neither hovered nor focused, and resumes with the
 * time that was left. destroy() stops it for good.
 */
export class ToastTimer {
	#remaining: number;
	#started = 0;
	#timer: ReturnType<typeof setTimeout> | undefined;
	#hovered = false;
	#focused = false;
	#done = false;
	readonly #onExpire: () => void;

	constructor(ms: number, onExpire: () => void) {
		this.#remaining = ms;
		this.#onExpire = onExpire;
		this.#run();
	}

	get running() {
		return this.#timer !== undefined;
	}

	setHovered(value: boolean) {
		this.#hovered = value;
		this.#sync();
	}

	setFocused(value: boolean) {
		this.#focused = value;
		this.#sync();
	}

	#sync() {
		if (this.#done) return;
		if (this.#hovered || this.#focused) this.#pause();
		else if (!this.running) this.#run();
	}

	#run() {
		this.#started = Date.now();
		this.#timer = setTimeout(() => {
			this.#timer = undefined;
			this.#done = true;
			this.#onExpire();
		}, this.#remaining);
	}

	#pause() {
		if (!this.running) return;
		clearTimeout(this.#timer);
		this.#timer = undefined;
		this.#remaining = Math.max(0, this.#remaining - (Date.now() - this.#started));
	}

	destroy() {
		this.#done = true;
		clearTimeout(this.#timer);
		this.#timer = undefined;
	}
}

class ToastStore {
	items = $state<ToastItem[]>([]);
	#next = 1;

	add(message: string, options: ToastOptions = {}): number {
		const id = this.#next++;
		this.items.push({
			id,
			message,
			action: options.action,
			duration: effectiveDuration(options),
			leaving: false
		});
		return id;
	}

	/** Start the fade; the toast leaves the list LEAVE_MS later. */
	dismiss(id: number) {
		const item = this.items.find((i) => i.id === id);
		if (!item || item.leaving) return;
		item.leaving = true;
		setTimeout(() => this.remove(id), LEAVE_MS);
	}

	remove(id: number) {
		this.items = this.items.filter((i) => i.id !== id);
	}

	clear() {
		this.items = [];
	}
}

export const toasts = new ToastStore();

/**
 * Shows a message and returns its id for dismissToast. It stays until dismissed unless a
 * duration is given. Say what happened in words ("Saved.", "Could not save the draft. Try again."): there
 * is no status colour or icon.
 */
export function toast(message: string, options?: ToastOptions): number {
	return toasts.add(message, options);
}

/** Dismisses the toast with this id, as returned by toast(). */
export function dismissToast(id: number) {
	toasts.dismiss(id);
}
