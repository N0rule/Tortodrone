import { el, date, badge, panel, facts, toolbar, button, copy } from '../ui.js';
import { DataTable } from '../components/table.js';
export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        detail = el('div', null, 'stack');
    // The target names what was acted on: "username", "username:knight", a mailing's
    // subject, or nothing at all when the action has no subject. Older rows still
    // carry the "username:knight:id" shape, so a trailing numeric id is dropped.
    const parseTarget = (target) => {
        const parts = String(target || '')
            .split(':')
            .map((part) => part.trim());
        const [first = '', second = ''] = parts;
        const looksLikeAccount = first && !first.includes(' ') && parts.length < 3;
        const looksLikeKnight = second && !/^\d+$/.test(second);
        return {
            username: looksLikeAccount ? first : '',
            knight: looksLikeKnight ? second : '',
            label: String(target || '').trim()
        };
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
        // The table's search covers every column, so "who else did this" needs no
        // second control, just a button that fills it in.
        links.append(
            button(
                'Find this action',
                () => {
                    table.query.value = row.action;
                    table.query.dispatchEvent(new Event('input'));
                },
                'quiet'
            ),
            button('Copy event', () => copy(JSON.stringify(row, null, 2), 'Event copied.'), 'quiet')
        );
        detail.replaceChildren(
            panel(
                'Event detail',
                facts([
                    ['Time', date(row.time)],
                    ['Actor', row.actor],
                    ['Action', row.action],
                    ['Target', parseTarget(row.target).label || 'No subject recorded'],
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
            ['Target', (r) => parseTarget(r.target).label || '—'],
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
    /** Deep links (#audit?actor=…) re-filter the trail in place. */
    function applyParams(params) {
        // Navigating back to a bare #audit clears the filter instead of leaving the
        // previous actor's rows on screen.
        table.query.value = params.get('actor') || '';
        table.query.dispatchEvent(new Event('input'));
    }
    return { node, refresh, interval: 10000, navigate: applyParams };
}
