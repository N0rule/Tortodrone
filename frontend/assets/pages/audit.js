import { el, date, badge, panel, facts, toolbar, button, routeLink, copy } from '../ui.js';
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
        return {
            // "name:name" is a knight; a bare word is a record name, and only the
            // recorded action can say which kind of record it is.
            username: second ? first : /^[^\s]+$/.test(first) ? first : '',
            knight: second && /^[^\s]+$/.test(second) ? second : '',
            label: String(target || '').trim()
        };
    };
    /** The page that owns what an action changed, so the trail is not a dead end. */
    const ownerOf = (action) => {
        const table = [
            [/^mail/, ['mailing', 'Open Player Mail']],
            [/^(depot|offer|promotion)/, ['depot', 'Open the Depot']],
            [/^auction/, ['auctions', 'Open auctions']],
            [/^(news|welcome)/, ['news', 'Open News']],
            [/^content/, ['content', 'Open Content']],
            [/^(webui-user|login|logout)/, ['webui-users', 'Open WebUI identities']]
        ];
        const match = table.find(([pattern]) => pattern.test(action));
        return match ? { page: match[1][0], label: match[1][1] } : null;
    };
    let selected = null;
    const placeholder = () =>
        el('p', 'Select an event to inspect its recorded outcome and related records.', 'muted');
    const showDetail = (row) => {
        // `row` is the record, not an element: table.markInspected owns the
        // highlight, and touching it here threw on the second Inspect and left the
        // pane pinned to the first event.
        selected = row || null;
        // No row, no pane: an empty inspector above the trail is the first thing an
        // operator sees on a page that has nothing to inspect yet.
        detail.hidden = !row;
        if (!row) {
            detail.replaceChildren(placeholder());
            return;
        }
        // The pane sits above a long table, so it says which row it describes and
        // takes the reader to itself instead of painting off-screen.
        detail.scrollIntoView({ block: 'nearest' });
        table.markInspected?.(row);
        const target = parseTarget(row.target);
        const links = toolbar();
        const owner = ownerOf(row.action);
        // Every route out of the inspector is a real link, so the operator can
        // middle-click it into a second tab while he keeps reading.
        if (owner) links.append(routeLink(owner.label, owner.page, {}, 'button secondary'));
        // Only an account-scoped action names an account. A console command or a
        // mail subject is a word, not a username, and must not offer a link that
        // lands on "No such account".
        const accountScoped = /^(account|knight|login|logout|webui-user)/.test(row.action);
        if (target.username && !target.knight && accountScoped)
            links.append(
                routeLink(
                    'Open account',
                    'accounts',
                    { username: target.username },
                    'button secondary'
                )
            );
        if (target.label)
            links.append(
                routeLink('Filter by this target', 'audit', { target: target.label }, 'button quiet')
            );
        links.append(routeLink('Filter by actor', 'audit', { actor: row.actor }, 'button quiet'));
        if (target.username && target.knight)
            links.append(
                routeLink(
                    'Open knight',
                    'knights',
                    { username: target.username, knight: target.knight },
                    'button secondary'
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
            [
                'Actor',
                (r) => {
                    const open = el('button', r.actor, 'link');
                    open.type = 'button';
                    open.onclick = () => ctx.go('audit', { actor: r.actor });
                    return open;
                }
            ],
            ['Action', (r) => r.action],
            [
                'Target',
                (r) => {
                    const label = parseTarget(r.target).label;
                    if (!label) return '—';
                    const open = el('button', label, 'link');
                    open.type = 'button';
                    open.onclick = () => ctx.go('audit', { target: label });
                    return open;
                }
            ],
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
            initialQuery: ctx.params.get('actor') || ctx.params.get('target') || '',
            empty: {
                title: 'No audit events match this view',
                hint: 'Every WebUI write is recorded here. Clear the search to see the whole trail.',
                icon: 'audit'
            },
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
    // The trail is the page; the inspector appears above it once a row is chosen, so
    // the first thing an operator reads is the events, not an empty pane.
    detail.hidden = true;
    node.append(table.node, detail);
    async function refresh() {
        table.update(await ctx.request('audit'));
    }
    await refresh();
    /** Deep links (#audit?actor=…) re-filter the trail in place. */
    function applyParams(params) {
        // Navigating back to a bare #audit clears the filter instead of leaving the
        // previous actor's rows on screen. `target` names a record (a game account
        // or knight); `actor` names the WebUI identity that acted.
        table.query.value = params.get('actor') || params.get('target') || '';
        table.query.dispatchEvent(new Event('input'));
        showDetail(null);
    }
    return { node, refresh, interval: 10000, navigate: applyParams };
}
