<script lang="ts">
	const { children } = $props();
</script>

<div class="bg-canvas text-fg-primary min-h-screen overflow-x-hidden">
	<div class="mx-auto w-full max-w-[1600px]">
		{@render children?.()}
	</div>
</div>

<style>
	:global(body) {
		background-color: var(--bg-canvas);
	}
</style>
