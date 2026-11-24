import test from 'node:test';
import assert from 'node:assert/strict';
import { sortRows, filterRows } from '../../webui/assets/components/table.js';

test('sorting evaluates cell content once per row and retains numeric text order', () => {
    const rows = ['Knight 10', 'Knight 2', 'Knight 1', 'Knight 2'].map((name, id) => ({
        name,
        id
    }));
    let reads = 0;
    const result = sortRows(
        rows,
        (row) => {
            reads++;
            return { textContent: row.name };
        },
        1
    );
    assert.equal(reads, rows.length);
    assert.deepEqual(
        result.map((r) => r.id),
        [2, 1, 3, 0]
    );
    assert.deepEqual(
        rows.map((r) => r.id),
        [0, 1, 2, 3]
    );
});

test('numeric, descending, empty and equal sort keys keep existing semantics', () => {
    const rows = [{ value: 10 }, { value: -2 }, { value: 3 }];
    assert.deepEqual(
        sortRows(rows, (r) => r.value, -1),
        [rows[0], rows[2], rows[1]]
    );
    assert.deepEqual(
        sortRows(rows, () => null, 1),
        rows
    );
    assert.deepEqual(
        sortRows(
            [],
            () => {
                throw new Error('No rows');
            },
            1
        ),
        []
    );
});

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
