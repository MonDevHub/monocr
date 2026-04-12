<script lang="ts">
	interface Props {
		type: 'success' | 'error' | 'warning' | 'neutral';
		label: string;
		icon?: string;
	}

	const { type, label, icon }: Props = $props();

	const typeClasses = {
		success: 'bg-emerald-500/5 text-emerald-500',
		error: 'bg-red-500/5 text-red-500',
		warning: 'bg-amber-500/5 text-amber-500',
		neutral: 'bg-fg-muted/10 text-fg-muted'
	};
</script>

<div
	class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 {typeClasses[
		type
	]} transition-colors duration-200"
>
	{#if icon}
		<span class="material-symbols-outlined text-[13px]">{icon}</span>
	{/if}
	<span class="text-[9px] font-bold tracking-widest uppercase">{label}</span>
</div>
