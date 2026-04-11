<script lang="ts">
	import { m } from '$lib/i18n';
</script>

<!-- eslint-disable svelte/no-navigation-without-resolve -->
<footer class="text-fg-secondary mt-auto py-4 text-center text-[11px]">
	<div class="mx-auto max-w-4xl space-y-2">
		<nav class="flex justify-center gap-4 text-[9px] font-bold tracking-widest uppercase">
			<a href="/privacy" class="hover:text-primary transition-colors">Privacy & Trust</a>
			<a href="https://github.com/mondevhub/monocr-web" class="hover:text-primary transition-colors"
				>Source Code</a
			>
		</nav>
		<div class="space-y-0.5">
			<p class="text-[9px] opacity-60">
				{m.footer_copy({ year: new Date().getFullYear().toString() })}
			</p>
			<p class="text-[8px] opacity-40">
				{m.footer_tech({ ocrs: 'MobileNetV3+BiLSTM', onnx: 'ONNX Runtime Web' })}
			</p>
		</div>
	</div>
</footer>
