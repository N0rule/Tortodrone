import { el, toolbar, badge, details, facts, panel, button, copy, report, routeLink } from '../ui.js';
import { itemPicker } from '../components/item-picker.js';
import { definitionsFor, itemLine, itemLinks, inspectItemName } from '../components/items.js';
import { openDialog, closeDialog } from '../components/dialog.js';
import { DataTable } from '../components/table.js';
import { route, go } from '../router.js';
export async function mount(ctx) {
    // Read once, in `refresh()` below. The page used to issue the same `content` read
    // twice per mount — once here, once a few lines down — for the same answer.
    let data = null,
        appliedSearch = ctx.params.get('search'),
        opened = null;
    const node = el('div', null, 'stack'),
        summary = el('div', null, 'stack');
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
                    const cell = el('div', null, 'offer-reference');
                    const label = station.name && station.name !== '~' ? station.name : station.actor;
                    const link = routeLink(label, 'content', { station: keyOf(station) });
                    link.title = station.actor;
                    cell.append(link);
                    if (station.name && station.name !== '~')
                        cell.append(el('small', station.actor, 'muted'));
                    return cell;
                },
                (station) => station.name && station.name !== '~' ? station.name : station.actor
            ],
            ['Kind', (station) => station.kind || '—'],
            [
                'Group',
                // A group is an internal scene path; the leaf name is the label and the
                // path stays available as its tooltip.
                (station) => {
                    const path = station.group || '';
                    const leaf = path.split('/').filter(Boolean).pop() || '—';
                    const node = el('span', leaf);
                    node.title = path;
                    return node;
                },
                (station) => station.group || ''
            ],
            [
                'Catalogue',
                (station) => {
                    if (!station.shop?.catalog) return el('span', 'Recipes only', 'muted');
                    const name = el('span', station.shop.catalog.split('/').slice(-1)[0]);
                    name.title = station.shop.catalog;
                    return name;
                },
                (station) => station.shop?.catalog || ''
            ],
            [
                'Level',
                (station) => station.shop?.level ?? '—',
                (station) => station.shop?.level ?? 0
            ],
            [
                'Recipes',
                (station) => station.recipes?.length ?? 0,
                (station) => station.recipes?.length ?? 0
            ],
            [
                'Model',
                (station) => {
                    const path = station.model || '';
                    const node = el('span', path.split('/').filter(Boolean).pop() || '—');
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
            exportName: 'merchant-stations',
            pageSize: 10,
            empty: {
                title: 'No merchant stations are exported',
                hint: 'Stations come from the exported town catalogue; add one and export again.',
                icon: 'content'
            },
            actions: (get) => [
                button('Open station', () => openStation(get()), 'secondary'),
                button(
                    'Find items',
                    () => {
                        setPickerQuery(get().shop?.catalog || get().actor);
                        picker.node.querySelector('input')?.focus();
                        picker.node.scrollIntoView({ block: 'center' });
                    },
                    'secondary'
                )
            ]
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
        const recipeList = el('div', null, 'station-recipes');
        const recipes = station.recipes || [];
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
        openDialog({
            title: station.name && station.name !== '~' ? station.name : station.actor,
            content: panel(
                '',
                el('p', station.description || station.actor, 'muted'),
                facts([
                    ['Actor', station.actor],
                    ['Kind', station.kind || '—'],
                    ['Group', station.group || '—'],
                    ['Catalogue', station.shop?.catalog || 'none'],
                    [
                        'Shop level / cycle',
                        `${station.shop?.level ?? '—'} · ${station.shop?.cycle || '—'}`
                    ],
                    ['Preparer', station.shop?.preparer || '—'],
                    ['Per knight', station.shop?.perKnight ? 'yes' : 'no'],
                    ['Recipes', String(recipes.length)]
                ]),
                el('h3', 'Recipes'),
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
