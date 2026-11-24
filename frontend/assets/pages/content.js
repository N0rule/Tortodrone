import { el, toolbar, badge, details, facts, panel, button, copy } from '../ui.js';
import { itemPicker } from '../components/item-picker.js';
import { itemLine } from '../components/items.js';
import { openDialog } from '../components/dialog.js';
import { DataTable } from '../components/table.js';
export async function mount(ctx) {
    const data = await ctx.request('content'),
        node = el('div', null, 'stack');
    const initial = ctx.params.get('search') || '';
    const picker = itemPicker((def) =>
        openDialog({
            title: def.name,
            content: panel(
                '',
                itemLine(def),
                details('Definition and authoritative properties', def)
            ),
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        }),
        { query: initial }
    );
    node.append(
        toolbar(
            badge(`${data.maps} maps`),
            badge(`${data.items.toLocaleString()} items`),
            badge(
                data.warnings.length ? `${data.warnings.length} warnings` : 'Content validated',
                data.warnings.length ? 'gold' : 'good'
            )
        )
    );
    for (const warning of data.warnings) node.append(el('p', warning, 'warning'));
    node.append(picker.node);
    // The merchant catalogue is operator data: a table with the same search,
    // sort and export as every other list, plus a jump into the catalogue search.
    const merchants = Array.isArray(data.merchants) ? data.merchants : Object.values(data.merchants ?? {});
    const stationTable = new DataTable(
        [
            [
                'Station',
                (station) => {
                    const cell = el('div', null, 'offer-reference');
                    const label = station.name && station.name !== '~' ? station.name : station.actor;
                    cell.append(el('strong', label));
                    if (station.name && station.name !== '~')
                        cell.append(el('small', station.actor, 'muted'));
                    return cell;
                },
                (station) => station.actor
            ],
            ['Kind', (station) => station.kind || '—'],
            ['Group', (station) => station.group || '—'],
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
            ['Model', (station) => station.model?.split('/').slice(-1)[0] || '—']
        ],
        {
            key: (station) => station.actor || station.name,
            id: 'content-merchants',
            caption: 'Merchant stations in the exported town catalogue',
            exportName: 'merchant-stations',
            pageSize: 10,
            actions: (get) => [
                button('Open station', () => openStation(get()), 'secondary'),
                button(
                    'Find items',
                    () => {
                        const field = picker.node.querySelector('input');
                        if (field) {
                            field.value = get().shop?.catalog || get().actor;
                            field.dispatchEvent(new Event('input', { bubbles: true }));
                            field.focus();
                            picker.node.scrollIntoView({ block: 'center' });
                        }
                    },
                    'secondary'
                )
            ]
        }
    );
    function openStation(station) {
        const recipeList = el('div', null, 'station-recipes');
        const recipes = station.recipes || [];
        if (!recipes.length) recipeList.append(el('p', 'No recipes on this station.', 'muted'));
        else
            for (const config of recipes) {
                const row = el('div', null, 'station-recipe');
                row.append(
                    itemLine(undefined, { config }),
                    button(
                        'Open item',
                        () => {
                            const field = picker.node.querySelector('input');
                            if (field) {
                                field.value = config;
                                field.dispatchEvent(new Event('input', { bubbles: true }));
                                field.focus();
                            }
                        },
                        'quiet'
                    )
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
                    ['Shop level / cycle', `${station.shop?.level ?? '—'} · ${station.shop?.cycle || '—'}`],
                    ['Preparer', station.shop?.preparer || '—'],
                    ['Per knight', station.shop?.perKnight ? 'yes' : 'no'],
                    ['Recipes', String(recipes.length)]
                ]),
                el('h3', 'Recipes'),
                recipeList,
                toolbar(button('Copy actor', () => copy(station.actor, 'Actor path copied.'), 'secondary')),
                details('Raw catalogue entry', station)
            ),
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
    }
    stationTable.update(merchants);
    node.append(panel('Merchant catalogue', stationTable.node));
    return { node, dispose: () => picker.dispose() };
}
