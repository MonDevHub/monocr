/**
 * Which sync badge a history row shows, if any.
 *
 * Only a record the person consented to share is ever uploaded (sync-service
 * syncs `consentGiven` records and nothing else). A local scan therefore has no
 * sync state at all, and a spinning "Pending" badge on it promised an upload
 * that never comes, on an app whose claim is that nothing leaves the device.
 */
export interface SyncState {
	consentGiven: boolean;
	isSynced: boolean;
	syncError?: string | null;
}

export type SyncBadge = 'synced' | 'failed' | 'pending' | null;

export function syncBadge(record: SyncState): SyncBadge {
	if (!record.consentGiven) return null;
	if (record.isSynced) return 'synced';
	if (record.syncError) return 'failed';
	return 'pending';
}
