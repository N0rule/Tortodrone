import test from 'node:test';
import assert from 'node:assert/strict';
import { toCsv, toJson } from '../../../frontend/assets/components/export.js';

const columns = [
    ['Name', (row) => row.name],
    ['Count', (row) => row.count],
    ['Note', (row) => row.note]
];

test('CSV writes a header row and CRLF line endings', () => {
    const csv = toCsv([{ name: 'Aelric', count: 2, note: '' }], columns);
    assert.equal(csv, 'Name,Count,Note\r\nAelric,2,\r\n');
});

test('CSV quotes separators, quotes and newlines and doubles inner quotes', () => {
    const csv = toCsv([{ name: 'Knight, the "Brave"', count: 1, note: 'line\nbreak' }], columns);
    assert.ok(csv.includes('"Knight, the ""Brave"""'));
    assert.ok(csv.includes('"line\nbreak"'));
});

test('CSV neutralises spreadsheet formulas without dropping the value', () => {
    const csv = toCsv([{ name: '=1+1', count: 0, note: '@cmd' }], columns);
    assert.ok(csv.includes("'=1+1"));
    assert.ok(csv.includes("'@cmd"));
});

test('CSV renders missing values as empty cells', () => {
    assert.equal(toCsv([{ name: null, count: undefined, note: 0 }], columns), 'Name,Count,Note\r\n,,0\r\n');
});

test('JSON export keeps the visible column names and parsed values', () => {
    const rows = [{ name: 'Brannoc', count: 3, note: null }];
    assert.deepEqual(JSON.parse(toJson(rows, columns)), [
        { Name: 'Brannoc', Count: 3, Note: null }
    ]);
});
