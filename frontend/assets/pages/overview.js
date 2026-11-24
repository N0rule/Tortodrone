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
    // The three listener controls are weighted by what the server is doing: the
    // one an operator most likely wants next is filled, the impossible one is off
    // and says why. They read `listenerRunning`, refreshed with every poll.
    let listenerRunning = null;
    const listenerButtons = new Map();
    const listenerButton = (title, route) => {
        const node = button(
            title,
            () =>
                confirm(
                    title + ' game listener',
                    route,
                    {},
                    [],
                    route === 'start'
                        ? 'Start the game listener.'
                        : 'Connected players will be disconnected. HTTP administration remains available.'
                ),
            'secondary',
            () => {
                // Before the first status lands the controls stay usable: guessing
                // "off" would flash a disabled Start on every page load.
                if (listenerRunning === null) return true;
                if (route === 'start')
                    return listenerRunning ? 'The listener is already running.' : true;
                if (route === 'stop')
                    return listenerRunning ? true : 'The listener is already stopped.';
                return true;
            }
        );
        listenerButtons.set(route, node);
        return node;
    };
    if (ctx.admin)
        strip.append(
            toolbar(
                ...['start', 'stop', 'restart'].map((route) =>
                    listenerButton(route[0].toUpperCase() + route.slice(1), route)
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
    // Each tile is the most inviting click target on the landing page, so each one
    // goes where its number comes from instead of being a decorated div.
    for (const [title, destination, hint] of [
        ['Online players', 'players', 'Open live sessions'],
        ['Active scenes', 'players', 'Open scene population'],
        ['Listener uptime', 'settings', 'Open the WebUI session details'],
        ['Database health', 'diagnostics', 'Open database operations and runtime details'],
        ['Live monsters', 'overview', 'Open scene monster runtime'],
        ['Engaged monsters', 'overview', 'Open scene monster runtime']
    ]) {
        const n = el('button', null, 'metric'),
            value = el('strong'),
            sub = el('small');
        n.type = 'button';
        n.title = hint;
        n.append(el('span', title, 'muted'), value, sub);
        n.onclick = () => {
            if (destination === 'diagnostics') {
                diagnostics.open = true;
                diagnostics.scrollIntoView({ block: 'center' });
                return;
            }
            if (destination === 'overview') {
                monsters.node.scrollIntoView({ block: 'start' });
                monsters.node.querySelector('table th button')?.focus();
                return;
            }
            ctx.go(destination);
        };
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
        identityWarning = el('p', null, 'identity-warning'),
        identityKey = el('textarea', null, 'identity-key'),
        identityActions = toolbar();
    identityKey.readOnly = true;
    identityKey.spellcheck = false;
    identityKey.hidden = true;
    identityKey.rows = 6;
    identityKey.setAttribute('aria-label', 'Server public key, modulus and exponent');
    identityWarning.hidden = true;
    const identityBits = el('span'),
        identityExponent = el('span'),
        identityFileHash = el('code', null, 'identity-file-hash'),
        identityModified = el('span'),
        identityPair = el('span');
    const identityFacts = facts([
        ['Fingerprint (SHA-256)', identityFingerprint],
        ['Key', identityBits],
        ['Exponent', identityExponent],
        ['Public file SHA-256', identityFileHash],
        ['Public file modified', identityModified],
        ['Signing key', identityPair],
        ['Recorded in', 'data/keys/public.txt']
    ]);
    const identityPanel = panel(
        'Server identity',
        identityNote,
        identityFacts,
        identityActions,
        identityWarning,
        identityKey
    );
    // Handlers read the current key at click time, so a rotated key is the one an
    // operator copies; the panels are built once and never hold stale values.
    let identityLoaded = false,
        identityCurrent = null;
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
            [
                'Scene',
                (r) => {
                    const open = el('button', r.name, 'link');
                    open.type = 'button';
                    open.onclick = () => ctx.go('players', { scene: r.name });
                    return open;
                }
            ],
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
            // A scene name in the URL pre-filters the runtime table, so the
            // Players page can link straight to one scene here.
            initialQuery: ctx.params.get('scene') || '',
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
                    // The row names a line; the log page can filter to that line.
                    () => ctx.go('logs', { severity: get().level, search: get().message }),
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
    const currentKey = () =>
        identityCurrent ? `${identityCurrent.modulus}#${identityCurrent.exponent}` : '';
    async function renderIdentity(identity) {
        if (!identity.available) {
            // A degraded panel still tells the operator what exists on disk: "no
            // key" and "a key I cannot read" are different problems.
            text(identityFingerprint, 'Unavailable');
            text(
                identityNote,
                identity.reason ||
                    'The server has not created its keypair yet; it is created on the next start.'
            );
            text(identityBits, identity.keyStored ? 'signing key present' : 'no keypair yet');
            text(identityExponent, '—');
            text(identityFileHash, '—');
            text(identityModified, '—');
            text(identityPair, identity.keyStored ? 'unknown' : 'absent');
            identityKey.value = '';
            identityKey.hidden = true;
            toggleKey.textContent = 'Show full key';
            toggleKey.setAttribute('aria-expanded', 'false');
            identityWarning.hidden = false;
            identityWarning.textContent = identity.keyStored
                ? 'A signing key is stored but its public half could not be read. Nothing here is served to clients until data/keys/public.txt is fixed.'
                : 'Nothing here is served to clients yet: the first start creates data/keys/private.pk8 and data/keys/public.txt.';
            identityActions.replaceChildren();
            identityLoaded = false;
            identityCurrent = null;
            return;
        }
        text(identityFingerprint, identity.fingerprint);
        identityFingerprint.title = identity.fingerprintHex || identity.fingerprint;
        text(
            identityNote,
            `The public half only. Paste these characters into a client's server key to trust this server; the private key never leaves the machine.`
        );
        text(identityBits, `RSA ${identity.bits}-bit`);
        text(identityExponent, `0x${identity.exponent} (${Number.parseInt(identity.exponent, 16)})`);
        text(identityFileHash, identity.fileSha256 || '—');
        identityFileHash.title = identity.fileSha256Command
            ? `Verify on the host with: ${identity.fileSha256Command}`
            : '';
        text(identityModified, date(identity.publicModified || identity.modified));
        text(
            identityPair,
            identity.pairMatches === false
                ? 'does not match the public file'
                : identity.pairMatches === true
                  ? 'matches the public file'
                  : 'unreadable'
        );
        identityPair.classList.toggle('bad', identity.pairMatches === false);
        const mismatch = identity.pairMatches === false;
        identityWarning.hidden = !mismatch;
        // A mismatch can appear while the page is open, so it is announced.
        if (mismatch) identityWarning.setAttribute('role', 'alert');
        else identityWarning.removeAttribute('role');
        identityWarning.textContent = mismatch
            ? `The stored public half does not match the private key that signs handshakes, so the server refuses to start and the value below is not the key clients need.${
                  identity.expectedPublicKey
                      ? ` The key the private half implies is ${identity.expectedPublicKey.slice(0, 24)}… — copy that one instead.`
                      : ''
              } Stop the server, restore the matching public.txt from backup (or delete data/keys and let the next start create a fresh pair), then start it again.`
            : '';
        identityCurrent = identity;
        const keyText = `${identity.modulus}#${identity.exponent}`;
        // A five-second poll must not eat a selection the operator is copying:
        // only rewrite the field when it is not focused and the value changed.
        if (!identityKey.hidden && document.activeElement !== identityKey && identityKey.value !== keyText)
            identityKey.value = keyText;
        else if (identityKey.hidden && identityKey.value !== keyText) identityKey.value = keyText;
        if (!identityLoaded) {
            identityLoaded = true;
            identityActions.append(
                button(
                    'Copy public key',
                    () =>
                        copy(
                            identityCurrent?.pairMatches === false && identityCurrent?.expectedPublicKey
                                ? identityCurrent.expectedPublicKey
                                : currentKey(),
                            'Server public key copied.'
                        ),
                    'secondary'
                ),
                button(
                    'Download public.txt',
                    () => download('public.txt', currentKey() + '\n'),
                    'secondary'
                ),
                toggleKey,
                button(
                    'Copy fingerprint',
                    () => copy(identityCurrent?.fingerprint ?? '', 'Fingerprint copied.'),
                    'secondary'
                ),
                button(
                    'Copy file hash',
                    () => copy(identityCurrent?.fileSha256 ?? '', 'Public file hash copied.'),
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
        const running = s.state === 'RUNNING';
        if (running !== listenerRunning) {
            listenerRunning = running;
            for (const [route, node] of listenerButtons) {
                // One weight on the row: the next sensible action is filled, the
                // others stay neutral. Both classes set the same custom property,
                // so they are swapped rather than stacked.
                const lead = running ? route === 'restart' : route === 'start';
                node.classList.toggle('primary', lead);
                node.classList.toggle('secondary', !lead);
                node.refreshAvailability?.();
            }
        }
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
        warnings.update(
            (logs.events || logs).filter((r) => ['WARN', 'ERROR'].includes(r.level)).reverse()
        );
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
    /** Deep links (#overview?scene=…) focus the scene runtime table. */
    function applyParams(params) {
        const value = params.get('scene');
        if (value == null) return;
        monsters.query.value = value;
        monsters.query.dispatchEvent(new Event('input'));
        monsters.node.scrollIntoView({ block: 'center' });
    }
    return { node, refresh, interval: 5000, navigate: applyParams };
}
