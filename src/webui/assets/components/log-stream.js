import { el, input, select, toolbar, button, text, download } from '../ui.js';
/** Retains bounded output and appends only new rows. Scroll and controls have stable identity. */
export function logStream({ commands = [] } = {}) {
    const node = el('div', null, 'stack'),
        output = el('div', null, 'terminal'),
        search = input('Search output'),
        severity = select('Severity', ['All', 'INFO', 'WARN', 'ERROR', 'DEBUG'], 'All'),
        count = el('small');
    output.setAttribute('role', 'log');
    output.setAttribute('aria-label', 'Server output');
    output.setAttribute('aria-live', 'off');
    search.placeholder = 'Search output';
    let paused = false,
        auto = true,
        rows = [],
        pending = [],
        clearing = new Set();
    const cache = new Map();
    const pause = button('Pause', () => {
        paused = !paused;
        text(pause, paused ? 'Resume' : 'Pause');
        if (!paused) update(pending);
    });
    const scroll = button('Autoscroll on', () => {
        auto = !auto;
        text(scroll, auto ? 'Autoscroll on' : 'Autoscroll off');
        if (auto) output.scrollTop = output.scrollHeight;
    });
    function matches(row) {
        return (
            (severity.value === 'All' || row.level === severity.value) &&
            row.line.toLowerCase().includes(search.value.toLowerCase())
        );
    }
    const key = (row) => row.id || `${row.timestamp}:${row.thread}:${row.level}:${row.line}`;
    const commandRows = () =>
        commands.map((entry) => ({
            id: 'command-' + entry.time + '-' + entry.command,
            timestamp: new Date(entry.time).toISOString(),
            level: entry.error ? 'ERROR' : 'COMMAND',
            line: '> ' + entry.command + '\n' + entry.output,
            entry
        }));
    function display(force = false) {
        const retained = [...rows, ...commandRows()].sort(
                (a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)
            ),
            visible = retained.filter(matches),
            atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 35;
        const anchor = [...output.children].find(
                (n) => n.offsetTop >= output.offsetTop + output.scrollTop
            ),
            before = anchor?.getBoundingClientRect().top;
        const occurrences = new Map(),
            desired = visible.map((row) => {
                const signature = key(row),
                    occurrence = occurrences.get(signature) || 0;
                occurrences.set(signature, occurrence + 1);
                const id = signature + ':' + occurrence;
                let line = cache.get(id);
                if (!line) {
                    line = el('div', null, 'terminal-line ' + row.level);
                    const time = el(
                        'time',
                        new Date(row.timestamp).toLocaleTimeString([], { hour12: false }),
                        'log-time'
                    );
                    time.dateTime = row.timestamp;
                    const level = el('span', row.level, 'log-level');
                    const message = el('span', row.message ?? row.line, 'log-message');
                    if (row.entry) {
                        message.replaceChildren(
                            el('span', '> ' + row.entry.command, 'command'),
                            el('pre', row.entry.output, 'command-response')
                        );
                    }
                    line.append(time, level, message);
                    line.title = row.timestamp || '';
                    cache.set(id, line);
                }
                return line;
            });
        const wanted = new Set(desired);
        for (const child of [...output.children]) if (!wanted.has(child)) child.remove();
        desired.forEach((child, i) => {
            if (output.children[i] !== child)
                output.insertBefore(child, output.children[i] || null);
        });
        for (const [id, line] of cache) if (!wanted.has(line)) cache.delete(id);
        if (!auto && anchor?.isConnected && before != null)
            output.scrollTop += anchor.getBoundingClientRect().top - before;
        text(
            count,
            `${visible.length} visible · ${retained.length} retained${paused ? ' · paused' : ''}`
        );
        if (auto && (atBottom || force)) {
            output.scrollTop = output.scrollHeight;
            if (!output.isConnected)
                requestAnimationFrame(() => {
                    if (auto && output.isConnected) output.scrollTop = output.scrollHeight;
                });
        }
    }
    function update(incoming) {
        pending = incoming;
        if (paused) return;
        rows = incoming.filter((r) => !clearing.has(key(r))).slice(-1000);
        display();
        const current = new Set(incoming.map(key));
        clearing = new Set([...clearing].filter((k) => current.has(k)));
    }
    search.oninput = severity.onchange = () => display(true);
    output.addEventListener('scroll', () => {
        if (auto && output.scrollHeight - output.scrollTop - output.clientHeight > 50) {
            auto = false;
            text(scroll, 'Autoscroll off');
        }
    });
    node.append(
        toolbar(
            search,
            severity,
            pause,
            scroll,
            button('Clear display', () => {
                clearing = new Set(pending.map(key));
                rows = [];
                commands.length = 0;
                display(true);
            }),
            button('Copy visible', () =>
                navigator.clipboard.writeText(
                    [...rows, ...commandRows()]
                        .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
                        .filter(matches)
                        .map((r) => `${r.timestamp} [${r.level}] ${r.message ?? r.line}`)
                        .join('\n')
                )
            ),
            button('Export', () =>
                download(
                    'tortodrone-log.txt',
                    [...rows, ...commandRows()]
                        .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
                        .filter(matches)
                        .map((r) => `${r.timestamp} [${r.level}] ${r.message ?? r.line}`)
                        .join('\n')
                )
            ),
            count
        ),
        output
    );
    return {
        node,
        update,
        output,
        redraw: () => display(),
        tools: node.firstChild,
        get paused() {
            return paused;
        }
    };
}
