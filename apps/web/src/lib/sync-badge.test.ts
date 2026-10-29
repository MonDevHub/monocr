import { describe, expect, it } from 'vitest';
import { syncBadge } from './sync-badge';

describe('syncBadge', () => {
	it('shows nothing for a scan that was never shared, whatever its flags', () => {
		expect(syncBadge({ consentGiven: false, isSynced: false })).toBeNull();
		expect(syncBadge({ consentGiven: false, isSynced: false, syncError: 'x' })).toBeNull();
		expect(syncBadge({ consentGiven: false, isSynced: true })).toBeNull();
	});

	it('shows pending, failed and synced for a shared record', () => {
		expect(syncBadge({ consentGiven: true, isSynced: false })).toBe('pending');
		expect(syncBadge({ consentGiven: true, isSynced: false, syncError: 'boom' })).toBe('failed');
		expect(syncBadge({ consentGiven: true, isSynced: true })).toBe('synced');
	});
});
