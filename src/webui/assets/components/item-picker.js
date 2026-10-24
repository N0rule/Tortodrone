import { api } from '../api.js';
import { el, input, select, toolbar, button, text, report } from '../ui.js';
import { itemTile } from './items.js';

/** Bounded server pages; obsolete search results never replace the latest results. */
export function itemPicker(onChoose, { grantable = false } = {}) {
    const node = el('div', null, 'stack'),
        search = input('Search items'),
        categories = select('Item category', [['', 'All categories']], '');
    search.placeholder = 'Search by item name';
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
    const paging = toolbar(count, previous, next);
    node.append(toolbar(search, categories), grid, paging);
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
                    size: 24
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
            ...(tiles.length ? tiles : [el('p', 'No items match these filters.', 'empty')])
        );
        text(count, `${result.total.toLocaleString()} items · Page ${page}`);
        previous.disabled = page <= 1;
        next.disabled = page * result.size >= result.total;
    }
    search.oninput = () => {
        generation++;
        request?.abort();
        clearTimeout(timer);
        timer = setTimeout(() => {
            page = 1;
            load().catch(report);
        }, 250);
    };
    categories.onchange = () => {
        page = 1;
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
            clearTimeout(timer);
        }
    };
}
