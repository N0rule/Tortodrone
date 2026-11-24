import { el, toolbar, badge, details, panel, button } from '../ui.js';
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
            ['Station', (station) => station.name || station.actor, (station) => station.name || station.actor],
            ['Kind', (station) => station.kind || '—'],
            ['Group', (station) => station.group || '—'],
            ['Catalogue', (station) => station.shop?.catalog || '—'],
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
            ['Title', (station) => station.title || '—']
        ],
        {
            key: (station) => station.actor || station.name,
            id: 'content-merchants',
            caption: 'Merchant stations in the exported town catalogue',
            exportName: 'merchant-stations',
            pageSize: 10,
            actions: (get) => [
                button('View', () =>
                    openDialog({
                        title: get().name || get().actor,
                        content: panel(
                            '',
                            details('Station definition', get()),
                            el('p', get().description || 'No description in the catalogue.', 'muted')
                        ),
                        submit: 'Close',
                        notifySaved: false,
                        onSubmit: async () => {}
                    })
                ),
                button(
                    'Find items',
                    () => {
                        const field = picker.node.querySelector('input');
                        if (field) {
                            field.value = get().name || get().actor;
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
    stationTable.update(merchants);
    node.append(panel('Merchant catalogue', stationTable.node));
    return { node, dispose: () => picker.dispose() };
}
