import { text, $ } from '../ui.js';
import { logStream } from '../components/log-stream.js';

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
        onLoadFile: (limit) => ctx.request('logs-file', { limit: String(limit) })
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
            text(stream.state, 'Disconnected · ' + error.message);
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
