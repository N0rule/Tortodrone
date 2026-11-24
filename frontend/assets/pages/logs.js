import { el, button, text, $ } from '../ui.js';
import { route } from '../router.js';
import { logStream } from '../components/log-stream.js';

/**
 * A warning in the log names a subsystem and stops there. These three pages are
 * where an operator checks what the line is about, so they sit in the same bar as
 * the search instead of being three routes to remember.
 */
const RELATED = [
    ['audit', 'Audit trail'],
    ['accounts', 'Accounts'],
    ['overview', 'Overview']
];

/**
 * The three pages, as one labelled group separated from the filters by a rule. They
 * leave the pane and the controls beside them shape it, and an unlabelled row of
 * three words at the end of a toolbar read as three more filters.
 */
function relatedStrip(ctx) {
    const strip = el('div', null, 'toolbar-links');
    strip.append(el('span', 'Related', 'toolbar-links-label'));
    for (const [page, label] of RELATED) {
        const go = button(label, () => ctx.go(page), 'link');
        go.title = `Open ${label} — the page that explains what this line is about`;
        strip.append(go);
    }
    return strip;
}

/**
 * The log page is a tail, not a table: it fills the reading area, keeps the last
 * rows visible through a failed read, and says where the complete file lives.
 *
 * A failed poll backs off instead of hammering the server every four seconds, and
 * the first successful read clears the error notice the failure raised.
 */
export async function mount(ctx) {
    const stream = logStream({
        title: 'tortodrone — server log',
        severity: ctx.params.get('severity') || 'All',
        search: ctx.params.get('search') || '',
        period: ctx.params.get('period') || 'All time',
        raw: ctx.params.get('raw') === '1',
        wrap: ctx.params.get('wrap') !== '0',
        fill: true,
        onLoadFile: (limit) => ctx.request('logs-file', { limit: String(limit) }),
        // Below 601px these three cost a whole row of chrome the pane cannot spare,
        // and the drawer already lists all three pages.
        extras: window.matchMedia('(min-width: 601px)').matches ? [relatedStrip(ctx)] : [],
        // The filter is the view, so it belongs in the address: the operator can copy
        // what they are looking at instead of describing it to a colleague. Replace
        // rather than push, so a typed search does not stack history entries.
        onFilter: ({ severity, search, period, raw, wrap }) => {
            // Every control that shapes the pane is written back, not only the two the
            // filters used to own: a URL could say `raw=1` while the pane showed parsed
            // lines, and the toggles never appeared in a copied link at all.
            const params = route().params;
            if (severity && severity !== 'All') params.set('severity', severity);
            else params.delete('severity');
            if (search) params.set('search', search);
            else params.delete('search');
            if (period && period !== 'All time') params.set('period', period);
            else params.delete('period');
            if (raw) params.set('raw', '1');
            else params.delete('raw');
            if (wrap === false) params.set('wrap', '0');
            else params.delete('wrap');
            const hash = '#logs' + (params.size ? '?' + params : '');
            if (location.hash !== hash) window.history.replaceState(null, '', hash);
        }
    });
    let failures = 0;
    // A failing read is a terminal state, not a page-level crash: the last rows
    // stay on screen and the status line names the reason until a read succeeds.
    async function refresh() {
        try {
            stream.update(await ctx.request('logs'));
            if (failures) {
                failures = 0;
                const notice = $('#notice');
                if (notice && !notice.hidden) notice.hidden = true;
            }
            text(stream.state, '');
            stream.state.classList.remove('error');
            // The LED is computed while painting, so a recovering stream repaints to
            // stop showing the failure colour it earned a moment ago.
            if (failures === 0) stream.redraw();
        } catch (error) {
            failures++;
            // The same failure says the same thing in both panes, and no raw browser
            // string: "Disconnected · Failed to fetch" was a TypeError read aloud.
            text(stream.state, 'Offline · reconnecting');
            stream.state.classList.add('error');
            // Same reason, other direction: a dead stream must not keep a calm green
            // LED, so repaint the status and the LED with the error in place.
            stream.redraw();
            throw error;
        }
    }
    await refresh();
    return {
        node: stream.node,
        refresh,
        /** The stream listens on `document` and `window`; leaving the page detaches it. */
        dispose: () => stream.dispose?.(),
        // Back off while the server is unreachable, then come straight back.
        get interval() {
            return failures ? Math.min(4000 * 2 ** failures, 60000) : 4000;
        },
        // A deep link such as #logs?severity=WARN re-filters in place.
        navigate(params) {
            stream.fit();
            stream.filter({
                severity: params.get('severity') || 'All',
                search: params.get('search') || '',
                period: params.get('period') || 'All time',
                raw: params.get('raw') === '1',
                wrap: params.get('wrap') !== '0'
            });
        }
    };
}
