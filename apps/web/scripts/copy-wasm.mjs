import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const srcDir = path.resolve(__dirname, '../node_modules/onnxruntime-web/dist');
const destDir = path.resolve(__dirname, '../static/wasm');

if (!fs.existsSync(destDir)) {
	fs.mkdirSync(destDir, { recursive: true });
}

// Copy .wasm files
try {
	const files = fs
		.readdirSync(srcDir)
		.filter(
			(file) => (file.endsWith('.wasm') || file.endsWith('.mjs')) && !file.includes('asyncify')
		);

	if (files.length === 0) {
		console.warn('No .wasm files found in onnxruntime-web/dist');
	}

	files.forEach((file) => {
		const srcFile = path.join(srcDir, file);
		const destFile = path.join(destDir, file);
		fs.copyFileSync(srcFile, destFile);
		console.log(`Copied ${file} to static/wasm/`);
	});
	console.log('[OK] WASM assets copied successfully');
} catch (err) {
	console.error('Error copying WASM assets:', err);
	process.exit(1);
}
