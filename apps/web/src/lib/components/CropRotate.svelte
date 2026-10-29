<script lang="ts">
	import { onMount } from 'svelte';
	import { m } from '$lib/paraglide/messages';
	import {
		adjustImage,
		dragRect,
		fitSize,
		rotatedSize,
		rotationMatrix,
		toSourceRect,
		type Rect
	} from '$lib/image-adjust';

	interface Props {
		file: File;
		onapply: (adjusted: File) => void;
		oncancel: () => void;
	}

	const { file, onapply, oncancel }: Props = $props();

	let canvas = $state<HTMLCanvasElement>();
	let bitmap: ImageBitmap | null = null;
	let turns = $state(0);
	// In display pixels, relative to the canvas.
	let crop = $state<Rect | null>(null);
	let anchor: { x: number; y: number } | null = null;
	let applying = $state(false);
	let failed = $state(false);
	let loaded = $state(false);

	let display = { width: 1, height: 1, scale: 1 };

	function draw() {
		if (!canvas || !bitmap) return;
		const rotated = rotatedSize(bitmap.width, bitmap.height, turns);
		const maxWidth = Math.min(640, (canvas.parentElement?.clientWidth ?? 640) - 8);
		display = fitSize(rotated.width, rotated.height, maxWidth, window.innerHeight * 0.5);
		canvas.width = display.width;
		canvas.height = display.height;
		const ctx = canvas.getContext('2d')!;
		ctx.setTransform(1, 0, 0, 1, 0, 0);
		ctx.fillStyle = 'white';
		ctx.fillRect(0, 0, display.width, display.height);
		const [a, b, c, d, e, f] = rotationMatrix(turns, bitmap.width, bitmap.height);
		const s = display.scale;
		ctx.setTransform(a * s, b * s, c * s, d * s, e * s, f * s);
		ctx.drawImage(bitmap, 0, 0);
		ctx.setTransform(1, 0, 0, 1, 0, 0);
		if (crop) {
			// Dim everything outside the selection.
			ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
			ctx.fillRect(0, 0, display.width, crop.y);
			ctx.fillRect(0, crop.y + crop.height, display.width, display.height - crop.y - crop.height);
			ctx.fillRect(0, crop.y, crop.x, crop.height);
			ctx.fillRect(crop.x + crop.width, crop.y, display.width - crop.x - crop.width, crop.height);
			ctx.strokeStyle = '#ffffff';
			ctx.lineWidth = 2;
			ctx.strokeRect(crop.x, crop.y, crop.width, crop.height);
		}
	}

	onMount(() => {
		let alive = true;
		createImageBitmap(file)
			.then((b) => {
				if (!alive) return b.close();
				bitmap = b;
				loaded = true;
				draw();
			})
			.catch(() => (failed = true));
		return () => {
			alive = false;
			bitmap?.close();
		};
	});

	$effect(() => {
		// Redraw on any change to the turn or the selection.
		void turns;
		void crop;
		draw();
	});

	function point(e: PointerEvent) {
		const box = canvas!.getBoundingClientRect();
		// The canvas can be shown at a different size than its pixel size.
		const sx = display.width / box.width;
		const sy = display.height / box.height;
		return { x: (e.clientX - box.left) * sx, y: (e.clientY - box.top) * sy };
	}

	function onpointerdown(e: PointerEvent) {
		anchor = point(e);
		canvas!.setPointerCapture(e.pointerId);
	}

	function onpointermove(e: PointerEvent) {
		if (!anchor) return;
		crop = dragRect(anchor, point(e), display);
	}

	function onpointerup() {
		anchor = null;
	}

	function rotate() {
		turns = (turns + 1) % 4;
		// A selection drawn on the old orientation means something else now.
		crop = null;
	}

	async function apply() {
		applying = true;
		try {
			const source = bitmap ? rotatedSize(bitmap.width, bitmap.height, turns) : null;
			const sourceCrop = crop && source ? toSourceRect(crop, display.scale, source) : null;
			onapply(await adjustImage(file, turns, sourceCrop));
		} catch (e) {
			console.error(e);
			failed = true;
			applying = false;
		}
	}
</script>

<div class="bg-canvas-subtle/30 flex flex-col items-center gap-3 rounded-lg p-3">
	<p class="text-fg-secondary text-center text-[13px]">{m.adjust_hint()}</p>
	{#if failed}
		<p role="alert" class="text-[13px] text-red-700 dark:text-red-300">
			{m.error_ocr_failed({ msg: file.name })}
		</p>
	{/if}
	<canvas
		bind:this={canvas}
		aria-label={m.adjust_canvas_label()}
		class="max-w-full touch-none rounded-md {loaded ? 'cursor-crosshair' : ''}"
		{onpointerdown}
		{onpointermove}
		{onpointerup}
		onpointercancel={onpointerup}
	></canvas>
	<div class="grid w-full grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:justify-center">
		<button type="button" class="btn-secondary min-h-11" onclick={rotate} disabled={!loaded}>
			{m.adjust_rotate()}
		</button>
		<button
			type="button"
			class="btn-secondary min-h-11"
			onclick={() => (crop = null)}
			disabled={!crop}
		>
			{m.adjust_clear_crop()}
		</button>
		<button type="button" class="btn-secondary min-h-11" onclick={oncancel}>
			{m.main_cancel()}
		</button>
		<button
			type="button"
			class="btn-primary min-h-11"
			onclick={apply}
			disabled={!loaded || applying}
		>
			{m.adjust_apply()}
		</button>
	</div>
</div>
