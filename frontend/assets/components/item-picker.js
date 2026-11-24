import { api } from '../api.js';
import { el, input, select, toolbar, button, text, report } from '../ui.js';
import { itemLine, itemTile } from './items.js';
import { emptyState } from './states.js';
import { exportRows } from './export.js';
import { DataTable } from './table.js';
import { toast } from './toast.js';

/** Bounded server pages; obsolete search results never replace the latest results. */
export function itemPicker(
    onChoose,
    // `exportable` is for the page that shows the catalogue. Inside a `showModal()` dialog
    // the picker's toast lands under the backdrop — the top layer paints above every
    // body-level layer — so an export offered there would fetch ten pages and then
    // confirm it somewhere nobody can see.
    { grantable = false, compatibleSlot = null, query = '', exportable = true } = {}
) {
    const node = el('div', null, 'stack'),
        search = input('Search items'),
        categories = select('Item category', [['', 'All categories']], '');
    search.placeholder = 'Search by item name';
    if (query) search.value = query;
    const grid = el('div', null, 'item-grid'),
        count = el('span', null, 'muted');
    let page = 1,
        generation = 0,
        timer,
        selected = null,
        request;
    const previous = button('Previous', () => {
            page--;
            return load();
        }),
        next = button('Next', () => {
            page++;
            return load();
        });
    /**
     * The catalogue is the one list in the application with no export: it is read
     * through a 24-tile window, so an operator who wanted the set had to page and
     * transcribe. The server pages at 100 rows per request, so the export walks the
     * pages it is allowed to and says so when the set is larger than it fetched —
     * a partial file that claims to be whole is worse than no file.
     */
    const EXPORT_CAP = 1000;
    const COLUMNS = [
        // The catalogue's own table is the last place an item was a bare string: the
        // name cell is the same line the depot and the auctions use, art first, and
        // `note` is empty because the Category column beside it already says it.
        ['Name', (row) => itemLine(row, null, { compact: true, note: '' }), (row) => row.name],
        ['Config', (row) => row.config],
        [
            'Category',
            (row) => row.category,
            (row) => row.category
        ],
        ['Grantable', (row) => (row.grantable ? 'yes' : 'no'), (row) => (row.grantable ? 1 : 0)],
        // The server answers this column for both outcomes — "Available", or the reason an
        // item cannot be granted — so the header states the question, not the failure.
        ['Availability', (row) => row.reason ?? '']
    ];
    // The walk can be ten requests in a row, so it carries its own signal: leaving the
    // page must stop it, not download a file and toast for a page that is gone.
    let exportRequest = null;
    /**
     * Read the filtered catalogue, 100 rows at a time. Both the export and the ordered
     * view are this walk: the catalogue was only ever a 24-tile window in server order,
     * so "every item by category" meant exporting a CSV and opening it elsewhere.
     */
    async function walk(signal) {
        const rows = [];
        let total = null;
        for (let at = 1; rows.length < EXPORT_CAP; at++) {
            const chunk = await api.request(
                'catalogue',
                {
                    search: search.value,
                    category: categories.value,
                    page: at,
                    size: 100,
                    slot: compatibleSlot ?? ''
                },
                false,
                signal
            );
            total = chunk.total;
            if (!chunk.entries.length) break;
            rows.push(...chunk.entries);
            if (rows.length >= chunk.total) break;
        }
        return { rows: rows.slice(0, EXPORT_CAP), total: Number(total ?? rows.length) };
    }
    const exportButton = button(
        'Export',
        async () => {
            exportButton.setBusy?.(true);
            exportRequest?.abort();
            const current = (exportRequest = new AbortController());
            try {
                const { rows, total } = await walk(current.signal);
                exportRows('catalogue', rows, COLUMNS);
                toast(
                    `Exported ${rows.length.toLocaleString()} of ${total.toLocaleString()} items.`,
                    total > rows.length
                        ? {
                              type: 'info',
                              detail: `The server pages the catalogue, so this file stops at ${EXPORT_CAP.toLocaleString()} rows: narrow the search to export the rest.`
                          }
                        : { type: 'success' }
                );
            } catch (error) {
                // A walk this page cancelled is not a failure to report.
                if (error.name !== 'AbortError') report(error);
            } finally {
                if (exportRequest === current) exportRequest = null;
                exportButton.setBusy?.(false);
            }
        },
        'secondary'
    );
    exportButton.title =
        'Download every item matching the search and category, up to 1,000 rows as CSV';

    /**
     * The same set, in an order a person can read: sortable columns, a pager, and the
     * table's own CSV/JSON export. Built on first open and then kept, so opening it
     * twice does not walk the catalogue twice.
     */
    const tableHost = el('div'),
        asTable = el('details', null, 'catalogue-table');
    asTable.append(el('summary', 'As a table'), tableHost);
    let table = null,
        tableRequest = null;
    async function buildTable() {
        // This walk is ten requests in a row like the export's, so it carries a signal
        // too: closing the page mid-walk used to leave the requests running and build a
        // table into a host nobody was looking at.
        tableRequest?.abort();
        const current = (tableRequest = new AbortController());
        tableHost.replaceChildren(el('p', 'Reading the catalogue…', 'muted'));
        try {
            const { rows, total } = await walk(current.signal);
            // A newer walk owns the host: an old one that resolved after a filter
            // change must not paint its stale set over the newer answer.
            if (tableRequest !== current) return;
            table = new DataTable(COLUMNS, {
                id: 'catalogue-as-table',
                caption: 'Catalogue items',
                key: (row) => row.config,
                pageSize: 30,
                persist: false,
                exportName: 'content-items',
                empty: {
                    title: 'No item matches these filters',
                    hint: 'Clear the search or the category above.',
                    icon: 'search'
                }
            });
            table.update(rows);
            tableHost.replaceChildren(
                el(
                    'small',
                    `${rows.length.toLocaleString()} of ${total.toLocaleString()} items matching the search and category above. The grid pages the server 24 tiles at a time; this table is the same set read straight through.`,
                    'muted'
                ),
                table.node
            );
        } catch (error) {
            if (error.name !== 'AbortError' && tableRequest === current) {
                tableHost.replaceChildren(el('p', error.message, 'error'));
                report(error);
            }
        } finally {
            if (tableRequest === current) tableRequest = null;
        }
    }
    asTable.ontoggle = () => {
        if (asTable.open && !table) buildTable();
    };
    /**
     * The table is a snapshot of the filter it was read under, so a new search or
     * category retires it instead of leaving a set on screen under a caption that
     * claims to match the controls above it — the failure this page keeps repeating.
     */
    function retireTable(rebuild = true) {
        if (!table && !tableRequest) return;
        tableRequest?.abort();
        table = null;
        tableHost.replaceChildren();
        // A keystroke retires the set without starting a ten-page walk; the debounced
        // search asks for the rebuild once the typing stops.
        if (rebuild && asTable.open) buildTable();
    }
    const paging = toolbar(count, ...(exportable ? [exportButton] : []), previous, next);
    node.append(toolbar(search, categories), grid, ...(exportable ? [asTable] : []), paging);
    async function load() {
        clearTimeout(timer);
        const id = ++generation;
        request?.abort();
        request = new AbortController();
        previous.disabled = next.disabled = true;
        grid.setAttribute('aria-busy', 'true');
        let result;
        try {
            result = await api.request(
                'catalogue',
                {
                    search: search.value,
                    category: categories.value,
                    page,
                    size: 24,
                    slot: compatibleSlot ?? ''
                },
                false,
                request.signal
            );
        } catch (error) {
            if (id !== generation || error.name === 'AbortError') return;
            grid.setAttribute('aria-busy', 'false');
            grid.replaceChildren(el('p', error.message, 'error'), button('Retry search', load));
            text(count, 'Catalogue unavailable');
            return;
        }
        if (id !== generation) return;
        grid.setAttribute('aria-busy', 'false');
        if (categories.options.length === 1)
            for (const name of result.categories) {
                const o = el('option', name);
                o.value = name;
                categories.append(o);
            }
        const tiles = result.entries.map((def) => {
            const tile = itemTile(def, null, () => {
                selected = def;
                for (const n of grid.children) n.setAttribute('aria-pressed', n === tile);
                onChoose(def);
            });
            tile.disabled = grantable && !def.grantable;
            tile.setAttribute('aria-pressed', selected?.config === def.config);
            return tile;
        });
        grid.replaceChildren(
            ...(tiles.length
                ? tiles
                : [
                      emptyState({
                          compact: true,
                          icon: 'search',
                          title: 'No items match these filters',
                          message:
                              search.value || categories.value
                                  ? 'The catalogue had a category and a search term to go on. Clearing one of them is the next step.'
                                  : 'The server returned no items for this slot at all.',
                          action:
                              search.value || categories.value
                                  ? {
                                        label: 'Clear filters',
                                        run: () => {
                                            search.value = '';
                                            categories.value = '';
                                            page = 1;
                                            load().catch(report);
                                        }
                                    }
                                  : null
                      })
                  ])
        );
        text(count, `${result.total.toLocaleString()} items · Page ${page}`);
        previous.disabled = page <= 1;
        next.disabled = page * result.size >= result.total;
    }
    search.oninput = () => {
        generation++;
        request?.abort();
        retireTable(false);
        clearTimeout(timer);
        timer = setTimeout(() => {
            page = 1;
            load().catch(report);
            if (asTable.open && !table) buildTable();
        }, 250);
    };
    categories.onchange = () => {
        page = 1;
        retireTable();
        load().catch(report);
    };
    grid.onkeydown = (event) => {
        const tiles = [...grid.querySelectorAll('button:not(:disabled)')],
            at = tiles.indexOf(document.activeElement);
        if (at < 0 || !['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp'].includes(event.key))
            return;
        event.preventDefault();
        const columns = Math.max(
            1,
            Math.round(grid.clientWidth / tiles[0].getBoundingClientRect().width)
        );
        const delta = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns }[
            event.key
        ];
        tiles[Math.max(0, Math.min(tiles.length - 1, at + delta))]?.focus();
    };
    load().catch(report);
    return {
        node,
        dispose() {
            generation++;
            request?.abort();
            exportRequest?.abort();
            tableRequest?.abort();
            clearTimeout(timer);
        }
    };
}
