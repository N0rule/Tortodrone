import { el, panel, toolbar, button, select, text, duration, details, facts, date } from '../ui.js';
import { chart } from '../components/chart.js';
import { confirm, field } from '../components/dialog.js';
import { DataTable } from '../components/table.js';

export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        strip = el('div', null, 'server-strip'),
        state = el('strong'),
        endpoint = el('small'),
        server = el('div'),
        version = el('span', null, 'muted');
    server.append(state, endpoint);
    strip.append(server, version);
    if (ctx.admin)
        strip.append(
            toolbar(
                ...[
                    ['Start', 'start'],
                    ['Stop', 'stop'],
                    ['Restart', 'restart']
                ].map(([title, route]) =>
                    button(title, () =>
                        confirm(
                            title + ' game listener',
                            route,
                            {},
                            [],
                            route === 'start'
                                ? 'Start the game listener.'
                                : 'Connected players will be disconnected. HTTP administration remains available.'
                        )
                    )
                ),
                button(
                    'Shut down',
                    () =>
                        confirm(
                            'Shut down process',
                            'shutdown',
                            {},
                            [field('shutdown', 'Type SHUT DOWN PROCESS')],
                            'Stops the entire server process, including this WebUI.'
                        ),
                    'danger'
                )
            )
        );
    const metrics = el('div', null, 'metric-strip'),
        counters = [];
    for (const title of ['Online players', 'Active scenes', 'Listener uptime', 'Database health']) {
        const n = el('div', null, 'metric'),
            value = el('strong'),
            sub = el('small');
        n.append(el('span', title, 'muted'), value, sub);
        metrics.append(n);
        counters.push([value, sub]);
    }
    const range = select(
        'Monitoring range',
        [
            [5, '5 minutes'],
            [15, '15 minutes'],
            [60, '1 hour']
        ],
        5
    );
    const cpu = chart('Process CPU', '%', [
        { name: 'CPU', value: (s) => (s.runtime.processCpu < 0 ? -1 : s.runtime.processCpu * 100) }
    ]);
    const memory = chart('JVM heap', 'MiB', [
        { name: 'Used', value: (s) => s.runtime.heapUsed / 1048576 },
        { name: 'Committed', value: (s) => s.runtime.heapCommitted / 1048576 }
    ]);
    const population = chart('Players and scenes', '', [
        { name: 'Players', value: (s) => s.players },
        { name: 'Scenes', value: (s) => s.scenes }
    ]);
    const activity = chart('Connection activity', 'events / sample', [
        { name: 'Connected', value: (s, i, a) => (i ? s.connected - a[i - 1].connected : -1) },
        {
            name: 'Disconnected',
            value: (s, i, a) => (i ? s.disconnected - a[i - 1].disconnected : -1)
        }
    ]);
    const charts = el('div', null, 'two-columns');
    charts.append(cpu.node, memory.node, population.node, activity.node);
    const diagnostics = details('Database operations and runtime details', {});
    const warnings = new DataTable(
        [
            ['Time', (r) => date(r.timestamp), (r) => r.timestamp],
            ['Severity', (r) => r.level],
            ['Message', (r) => r.message]
        ],
        { key: (r) => `${r.timestamp}:${r.thread}:${r.message}`, pageSize: 8 }
    );
    node.append(
        strip,
        metrics,
        toolbar(range, el('small', 'Measured every 5 seconds · retained in memory for 1 hour')),
        charts,
        panel('Recent warnings and errors', warnings.node),
        diagnostics
    );
    let diagnosticSignature;
    async function refresh() {
        const [s, history, logs] = await Promise.all([
            ctx.request('status'),
            ctx.request('metrics', { minutes: range.value }),
            ctx.request('logs')
        ]);
        text(state, s.state);
        text(endpoint, s.endpoint || 'Listener stopped');
        text(version, `Tortodrone ${s.version}`);
        text(counters[0][0], s.sessions.filter((p) => p.knightId != null).length);
        text(counters[0][1], `${s.sessions.length} connections`);
        text(counters[1][0], s.scenes.length);
        text(counters[1][1], `${s.scenes.reduce((a, v) => a + v.players, 0)} scene occupants`);
        text(counters[2][0], duration(s.uptimeSeconds));
        text(counters[2][1], s.failure || '');
        text(counters[3][0], s.database.failures ? 'Review' : 'Available');
        text(counters[3][1], `${s.accounts} accounts`);
        for (const c of [cpu, memory, population, activity]) c.update(history);
        warnings.update(logs.filter((r) => ['WARN', 'ERROR'].includes(r.level)).reverse());
        const signature = JSON.stringify(s.database);
        if (signature !== diagnosticSignature) {
            text(
                diagnostics.querySelector('pre'),
                JSON.stringify({ database: s.database, runtime: s.metrics }, null, 2)
            );
            diagnosticSignature = signature;
        }
        ctx.connection(s.state, s.state === 'RUNNING');
    }
    range.onchange = () => ctx.refresh();
    await refresh();
    return { node, refresh, interval: 5000 };
}
