import test from 'node:test';
import assert from 'node:assert/strict';
import { mailingStatus, mailingItems } from '../../../frontend/assets/pages/mailing.js';

test('mailing status follows the delivery window and cancellation', () => {
    const now = 1_000_000;
    assert.equal(
        mailingStatus({ enabled: true, starts: now + 1, ends: now + 100 }, now),
        'Scheduled'
    );
    assert.equal(mailingStatus({ enabled: true, starts: now, ends: now + 100 }, now), 'Active');
    assert.equal(mailingStatus({ enabled: true, starts: now - 100, ends: now }, now), 'Ended');
    assert.equal(
        mailingStatus({ enabled: false, starts: now - 100, ends: now + 100 }, now),
        'Cancelled'
    );
});

test('mailing item serialization keeps counts and bound without mutating the draft', () => {
    const gifts = [
        { config: 'Material/Gel Drop', count: 2, bound: true },
        { config: 'Weapon/Sword/Calibur', count: 1, bound: false }
    ];
    const before = structuredClone(gifts);
    assert.equal(
        mailingItems(gifts),
        '[{"config":"Material/Gel Drop","count":2,"bound":true},{"config":"Weapon/Sword/Calibur","count":1,"bound":false}]'
    );
    assert.deepEqual(gifts, before, 'saving must not rewrite the open draft');
});
