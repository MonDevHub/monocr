import fs from 'fs/promises';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Assumes parseGoogleSheets.mjs outputs to 'src/lib/data/translations.json'
const INPUT_JSON = join(__dirname, '..', 'src/lib/data', 'translations.json');
const MESSAGES_DIR = join(__dirname, '..', 'messages');

const languages = ['en', 'my', 'mnw'];

const extractTranslation = (row, lang) => {
	// Support both "en" and "value_en" format based on your earlier prompt
	if (row[`value_${lang}`] !== undefined) return String(row[`value_${lang}`]);
	if (row[lang] !== undefined) return String(row[lang]);

	// Support fallback for spreadsheet legacy 'mm' column instead of 'my'
	if (lang === 'my') {
		if (row['value_mm'] !== undefined) return String(row['value_mm']);
		if (row['mm'] !== undefined) return String(row['mm']);
	}

	return '';
};

const run = async () => {
	try {
		const data = await fs.readFile(INPUT_JSON, 'utf8');
		const rows = JSON.parse(data);

		if (!Array.isArray(rows)) {
			throw new Error('Expected JSON input from Google Sheets to be an array of objects.');
		}

		if (rows.length === 0) {
			throw new Error('Translation data is empty. Aborting to prevent accidental wipe of locales.');
		}

		const dictionaries = { en: {}, my: {}, mnw: {} };
		let count = 0;

		for (const row of rows) {
			const key = row.key || row.unique_key; // Support "key" or "unique_key"
			if (!key) continue;

			for (const lang of languages) {
				dictionaries[lang][key] = extractTranslation(row, lang);
			}
			count++;
		}

		await fs.mkdir(MESSAGES_DIR, { recursive: true });

		for (const lang of languages) {
			const filePath = join(MESSAGES_DIR, `${lang}.json`);
			await fs.writeFile(filePath, JSON.stringify(dictionaries[lang], null, '\t') + '\n', 'utf8');
			console.log(`Updated ${filePath}`);
		}

		// Clean up the temporary JSON file after successful processing
		try {
			await fs.unlink(INPUT_JSON);
			console.log(`Cleaned up temporary file: ${INPUT_JSON}`);
		} catch (cleanupError) {
			console.error(`Failed to clean up temporary file: ${cleanupError.message}`);
		}

		console.log(`Successfully processed ${count} translation keys into locale maps.`);
	} catch (e) {
		if (e.code === 'ENOENT') {
			console.error(`Input file not found: ${INPUT_JSON}`);
			console.error(
				`Make sure to update parseGoogleSheets.mjs to download your translations tab and save it as translations.json, then run 'pnpm run parse:gs' first.`
			);
		} else {
			console.error('Error parsing locale files:', e.message);
		}
	}
};

run();
