import { el, toolbar, badge, details, facts, panel, button, copy, report, routeLink } from '../ui.js';
import { itemPicker } from '../components/item-picker.js';
import { definitionsFor, itemLine, itemLinks, inspectItemName } from '../components/items.js';
import { openDialog, closeDialog } from '../components/dialog.js';
import { DataTable } from '../components/table.js';
import { symbol } from '../components/symbols.js';
import { route, go } from '../router.js';
export async function mount(ctx) {
    // Read once, in `refresh()` below. The page used to issue the same `content` read
    // twice per mount — once here, once a few lines down — for the same answer.
    let data = null,
        appliedSearch = ctx.params.get('search'),
        opened = null;
    const node = el('div', null, 'stack'),
        summary = el('div', null, 'stack content-summary');
    const keyOf = (station) => station.actor || station.name;
    /** Rewrite this page's address, keeping the params this action does not own. */
    function here(changes = {}, drop = []) {
        const params = route().params;
        for (const name of drop) params.delete(name);
        for (const [name, value] of Object.entries(changes)) params.set(name, String(value));
        go('content', Object.fromEntries(params));
    }
    /** One item inspector for the picker, the recipes and the deep link. */
    const showItem = async (config, definition) => {
        try {
            // A deep link (?item=) has no definition to hand, and the cross-links
            // need its name: resolve it first so the inspector offers the same
            // actions as the picker.
            const known =
                definition ??
                (await definitionsFor([config]).catch(() => new Map())).get(config) ??
                null;
            await inspectItemName(config, {
                definition: known,
                // The catalogue is already behind this dialog, so the economy
                // jump and the way back are the useful actions here.
                actions: [
                    button('Back to catalogue', () => here({}, ['item']), 'secondary'),
                    ...itemLinks(config, known?.name, { catalogue: false })
                ]
            });
            unlinkOnClose();
        } catch (error) {
            report(error);
        }
    };
    const picker = itemPicker((def) => showItem(def.config, def), {
        query: ctx.params.get('search') || ''
    });
    function setPickerQuery(value) {
        const field = picker.node.querySelector('input');
        if (!field || field.value === value) return;
        field.value = value;
        field.dispatchEvent(new Event('input', { bubbles: true }));
    }
    const merchants = () =>
        Array.isArray(data.merchants) ? data.merchants : Object.values(data.merchants ?? {});
    /* A station's kind is the engine class that opens it — `OpenCatalogCrafter` —
       which is the one spelling an operator cannot read as a word. The label is the
       class name with its `Open` verb dropped and the camel humps split, and the
       engine's own spelling stays on the cell as its tooltip and in the dialog. */
    /* The engine spells its enums in capitals. A caption in this console is sentence
       case, so a token that arrives entirely upper case is lowered and given one
       capital; anything already carrying a lower-case letter is a name and is left
       alone. */
    const engineWord = (value) => {
        const word = String(value ?? '').trim();
        if (!word) return '—';
        return /[A-Z]/.test(word) && !/[a-z]/.test(word)
            ? word.charAt(0) + word.slice(1).toLowerCase()
            : word;
    };
    /* A group is a scene path; the reader gets its leaf. */
    const groupLeaf = (station) =>
        String(station.group || '')
            .split('/')
            .filter(Boolean)
            .pop() || '—';
    const kindLabel = (kind) => {
        const bare = String(kind || '').replace(/^Open/, '');
        const words = bare
            .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
            // The engine spells it "Catalog"; the column header, the page name and the
            // rest of this console spell it "Catalogue", and one table cannot hold both.
            .replace(/Catalog (Crafter|Maker)/g, 'Catalogue $1')
            .replace(/^./, (first) => first.toUpperCase());
        return words || '—';
    };
    /* The art says what the station does, in client glyphs only: a shop and a depot
       share the depot mark because they are the same counter, and everything that
       makes or converts items wears the catalogue's own mark. A kind with no glyph
       of its own gets none. */
    const kindArt = (kind) => {
        const bare = String(kind || '').replace(/^Open/, '').toLowerCase();
        if (bare.includes('auction')) return 'auctions';
        if (bare.includes('depot')) return 'depot';
        if (bare.includes('shop')) return 'depot';
        // A kind the pack has no glyph for wears the catalogue's own mark: a station in
        // this list is a station in the town catalogue, and one row without a face
        // beside sixteen with one read as a failed image.
        return 'content';
    };
    // The merchant catalogue is operator data: a table with the same search,
    // sort and export as every other list, plus a jump into the catalogue search.
    const stationTable = new DataTable(
        [
            [
                'Station',
                (station) => {
                    // One string per station, and the link is on the value the reader
                    // sees: a button and a hidden actor path meant no station address
                    // could be copied or middle-clicked.
                    const cell = el('div', null, 'offer-reference with-art');
                    // A merchant had no face in this catalogue at all: ninety-nine
                    // rows of names, where every other list on the page opens with
                    // the client's own picture of the thing.
                    const art = kindArt(station.kind);
                    const mark = art ? symbol(art) : null;
                    if (mark) {
                        mark.classList.add('station-mark');
                        cell.append(mark);
                    }
                    /* The readable name leads and the engine's identifier follows, in
                       the mono face that says "this is a path". A station with no name
                       of its own — most of them — used to put the whole actor path on
                       the first line at full ink, so the string only the engine can
                       read outranked every name on the page. Such a station is led by
                       the last segment of its own path and the breadcrumb to it sits
                       underneath. */
                    const segments = String(station.actor || '')
                        .split('/')
                        .filter(Boolean);
                    const named = Boolean(station.name) && station.name !== '~';
                    const label = named ? station.name : segments.at(-1) || '—';
                    const link = routeLink(label, 'content', { station: keyOf(station) });
                    link.title = station.actor;
                    cell.append(link);
                    const trail = named ? station.actor : segments.slice(0, -1).join('/');
                    if (trail) {
                        // The actor is a path, and a long one wrapped the cell to three
                        // lines beside a name of one: the row keeps its height and the
                        // whole path stays reachable as the tooltip.
                        const actor = el('small', trail, 'muted');
                        actor.title = station.actor;
                        cell.append(actor);
                    }
                    return cell;
                },
                (station) => station.name && station.name !== '~' ? station.name : station.actor
            ],
            [
                'Kind',
                (station) => {
                    const node = el('span', kindLabel(station.kind), 'station-kind');
                    node.title = station.kind || '';
                    return node;
                },
                (station) => station.kind || ''
            ],
            [
                'Group',
                // A group is an internal scene path; the leaf name is the label and the
                // path stays available as its tooltip.
                (station) => {
                    const path = station.group || '';
                    const leaf = path.split('/').filter(Boolean).pop() || '—';
                    const node = el('span', engineWord(leaf));
                    node.title = path;
                    return node;
                },
                (station) => station.group || ''
            ],
            [
                'Catalogue',
                (station) => {
                    // "Recipes only" beside "Recipes: 0" contradicted itself in nine rows
                    // of ten: the cell names the captured catalogue, and a station without
                    // one has nothing to name.
                    if (!station.shop?.catalog) {
                        const none = el('span', '—', 'muted');
                        none.title = 'No shop catalogue was captured for this station';
                        return none;
                    }
                    const name = el(
                        'span',
                        station.shop.catalog.split('/').slice(-1)[0],
                        'cell-clip'
                    );
                    name.title = station.shop.catalog;
                    return name;
                },
                (station) => station.shop?.catalog || ''
            ],
            [
                'Level',
                (station) => {
                    // A station whose catalogue was never captured has no level to
                    // report: a printed 0 beside an empty Catalogue cell is a figure the
                    // export does not hold, and it sorts as the lowest level in town.
                    if (station.shop?.level == null) return el('span', '—', 'muted');
                    return String(station.shop.level);
                },
                (station) => station.shop?.level ?? 0,
                'end'
            ],
            [
                'Recipes',
                (station) => {
                    if (!station.recipes?.length) return el('span', '—', 'muted');
                    return String(station.recipes.length);
                },
                (station) => station.recipes?.length ?? 0,
                'end'
            ],
            [
                'Model',
                (station) => {
                    const path = station.model || '';
                    const node = el(
                        'span',
                        path.split('/').filter(Boolean).pop() || '—',
                        'cell-clip'
                    );
                    node.title = path;
                    return node;
                },
                (station) => station.model || ''
            ]
        ],
        {
            key: keyOf,
            id: 'content-merchants',
            // The item picker owns `?search=` on this page, so the merchant table owns
            // its own param: one name, one owner, no view that cannot be named.
            address: true,
            addressParam: 'merchant',
            caption: 'Merchant stations in the exported town catalogue',
            searchPlaceholder: 'Search station, kind or group',
            exportName: 'merchant-stations',
            pageSize: 10,
            defaultSort: { index: 0 },
            // The catalogue prints "Recipes only" down a column and one level down
            // another; a column that says the same thing in every row is a header
            // over nothing, and it comes back the moment a filter makes it vary.
            dropConstantColumns: true,
            empty: {
                title: 'No merchant stations are exported',
                hint: 'Stations come from the exported town catalogue; add one and export again.',
                icon: 'content'
            },
            /* One action per row, and the one the row can actually do. Every row used
               to carry "Open station" and "Find items" side by side — 198 equal buttons
               down the table, where the station name above each pair already opened the
               station and "Find items" stayed enabled on rows whose catalogue is empty,
               so two thirds of the column did nothing. */
            actions: (get) => {
                const station = get();
                if (!station.shop?.catalog)
                    return [button('Open station', () => openStation(station), 'secondary')];
                return [
                    button(
                        'Find items',
                        () => {
                            setPickerQuery(station.shop.catalog);
                            picker.node.querySelector('input')?.focus();
                            picker.node.scrollIntoView({ block: 'center' });
                        },
                        'secondary'
                    )
                ];
            }
        }
    );
    /**
     * Closing a deep-linked inspector drops its params, so the address never names
     * a record that is no longer on screen. A replacement dialog opens in the same
     * tick, so the check is deferred and skipped while one is open.
     */
    function unlinkOnClose() {
        const dialog = document.querySelector('#dialog'),
            previous = dialog.onclose;
        dialog.onclose = (event) => {
            previous?.call(dialog, event);
            setTimeout(() => {
                if (dialog.open) return;
                const owned = ['station', 'item'].filter((name) => route().params.has(name));
                if (owned.length) here({}, owned);
            }, 0);
        };
    }
    function openStation(station) {
        const recipes = station.recipes || [];
        // A list with nothing in it is a sentence, not a frame: the bordered scroll box
        // around the word "no recipes" was a third border inside two.
        const recipeList = recipes.length ? el('div', null, 'station-recipes') : el('div');
        if (!recipes.length) recipeList.append(el('p', 'No recipes on this station.', 'muted'));
        else
            for (const config of recipes) {
                // Names stay text inside an editor, so the row carries its own control.
                const row = el('div', null, 'station-recipe');
                row.append(
                    itemLine(undefined, { config }),
                    button('Open item', () => showItem(config), 'quiet')
                );
                recipeList.append(row);
            }
        // The head is the row that opened it: the same client glyph, the kind and the
        // same breadcrumb underneath. The title already names the record, so the head
        // does not name it a second time 70px below — the offer dialog reads the same
        // way, and its head carries only what the title cannot.
        const segments = String(station.actor || '')
                .split('/')
                .filter(Boolean),
            named = Boolean(station.name) && station.name !== '~',
            label = named ? station.name : segments.at(-1) || '—',
            head = el('div', null, 'offer-reference with-art record-head'),
            names = el('span');
        const mark = symbol(kindArt(station.kind));
        if (mark) {
            mark.classList.add('station-mark');
            head.append(mark);
        }
        names.append(el('strong', kindLabel(station.kind), 'station-kind'));
        const breadcrumb = el('small', station.actor, 'muted');
        breadcrumb.title = station.actor;
        head.append(names, breadcrumb);
        openDialog({
            title: label,
            content: panel(
                '',
                head,
                ...(station.description ? [el('p', station.description, 'muted record-state')] : []),
                facts([
                    ['Group', engineWord(groupLeaf(station))],
                    [
                        'Catalogue',
                        engineWord(
                            String(station.shop?.catalog || '')
                                .split('/')
                                .filter(Boolean)
                                .pop()
                        )
                    ],
                    [
                        'Shop level / cycle',
                        `${station.shop?.level ?? '—'} · ${engineWord(station.shop?.cycle)}`
                    ],
                    ['Preparer', engineWord(station.shop?.preparer)],
                    ['Per knight', station.shop?.perKnight ? 'yes' : 'no']
                ]),
                // The count belongs to the heading, the way the offer dialog counts its
                // promotions, and not to a fact row that repeats the list under it.
                el('h3', recipes.length ? `Recipes (${recipes.length})` : 'Recipes'),
                recipeList,
                toolbar(
                    button('Back to catalogue', () => here({}, ['station']), 'secondary'),
                    button('Copy actor', () => copy(station.actor, 'Actor path copied.'), 'quiet')
                ),
                details('Raw catalogue entry', station)
            ),
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        unlinkOnClose();
    }
    function paintSummary() {
        summary.replaceChildren(
            toolbar(
                // A count is what the server sends, and the page used to look like it
                // was hiding a map list. Say what the number is and where the maps
                // actually live.
                Object.assign(badge(`${data.maps} compiled scenes`), {
                    title:
                        'Scenes compiled into the content pack. This page validates them; the scene files themselves are in the pack on disk.'
                }),
                badge(`${data.items.toLocaleString()} items`),
                badge(
                    data.warnings.length ? `${data.warnings.length} warnings` : 'Content validated',
                    data.warnings.length ? 'gold' : 'good'
                )
            ),
            ...data.warnings.map((warning) => el('p', warning, 'warning'))
        );
    }
    // The page is a catalogue of stations at the top and an item search below it: the
    // picker used to sit above the table and pushed every merchant row past the fold.
    node.append(summary, panel('Merchant catalogue', stationTable.node), picker.node);
    async function refresh() {
        data = await ctx.request('content');
        if (!ctx.alive()) return;
        paintSummary();
        stationTable.update(merchants());
        if (!coldApplied) {
            coldApplied = true;
            opened = null;
            applyParams(ctx.params);
        }
    }
    /**
     * Deep links select a station, open one item, or drive the catalogue search,
     * all in place. A param that is gone clears the state it owned.
     */
    function applyParams(params) {
        // The merchant table owns `?merchant=`, and an in-app link has to drive it the
        // same way a cold load does: the table read its parameter when it was built.
        stationTable.setQuery(params.get('merchant') ?? '');
        const search = params.get('search');
        if (search !== null || appliedSearch !== null) {
            appliedSearch = search;
            setPickerQuery(search ?? '');
        }
        const station = params.get('station'),
            item = params.get('item');
        const key = station ? 'station:' + station : item ? 'item:' + item : null;
        if (key === opened) return;
        const previous = opened;
        opened = key;
        if (!key) {
            // The address no longer names a record, so the pane describing one has to
            // go with it: history Back used to leave an inspector open on something the
            // URL had already dropped.
            if (previous) closeDialog();
            return;
        }
        if (station) {
            const found = merchants().find((entry) => keyOf(entry) === station);
            if (found) openStation(found);
            else ctx.notify(`Station ${station} is not in the exported catalogue.`, true);
        } else if (item) showItem(item);
    }
    // The catalogue is not here until the first read, so the address is applied after
    // it: a cold-linked station used to wait for the next poll (~15 s) or be reported
    // as "not in the exported catalogue" for a station the page was about to draw.
    let coldApplied = false;
    await refresh();
    return { node, dispose: () => picker.dispose(), refresh, navigate: applyParams };
}
