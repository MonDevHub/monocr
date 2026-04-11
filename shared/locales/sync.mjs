import { google } from 'googleapis';
import fs from 'fs/promises';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { config } from 'dotenv';

config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const ROOT = join(__dirname, '../..');

const SPREADSHEET_ID = '1sr8WtiMEyDuDd1amI-wzAz5d2acZlVC7zOZMqixOADQ';
const { GOOGLESHEET_PROJECT_ID, GOOGLESHEET_PRIVATE_KEY, GOOGLESHEET_CLIENT_EMAIL } = process.env;

const getGoogleSheetsClient = async () => {
    if (!GOOGLESHEET_PROJECT_ID || !GOOGLESHEET_PRIVATE_KEY || !GOOGLESHEET_CLIENT_EMAIL) {
        throw new Error('Missing Google Sheets credentials in environment variables. Check GOOGLESHEET_PROJECT_ID, GOOGLESHEET_PRIVATE_KEY, and GOOGLESHEET_CLIENT_EMAIL.');
    }
    const auth = new google.auth.GoogleAuth({
        scopes: 'https://www.googleapis.com/auth/spreadsheets.readonly',
        projectId: GOOGLESHEET_PROJECT_ID,
        credentials: {
            private_key: GOOGLESHEET_PRIVATE_KEY.replace(/\\n/g, '\n'),
            client_email: GOOGLESHEET_CLIENT_EMAIL
        }
    });
    const client = await auth.getClient();
    return google.sheets({ version: 'v4', auth: client });
};

const fetchTranslations = async (sheetsClient) => {
    const response = await sheetsClient.spreadsheets.values.get({
        spreadsheetId: SPREADSHEET_ID,
        range: 'translations!A:Z'
    });
    const values = response.data.values;
    if (!values || values.length === 0) throw new Error('No data found in sheet');

    const headers = values[0];
    const rows = values.slice(1);

    return rows.map(row => {
        const item = {};
        headers.forEach((header, i) => {
            item[header] = row[i] || '';
        });
        return item;
    });
};

const updateWeb = async (translations) => {
    const langs = ['en', 'mnw', 'my'];
    for (const lang of langs) {
        const data = {};
        translations.forEach(t => {
            if (!t.key) return;
            
            // Platform filtering
            if (t.platforms && !t.platforms.toLowerCase().includes('all') && !t.platforms.toLowerCase().includes('web')) {
                return;
            }

            data[t.key] = t[lang] || t['en'] || '';
        });
        const path = join(ROOT, `apps/web/messages/${lang}.json`);
        await fs.mkdir(dirname(path), { recursive: true });
        await fs.writeFile(path, JSON.stringify(data, null, 2), 'utf8');
        console.log(`[Web] [${lang}] updated`);
    }
};

const updateAndroid = async (translations) => {
    const langMap = {
        'en': 'values',
        'my': 'values-my',
        'mnw': 'values-mnw'
    };

    for (const [lang, folder] of Object.entries(langMap)) {
        const path = join(ROOT, `apps/android/app/src/main/res/${folder}/strings.xml`);
        let existingContent = '';
        try {
            existingContent = await fs.readFile(path, 'utf8');
        } catch (e) {
            existingContent = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>';
        }

        // Parse existing strings into a Map to preserve Android-specific resources
        const stringMap = new Map();
        const stringRegex = /<string name="([^"]+)">([\s\S]*?)<\/string>/g;
        let match;
        while ((match = stringRegex.exec(existingContent)) !== null) {
            stringMap.set(match[1], match[2]);
        }

        translations.forEach(t => {
            if (!t.key) return;
            
            // Platform filtering
            if (t.platforms && !t.platforms.toLowerCase().includes('all') && !t.platforms.toLowerCase().includes('android')) {
                return;
            }

            let val = t[lang] || t['en'] || '';
            if (val) {
                // Escape XML special characters for Android
                val = val
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/'/g, "\\'")
                    .replace(/"/g, '\\"')
                    .replace(/\n/g, '\\n');
                stringMap.set(t.key, val);
            }
        });

        let xml = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n';
        for (const [key, value] of stringMap.entries()) {
            xml += `    <string name="${key}">${value}</string>\n`;
        }
        xml += '</resources>';

        await fs.mkdir(dirname(path), { recursive: true });
        await fs.writeFile(path, xml, 'utf8');
        console.log(`[Android] [${lang}] updated (merged)`);
    }
};

const updateIOS = async (translations) => {
    const path = join(ROOT, 'apps/ios/monocr-ios/Localizable.xcstrings');
    let content;
    try {
        content = JSON.parse(await fs.readFile(path, 'utf8'));
    } catch (e) {
        content = { sourceLanguage: "en", strings: {}, version: "1.0" };
    }

    translations.forEach(t => {
        if (!t.key) return;

        // Platform filtering
        if (t.platforms && !t.platforms.toLowerCase().includes('all') && !t.platforms.toLowerCase().includes('ios')) {
            return;
        }

        if (!content.strings[t.key]) {
            content.strings[t.key] = { extractionState: "manual", localizations: {} };
        } else if (!content.strings[t.key].localizations) {
            content.strings[t.key].localizations = {};
        }
        
        ['en', 'mnw', 'my'].forEach(lang => {
            content.strings[t.key].localizations[lang] = {
                stringUnit: {
                    state: "translated",
                    value: t[lang] || t['en'] || ''
                }
            };
        });
    });

    await fs.writeFile(path, JSON.stringify(content, null, 2), 'utf8');
    console.log(`[iOS] strings updated`);
};

const run = async () => {
    console.log('Starting localization sync...');
    console.log(`Root directory: ${ROOT}`);
    try {
        const client = await getGoogleSheetsClient();
        console.log('Fetching translations from Google Sheets...');
        const translations = await fetchTranslations(client);
        console.log(`Fetched ${translations.length} translation keys.`);
        
        console.log('\nUpdating platforms...');
        await updateWeb(translations);
        await updateAndroid(translations);
        await updateIOS(translations);
        
        console.log('\nAll platforms synced successfully!');
    } catch (err) {
        console.error('\nSync failed:', err.message);
        process.exit(1);
    }
};

run();
