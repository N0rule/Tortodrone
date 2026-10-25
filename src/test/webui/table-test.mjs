import test from 'node:test';
import assert from 'node:assert/strict';
import { sortRows } from '../../webui/assets/components/table.js';

test('sorting evaluates cell content once per row and retains numeric text order', () => {
    const rows = ['Knight 10', 'Knight 2', 'Knight 1', 'Knight 2'].map((name, id) => ({ name, id }));
    let reads = 0;
    const result = sortRows(rows, (row) => {
        reads++;
        return { textContent: row.name };
    }, 1);
    assert.equal(reads, rows.length);
    assert.deepEqual(result.map((r) => r.id), [2, 1, 3, 0]);
    assert.deepEqual(rows.map((r) => r.id), [0, 1, 2, 3]);
});

test('numeric, descending, empty and equal sort keys keep existing semantics', () => {
    const rows = [{ value: 10 }, { value: -2 }, { value: 3 }];
    assert.deepEqual(sortRows(rows, (r) => r.value, -1), [rows[0], rows[2], rows[1]]);
    assert.deepEqual(sortRows(rows, () => null, 1), rows);
    assert.deepEqual(sortRows([], () => { throw new Error('No rows'); }, 1), []);
});
