import test from 'node:test';
import assert from 'node:assert/strict';
import { read, schema, write } from '../../../frontend/assets/prefs.js';

test('unknown stored values fall back to the documented default', () => {
    for (const [key, values] of Object.entries(schema)) {
        assert.equal(read(key), values[0], 'Default for ' + key);
    }
});

test('numeric preferences return numbers, not strings', () => {
    assert.equal(typeof read('pageSize'), 'number');
    assert.equal(typeof read('pollScale'), 'number');
});

test('writing without browser storage still applies coercion', () => {
    assert.equal(write('pageSize', 999), 30);
    assert.equal(write('pollScale', '2'), 2);
    assert.equal(write('theme', 'chartreuse'), 'tortodrone');
    assert.equal(write('theme', 'dark'), 'tortodrone');
    assert.equal(write('theme', 'light'), 'catppuccin');
    assert.equal(write('clock', 'nonsense'), 'date');
    assert.equal(write('motion', 'reduce'), 'reduce');
});

test('the schema stays the single source of defaults', () => {
    assert.deepEqual(Object.keys(schema), [
        'theme',
        'pageSize',
        'pollScale',
        'clock',
        'motion'
    ]);
    assert.equal(schema.pageSize[0], 30);
});
