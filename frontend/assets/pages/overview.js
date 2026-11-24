import {
    el,
    panel,
    toolbar,
    button,
    routeLink,
    select,
    text,
    duration,
    details,
    facts,
    date,
    copy,
    download,
    number,
    sceneLabel
} from '../ui.js';
import { chart } from '../components/chart.js';
import { confirm, field, openDialog } from '../components/dialog.js';
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
        ['Listener uptime', 'uptime', 'Open the process runtime details'],
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
            if (destination === 'uptime') {
                diagnostics.open = true;
                diagnostics.scrollIntoView({ block: 'center' });
                return;
            }
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
    // The fourth metric tile leads here, so it reads like the rest of the page: a
    // ledger of named figures, with the raw payload kept one disclosure deeper.
    const diagnosticsBody = el('div', null, 'stack'),
        diagnosticsFacts = facts([]);
    let diagnosticsSignature = '',
        uptimeSeconds = 0,
        listenerPort = '';
    const diagnosticsRaw = el('div');
    const diagnostics = el('details', null, 'stack-tight');
    diagnostics.append(
        el('summary', 'Database operations and runtime details'),
        diagnosticsBody
    );
    diagnosticsBody.append(diagnosticsFacts);
    function renderDiagnostics(database, status) {
        if (status) uptimeSeconds = Number(status.uptimeSeconds) || 0;
        const signature = JSON.stringify([database, uptimeSeconds]);
        if (signature === diagnosticsSignature) return;
        diagnosticsSignature = signature;
        const operations = database?.operations ?? 0,
            slow = database?.slowOperations ?? 0,
            worst = database?.maximumMillis ?? 0,
            rollbacks = database?.rollbacks ?? 0,
            failures = database?.failures ?? 0;
        const cells = [
            ['Process uptime', duration(uptimeSeconds)],
            ['Operations recorded', number(operations)],
            ['Slowest statement', worst ? `${number(Math.round(worst))} ms` : '—'],
            ['Slow statements', number(slow)],
            ['Rolled back', number(rollbacks)],
            ['Failures', number(failures)]
        ];
        diagnosticsFacts.replaceChildren(
            ...cells.flatMap(([key, value]) => [el('dt', key), el('dd', value)])
        );
        diagnosticsRaw.replaceChildren(details('Raw runtime payload', database ?? {}));
    }
    // The handshake identity is the one thing an operator otherwise has to SSH in
    // for; it sits on the page they land on.
    const identityFingerprint = el('code', null, 'identity-fingerprint'),
        identityNote = el('p', null, 'muted identity-note'),
        identityWarning = el('p', null, 'identity-warning'),
        identityKey = el('textarea', null, 'identity-key'),
        identityActions = toolbar();
    identityKey.readOnly = true;
    identityKey.spellcheck = false;
    // Six rows is the whole key at a desktop width; `field-sizing: content` grows it
    // further on a narrow screen, so the trailing exponent is never behind a
    // scrollbar. The box used to show 80px of a 120px value.
    identityKey.rows = 6;
    identityKey.setAttribute('aria-label', 'Server public key, modulus and exponent');
    identityWarning.hidden = true;
    const identityBits = el('span'),
        identityFileHash = el('code', null, 'identity-file-hash'),
        identityModified = el('span'),
        identityPair = el('span');
    // A value an action acts on sits beside the action: the fingerprint and the
    // file hash are what two of these controls copy, so hiding them behind a
    // disclosure made the operator open a toggle to see what he was copying.
    const identityPairValue = (value, action) => {
        const row = el('div', null, 'identity-value');
        row.append(value, action);
        return row;
    };
    const identityFacts = facts([
        [
            'Fingerprint (SHA-256)',
            identityPairValue(
                identityFingerprint,
                button(
                    'Copy fingerprint',
                    () => copy(identityCurrent?.fingerprint ?? '', 'Fingerprint copied.'),
                    'quiet sm'
                )
            )
        ],
        ['Key', identityBits],
        [
            'Public file SHA-256',
            identityPairValue(
                identityFileHash,
                button(
                    'Copy file hash',
                    () => copy(identityCurrent?.fileSha256 ?? '', 'Public file hash copied.'),
                    'quiet sm'
                )
            )
        ],
        ['Public file modified', identityModified],
        ['Signing key', identityPair],
        ['Recorded in', 'data/keys/public.txt']
    ]);
    // The field's label names the key it holds, so the mismatch state cannot hand
    // out one key under the name of another.
    const identityFieldLabel = el('p', null, 'muted identity-field-label'),
        identityCaption = el('p', null, 'muted identity-caption'),
        identityReach = el('p', null, 'muted identity-caption'),
        identityRotation = el('p', null, 'muted identity-caption');
    // Only the mismatch branch used to warn. A deliberate rotation of a healthy pair
    // looks exactly like a healthy card, and every pinned client breaks silently.
    text(
        identityRotation,
        'If this fingerprint has changed since a client was set up, that client must be re-keyed before it can connect: the fingerprint, not the file path, is what it trusts.'
    );
    const IDENTITY_NOTE =
        "The public half only. Paste it into the client's Add Server dialog, in the Public key field (game.publicKey), on every machine that plays here. The private key never leaves the server.";
    text(identityNote, IDENTITY_NOTE);
    const identityPanel = panel(
        'Server identity',
        identityNote,
        identityFieldLabel,
        identityKey,
        identityActions,
        identityWarning,
        identityCaption,
        identityFacts,
        identityReach,
        identityRotation
    );
    // One panel, one address: `#overview?panel=identity` is the link to hand a
    // colleague who only needs the key.
    identityPanel.id = 'identity';
    // Handlers read the current key at click time, so a rotated key is the one an
    // operator copies; the panels are built once and never hold stale values.
    let identityLoaded = false,
        identityCurrent = null;
    const monsters = new DataTable(
        [
            [
                'Scene',
                // A real link, and the only one in this row: the same destination as a
                // button beside it was a second control for one idea.
                (r) => routeLink(sceneLabel(r.name), 'players', { scene: r.name })
            ],
            ['State', (r) => r.state],
            ['Players', (r) => r.players],
            ['Live', (r) => r.monsters.total],
            ['Awake', (r) => r.monsters.awake],
            ['Engaged', (r) => r.monsters.engaged]
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
            // The six columns answer "what is in this scene"; the path and tick
            // internals answer "why is it slow", which is a per-scene question.
            actions: (get) => [
                button(
                    'Details',
                    () => {
                        const m = get().monsters;
                        openDialog({
                            title: 'Scene runtime · ' + get().name,
                            content: facts([
                                ['State', get().state],
                                ['Players', number(get().players)],
                                ['Live monsters', number(m.total)],
                                ['Awake', number(m.awake)],
                                ['Engaged', number(m.engaged)],
                                ['Spawned', number(m.spawned)],
                                ['Despawned', number(m.despawned)],
                                ['Path searches', number(m.pathSearches)],
                                ['Path queue', number(m.pathQueued)],
                                ['Dropped', number(m.pathDropped)],
                                ['Path failures', number(m.pathFailures)],
                                ['Tick p99', m.tickP99Millis.toFixed(2) + ' ms']
                            ]),
                            submit: 'Close',
                            notifySaved: false,
                            onSubmit: async () => {}
                        });
                    },
                    'secondary'
                )
                // The scene name is the link to its players, so this row carries one
                // destination, not the same one twice.
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
            empty: {
                title: 'No warnings or errors since the server started',
                hint: 'Every line the process writes is in Logs, including the ones below a warning.',
                action: { label: 'Open Logs', run: () => ctx.go('logs') },
                icon: 'logs'
            },
            actions: (get) => [
                routeLink(
                    'Open in Logs',
                    // The row names a line; the log page can filter to that line.
                    'logs',
                    { severity: get().level, search: get().message },
                    'button secondary'
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
    // The value a client must trust: the file's key, or the one the private half
    // implies when the two disagree. Copy, download and the field all use it, so a
    // mismatch can never hand out two different keys.
    const trustedKey = () =>
        identityCurrent?.pairMatches === false && identityCurrent?.expectedPublicKey
            ? identityCurrent.expectedPublicKey
            : currentKey();
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
            text(identityFileHash, '—');
            text(identityModified, '—');
            text(identityPair, identity.keyStored ? 'unknown' : 'absent');
            identityKey.value = '';
            text(identityFieldLabel, 'No public key is available on this server yet.');
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
        // One row, not two: the exponent of an RSA handshake key is always 65537 and
        // the facts grid was mostly whitespace.
        text(
            identityBits,
            `RSA ${identity.bits}-bit · exponent 0x${identity.exponent} (${Number.parseInt(identity.exponent, 16)})`
        );
        text(identityFileHash, identity.fileSha256 || '—');
        identityFileHash.title = identity.fileSha256Command
            ? `Verify on the host with: ${identity.fileSha256Command}`
            : '';
        text(identityModified, date(identity.publicModified || identity.modified));
        // An explanation that only exists in a title is invisible on a touch screen,
        // to keyboard focus and in print.
        text(
            identityCaption,
            [
                // The file holds a hex modulus and exponent, not a DER blob: the old
                // wording claimed the fingerprint was computed from data that the file
                // does not contain.
                'The fingerprint is the SHA-256 of the DER SubjectPublicKeyInfo this server builds from the modulus and exponent below.',
                identity.fingerprintNote || '',
                identity.fileSha256Command
                    ? `Type ${identity.fileSha256Command} on the host to reproduce the file hash beside it.`
                    : ''
            ]
                .filter(Boolean)
                .join(' ')
        );
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
            ? `The stored public half does not match the private key that signs handshakes, so the server will refuse to start until the pair matches again.${
                  identity.expectedPublicKey
                      ? ' The field below, Copy and Download all use the key the private half implies — the one clients must trust.'
                      : ''
              } The fingerprint and file hash below still describe data/keys/public.txt, so they are stale until you stop the server, restore the matching public.txt from your backup and start it again. Deleting data/keys lets the next start create a fresh pair, but every client pinned to the old key — including the one you are about to set up — must be re-keyed.`
            : '';
        identityCurrent = identity;
        text(identityNote, IDENTITY_NOTE);
        text(
            identityFieldLabel,
            mismatch
                ? 'Key the private half implies: paste this one into the client.'
                : 'Public key: modulus and exponent, in the form the client stores.'
        );
        // A five-second poll must not eat a selection the operator is copying:
        // only rewrite the field when it is not focused and the value changed.
        text(
            identityReach,
            `Clients dial this machine's own address${
                listenerPort ? ` on port ${listenerPort}` : ' on the game port below'
            }; a bind address of 0.0.0.0 above means "every interface", not a destination.`
        );
        const shown = trustedKey();
        if (document.activeElement !== identityKey && identityKey.value !== shown)
            identityKey.value = shown;
        if (!identityLoaded) {
            identityLoaded = true;
            identityActions.append(
                button(
                    'Copy public key',
                    () => copy(trustedKey(), 'Server public key copied.'),
                    'primary'
                ),
                button(
                    'Download public.txt',
                    // Normally the server sends the file's own bytes, so `sha256sum`
                    // on the download matches the printed hash. In the mismatch state
                    // the file is the stale half, and the button hands out the key the
                    // warning above promises.
                    () =>
                        download(
                            'public.txt',
                            identityCurrent?.pairMatches === false
                                ? trustedKey()
                                : (identityCurrent?.fileText ?? trustedKey())
                        ),
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
        // The port a client dials is the listener's own; the host in that string is
        // the bind address, which is why the identity panel says so out loud.
        listenerPort = String(s.endpoint || '').includes(':')
            ? String(s.endpoint).split(':').pop()
            : '';
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
        // Named figures first, the JSON payload one disclosure deeper, and no
        // rewrite of the DOM when nothing changed.
        renderDiagnostics(s.database, s);
        if (diagnosticsRaw.childElementCount === 0) {
            diagnosticsBody.append(diagnosticsRaw);
        }
        const signature = JSON.stringify({ database: s.database, runtime: s.metrics });
        if (signature !== diagnosticSignature) {
            diagnosticsRaw.replaceChildren(
                details('Raw runtime payload', { database: s.database, runtime: s.metrics })
            );
            diagnosticSignature = signature;
        }
        ctx.connection(s.state, s.state === 'RUNNING');
    }
    range.onchange = () => ctx.refresh();
    await refresh();
    applyParams(ctx.params);
    /** Deep links (#overview?scene=…, ?panel=identity) focus one block of the page. */
    function applyParams(params) {
        // The key card has an address, so "send me the key" is a link rather than a
        // scroll position someone has to describe.
        if (params.get('panel') === 'identity')
            requestAnimationFrame(() => identityPanel.scrollIntoView({ block: 'start' }));
        const value = params.get('scene');
        if (value == null) return;
        monsters.query.value = value;
        monsters.query.dispatchEvent(new Event('input'));
        monsters.node.scrollIntoView({ block: 'center' });
    }
    return { node, refresh, interval: 5000, navigate: applyParams };
}
