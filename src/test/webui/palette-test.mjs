import test from 'node:test';
import assert from 'node:assert/strict';
import { rank, score } from '../../webui/assets/components/palette.js';

test('an empty query keeps every command available', () => {
    assert.equal(score('Knights', ''), 1);
    assert.equal(score('Knights', '   '), 1);
});

test('subsequence matching rejects misses and stays order-sensitive', () => {
    assert.equal(score('Knights', 'zz'), 0);
    assert.equal(score('Knights', 'gk'), 0, 'characters must appear in query order');
    assert.ok(score('Knights', 'kni') > 0);
    assert.ok(score('Knights', 'kgh') > 0);
});

test('prefix and word-start matches outrank scattered ones', () => {
    assert.ok(score('Uplink News', 'upl') > score('Uplink News', 'upn'));
    assert.ok(score('Auction House', 'ho') > score('Auction House', 'ou'));
});

test('a shorter candidate wins when the match quality is the same', () => {
    assert.ok(score('Logs', 'lo') > score('Player Mail Logs', 'lo'));
});

test('ranking filters misses and sorts by descending score', () => {
    const entries = [
        { title: 'Accounts', hint: 'People' },
        { title: 'Knights', hint: 'People' },
        { title: 'Console', hint: 'Operations' }
    ];
    assert.deepEqual(
        rank(entries, 'kni').map((entry) => entry.title),
        ['Knights']
    );
    assert.deepEqual(
        rank(entries, '').map((entry) => entry.title),
        ['Accounts', 'Knights', 'Console']
    );
    assert.deepEqual(rank(entries, 'zzz'), []);
});

test('descriptions outside the title still match through the describe hook', () => {
    const entries = [{ title: 'Depot', hint: 'supply depot offers' }];
    assert.equal(rank(entries, 'offers', (entry) => entry.hint).length, 1);
    assert.equal(rank(entries, 'offers').length, 1);
});
