import { el, input, select, toolbar, button, text, download, copy, clock as clockTime } from '../ui.js';

/**
 * A terminal surface rather than a list of cards: a fixed width gutter for time
 * and severity, monospaced output that keeps its columns, a status line, and the
 * keys a console user reaches for (Ctrl+F search, Ctrl+L clear, End to tail).
 *
 * `commands` receives command/output entries (the Console page) and renders them
 * as a prompt line plus an indented response, never as a separate list.
 */
export function logStream({
    commands = [],
    title = 'Server output',
    prompt = 'tortodrone',
    severity: initialSeverity = 'All',
    search: initialSearch = ''
} = {}) {
    const node = el('div', null, 'terminal-shell stack-tight'),
        bar = el('div', null, 'terminal-bar'),
        dots = el('span', null, 'terminal-dots'),
        titleNode = el('span', title, 'terminal-title'),
        barMeta = el('span', null, 'terminal-bar-meta'),
        tools = toolbar(),
        output = el('div', null, 'terminal-body'),
        footer = el('div', null, 'terminal-status'),
        state = el('span', null, 'terminal-state'),
        statusLeft = el('span', null, 'terminal-hint'),
        statusRight = el('span', null, 'terminal-tail'),
        jump = el('button', 'Jump to latest', 'terminal-jump');
    tools.classList.add('terminal-tools');
    dots.setAttribute('aria-hidden', 'true');
    for (const dot of ['close', 'minimise', 'maximise']) {
        const span = el('span');
        span.dataset.dot = dot;
        dots.append(span);
    }
    bar.append(dots, titleNode, barMeta);
    // A pure stream would talk over a screen reader thousands of times; the console
    // announces its command responses, the status line announces everything else.
    // `role=log` is a live region by definition: only the console, which answers
    // one command at a time, uses it. The plain stream stays a labelled group.
    output.setAttribute('role', commands ? 'log' : 'group');
    output.setAttribute('aria-label', title);
    output.setAttribute('aria-live', commands ? 'polite' : 'off');
    if (commands) output.setAttribute('aria-atomic', 'false');
    output.tabIndex = 0;
    jump.type = 'button';
    jump.hidden = true;
    jump.setAttribute('aria-label', 'Jump to the newest line');
    footer.setAttribute('role', 'status');
    footer.setAttribute('aria-live', 'polite');
    statusLeft.textContent = 'Ctrl+F search · Ctrl+L clear · End tail';
    footer.append(state, statusLeft, statusRight);
    node.append(bar, tools, output, jump, footer);

    const search = input('Search output'),
        severity = select('Severity', ['All', 'INFO', 'WARN', 'ERROR', 'DEBUG', 'COMMAND'], 'All'),
        count = el('small', null, 'terminal-count');
    search.placeholder = 'Search output';
    search.classList.add('terminal-search');
    search.title = 'Search the output (Ctrl+F)';
    if (initialSearch) search.value = initialSearch;
    if (['All', 'INFO', 'WARN', 'ERROR', 'DEBUG', 'COMMAND'].includes(initialSeverity))
        severity.value = initialSeverity;

    let paused = false,
        auto = true,
        rows = [],
        pending = [],
        dropped = 0,
        clearing = new Set();

    const cache = new Map();
    const pause = button('Pause', () => {
        paused = !paused;
        text(pause, paused ? 'Resume' : 'Pause');
        pause.title = paused ? 'Show incoming lines again' : 'Freeze the visible output';
        if (!paused) update(pending);
        renderStatus();
    });
    pause.title = 'Freeze the visible output';
    const follow = button('Stop tailing', () => {
        auto = !auto;
        text(follow, auto ? 'Stop tailing' : 'Start tailing');
        follow.title = auto
            ? 'Keep the newest line in view'
            : 'Follow new lines again from the bottom';
        if (auto) {
            output.scrollTop = output.scrollHeight;
            jump.hidden = true;
        }
        renderStatus();
    });
    follow.title = 'Keep the newest line in view';

    function matches(row) {
        if (severity.value !== 'All' && row.level !== severity.value) return false;
        const needle = search.value.toLowerCase();
        if (!needle) return true;
        // Search what a reader can see: the message (or the command and its
        // response) plus the thread; never the hidden raw line.
        const visible = row.entry ? `${row.entry.command} ${row.entry.output}` : row.message ?? row.line;
        return `${row.thread ?? ''} ${visible}`.toLowerCase().includes(needle);
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

    // A structured answer reads far better indented, the way a shell would print it.
    function formatOutput(value) {
        const textValue = typeof value === 'string' ? value : String(value ?? '');
        const trimmed = textValue.trim();
        const structured =
            (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
            (trimmed.startsWith('[') && trimmed.endsWith(']'));
        if (structured)
            try {
                return JSON.stringify(JSON.parse(trimmed), null, 2);
            } catch {
                /* keep the raw text when it is not valid JSON */
            }
        return textValue;
    }

    function renderStatus() {
        const visible = [...rows, ...commandRows()].filter(matches).length;
        text(
            barMeta,
            `${visible} ${visible === 1 ? 'line' : 'lines'}${paused ? ' · paused' : ''}`
        );
        text(statusRight, auto ? 'tail' : 'scrolled back');
        statusRight.dataset.state = auto ? 'tail' : 'paused';
    }

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
                    line = el('div', null, 'terminal-line level-' + row.level);
                    const time = el('time', clockTime(row.timestamp), 'log-time');
                    time.dateTime = row.timestamp;
                    const level = el('span', row.level === 'COMMAND' ? 'cmd' : row.level, 'log-level');
                    const thread = el('span', row.thread || '', 'log-thread');
                    thread.title = row.thread || '';
                    const message = el('span', null, 'log-message');
                    if (row.entry) {
                        message.replaceChildren(
                            el('span', `${prompt}$ ${row.entry.command}`, 'command')
                        );
                        // A command that has not answered yet shows a running
                        // marker instead of an empty response block.
                        if (row.entry.pending)
                            message.append(el('span', 'running…', 'command-pending'));
                        else
                            message.append(
                                el('pre', formatOutput(row.entry.output), 'command-response')
                            );
                    } else message.textContent = row.message ?? row.line;
                    line.append(time, level, thread, message);
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
            `${visible.length} of ${retained.length}${dropped ? ` · last 1000, ${dropped} older not shown` : ''}`
        );
        renderStatus();
        if (auto && (atBottom || force)) {
            output.scrollTop = output.scrollHeight;
            if (!output.isConnected)
                requestAnimationFrame(() => {
                    if (auto && output.isConnected) output.scrollTop = output.scrollHeight;
                });
        }
        jump.hidden = auto || atBottom;
    }

    function update(incoming) {
        pending = incoming;
        if (paused) {
            renderStatus();
            return;
        }
        const kept = incoming.filter((r) => !clearing.has(key(r)));
        dropped = Math.max(0, kept.length - 1000);
        rows = kept.slice(-1000);
        display();
        const current = new Set(incoming.map(key));
        clearing = new Set([...clearing].filter((k) => current.has(k)));
    }

    // Filtering must not yank a reader who scrolled back up to the bottom.
    search.oninput = severity.onchange = () => display();
    output.addEventListener('scroll', () => {
        const away = output.scrollHeight - output.scrollTop - output.clientHeight > 50;
        jump.hidden = !away;
        if (auto && away) {
            auto = false;
            text(follow, 'Start tailing');
            renderStatus();
        }
    });
    // Terminal keys: search, clear, tail. Ignored while a field has focus.
    node.addEventListener('keydown', (event) => {
        if (!event.ctrlKey && !event.metaKey) {
            const inside = event.target === output || event.target === node;
            if (event.key === 'End' && inside) {
                event.preventDefault();
                auto = true;
                text(follow, 'Stop tailing');
                output.scrollTop = output.scrollHeight;
                renderStatus();
            } else if (event.key === 'p' && inside) {
                event.preventDefault();
                pause.click();
            }
            return;
        }
        const keyName = event.key.toLowerCase();
        if (keyName === 'f') {
            event.preventDefault();
            search.focus();
            search.select();
        } else if (keyName === 'l') {
            event.preventDefault();
            clear();
        }
    });
    jump.onclick = () => {
        auto = true;
        text(follow, 'Stop tailing');
        output.scrollTop = output.scrollHeight;
        jump.hidden = true;
        renderStatus();
        output.focus();
    };

    function clear() {
        clearing = new Set(pending.map(key));
        pending = [];
        rows = [];
        commands.length = 0;
        display(true);
    }

    tools.append(
        search,
        severity,
        pause,
        follow,
        button('Clear', clear),
        button('Copy', () =>
            copy(
                [...rows, ...commandRows()]
                    .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
                    .filter(matches)
                    .map(
                        (r) =>
                            `${r.timestamp} [${r.level}] ${r.thread ? r.thread + ' ' : ''}${r.message ?? r.line}`
                    )
                    .join('\n'),
                'Visible output copied.'
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
    );
    renderStatus();
    return {
        node,
        update,
        output,
        footer,
        state,
        redraw: () => display(),
        tools,
        clear,
        focusSearch: () => search.focus(),
        /** Re-apply a deep-linked filter without reloading the page. */
        filter({ severity: level, search: term } = {}) {
            if (level && ['All', 'INFO', 'WARN', 'ERROR', 'DEBUG', 'COMMAND'].includes(level))
                severity.value = level;
            if (typeof term === 'string') search.value = term;
            display();
        },
        get paused() {
            return paused;
        }
    };
}
