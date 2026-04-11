import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.join(__dirname, '../.env');

const REQUIRED_KEYS = [
    // Localization
    'GOOGLESHEET_PROJECT_ID',
    'GOOGLESHEET_PRIVATE_KEY',
    'GOOGLESHEET_CLIENT_EMAIL',
    
    // Shared S3/R2
    'R2_ACCOUNT_ID',
    'R2_ACCESS_KEY_ID',
    'R2_SECRET_ACCESS_KEY',
    'R2_BUCKET_NAME',
    
    // Backend
    'API_KEY',
    'PORT',
    'RATE_LIMIT_REQUESTS',
    'RATE_LIMIT_BURST'
];

console.log('🔍 Auditing environment configuration...');

if (!fs.existsSync(envPath)) {
    console.error('Error: .env file missing in root directory.');
    console.log('Copy .env.example to .env and fill in your credentials.');
    process.exit(1);
}

const HARD_REQUIREMENTS = [
  'CLOUDFLARE_R2_ACCESS_KEY_ID',
  'CLOUDFLARE_R2_SECRET_ACCESS_KEY',
  'CLOUDFLARE_ACCOUNT_ID',
  'GOOGLE_SHEETS_LOCALE_ID'
];

const SOFT_REQUIREMENTS = [
  'API_KEY',
  'PORT',
  'RATE_LIMIT_REQUESTS',
  'RATE_LIMIT_BURST'
];

console.log('🔍 Auditing environment configuration...');

const missingHard = HARD_REQUIREMENTS.filter(key => !process.env[key]);
const missingSoft = SOFT_REQUIREMENTS.filter(key => !process.env[key]);

if (missingHard.length > 0) {
  console.error('CRITICAL: Missing core environment variables (R2/Cloudflare):');
  missingHard.forEach(key => console.error(`   - ${key}`));
  process.exit(1);
}

if (missingSoft.length > 0) {
  console.warn('WARNING: Missing service-specific environment variables:');
  missingSoft.forEach(key => console.warn(`   - ${key}`));
  console.log('Operational context for local services may be limited.');
}

console.log('Audit complete. Environment configuration is acceptable for build/orchestration.');
