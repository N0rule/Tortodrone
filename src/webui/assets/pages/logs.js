import { logStream } from '../components/log-stream.js';
export async function mount(ctx) {
    const stream = logStream();
    async function refresh() {
        if (!stream.paused) stream.update(await ctx.request('logs'));
    }
    await refresh();
    return { node: stream.node, refresh, interval: 4000 };
}
