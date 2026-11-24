import { el, button, input, select, toolbar, text } from '../ui.js';
import { emptyState } from './states.js';
import { exportControls } from './export.js';
import { read, write, schema } from '../prefs.js';

const collator = new Intl.Collator(undefined, { numeric: true });
/** Evaluate renderers once per row; sorting must not construct cells for every comparison. */
export function sortRows(rows, value, direction) {
    return rows
        .map((row) => {
            const result = value(row);
            return { row, value: result?.textContent ?? result ?? '' };
        })
        .sort(
            (a, b) =>
                direction *
                (typeof a.value === 'number' && typeof b.value === 'number'
                    ? a.value - b.value
                    : collator.compare(String(a.value), String(b.value)))
        )
        .map(({ row }) => row);
}

/**
 * Apply the search text and filter selects to a row set. Exported so the
 * filtering contract is covered without a DOM.
 * @param {Array<Record<string, any>>} rows
 * @param {{query?: string, choices?: Array<[string, (row: any) => boolean]>, describe?: (row: any) => string}} state
 */
export function filterRows(rows, { query = '', choices = [], describe = (row) => JSON.stringify(row) }) {
    const needle = query.trim().toLowerCase();
    return rows.filter(
        (row) =>
            (!needle || describe(row).toLowerCase().includes(needle)) &&
            choices.every(([, match]) => match(row))
    );
}

function storageKey(id) {
    return 'tortodrone-table-' + id;
}

/**
 * An empty list needs its own sentence: one shared line is wrong somewhere.
 * A string is read as the supporting sentence, an object as {title, hint, action}.
 * @param {string|{title?: string, hint?: string, icon?: string,
 *     action?: {label: string, run: () => void}}|undefined} value
 */
function emptySpec(value) {
    if (!value) return null;
    if (typeof value === 'string') return { title: 'Nothing to show yet', hint: value };
    return value;
}

function loadState(id) {
    if (!id) return null;
    try {
        return JSON.parse(localStorage.getItem(storageKey(id)) || 'null');
    } catch {
        return null;
    }
}

function saveState(id, state) {
    if (!id) return;
    try {
        localStorage.setItem(storageKey(id), JSON.stringify(state));
    } catch {
        /* Storage is optional; the table still works for this session. */
    }
}

/**
 * Keyed rows and persistent controls. Updating data never replaces the table or
 * its inputs. Every table adds a caption, a scrollable labelled region, a live
 * result count, optional row selection with bulk actions, and CSV/JSON export of
 * exactly the filtered rows. Pass `empty` for a specific sentence about why this
 * list is empty and what creates its records.
 */
export class DataTable {
    constructor(
        columns,
        {
            key = (row) => row.id,
            actions,
            filters = [],
            pageSize,
            search = true,
            presentationKey = () => '',
            id,
            caption,
            selectable = false,
            bulkActions,
            exportName = id,
            persist = true,
            initialQuery = '',
            empty,
            onRefresh,
        } = {}
    ) {
        Object.assign(this, {
            columns,
            key,
            actions,
            filters,
            presentationKey,
            id: persist ? id : undefined,
            caption,
            selectable: selectable && !!bulkActions,
            bulkActions,
            onRefresh,
            exportName: exportName || caption || 'records',
            emptyText: emptySpec(empty)
        });
        this.describe = new Map();
        this.rows = [];
        this.cache = new Map();
        this.selected = new Set();
        this.page = 1;
        this.pageSize =
            Number.isFinite(Number(pageSize)) && Number(pageSize) > 0
                ? Math.floor(Number(pageSize))
                : read('pageSize');
        this.sort = -1;
        this.direction = 1;
        this.node = el('div', null, 'data-table');

        const saved = persist ? loadState(this.id) : null;
        if (saved) {
            if (Number.isInteger(saved.sort) && saved.sort < columns.length) this.sort = saved.sort;
            if (saved.direction === -1 || saved.direction === 1) this.direction = saved.direction;
            if (schema.pageSize.includes(saved.pageSize)) this.pageSize = saved.pageSize;
        }

        this.query = input('Search records');
        this.query.type = 'search';
        this.query.placeholder = 'Search records';
        if (initialQuery) this.query.value = initialQuery;
        this.controls = toolbar();
        this.controls.classList.add('table-tools');
        if (search) this.controls.append(this.query);
        this.filtersByName = {};
        this.filtersInitial = {};
        this.choices = filters.map(([name, values, match, initial]) => {
            const savedValue = saved?.filters?.[name];
            const start = values.includes(savedValue) ? savedValue : (initial ?? values[0]);
            const control = select(name, values, start);
            control.dataset.filter = name;
            control.onchange = () => {
                this.page = 1;
                this.remember();
                this.render();
            };
            this.controls.append(control);
            this.filtersByName[name] = control;
            this.filtersInitial[name] = start;
            return [control, name, match];
        });
        this.controls.append(el('span', null, 'spacer'));
        // One shared exporter keeps the download format in a single place.
        const exporter = exportControls({
            name: this.exportName,
            rows: () => this.filteredRows(),
            columns: this.exportColumns(),
            onDone: (count, format) =>
                text(
                    this.count,
                    `${count} ${count === 1 ? 'record' : 'records'} exported as ${format.toUpperCase()}`
                )
        });
        this.controls.append(...exporter.children);
        if (!search && !filters.length) this.controls.classList.add('table-tools-plain');

        this.wrap = el('div', null, 'table-wrap');
        this.wrap.tabIndex = 0;
        this.wrap.setAttribute('role', 'region');
        this.wrap.setAttribute('aria-label', (caption || 'Records') + ' table, scrollable');
        this.table = el('table');
        this.table.dataset.columns = String(columns.length);
        if (caption) {
            const captionNode = el('caption', caption, 'visually-hidden');
            this.table.append(captionNode);
        }
        const head = el('thead'),
            row = el('tr');
        if (this.selectable) {
            const cell = el('th', null, 'select-column');
            cell.scope = 'col';
            this.all = el('input');
            this.all.type = 'checkbox';
            this.all.setAttribute('aria-label', 'Select every record on this page');
            this.all.onchange = () => this.selectPage(this.all.checked);
            // The label is the hit area, so the control keeps a 24px target
            // without drawing a heavy checkbox.
            const allLabel = el('label', null, 'select-target');
            allLabel.append(this.all);
            cell.append(allLabel);
            row.append(cell);
        }
        columns.forEach(([name], index) => {
            const cell = el('th');
            cell.scope = 'col';
            const label = button(
                name,
                () => {
                    this.direction = this.sort === index ? -this.direction : 1;
                    this.sort = index;
                    for (const th of head.querySelectorAll('th')) th.removeAttribute('aria-sort');
                    cell.setAttribute(
                        'aria-sort',
                        this.direction === 1 ? 'ascending' : 'descending'
                    );
                    if (this.sortSelect) this.sortSelect.value = String(this.sort);
                    this.remember();
                    this.render();
                },
                'quiet'
            );
            label.title = 'Sort by ' + name;
            cell.append(label);
            if (this.sort === index)
                cell.setAttribute(
                    'aria-sort',
                    this.direction === 1 ? 'ascending' : 'descending'
                );
            row.append(cell);
        });
        if (actions) {
            const cell = el('th', 'Actions');
            cell.scope = 'col';
            row.append(cell);
        }
        head.append(row);
        this.body = el('tbody');
        this.table.append(head, this.body);
        this.wrap.append(this.table);

        this.bulk = el('div', null, 'bulk-bar');
        this.bulk.setAttribute('role', 'status');
        this.bulk.hidden = true;
        this.bulkCount = el('span');
        const clear = button('Clear selection', () => this.clearSelection(), 'quiet');
        this.bulk.append(this.bulkCount, clear);
        if (this.bulkActions) {
            const start = this.bulkActions(() => this.selectedRows());
            this.bulk.append(toolbar(...start));
            this.bulkButtons = start;
        }

        this.count = el('span', null, 'table-count');
        this.count.setAttribute('role', 'status');
        this.count.setAttribute('aria-live', 'polite');
        const sizes = schema.pageSize.includes(this.pageSize)
            ? schema.pageSize
            : [...schema.pageSize, this.pageSize].sort((a, b) => a - b);
        this.size = select(
            'Rows per page',
            sizes.map((size) => [size, size + ' per page']),
            this.pageSize
        );
        this.size.onchange = () => {
            this.pageSize = Number(this.size.value);
            this.page = 1;
            write('pageSize', this.pageSize);
            this.render();
        };
        this.previous = button('Previous', () => {
            this.page--;
            this.render();
        });
        this.next = button('Next', () => {
            this.page++;
            this.render();
        });
        this.sortSelect = select(
            'Sort column',
            [
                ['-1', 'Default order'],
                ...this.columns.map(([name], index) => [String(index), name])
            ],
            String(this.sort)
        );
        this.sortSelect.classList.add('sort-select');
        this.sortSelect.onchange = () => {
            const index = Number(this.sortSelect.value);
            if (index < 0) {
                this.sort = -1;
                this.direction = 1;
            } else if (index === this.sort) {
                this.direction = -this.direction;
            } else {
                this.sort = index;
                this.direction = 1;
            }
            const header = this.table.querySelectorAll('thead th')[index + (this.selectable ? 1 : 0)];
            for (const th of this.table.querySelectorAll('thead th')) th.removeAttribute('aria-sort');
            if (this.sort >= 0)
                header?.setAttribute(
                    'aria-sort',
                    this.direction === 1 ? 'ascending' : 'descending'
                );
            this.remember();
            this.render();
        };
        this.sortSelect.setAttribute('aria-label', 'Sort by column');
        const paging = el('div', null, 'pagination');
        paging.append(this.count, this.previous, this.next, this.size, this.sortSelect);
        this.node.append(this.controls, this.bulk, this.wrap, paging);

        if (search) {
            let timer;
            this.query.oninput = () => {
                clearTimeout(timer);
                timer = setTimeout(() => {
                    this.page = 1;
                    this.render();
                }, 140);
            };
            this.query.onkeydown = (event) => {
                if (event.key === 'Escape' && this.query.value) {
                    event.stopPropagation();
                    this.query.value = '';
                    this.page = 1;
                    this.render();
                }
            };
        }
    }

    remember() {
        saveState(this.id, {
            sort: this.sort,
            direction: this.direction,
            pageSize: this.pageSize,
            filters: Object.fromEntries(
                Object.entries(this.filtersByName ?? {}).map(([name, control]) => [
                    name,
                    control.value
                ])
            )
        });
    }

    describeRow(row) {
        let description = this.describe.get(row);
        if (description === undefined) {
            description = JSON.stringify(row);
            this.describe.set(row, description);
        }
        return description;
    }

    update(rows) {
        this.rows = rows;
        this.describe = new Map();
        this.render();
    }

    /**
     * Deep links drive the same controls the operator would, so page state has a
     * single owner and the URL never disagrees with the list. An empty value
     * clears the control, which is how a stale filter stops surviving a visit.
     */
    setQuery(value) {
        if (!this.query) return;
        const next = String(value ?? '');
        if (this.query.value === next) return;
        this.query.value = next;
        this.page = 1;
        this.render();
    }

    /** Set a named filter when the value is one of its options; '' resets it. */
    setFilter(name, value) {
        const control = this.filtersByName?.[name];
        if (!control) return;
        const next = value == null || value === '' ? (this.filtersInitial[name] ?? 'All') : String(value);
        if (![...control.options].some((option) => option.value === next)) return;
        if (control.value === next) return;
        control.value = next;
        this.page = 1;
        this.remember();
        this.render();
    }

    /** Reset the search and every filter to the view the table opened with. */
    clearFilters() {
        if (this.query) this.query.value = '';
        for (const [control, name] of this.choices) {
            control.value = this.filtersInitial[name] ?? control.options[0].value;
        }
        this.page = 1;
        this.render();
    }

    /** Rows after search, filters and sorting, across every page. */
    filteredRows() {
        let rows = filterRows(this.rows, {
            query: this.query?.value ?? '',
            choices: this.choices.map(([control, , match]) => [control.value, (row) => match(row, control.value)]),
            describe: (row) => this.describeRow(row)
        });
        if (this.sort >= 0) {
            const fn = this.columns[this.sort][2] || this.columns[this.sort][1];
            rows = sortRows(rows, fn, this.direction);
        }
        return rows;
    }

    exportColumns() {
        return this.columns.map(([name, render, sortValue]) => [
            name,
            (row) => {
                const value = (sortValue || render)(row);
                return value?.textContent ?? (value instanceof Node ? '' : value);
            }
        ]);
    }

    selectPage(checked) {
        for (const row of this.pageRows ?? []) {
            const key = String(this.key(row));
            if (checked) this.selected.add(key);
            else this.selected.delete(key);
        }
        this.render();
    }

    clearSelection() {
        this.selected.clear();
        this.render();
    }

    selectedRows() {
        const wanted = new Set(this.selected);
        return this.rows.filter((row) => wanted.has(String(this.key(row))));
    }

    render() {
        const rows = this.filteredRows();
        const pages = Math.max(1, Math.ceil(rows.length / this.pageSize));
        this.page = Math.max(1, Math.min(this.page, pages));
        this.pageRows = rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize);
        text(
            this.count,
            `${rows.length.toLocaleString()} ${rows.length === 1 ? 'record' : 'records'}${
                rows.length === this.rows.length ? '' : ' of ' + this.rows.length.toLocaleString()
            } · page ${this.page} / ${pages}`
        );
        this.previous.disabled = this.page <= 1;
        this.next.disabled = this.page >= pages;
        this.size.value = String(this.pageSize);

        const desired = [];
        for (const record of this.pageRows) {
            const key = String(this.key(record));
            let entry = this.cache.get(key);
            if (!entry) {
                const row = el('tr');
                entry = { node: row, record, signature: '', cells: [] };
                if (this.selectable) {
                    const cell = el('td', null, 'select-column');
                    const box = el('input');
                    box.type = 'checkbox';
                    box.className = 'row-select';
                    box.setAttribute('aria-label', 'Select record ' + key);
                    box.onchange = () => {
                        if (box.checked) this.selected.add(key);
                        else this.selected.delete(key);
                        this.syncSelection();
                    };
                    const boxLabel = el('label', null, 'select-target');
                    boxLabel.append(box);
                    cell.append(boxLabel);
                    row.append(cell);
                    entry.box = box;
                }
                for (const _ of this.columns) {
                    const td = el('td');
                    row.append(td);
                    entry.cells.push(td);
                }
                if (this.actions) {
                    const td = el('td');
                    const controls = this.actions(() => entry.record);
                    entry.actions = controls;
                    td.append(toolbar(...controls));
                    row.append(td);
                }
                this.cache.set(key, entry);
            }
            entry.record = record;
            for (const action of entry.actions || []) action.refreshAvailability?.();
            if (entry.box) entry.box.checked = this.selected.has(key);
            const signature = this.describeRow(record) + this.presentationKey(record);
            if (entry.signature !== signature) {
                this.columns.forEach(([name, fn], index) => {
                    const value = fn(record),
                        cell = entry.cells[index];
                    cell.dataset.label = name;
                    if (value instanceof Node) cell.replaceChildren(value);
                    else text(cell, value);
                });
                entry.signature = signature;
            }
            desired.push(entry.node);
        }

        if (!desired.length) {
            if (!this.empty) {
                this.empty = el('tr', null, 'empty-row');
                const cell = el('td');
                cell.colSpan = this.columns.length + (this.actions ? 1 : 0) + (this.selectable ? 1 : 0);
                this.empty.append(cell);
                this.emptyCell = cell;
            }
            const searching = !!this.query?.value;
            this.emptyCell.replaceChildren(
                this.rows.length
                    ? emptyState({
                          title: 'No records match this view',
                          message: searching
                              ? `Nothing matches “${this.query.value}”. Try a shorter word or another column.`
                              : 'Loosen the filters, or clear them to see every record.',
                          action: {
                              label: 'Clear filters',
                              run: () => this.clearFilters()
                          },
                          icon: 'search',
                          compact: true
                      })
                    : emptyState({
                          title: this.emptyText?.title || 'Nothing to show yet',
                          message:
                              this.emptyText?.hint ||
                              'No records exist in this view. Check the server state, then refresh.',
                          action:
                              this.emptyText?.action ||
                              (this.onRefresh
                                  ? { label: 'Refresh', run: () => this.onRefresh() }
                                  : undefined),
                          icon: this.emptyText?.icon || 'details',
                          compact: true
                      })
            );
            desired.push(this.empty);
        } else if (this.emptyCell) {
            this.emptyCell.replaceChildren();
        }

        const wanted = new Set(desired);
        for (const node of [...this.body.children]) if (!wanted.has(node)) node.remove();
        desired.forEach((node, i) => {
            if (this.body.children[i] !== node)
                this.body.insertBefore(node, this.body.children[i] || null);
        });
        const keys = new Set(this.rows.map((r) => String(this.key(r))));
        for (const key of [...this.cache.keys()]) if (!keys.has(key)) this.cache.delete(key);
        for (const key of [...this.selected]) if (!keys.has(key)) this.selected.delete(key);
        this.syncSelection();
    }

    syncSelection() {
        if (!this.selectable) return;
        const visible = (this.pageRows ?? []).map((row) => String(this.key(row)));
        const chosen = visible.filter((key) => this.selected.has(key)).length;
        this.all.checked = visible.length > 0 && chosen === visible.length;
        this.all.indeterminate = chosen > 0 && chosen < visible.length;
        this.all.setAttribute(
            'aria-label',
            `Select every record on this page (${chosen} of ${visible.length} selected)`
        );
        const total = this.selected.size;
        this.bulk.hidden = total === 0;
        text(
            this.bulkCount,
            `${total} ${total === 1 ? 'record' : 'records'} selected`
        );
        for (const control of this.bulkButtons ?? []) control.refreshAvailability?.();
    }
}
