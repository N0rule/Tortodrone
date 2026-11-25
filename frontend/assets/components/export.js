import { el, button, toolbar, download } from '../ui.js';

/** Values that a spreadsheet would otherwise reinterpret as formulas. */
const risky = /^[=+\-@\t\r]/;
const needsQuoting = /[",\n\r]/;

/**
 * Serialise rows into RFC 4180 CSV. Pure, so it is covered by node:test.
 * @param {Array<Record<string, any>>} rows
 * @param {Array<[string, (row: any) => any]>} columns
 * @returns {string}
 */
export function toCsv(rows, columns) {
    const cell = (value) => {
        if (value === null || value === undefined) return '';
        const text = String(value);
        const safe = risky.test(text) ? "'" + text : text;
        return needsQuoting.test(safe) ? '"' + safe.replaceAll('"', '""') + '"' : safe;
    };
    const lines = [columns.map(([name]) => cell(name)).join(',')];
    for (const row of rows) lines.push(columns.map(([, value]) => cell(value(row))).join(','));
    return lines.join('\r\n') + '\r\n';
}

/** Reduce a row set to plain JSON-safe records for export. */
export function toJson(rows, columns) {
    return JSON.stringify(
        rows.map((row) => Object.fromEntries(columns.map(([name, value]) => [name, value(row)]))),
        null,
        2
    );
}

/**
 * Download the given rows as CSV or JSON.
 * @param {string} name file stem, without extension
 * @param {Array<Record<string, any>>} rows
 * @param {Array<[string, (row: any) => any]>} columns
 * @param {'csv'|'json'} [format]
 */
export function exportRows(name, rows, columns, format = 'csv') {
    const stem = name.replace(/[^a-z0-9._-]+/gi, '-').replace(/^-+|-+$/g, '').toLowerCase();
    if (format === 'json') {
        download(`${stem}.json`, toJson(rows, columns), 'application/json');
        return rows.length;
    }
    download(`${stem}.csv`, toCsv(rows, columns), 'text/csv;charset=utf-8');
    return rows.length;
}

/**
 * Buttons that export exactly the rows currently visible after search and
 * filters. `describe` supplies the rows lazily so the control stays live.
 * @param {{name: string, rows: () => Array<any>, columns: Array<[string, (row: any) => any]>, onDone?: (count: number, format: string) => void}} options
 */
export function exportControls({ name, rows, columns, onDone }) {
    const run = (format) => () => {
        const current = rows();
        const count = exportRows(name, current, columns, format);
        onDone?.(count, format);
    };
    const csv = button('Export CSV', run('csv'), 'secondary');
    const json = button('Export JSON', run('json'), 'secondary');
    json.classList.add('optional-export');
    return toolbar(csv, json);
}
