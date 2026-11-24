import { button, text, $ } from '../ui.js';
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
        raw: ctx.params.get('raw') === '1',
        fill: true,
        onLoadFile: (limit) => ctx.request('logs-file', { limit: String(limit) }),
        // Below 601px these three cost a whole row of chrome the pane cannot spare,
        // and the drawer already lists all three pages.
        extras: window.matchMedia('(min-width: 601px)').matches
            ? RELATED.map(([page, label]) => {
                  const go = button(label, () => ctx.go(page), 'link');
                  go.title = `Open ${label} — the page that explains what this line is about`;
                  return go;
              })
            : [],
        // The filter is the view, so it belongs in the address: the operator can copy
        // what they are looking at instead of describing it to a colleague. Replace
        // rather than push, so a typed search does not stack history entries.
        onFilter: ({ severity, search }) => {
            // Only the two params this filter owns are rewritten: `raw=1` describes the
            // same pane and used to be dropped by the first keystroke.
            const params = route().params;
            if (severity && severity !== 'All') params.set('severity', severity);
            else params.delete('severity');
            if (search) params.set('search', search);
            else params.delete('search');
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
                search: params.get('search') || ''
            });
        }
    };
}
