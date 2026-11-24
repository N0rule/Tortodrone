import { text } from '../ui.js';
import { logStream } from '../components/log-stream.js';
export async function mount(ctx) {
    const stream = logStream({
        title: 'tortodrone — server log',
        severity: ctx.params.get('severity') || 'All',
        search: ctx.params.get('search') || ''
    });
    // A failing read is a terminal state, not a page-level crash: the last rows
    // stay on screen and the status line names the reason until a read succeeds.
    async function refresh() {
        if (stream.paused) return;
        try {
            stream.update(await ctx.request('logs'));
            text(stream.state, '');
            stream.state.classList.remove('error');
        } catch (error) {
            text(stream.state, 'Disconnected · ' + error.message);
            stream.state.classList.add('error');
            throw error;
        }
    }
    await refresh();
    // The log is the whole page, so it fills the reading area like the console.
    stream.node.classList.add('terminal-fill');
    return { node: stream.node, refresh, interval: 4000 };
}
