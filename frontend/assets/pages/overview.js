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
    sceneLabel,
    landOn
} from '../ui.js';
import { chart } from '../components/chart.js';
import { confirm, field, openDialog } from '../components/dialog.js';
import { DataTable } from '../components/table.js';
import { symbol } from '../components/symbols.js';

export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        strip = el('div', null, 'server-strip'),
        state = el('strong'),
        endpoint = el('small'),
        server = el('div'),
        version = el('span', null, 'muted');
    server.append(state, endpoint);
    // The landing page opens on the process, and the process serves one game: the card
    // that says RUNNING wears the client's own mark, so the first screen belongs to this
    // console and not to any administration page. The page mounts before the art pack
    // is read, so the mark is painted again when it lands — asking once at mount
    // answered null and the card stayed bare.
    const serverMark = el('span', null, 'server-mark');
    const paintServerMark = () => {
        const art = symbol('brand');
        if (!art) {
            serverMark.remove();
            return;
        }
        art.classList.add('server-mark-art');
        serverMark.replaceChildren(art);
        if (!serverMark.isConnected) strip.prepend(serverMark);
    };
    paintServerMark();
    document.addEventListener('symbols-ready', () => ctx.alive() && paintServerMark(), {
        once: true
    });
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
                        ? 'Start the game listener. Starting one that is already running does nothing at all — nobody is dropped, and nothing changes.'
                        : 'Every connected player is dropped with this press. HTTP administration stays available, and players can sign in again once the listener is back.',
                    // Starting is a plain action; stopping and restarting are not. The
                    // three used to look identical in the dialog.
                    {
                        destructive: route !== 'start',
                        submit: route === 'start' ? 'Start' : title + ' the listener'
                    }
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
                // "Stop" and "Shut down" were a mechanism and an act of the same size
                // side by side: one drops players, the other ends the process, and
                // nothing on the strip said which. Every control now names its scope.
                ...['start', 'stop', 'restart'].map((route) =>
                    listenerButton(
                        route === 'start'
                            ? 'Start listener'
                            : route === 'stop'
                              ? 'Stop listener'
                              : 'Restart listener',
                        route
                    )
                ),
                button(
                    'Shut down server',
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
        uptimeSeconds = 0;
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
    /* The handshake key is the one thing an operator otherwise has to SSH in for, and
       the page they land on is where they look for it — but it is a value they copy
       once a month, so it earns one selectable line and the copy beside it. The file
       hash, the modification date, the pair status and three explainer notes described
       the key rather than being the key, and together they made the largest block on
       the landing page. The fingerprint is still one hover away, and the mismatch
       warning still opens in full, because that is the state where the key matters. */
    const identityKey = el('code', null, 'identity-key'),
        identityHint = el('p', null, 'identity-hint'),
        identityState = el('p', null, 'identity-state'),
        identityWarning = el('p', null, 'identity-warning'),
        identityActions = toolbar();
    identityKey.setAttribute('aria-label', 'Server public key, modulus and exponent');
    identityKey.tabIndex = 0;
    identityState.hidden = true;
    identityWarning.hidden = true;
    const identityRow = el('div', null, 'identity-value');
    identityRow.append(identityKey, identityActions);
    const identityPanel = panel(
        'Server identity',
        identityWarning,
        identityState,
        identityRow,
        identityHint
    );
    // One panel, one address: `#overview?panel=identity` is the link to hand a
    // colleague who only needs the key. Not `identity` — the footer's session line
    // already owns that id, and two elements answering to one name resolved to the
    // wrong node for anything that looked it up.
    identityPanel.id = 'identity-panel';
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
            searchPlaceholder: 'Search scene or monster',
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
            searchPlaceholder: 'Search message or source',
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
        // The page opens on the process, then on its numbers. The key is a value an
        // operator copies once a month, so it closes the page instead of standing
        // between the operator and every figure the console reports.
        strip,
        metrics,
        toolbar(range, el('small', 'Measured every 5 seconds · retained in memory for 1 hour')),
        charts,
        panel('Scene monster runtime', monsters.node),
        panel('Recent warnings and errors', warnings.node),
        diagnostics,
        identityPanel
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
            // key" and "a key I cannot read" are different problems, and one line
            // each is all either of them needs.
            identityState.hidden = false;
            text(
                identityState,
                identity.reason ||
                    'The server has not created its keypair yet; it is created on the next start.'
            );
            text(identityKey, '—');
            identityKey.dataset.state = 'empty';
            identityKey.removeAttribute('title');
            text(
                identityHint,
                identity.keyStored
                    ? 'Nothing here is served to clients until data/keys/public.txt is readable.'
                    : 'Nothing here is served to clients yet: the first start writes data/keys/public.txt.'
            );
            identityActions.replaceChildren();
            identityWarning.hidden = true;
            identityLoaded = false;
            identityCurrent = null;
            return;
        }
        identityState.hidden = true;
        const mismatch = identity.pairMatches === false;
        identityWarning.hidden = !mismatch;
        // A mismatch can appear while the page is open, so it is announced.
        if (mismatch) identityWarning.setAttribute('role', 'alert');
        else identityWarning.removeAttribute('role');
        identityWarning.textContent = mismatch
            ? `The stored public half does not match the private key that signs handshakes, so the server will refuse to start until the pair matches again.${
                  identity.expectedPublicKey
                      ? ' The key below, Copy and Download all use the key the private half implies — the one clients must trust.'
                      : ''
              } Stop the server, restore the matching public.txt from your backup and start it again. Deleting data/keys lets the next start create a fresh pair, but every client pinned to the old key — including the one you are about to set up — must be re-keyed.`
            : '';
        identityCurrent = identity;
        // The port is on the strip above; this line says where the key goes, and one
        // sentence that fits on one line is read where two wrapped ones are skimmed.
        text(identityHint, "Paste it under game.publicKey in the client's Add Server dialog.");
        const shown = trustedKey();
        // A five-second poll must not eat a selection the operator is copying:
        // the line is rewritten only when it is unfocused and the value changed.
        if (document.activeElement !== identityKey && identityKey.textContent !== shown) {
            text(identityKey, shown);
            identityKey.dataset.state = mismatch ? 'mismatch' : 'ready';
            // The fingerprint is what a client pins, and what a rotation breaks. It
            // costs no layout to keep it on the value it belongs to.
            identityKey.title = identity.fingerprint
                ? `SHA-256 ${identity.fingerprint}`
                : 'Server public key';
        }
        if (!identityLoaded) {
            identityLoaded = true;
            identityActions.append(
                // One gold control per page: the server strip owns the primary, and a
                // copy is not the page's next action. The two secondary hand-offs sit
                // beside it as quiet words, so the row reads as one action and two
                // conveniences instead of three controls of equal weight.
                button(
                    'Copy key',
                    () => copy(trustedKey(), 'Server public key copied.'),
                    'secondary'
                ),
                // One label for one affordance, everywhere: "card" named a container
                // this console never calls a card, and the same job was called
                // "Permalink" on Player Mail. The accessible name says what it links to.
                (() => {
                    const control = button(
                        'Copy link',
                        () =>
                            copy(
                                `${location.origin}${location.pathname}#overview?panel=identity`,
                                'Link copied.'
                            ),
                        'quiet'
                    );
                    control.setAttribute('aria-label', 'Copy link to the server identity');
                    control.title = 'Copy a link to the server identity';
                    return control;
                })(),
                (() => {
                    const control = button(
                        'Download',
                        // Normally the server sends the file's own bytes, so `sha256sum`
                        // on the download matches the printed hash. In the mismatch
                        // state the file is the stale half, and the button hands out the
                        // key the warning above promises.
                        () =>
                            download(
                                'public.txt',
                                identityCurrent?.pairMatches === false
                                    ? trustedKey()
                                    : (identityCurrent?.fileText ?? trustedKey())
                            ),
                        'quiet'
                    );
                    control.title = 'Download public.txt';
                    return control;
                })()
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
                // One weight on the row, and it follows the safe productive action:
                // Start is filled when the listener is down, and nothing is filled
                // while it runs — a gold Restart steered the landing page toward
                // disconnecting every connected player.
                const lead = !running && route === 'start';
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
        // Every caption under a tile is the same shape: a figure and the noun it counts.
        // Two of the six ended on a verb clause ("live in active scenes"), which read as
        // the caption of a different kind of number than the four beside them.
        const monsterTotal = s.scenes.reduce((a, v) => a + v.monsters.total, 0);
        text(counters[4][0], monsterTotal);
        text(counters[4][1], `${s.scenes.length} active scenes`);
        text(
            counters[5][0],
            s.scenes.reduce((a, v) => a + v.monsters.engaged, 0)
        );
        text(counters[5][1], `${monsterTotal} monsters`);
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
        // The pill is the WebUI's link to the server, and nothing else: the game
        // server's own state is the hero word above, so writing it here made one slot
        // mean two things on two pages.
        ctx.connection('Connected', true);
    }
    range.onchange = () => ctx.refresh();
    await refresh();
    applyParams(ctx.params);
    /** Deep links (#overview?scene=…, ?panel=identity) focus one block of the page. */
    function applyParams(params) {
        // The key card has an address, so "send me the key" is a link rather than a
        // scroll position someone has to describe.
        if (params.get('panel') === 'identity') landOn(identityPanel);
        const value = params.get('scene');
        if (value == null) return;
        monsters.query.value = value;
        monsters.query.dispatchEvent(new Event('input'));
        landOn(monsters.node, 'center');
    }
    return { node, refresh, interval: 5000, navigate: applyParams };
}
