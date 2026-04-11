import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const srcFile = path.resolve(__dirname, '../node_modules/pdfjs-dist/build/pdf.worker.min.mjs');
const destDir = path.resolve(__dirname, '../static');
const destFile = path.resolve(destDir, 'pdf.worker.min.mjs');

if (!fs.existsSync(destDir)) {
	fs.mkdirSync(destDir, { recursive: true });
}

try {
	if (fs.existsSync(srcFile)) {
		fs.copyFileSync(srcFile, destFile);
		console.log('[OK] PDF worker script copied to static/ successfully');
	} else {
		console.error(`Error: PDF worker script not found at ${srcFile}`);
		process.exit(1);
	}
} catch (err) {
	console.error('Error copying PDF worker script:', err);
	process.exit(1);
}
