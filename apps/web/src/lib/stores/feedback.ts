import { writable } from 'svelte/store';

interface FeedbackData {
	text: string;
	previewUrl: string | null;
}

export const feedbackStore = writable<FeedbackData>({
	text: '',
	previewUrl: null
});
