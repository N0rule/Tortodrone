import test from 'node:test';
import assert from 'node:assert/strict';
import { filterRows } from '../../webui/assets/components/table.js';

const rows = [
    { id: 1, name: 'Aelric', state: 'Connected', knights: 2 },
    { id: 2, name: 'Brannoc', state: 'In scene', knights: 0 },
    { id: 3, name: 'Cynwulf', state: 'Connected', knights: 1 }
];

test('search is case-insensitive and covers every field', () => {
    assert.deepEqual(
        filterRows(rows, { query: 'cyn' }).map((row) => row.id),
        [3]
    );
    assert.deepEqual(
        filterRows(rows, { query: '2' }).map((row) => row.id),
        [1, 2],
        'raw field values match, including numeric ids'
    );
    assert.equal(filterRows(rows, { query: '  ' }).length, 3);
});

test('every filter choice must match the row', () => {
    assert.equal(filterRows(rows, { choices: [['state', () => true]] }).length, 3);
    assert.deepEqual(filterRows(rows, { choices: [['state', () => false]] }), []);
    assert.equal(filterRows(rows, { choices: [['a', () => true], ['b', () => false]] }).length, 0);
});

test('search and filters combine', () => {
    const choices = [['connected', (row) => row.state === 'Connected']];
    assert.deepEqual(
        filterRows(rows, { query: 'a', choices }).map((row) => row.id),
        [1, 3]
    );
});

test('a custom description keeps precomputed search text authoritative', () => {
    const described = rows.map((row) => ({ ...row, haystack: row.name.toUpperCase() }));
    assert.deepEqual(
        filterRows(described, {
            query: 'brannoc',
            describe: (row) => row.haystack
        }).map((row) => row.id),
        [2]
    );
});
