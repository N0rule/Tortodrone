import { el, button, input, select, toolbar, text, date } from '../ui.js';
import { route } from '../router.js';

/** An epoch sort key exports as the timestamp the cell prints, not as milliseconds. */
const stamp = (value) => date(value);
import { emptyState } from './states.js';
import { exportControls } from './export.js';
import { read, schema } from '../prefs.js';

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
            // `address: true` makes the query and the filters part of the URL: every
            // list that reads `?search=` on arrival must also write it back, or a
            // shared link can disagree with the screen it was meant to name. A page
            // with two tables gives one of them its own param name.
            address = false,
            addressParam = 'search',
            // What this table can be searched for. Six tables used one placeholder,
            // so "Search records" told the operator nothing about the columns that
            // are actually matched.
            searchPlaceholder = 'Search records',
            // A column that holds nothing, or that says the same thing on every row,
            // is a column of dashes where the table's real subject should have the
            // width. Blank columns always go; a constant one only when the caller
            // says so, because a filter that leaves one value is information.
            dropConstantColumns = false,
            // The order a list opens in. A table that sorts nothing renders in whatever
            // order the export happened to hold and says so on every header
            // (`aria-sort="none"`), which reads as an unsorted list with no order at all;
            // the caller names the column that is really the list's spine.
            defaultSort = null,
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
            dropConstantColumns,
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
        if (defaultSort && Number.isInteger(defaultSort.index) && defaultSort.index < columns.length) {
            this.sort = defaultSort.index;
            this.direction = defaultSort.direction === -1 ? -1 : 1;
        }
        this.node = el('div', null, 'data-table');

        const saved = persist ? loadState(this.id) : null;
        if (saved) {
            if (Number.isInteger(saved.sort) && saved.sort < columns.length) this.sort = saved.sort;
            if (saved.direction === -1 || saved.direction === 1) this.direction = saved.direction;
            if (schema.pageSize.includes(saved.pageSize)) this.pageSize = saved.pageSize;
        }

        this.query = input(searchPlaceholder);
        this.query.type = 'search';
        this.query.placeholder = searchPlaceholder;
        this.query.setAttribute('aria-label', searchPlaceholder);
        this.addressParam = addressParam;
        // A table that owns its address reads the search from it as well as writing it.
        // The flag is read from the option, not from a field that is assigned further
        // down: reading `this.address` here was always undefined, so the query never
        // came back from a cold load while the filters did.
        const fromAddress = address ? route().params.get(addressParam) : null;
        if (fromAddress) this.query.value = fromAddress;
        else if (initialQuery) this.query.value = initialQuery;
        this.controls = toolbar();
        this.controls.classList.add('table-tools');
        if (search) this.controls.append(this.query);
        this.filtersByName = {};
        this.filtersInitial = {};
        this.filterDefaults = {};
        this.address = address;
        this.choices = filters.map(([name, options, match, initial]) => {
            // A filter option is either the value itself or a `[value, label]` pair,
            // and the label is what a person reads ("Administrator" for the enum the
            // server speaks). Comparing and remembering the pair instead of its value
            // matched nothing at all, so the page that used one listed no rows.
            const values = options.map((option) => (Array.isArray(option) ? option : [option, option]));
            const has = (value) => value !== null && values.some(([id]) => id === value);
            const savedValue = saved?.filters?.[name];
            const fromAddress = this.address ? route().params.get(name.toLowerCase()) : null;
            // The address outranks a remembered preference: a shared link that says
            // `?availability=Disabled` has to show disabled offers even in a browser
            // that last looked at enabled ones, or the link silently lies.
            const start = has(fromAddress)
                ? fromAddress
                : has(savedValue)
                  ? savedValue
                  : (initial ?? values[0][0]);
            const control = select(name, values, start);
            control.dataset.filter = name;
            // The visible text of a filter was only its value ("All"), so what it
            // filtered was a screen-reader detail: the label is written in front of
            // it. Not a <label> wrapper, which would replace the select's own name.
            const wrap = el('span', null, 'filter-label');
            wrap.append(el('span', name, 'filter-label-text'), control);
            control.onchange = () => {
                this.page = 1;
                this.remember();
                this.render();
                this.syncAddress();
            };
            this.controls.append(wrap);
            this.filtersByName[name] = control;
            this.filtersInitial[name] = start;
            // What the filter reads before anybody chose anything: the baseline a
            // "Clear filters" press returns to, and the one the address is compared
            // with so a remembered filter is written into it instead of vanishing.
            this.filterDefaults[name] = initial ?? values[0][0];
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
        this.headCells = [];
        columns.forEach(([name, , , align], index) => {
            const cell = el('th');
            cell.scope = 'col';
            this.headCells.push(cell);
            // A column of numbers is read by its right edge; the header follows its
            // data, or the two disagree by the width of the longest word.
            if (align === 'end') cell.classList.add('numeric');
            /**
             * The direction of the column this table is actually sorted by, and nothing
             * on the others. A caret on every sortable header at rest was fifteen marks
             * per table saying "maybe": the order of the rows was still undiscoverable,
             * and each header carried a faint `↕` too pale to read. The slot is reserved
             * on every header so the label does not move when the mark arrives.
             */
            const mark = el('span', null, 'sort-mark');
            mark.setAttribute('aria-hidden', 'true');
            const label = button(
                name,
                () => {
                    this.direction = this.sort === index ? -this.direction : 1;
                    this.sort = index;
                    if (this.sortSelect) this.sortSelect.value = String(this.sort);
                    this.remember();
                    this.render();
                },
                'quiet'
            );
            label.title = 'Sort by ' + name;
            label.append(mark);
            cell.append(label);
            cell.dataset.sort = String(index);
            cell.setAttribute(
                'aria-sort',
                this.sort === index
                    ? this.direction === 1
                        ? 'ascending'
                        : 'descending'
                    : 'none'
            );
            row.append(cell);
        });
        if (actions) {
            // The header pins with its cells, or the label drifts off the column while
            // the buttons stay put (and the column before it is painted underneath).
            const cell = el('th', 'Actions', 'row-actions');
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
            // The size is this table's own, remembered beside its sort and filters.
            // Writing the global preference here made one table's pager silently
            // rewrite the default for every other table and for the Preferences page,
            // which promised the opposite: "each table remembers its own".
            this.remember();
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
                    this.syncAddress();
                }, 140);
            };
            this.query.onkeydown = (event) => {
                if (event.key === 'Escape' && this.query.value) {
                    event.stopPropagation();
                    this.query.value = '';
                    this.page = 1;
                    this.render();
                    this.syncAddress();
                }
            };
        }
    }

    /**
     * Write the query and the filters back into the address, keeping every param this
     * table does not own (`?tab=`, `?offer=`). `replaceState`, so typing a search does
     * not stack a history entry per keystroke.
     */
    syncAddress() {
        if (!this.address) return;
        const params = route().params;
        const query = this.query?.value?.trim() || '';
        const own = this.addressParam || 'search';
        if (query) params.set(own, query);
        else params.delete(own);
        for (const [control, name] of this.choices) {
            const chosen = control.value;
            // Compare with the factory value: comparing with what the table arrived
            // with deleted the very filter the operator could see applied.
            const first = this.filterDefaults[name] ?? this.filtersInitial[name];
            if (chosen && chosen !== first) params.set(name.toLowerCase(), chosen);
            else params.delete(name.toLowerCase());
        }
        const hash = '#' + route().name + (params.size ? '?' + params : '');
        if (location.hash !== hash) window.history.replaceState(null, '', hash);
    }

    /** Mark the one row an inspector describes, and clear the previous mark. */
    markInspected(record) {
        const key = record ? String(this.key(record)) : null;
        this.inspected = key;
        for (const [entryKey, entry] of this.cache) {
            entry.node.classList.toggle('inspected', entryKey === key);
            if (entryKey === key) entry.node.setAttribute('aria-current', 'true');
            else entry.node.removeAttribute('aria-current');
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
        // A remembered preference that the address does not mention is written into
        // it once, so a copied link carries the table the operator is looking at
        // instead of the table that browser happens to remember.
        this.syncAddress();
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
        const next =
            value == null || value === ''
                ? (this.filterDefaults[name] ?? this.filtersInitial[name] ?? 'All')
                : String(value);
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
            // The factory value, not `filtersInitial`: a filter this table remembered
            // from the last visit made "Clear filters" a button that changed nothing.
            control.value = this.filterDefaults[name] ?? control.options[0].value;
        }
        this.page = 1;
        this.remember();
        this.render();
        this.syncAddress();
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
                // The export must be the table the operator is looking at. A scalar
                // sort key IS the column's value (a subject, a count), so it wins; a
                // cell that stacks several values (subject + snippet) would otherwise
                // export as their concatenation, and an epoch key as its milliseconds.
                const key = sortValue ? sortValue(row) : undefined;
                if (typeof key === 'string' && key.trim()) return key;
                if (typeof key === 'number' && Number.isFinite(key)) {
                    if (key > 1e11) return stamp(key);
                    return key;
                }
                const rendered = render(row);
                if (rendered instanceof Node) return rendered.textContent ?? '';
                return rendered ?? '';
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

    /**
     * Paint the sort state from the table's own field.
     *
     * The mark used to be written once, when its header was built, so clicking "Kind"
     * left the arrow beside "Station": the rows were ordered by Kind, the paging bar's
     * select read "Kind", and the only visible direction pointed at a column that no
     * longer sorted anything. Every path that changes the order ends in `render()`.
     */
    paintMarks() {
        if (!this.headCells?.length) return;
        this.headCells.forEach((cell, index) => {
            const sorted = this.sort === index,
                up = this.direction === 1,
                mark = cell.querySelector('.sort-mark');
            if (mark) {
                // The arrow is a drawn triangle toggled by class, not a character: a
                // 12px ↑ and ↓ photographed as the same tick, so the mark named the
                // column and never the direction.
                mark.classList.toggle('sorted', sorted);
                mark.classList.toggle('is-asc', sorted && up);
                mark.classList.toggle('is-desc', sorted && !up);
            }
            if (!cell.hasAttribute('data-sort')) return;
            cell.setAttribute('aria-sort', sorted ? (up ? 'ascending' : 'descending') : 'none');
            const label = cell.querySelector('button');
            if (label)
                label.title = sorted
                    ? `${cell.textContent.trim()}, ${up ? 'ascending' : 'descending'} — click to reverse`
                    : `Sort by ${cell.textContent.trim()}`;
        });
    }

    render() {
        this.paintMarks();
        const rows = this.filteredRows();
        const pages = Math.max(1, Math.ceil(rows.length / this.pageSize));
        this.page = Math.max(1, Math.min(this.page, pages));
        this.pageRows = rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize);
        // One page needs no position: "page 1 / 1" is a fact the reader can already see.
        text(
            this.count,
            `${rows.length.toLocaleString()} ${rows.length === 1 ? 'record' : 'records'}${
                rows.length === this.rows.length ? '' : ' of ' + this.rows.length.toLocaleString()
            }${pages > 1 ? ` · page ${this.page} / ${pages}` : ''}`
        );
        this.previous.disabled = this.page <= 1;
        this.next.disabled = this.page >= pages;
        this.size.value = String(this.pageSize);
        // An empty table has nothing to page through: the size, the order and the two
        // page buttons are three controls that cannot change anything, and beside an
        // empty state they read as a broken footer. \"0 records\" under a sentence that
        // already says there is nothing was the fourth copy of the same fact.
        const paged = rows.length > 1;
        for (const control of [this.previous, this.next, this.size, this.sortSelect])
            control.hidden = !paged;
        this.count.hidden = rows.length === 0;

        const desired = [];
        for (const [rowIndex, record] of this.pageRows.entries()) {
            const key = String(this.key(record));
            let entry = this.cache.get(key);
            if (!entry) {
                const row = el('tr');
                row.dataset.key = key;
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
                for (const [, , , align] of this.columns) {
                    const td = el('td');
                    if (align === 'end') td.classList.add('numeric');
                    row.append(td);
                    entry.cells.push(td);
                }
                if (this.actions) {
                    const td = el('td', null, 'row-actions');
                    const controls = this.actions(() => entry.record);
                    entry.actions = controls;
                    for (const control of controls) {
                        const label = control.textContent?.trim();
                        if (label) control.dataset.actionLabel = label;
                    }
                    td.append(toolbar(...controls));
                    row.append(td);
                }
                this.cache.set(key, entry);
            }
            entry.record = record;
            for (const action of entry.actions || []) action.refreshAvailability?.();
            if (entry.box) entry.box.checked = this.selected.has(key);
            // A bare "Edit" repeated down a list says nothing out loud; the row's own
            // subject rides on each control's accessible name.
            const subject = String(entry.cells[0]?.textContent || '').trim();
            if (subject)
                for (const control of entry.actions || [])
                    if (control.dataset.actionLabel)
                        control.setAttribute(
                            'aria-label',
                            `${control.dataset.actionLabel} · ${subject}`
                        );
            const signature = this.describeRow(record) + this.presentationKey(record);
            if (entry.signature !== signature) {
                this.columns.forEach(([name, fn], index) => {
                    // A cell may know where it sits: a trail that spans midnight prints
                    // its date once a day instead of on fifty rows. The count is the
                    // row's position — the loop's own index is the column's.
                    const value = fn(record, rowIndex, this.pageRows),
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
            // `this.rows` is the whole set the last update() handed over, so a filter
            // that empties the view still reads as a miss, not as an empty table.
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
        this.dropColumns();
        // An empty table has nothing to label: eight headers over one sentence read as
        // a table that failed to load. The column names stay for a screen reader.
        this.table.classList.toggle('is-empty', rows.length === 0);
        this.syncSelection();
    }

    /**
     * Hide the columns this view has nothing to say in. A trail whose events carry no
     * target printed "—" down a whole column; the depot printed the same limit and the
     * same status thirty times. The header greys out with the cells, and the rule is
     * re-decided on every render, so a filter that brings the values back brings the
     * column back with them.
     */
    dropColumns() {
        if (!this.headCells?.length) return;
        const rows = this.pageRows ?? [];
        if (!rows.length) {
            for (const cell of this.headCells) cell.classList.remove('column-dropped');
            return;
        }
        const entries = rows
            .map((row) => this.cache.get(String(this.key(row))))
            .filter(Boolean);
        this.columns.forEach((_, index) => {
            let filled = false,
                first = null,
                constant = true;
            for (const entry of entries) {
                const value = String(entry.cells[index]?.textContent || '').trim();
                if (value && value !== '—') {
                    filled = true;
                    if (first === null) first = value;
                    else if (value !== first) constant = false;
                }
            }
            const hide = !filled || (this.dropConstantColumns && constant);
            this.headCells[index].classList.toggle('column-dropped', hide);
            for (const entry of entries)
                entry.cells[index].classList.toggle('column-dropped', hide);
        });
    }

    syncSelection() {
        if (!this.selectable) return;
        const visible = (this.pageRows ?? []).map((row) => String(this.key(row)));
        const chosen = visible.filter((key) => this.selected.has(key)).length;
        // A select-all over no rows is a checkbox that asks about nothing, so it is
        // not drawn at all: a disabled box in the header of an empty table is a mark
        // with no meaning.
        this.all.disabled = visible.length === 0;
        this.all.parentElement.hidden = visible.length === 0;
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
