<script lang="ts">
	import Icon from './Icon.svelte';

	interface Props {
		type: 'success' | 'error' | 'warning' | 'neutral';
		label: string;
		icon?: string;
	}

	const { type, label, icon }: Props = $props();

	const typeClasses = {
		success: 'bg-emerald-500/5 text-emerald-700 dark:text-emerald-400',
		error: 'bg-red-500/5 text-red-700 dark:text-red-400',
		warning: 'bg-amber-500/5 text-amber-700 dark:text-amber-400',
		neutral: 'bg-fg-muted/10 text-fg-muted'
	};
</script>

<div
	class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 {typeClasses[
		type
	]} transition-colors duration-200"
>
	{#if icon}
		<Icon name={icon} size={13} />
	{/if}
	<span class="text-[11px] font-bold tracking-widest uppercase">{label}</span>
</div>
