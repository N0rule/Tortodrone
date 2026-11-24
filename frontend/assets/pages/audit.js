import { el, date, badge, panel, facts, toolbar, button, copy } from '../ui.js';
import { DataTable } from '../components/table.js';
export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        detail = el('div', null, 'stack');
    // The target is written as username:knight:id, so a detail pane can offer the
    // exact page that owns the record instead of a dead string.
    const parseTarget = (target) => {
        const [username = '', knight = ''] = String(target || '').split(':');
        return { username: username.trim(), knight: knight.trim() };
    };
    const showDetail = (row) => {
        if (!row) {
            detail.replaceChildren(
                el('p', 'Select an event to inspect its recorded outcome and related records.', 'muted')
            );
            return;
        }
        const target = parseTarget(row.target);
        const links = toolbar();
        if (target.username)
            links.append(
                button(
                    'Open account',
                    () => ctx.go('accounts', { username: target.username }),
                    'secondary'
                ),
                button(
                    'Filter by actor',
                    () => ctx.go('audit', { actor: row.actor }),
                    'quiet'
                )
            );
        if (target.username && target.knight)
            links.append(
                button(
                    'Open knight',
                    () => ctx.go('knights', { username: target.username, knight: target.knight }),
                    'secondary'
                )
            );
        links.append(button('Copy event', () => copy(JSON.stringify(row, null, 2), 'Event copied.'), 'quiet'));
        detail.replaceChildren(
            panel(
                'Event detail',
                facts([
                    ['Time', date(row.time)],
                    ['Actor', row.actor],
                    ['Action', row.action],
                    ['Target', row.target || 'None recorded'],
                    ['Outcome', badge(row.outcome, row.outcome === 'committed' ? 'good' : '')],
                    [
                        'Note',
                        row.outcome === 'requested'
                            ? 'Requested without a recorded outcome — the action was interrupted; inspect before retrying.'
                            : 'Recorded outcome of the request.'
                    ]
                ]),
                links
            )
        );
    };
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
            id: 'audit',
            caption: 'Audit trail',
            exportName: 'audit-trail',
            initialQuery: ctx.params.get('actor') || '',
            actions: (get) => [
                button('Inspect', () => showDetail(get()), 'secondary')
            ],
            filters: [
                [
                    'Outcome',
                    ['All', 'requested', 'committed', 'refused', 'denied', 'authenticated'],
                    (r, v) => v === 'All' || r.outcome === v
                ]
            ]
        }
    );
    showDetail(null);
    node.append(detail, table.node);
    async function refresh() {
        table.update(await ctx.request('audit'));
    }
    await refresh();
    return { node, refresh, interval: 10000 };
}
