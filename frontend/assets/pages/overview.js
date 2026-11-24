import {
    el,
    panel,
    toolbar,
    button,
    select,
    text,
    duration,
    details,
    facts,
    date,
    copy,
    download
} from '../ui.js';
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
    for (const title of [
        'Online players',
        'Active scenes',
        'Listener uptime',
        'Database health',
        'Live monsters',
        'Engaged monsters'
    ]) {
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
    // The handshake identity is the one thing an operator otherwise has to SSH in
    // for; it sits on the page they land on.
    const identityFingerprint = el('code', null, 'identity-fingerprint'),
        identityNote = el('p', null, 'muted identity-note'),
        identityKey = el('textarea', null, 'identity-key'),
        identityActions = toolbar();
    identityKey.readOnly = true;
    identityKey.spellcheck = false;
    identityKey.rows = 3;
    identityKey.hidden = true;
    identityKey.setAttribute('aria-label', 'Server public key, modulus and exponent');
    const identityFacts = facts([
        ['Fingerprint (SHA-256)', identityFingerprint],
        ['Key', ''],
        ['Created', ''],
        ['Recorded in', 'data/keys/public.txt']
    ]);
    const identityPanel = panel('Server identity', identityNote, identityFacts, identityActions, identityKey);
    let identityLoaded = false;
    const toggleKey = button(
        'Show full key',
        () => {
            identityKey.hidden = !identityKey.hidden;
            toggleKey.textContent = identityKey.hidden ? 'Show full key' : 'Hide full key';
            toggleKey.setAttribute('aria-expanded', String(!identityKey.hidden));
            if (!identityKey.hidden) {
                // Revealed on demand, so select it in one gesture to copy by hand.
                identityKey.focus();
                identityKey.select();
            }
        },
        'secondary'
    );
    toggleKey.setAttribute('aria-expanded', 'false');
    const monsters = new DataTable(
        [
            ['Scene', (r) => r.name],
            ['State', (r) => r.state],
            ['Players', (r) => r.players],
            ['Live', (r) => r.monsters.total],
            ['Awake', (r) => r.monsters.awake],
            ['Engaged', (r) => r.monsters.engaged],
            ['Spawned', (r) => r.monsters.spawned],
            ['Despawned', (r) => r.monsters.despawned],
            ['Path searches', (r) => r.monsters.pathSearches],
            ['Queued', (r) => r.monsters.pathQueued],
            ['Dropped', (r) => r.monsters.pathDropped],
            ['Path failures', (r) => r.monsters.pathFailures],
            [
                'Tick p99',
                (r) => r.monsters.tickP99Millis.toFixed(2),
                (r) => r.monsters.tickP99Millis
            ]
        ],
        {
            key: (r) => `${r.templateId}:${r.instanceId}`,
            pageSize: 8,
            id: 'overview-monsters',
            caption: 'Scene monster runtime',
            exportName: 'scene-monsters',
            actions: (get) => [
                button(
                    'Players here',
                    () => ctx.go('players', { scene: get().name }),
                    'secondary'
                )
            ]
        }
    );
    const warnings = new DataTable(
        [
            ['Time', (r) => date(r.timestamp), (r) => r.timestamp],
            ['Severity', (r) => r.level],
            ['Message', (r) => r.message]
        ],
        {
            key: (r) => `${r.timestamp}:${r.thread}:${r.message}`,
            pageSize: 8,
            id: 'overview-warnings',
            caption: 'Recent warnings and errors',
            exportName: 'recent-warnings',
            actions: (get) => [
                button(
                    'Open in Logs',
                    () => ctx.go('logs', { severity: get().level }),
                    'secondary'
                )
            ]
        }
    );
    node.append(
        strip,
        identityPanel,
        metrics,
        toolbar(range, el('small', 'Measured every 5 seconds · retained in memory for 1 hour')),
        charts,
        panel('Scene monster runtime', monsters.node),
        panel('Recent warnings and errors', warnings.node),
        diagnostics
    );
    let diagnosticSignature;
    async function renderIdentity(identity) {
        if (!identity.available) {
            text(identityFingerprint, 'Unavailable');
            text(
                identityNote,
                identity.reason ||
                    'The server has not created its keypair yet; it is created on the next start.'
            );
            identityActions.replaceChildren();
            return;
        }
        text(identityFingerprint, identity.fingerprint);
        identityFingerprint.title = identity.fingerprintBase64;
        text(
            identityNote,
            `RSA ${identity.bits}-bit · the public half only. Clients use this to trust the server; the private key never leaves the server.`
        );
        const cells = identityFacts.querySelectorAll('dd');
        cells[1].textContent = `RSA ${identity.bits}-bit, exponent 0x${identity.exponent}`;
        cells[2].textContent = date(identity.modified);
        const key = `${identity.modulus}#${identity.exponent}`;
        identityKey.value = key;
        if (!identityLoaded) {
            identityLoaded = true;
            identityActions.append(
                button('Copy public key', () => copy(key, 'Server public key copied.'), 'primary'),
                button(
                    'Download public.txt',
                    () => download('public.txt', key + '\n'),
                    'secondary'
                ),
                toggleKey,
                button(
                    'Copy fingerprint',
                    () => copy(identity.fingerprint, 'Fingerprint copied.'),
                    'secondary'
                )
            );
        }
    }
    async function refresh() {
        const [s, history, logs, identity] = await Promise.all([
            ctx.request('status'),
            ctx.request('metrics', { minutes: range.value }),
            ctx.request('logs'),
            ctx.request('identity')
        ]);
        renderIdentity(identity);
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
        text(
            counters[4][0],
            s.scenes.reduce((a, v) => a + v.monsters.total, 0)
        );
        text(counters[4][1], 'live in active scenes');
        text(
            counters[5][0],
            s.scenes.reduce((a, v) => a + v.monsters.engaged, 0)
        );
        text(counters[5][1], 'full pursuit and attack');
        monsters.update(s.scenes);
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
