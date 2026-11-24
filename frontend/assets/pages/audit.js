import {
    el,
    date,
    badge,
    panel,
    facts,
    toolbar,
    button,
    routeLink,
    copy,
    text,
    landOn
} from '../ui.js';
import { route } from '../router.js';
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
            [/^(webui-user|login|logout)/, ['webui-users', 'Open WebUI identities']],
            [/^console/, ['console', 'Open the Console']],
            [/^kick/, ['players', 'Open live sessions']],
            [/^(status|logs|metrics)$/, ['logs', 'Open Logs']]
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
        // takes the reader to itself instead of painting off-screen — after the page
        // is attached, which for a cold load is a frame or two later.
        landOn(detail, 'start');
        table.markInspected?.(row);
        const target = parseTarget(row.target);
        const links = toolbar(
            button(
                'Clear selection',
                () => {
                    showDetail(null);
                    detail.hidden = true;
                    if (route().params.has('event')) ctx.go('audit', {});
                },
                'secondary'
            )
        );
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
                // The inspector has an address of its own, so an event can be cited.
                routeLink('Inspect', 'audit', { event: get().id }, 'button secondary')
            ],
            filters: [
                [
                    'Outcome',
                    ['All', 'requested', 'committed', 'refused', 'denied', 'authenticated'],
                    (r, v) => v === 'All' || r.outcome === v
                ],
                [
                    // The trail was searchable and outcome-filtered but had no time
                    // dimension at all, so "what happened yesterday" meant scrolling.
                    // Buckets rather than two date fields: the table's filters are
                    // selects, and a range picker nobody can use is worse than none.
                    'Period',
                    ['All time', 'Last hour', 'Last 24 hours', 'Last 7 days'],
                    (r, v) => {
                        const span = {
                            'Last hour': 3_600_000,
                            'Last 24 hours': 86_400_000,
                            'Last 7 days': 604_800_000
                        }[v];
                        if (!span) return true;
                        const at = Date.parse(r.time);
                        // A row whose time cannot be parsed is never silently dropped
                        // into "recent": it stays visible under every window.
                        return !Number.isFinite(at) || Date.now() - at <= span;
                    }
                ]
            ]
        }
    );
    showDetail(null);
    // The trail is the page; the inspector appears above it once a row is chosen, so
    // the first thing an operator reads is the events, not an empty pane.
    detail.hidden = true;
    // The read is capped at the newest 500 rows and this page used to say "Every
    // privileged request" over it: an operator on a busy server read the bottom row as
    // the beginning of the trail. The cap, and where the rest lives, are stated.
    const CAP = 500;
    const scope = el('small', null, 'muted');
    let paused = false,
        reading = false,
        // Pages the operator asked for, older than the live first page. Kept apart so a
        // poll can replace the newest page without dropping what was paged back to.
        older = [],
        exhausted = false;
    const more = button(
        'Older events',
        async () => {
            const ids = table.rows.map((r) => r.id);
            if (!ids.length) return;
            more.setBusy?.(true);
            try {
                const page = await ctx.request('audit', { before: Math.min(...ids) });
                // A short page is the end of the trail: say so instead of offering a
                // button that will fetch nothing for ever.
                exhausted = page.length < CAP;
                older = [...older, ...page];
                table.update([...table.rows, ...page]);
                more.refreshAvailability?.();
                describeScope();
            } catch (error) {
                ctx.report(error);
            } finally {
                more.setBusy?.(false);
            }
        },
        'secondary',
        () => !exhausted,
        // Without this the control is `aria-disabled` with no explanation on every trail
        // shorter than 500 rows — which is every fresh deployment — and a press answered
        // with the generic "not available on this server yet".
        { unavailable: 'That is the whole trail — there is nothing older to read.' }
    );
    more.title = 'Read the next 500 events older than the ones on screen';
    more.setAttribute('aria-label', 'Read older audit events');
    const pause = button('Pause', () => {
        paused = !paused;
        // The accessible name is the label, not a fixed string: it said "Pause the
        // audit trail" forever while the visible word flipped to Resume, so a screen
        // reader was told the opposite action. `aria-pressed` carries the state.
        text(pause, paused ? 'Resume' : 'Pause');
        pause.setAttribute('aria-pressed', String(paused));
        pause.title = paused
            ? 'Start reading the trail again'
            : 'Stop reading the trail; the rows on screen stay put';
        describeScope();
        if (!paused) refresh().catch(() => {});
    });
    pause.setAttribute('aria-pressed', 'false');
    function describeScope() {
        const count = table.rows.length;
        const pages = Math.max(1, Math.ceil(count / CAP));
        const tail = `${count.toLocaleString()} event${count === 1 ? '' : 's'} loaded${
            pages > 1 ? ` over ${pages} pages` : ''
        }.`;
        scope.textContent = paused
            ? `Paused: ${tail} The ten-second tick is paused; the header's Refresh, a write's own refresh and Older events still read.`
            : exhausted
              ? `${tail} That is the whole trail — Older events found nothing more.`
              : `${tail} The server sends the newest ${CAP} at a time; Older events reads back through data/webui.sqlite.`;
    }
    // The inspector sits above the trail: appended after sixty rows it was 2,200px
    // below the fold, so the citation link a row emits looked like a dead click.
    node.append(detail, toolbar(scope, more, pause), table.node);
    let pendingEvent = null;
    /**
     * `force` is the operator asking: the header's Refresh button, a `data-changed`
     * event from a write, or Resume. A pause stops the *poll*, not a deliberate read —
     * silently ignoring the header's Refresh while the button still greyed itself was
     * the same lie as the sticky notice it replaced. `reading` alone is what a second
     * concurrent read respects.
     */
    async function refresh(force = false) {
        // The ten-second poll used to re-insert rows at the top under whoever was
        // reading them; a pause stops that, not the operator's own refresh.
        if (reading || (paused && !force)) return;
        reading = true;
        let fresh;
        try {
            fresh = await ctx.request('audit');
        } finally {
            reading = false;
        }
        // The newest page is re-read; the pages the operator paged back to are kept, or a
        // poll would silently throw away everything below the first 500 rows.
        table.update([...fresh, ...older]);
        // A short first page is the end of the trail. Once the operator has paged back,
        // only the page they read decides that.
        if (!older.length) exhausted = fresh.length < CAP;
        // `available` was sampled when the button was built, so it has to be re-asked.
        more.refreshAvailability?.();
        describeScope();
        // A cold load applies the address before the trail exists; the event it names
        // is opened as soon as there are rows to find it in.
        if (pendingEvent !== null) {
            const wanted = pendingEvent;
            const row = table.rows.find((r) => r.id === wanted);
            pendingEvent = null;
            if (row) {
                showDetail(row);
                table.markInspected?.(row);
            } else ctx.notify(`There is no audit event #${wanted} on this server.`);
        }
    }
    await refresh();
    // A cold load carries the address too. Without this the inspector a row's own
    // href names stayed empty until the operator clicked it again.
    if (ctx.params.size) applyParams(ctx.params);
    /** Deep links (#audit?actor=…) re-filter the trail in place. */
    function applyParams(params) {
        // Navigating back to a bare #audit clears the filter instead of leaving the
        // previous actor's rows on screen. `target` names a record (a game account
        // or knight); `actor` names the WebUI identity that acted; `event` picks one
        // recorded event and opens its inspector.
        table.query.value = params.get('actor') || params.get('target') || '';
        table.query.dispatchEvent(new Event('input'));
        showDetail(null);
        const wanted = Number(params.get('event'));
        if (Number.isInteger(wanted) && wanted > 0) {
            const row = table.rows.find((r) => r.id === wanted);
            if (row) {
                showDetail(row);
                table.markInspected?.(row);
            } else if (table.rows.length) {
                // The trail is already on screen, so the miss is reported now instead of
                // after the next poll: a citation that lost its row looked like a link
                // that did nothing.
                ctx.notify(`There is no audit event #${wanted} on this server.`);
                pendingEvent = null;
            } else pendingEvent = wanted;
        }
    }
    return {
        node,
        // The shell's Refresh button and the refresh a write triggers are deliberate
        // reads, so a pause does not swallow them; the ten-second tick goes through
        // `poll` and is, which is the whole point of the pause. The shell already
        // refreshes every page on `data-changed`, so no listener of our own.
        refresh: () => refresh(true),
        poll: () => refresh(false),
        interval: 10000,
        navigate: applyParams
    };
}
