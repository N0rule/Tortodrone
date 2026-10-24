import { el, date, badge } from '../ui.js';
import { DataTable } from '../components/table.js';
export async function mount(ctx) {
    const node = el('div');
    const table = new DataTable(
        [
            ['Time', (r) => date(r.time), (r) => r.time],
            ['Actor', (r) => r.actor],
            ['Action', (r) => r.action],
            ['Target', (r) => r.target],
            [
                'Outcome',
                (r) =>
                    badge(
                        r.outcome,
                        r.outcome === 'committed'
                            ? 'good'
                            : ['refused', 'denied'].includes(r.outcome)
                              ? 'bad'
                              : ''
                    )
            ]
        ],
        {
            key: (r) => r.id,
            filters: [
                [
                    'Outcome',
                    ['All', 'requested', 'committed', 'refused', 'denied', 'authenticated'],
                    (r, v) => v === 'All' || r.outcome === v
                ]
            ]
        }
    );
    node.append(table.node);
    async function refresh() {
        table.update(await ctx.request('audit'));
    }
    await refresh();
    return { node, refresh, interval: 10000 };
}
