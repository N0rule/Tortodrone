import { el, button, input, select, toolbar, text } from '../ui.js';

/** Keyed rows and persistent controls. Updating data never replaces the table or its inputs. */
export class DataTable {
    constructor(
        columns,
        { key = (row) => row.id, actions, filters = [], pageSize = 30, search = true } = {}
    ) {
        Object.assign(this, { columns, key, actions, filters, pageSize });
        this.rows = [];
        this.cache = new Map();
        this.page = 1;
        this.sort = -1;
        this.direction = 1;
        this.node = el('div');
        this.query = input('Search records');
        this.query.placeholder = 'Search records';
        this.controls = toolbar();
        this.controls.classList.add('table-tools');
        if (search) this.controls.append(this.query);
        this.query.oninput = () => {
            this.page = 1;
            this.render();
        };
        this.choices = filters.map(([name, values, match]) => {
            const control = select(name, values, values[0]);
            control.onchange = () => {
                this.page = 1;
                this.render();
            };
            this.controls.append(control);
            return [control, match];
        });
        this.wrap = el('div', null, 'table-wrap');
        this.table = el('table');
        const head = el('thead'),
            row = el('tr');
        columns.forEach(([name], index) => {
            const cell = el('th');
            cell.scope = 'col';
            cell.append(
                button(
                    name,
                    () => {
                        this.direction = this.sort === index ? -this.direction : 1;
                        this.sort = index;
                        for (const th of head.querySelectorAll('th'))
                            th.removeAttribute('aria-sort');
                        cell.setAttribute(
                            'aria-sort',
                            this.direction === 1 ? 'ascending' : 'descending'
                        );
                        this.render();
                    },
                    'quiet'
                )
            );
            row.append(cell);
        });
        if (actions) row.append(el('th', 'Actions'));
        head.append(row);
        this.body = el('tbody');
        this.table.append(head, this.body);
        this.wrap.append(this.table);
        this.count = el('span');
        this.previous = button('Previous', () => {
            this.page--;
            this.render();
        });
        this.next = button('Next', () => {
            this.page++;
            this.render();
        });
        const paging = el('div', null, 'pagination');
        paging.append(this.count, this.previous, this.next);
        this.node.append(this.controls, this.wrap, paging);
    }
    update(rows) {
        this.rows = rows;
        this.render();
    }
    render() {
        const q = this.query.value.toLowerCase();
        const rows = this.rows.filter(
            (r) =>
                (!q || JSON.stringify(r).toLowerCase().includes(q)) &&
                this.choices.every(([c, match]) => match(r, c.value))
        );
        if (this.sort >= 0) {
            const fn = this.columns[this.sort][2] || this.columns[this.sort][1];
            rows.sort((a, b) => {
                const x = fn(a),
                    y = fn(b);
                return (
                    this.direction *
                    (typeof x === 'number' && typeof y === 'number'
                        ? x - y
                        : String(x?.textContent ?? x ?? '').localeCompare(
                              String(y?.textContent ?? y ?? ''),
                              undefined,
                              { numeric: true }
                          ))
                );
            });
        }
        const pages = Math.max(1, Math.ceil(rows.length / this.pageSize));
        this.page = Math.min(this.page, pages);
        text(this.count, `${rows.length.toLocaleString()} records · ${this.page} / ${pages}`);
        this.previous.disabled = this.page <= 1;
        this.next.disabled = this.page >= pages;
        const visible = rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize),
            desired = [];
        for (const record of visible) {
            const key = String(this.key(record));
            let entry = this.cache.get(key);
            if (!entry) {
                const row = el('tr');
                entry = { node: row, record, signature: '', cells: [] };
                for (const _ of this.columns) {
                    const td = el('td');
                    row.append(td);
                    entry.cells.push(td);
                }
                if (this.actions) {
                    const td = el('td');
                    const actions = this.actions(() => entry.record);
                    td.append(toolbar(...actions));
                    row.append(td);
                }
                this.cache.set(key, entry);
            }
            entry.record = record;
            const signature = JSON.stringify(record);
            if (entry.signature !== signature) {
                this.columns.forEach(([, fn], index) => {
                    const value = fn(record),
                        cell = entry.cells[index];
                    if (value instanceof Node) cell.replaceChildren(value);
                    else text(cell, value);
                });
                entry.signature = signature;
            }
            desired.push(entry.node);
        }
        if (!desired.length) {
            if (!this.empty) {
                this.empty = el('tr');
                const td = el('td', 'No matching records.', 'empty');
                td.colSpan = this.columns.length + (this.actions ? 1 : 0);
                this.empty.append(td);
            }
            desired.push(this.empty);
        }
        const wanted = new Set(desired);
        for (const node of [...this.body.children]) if (!wanted.has(node)) node.remove();
        desired.forEach((node, i) => {
            if (this.body.children[i] !== node)
                this.body.insertBefore(node, this.body.children[i] || null);
        });
        const keys = new Set(this.rows.map((r) => String(this.key(r))));
        for (const [key] of this.cache) if (!keys.has(key)) this.cache.delete(key);
    }
}
