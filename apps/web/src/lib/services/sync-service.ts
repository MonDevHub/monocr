import { db, type OCRRecord } from '$lib/storage/db';
import { CONFIG } from '$lib/config';

// Simple delay utility
const delay = (ms: number) => new Promise((res) => setTimeout(res, ms));

export const syncEvents = new EventTarget();

export class SyncService {
	private isSyncing = false;

	/**
	 * Starts the sync process. Should be called when the app initializes
	 * and whenever the network status changes to 'online'.
	 */
	async start() {
		if (typeof window === 'undefined') return;

		// Initial sync
		if (navigator.onLine) {
			this.syncAll();
		}

		// Listen for online events
		window.addEventListener('online', () => {
			this.syncAll();
		});

		// Periodic background sweep
		window.setInterval(
			() => {
				if (navigator.onLine) {
					this.syncAll();
				}
			},
			5 * 60 * 1000
		); // 5 minutes
	}

	/**
	 * Iterates through all unsynced records and attempts to upload them.
	 */
	async syncAll() {
		if (this.isSyncing || !db) return;
		this.isSyncing = true;

		try {
			// Find records that are NOT synced
			// Browsers reject IDBKeyRange with boolean values, so we filter over the cursor
			const unsyncedRecords = await db.records.filter((record) => !record.isSynced).toArray();

			for (let i = 0; i < unsyncedRecords.length; i++) {
				const record = unsyncedRecords[i];

				// POLICY: Only sync records that are explicitly feedbacks or contributions
				// AND have user consent given.
				const isAllowedCategory = ['feedback', 'contribution'].includes(record.category);

				if (isAllowedCategory && record.consentGiven && record.syncAttempts < 5) {
					await this.syncRecord(record);

					// Pacing: Add small delay between uploads to avoid bursting bandwidth
					if (i < unsyncedRecords.length - 1) {
						await delay(CONFIG.SYNC.BATCH_DELAY_MS);
					}
				}
			}
		} catch (err) {
			console.error('Background Sync Error:', err);
		} finally {
			this.isSyncing = false;
		}
	}

	/**
	 * Extracts the common upload logic to an R2 presigned URL
	 */
	private async uploadToR2(fileName: string, fileType: string, recordId: string, data: Blob) {
		const signUrl = `/api/sync/sign?fileName=${encodeURIComponent(fileName)}&fileType=${encodeURIComponent(fileType)}&recordId=${recordId}`;

		let lastError: Error | null = null;
		const maxRetries = 3;

		for (let attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				const signRes = await fetch(signUrl);

				if (!signRes.ok) {
					const errorText = await signRes.text();
					throw new Error(`Sign API failed (${signRes.status}): ${errorText}`);
				}

				const { uploadUrl } = await signRes.json();

				const uploadRes = await fetch(uploadUrl, {
					method: 'PUT',
					body: data,
					headers: {
						'Content-Type': fileType
					}
				});

				if (!uploadRes.ok) {
					throw new Error(`R2 Upload failed for ${fileName}: ${uploadRes.statusText}`);
				}

				return; // Success
			} catch (err) {
				lastError = err instanceof Error ? err : new Error(String(err));
				if (attempt < maxRetries) {
					const backoffMs = attempt * 1500; // 1.5s, 3.0s
					console.warn(
						`[Sync] Upload attempt ${attempt} failed for ${fileName}, retrying in ${backoffMs}ms...`
					);
					await new Promise((res) => window.setTimeout(res, backoffMs));
				}
			}
		}

		throw lastError || new Error('Upload failed after multiple attempts');
	}

	/**
	 * Manages the upload of a single record using a Signed URL.
	 */
	private async syncRecord(record: OCRRecord) {
		const typeLabel = record.fileType || 'unknown';
		console.info(`[Sync] Attempting ${record.fileName} (${typeLabel})...`);

		try {
			// 1. Upload the primary file data
			await this.uploadToR2(record.fileName, record.fileType, record.id, record.fileData);

			// 2. If it's a file upload AND there's an attached attached text transcription, upload text alongside it
			// We check if it's explicitly NOT just the fallback plain text submission
			const isJustTextBlob = record.fileName === 'Mon Text Contribution';
			if (!isJustTextBlob && record.text && record.text.trim().length > 0) {
				const textFileName = `${record.fileName.replace(/\.[^/.]+$/, '')}-transcription.txt`;
				const textBlob = new Blob([record.text], { type: 'text/plain' });
				await this.uploadToR2(textFileName, 'text/plain', record.id, textBlob);
			}

			// 3. Mark as synced in IndexedDB
			if (db) {
				await db.records.update(record.id, {
					isSynced: true,
					syncError: null,
					syncAttempts: record.syncAttempts + 1
				});
			}

			syncEvents.dispatchEvent(new Event('synced'));
			console.info(`✓ Successfully synced: ${record.fileName}`);
		} catch (err: unknown) {
			const message = err instanceof Error ? err.message : String(err);
			console.error(`✗ Sync failed for ${record.fileName}:`, message);
			if (db) {
				await db.records.update(record.id, {
					syncError: message || 'Unknown error during sync',
					syncAttempts: record.syncAttempts + 1
				});
			}
		}
	}
}

export const syncService = new SyncService();
