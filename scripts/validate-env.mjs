// Reports which service credentials are configured. Runs as predev/prebuild.
//
// This script blocked a clean clone from building until 2026-08-16, in three
// independent ways, and every one of them was invisible on the author's machine
// because an untracked .env and an exported shell happened to satisfy it:
//
//   1. It exited 1 when .env was absent. .env is gitignored, so a fresh clone
//      failed before anything compiled.
//   2. Its hard requirements were CLOUDFLARE_R2_ACCESS_KEY_ID,
//      CLOUDFLARE_ACCOUNT_ID and GOOGLE_SHEETS_LOCALE_ID — names that appear in
//      no template. .env.example defines R2_ACCESS_KEY_ID, R2_ACCOUNT_ID and
//      GOOGLESHEET_*. Following the documented `cp .env.example .env` still
//      failed, naming variables the reader could not find anywhere.
//   3. It read process.env without ever loading the .env whose existence it had
//      just demanded, so even a correct file was ignored unless the values were
//      also exported in the shell.
//
// It also gated the *web* build on R2 and Google Sheets credentials. The web app
// runs OCR in the browser against a model fetched from Hugging Face; it needs
// none of them. A build gate should fail on what the build actually needs.
//
// So: report, do not block. `--strict` restores exit-on-missing for deploy
// pipelines, where a missing credential should stop the release.

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.join(__dirname, '../.env');
const strict = process.argv.includes('--strict');

// Names as they appear in .env.example. Keeping these in step with that file is
// the whole job — a validator checking names no template defines is worse than
// no validator, because its failure message sends the reader looking for
// something that does not exist.
const GROUPS = {
  'Cloudflare R2 (feedback and contribution uploads)': [
    'R2_ACCOUNT_ID',
    'R2_ACCESS_KEY_ID',
    'R2_SECRET_ACCESS_KEY',
    'R2_BUCKET_NAME',
  ],
  'Google Sheets (localisation bridge)': [
    'GOOGLESHEET_PROJECT_ID',
    'GOOGLESHEET_PRIVATE_KEY',
    'GOOGLESHEET_CLIENT_EMAIL',
  ],
  'Feedback service': ['API_KEY', 'PORT', 'RATE_LIMIT_REQUESTS', 'RATE_LIMIT_BURST'],
};

/** Minimal .env reader — no dependency, and the file is ours. */
function readEnvFile(file) {
  if (!fs.existsSync(file)) return {};
  const out = {};
  for (const raw of fs.readFileSync(file, 'utf-8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

const fromFile = readEnvFile(envPath);
const resolved = (key) => process.env[key] || fromFile[key] || '';

console.log('Checking environment configuration');
console.log(
  fs.existsSync(envPath)
    ? `  .env found at ${path.relative(process.cwd(), envPath)}`
    : '  no .env (fine for the web app — it runs OCR in the browser)'
);

const missing = [];
for (const [group, keys] of Object.entries(GROUPS)) {
  const absent = keys.filter((k) => !resolved(k));
  const status = absent.length === 0 ? 'configured' : `${absent.length}/${keys.length} missing`;
  console.log(`  ${group}: ${status}`);
  if (absent.length) {
    absent.forEach((k) => console.log(`      - ${k}`));
    missing.push(...absent);
  }
}

if (missing.length === 0) {
  console.log('All service credentials are present.');
} else if (strict) {
  console.error(`Missing ${missing.length} variable(s) and --strict was passed.`);
  console.error('Copy .env.example to .env and fill them in.');
  process.exit(1);
} else {
  console.log(
    `${missing.length} variable(s) unset. The web app builds and runs without them; ` +
      'the feedback service and the localisation bridge will not.'
  );
}
