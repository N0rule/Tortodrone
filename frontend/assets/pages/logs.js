import { logStream } from '../components/log-stream.js';
export async function mount(ctx) {
    const stream = logStream({
        title: 'tortodrone — server log',
        severity: ctx.params.get('severity') || 'All',
        search: ctx.params.get('search') || ''
    });
    async function refresh() {
        if (!stream.paused) stream.update(await ctx.request('logs'));
    }
    await refresh();
    // The log is the whole page, so it fills the reading area like the console.
    stream.node.classList.add('terminal-fill');
    return { node: stream.node, refresh, interval: 4000 };
}
