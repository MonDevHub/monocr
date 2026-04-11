import { google } from 'googleapis';
import fs from 'fs/promises';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { config } from 'dotenv';

config();

// current file's directory for relative path operations
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// list of Google Sheets + tabs to fetch
const spreadsheetConfigs = [
	{
		id: '1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ', // ID
		folder: 'src/lib/data', // output folder
		tabs: ['translations'] // tab names
		// startRow: 0, // optional
		// endRow: 10   // optional
	}
];

const { GOOGLESHEET_PROJECT_ID, GOOGLESHEET_PRIVATE_KEY, GOOGLESHEET_CLIENT_EMAIL } = process.env;

const getGoogleSheetsClient = async () => {
	const auth = new google.auth.GoogleAuth({
		scopes: 'https://www.googleapis.com/auth/spreadsheets.readonly',
		projectId: GOOGLESHEET_PROJECT_ID,
		credentials: {
			private_key: GOOGLESHEET_PRIVATE_KEY,
			client_email: GOOGLESHEET_CLIENT_EMAIL
		}
	});
	const client = await auth.getClient();
	return google.sheets({ version: 'v4', auth: client });
};

// convert raw sheet  values into an array of objects using the first row as headers
const parseSheetData = (values, startRow, endRow) => {
	const headers = values[0]; // extract headers
	let rows = values.slice(1); // exclude header row

	// filter out start row and end row if specified
	if (startRow !== undefined) rows = rows.slice(startRow - 1);
	if (endRow !== undefined) rows = rows.slice(0, endRow - (startRow || 1) + 1);

	// map each row to an object using headers as keys
	return rows.map((row) => {
		const rowData = {};
		headers.forEach((header, i) => {
			rowData[header] = row[i] || '';
		});
		return rowData;
	});
};

const saveJSON = async (data, folder, filename) => {
	const dirPath = join(__dirname, '..', folder);
	const filePath = join(dirPath, `${filename}.json`);
	await fs.mkdir(dirPath, { recursive: true });
	await fs.writeFile(filePath, JSON.stringify(data, null, 2), 'utf8');
	console.log(`Saved: ${filePath}`);
};

const fetchAndSaveSheet = async (sheetsClient, { id, folder, tabs, startRow, endRow }) => {
	// get spreadsheet metadata to list available sheets
	const spreadsheet = await sheetsClient.spreadsheets.get({ spreadsheetId: id });
	const availableSheets = spreadsheet.data.sheets.map((s) => s.properties.title);

	// loop through each sheet/tab in the spreadsheet
	for (const sheetName of availableSheets) {
		// skip sheets not listed in 'tabs' config
		if (tabs && !tabs.includes(sheetName)) continue;

		const response = await sheetsClient.spreadsheets.values.get({
			spreadsheetId: id,
			range: `${sheetName}!A:Z`
		});

		const values = response.data.values;
		if (!values || values.length === 0) {
			console.log(`⚠️ No data in sheet: ${sheetName}`);
			continue;
		}

		const parsedData = parseSheetData(values, startRow, endRow);
		const filename = sheetName.trim().replace(/\s+/g, '_').toLowerCase();
		await saveJSON(parsedData, folder, filename);
	}
};

const run = async () => {
	const sheetsClient = await getGoogleSheetsClient();
	for (const config of spreadsheetConfigs) {
		await fetchAndSaveSheet(sheetsClient, config);
	}
};

run().catch((err) => console.error('Error:', err.message));
