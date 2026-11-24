import { el, input, select, toolbar, button, text, download, copy, clock as clockTime } from '../ui.js';

const LEVELS = ['All', 'INFO', 'WARN', 'ERROR', 'DEBUG', 'COMMAND'];

/**
 * A terminal surface rather than a list of cards: a gutter for time, severity and
 * thread, monospaced output that keeps its columns, a status line, and the keys a
 * console user reaches for (Ctrl+F search, Ctrl+L clear, End to tail).
 *
 * Two things this file refuses to do, because a log viewer that lies is worse than
 * no log viewer: it never claims a buffer is complete (it names its capacity, how
 * many lines were evicted and where the full file is), and it never freezes on
 * pause (a paused tail keeps reading and says how far behind it is).
 *
 * `commands` receives command/output entries (the Console page) and renders them
 * as a prompt line plus an indented response, never as a separate list.
 */
export function logStream({
    commands = null,
    title = 'Server output',
    prompt = 'tortodrone',
    severity: initialSeverity = 'All',
    search: initialSearch = '',
    raw = false,
    fill = false,
    onLoadFile = null,
    onLink = null
} = {}) {
    const node = el('div', null, 'terminal-shell stack-tight'),
        bar = el('div', null, 'terminal-bar'),
        led = el('span', null, 'terminal-led'),
        titleNode = el('h2', title, 'terminal-title'),
        barMeta = el('span', null, 'terminal-bar-meta'),
        tools = toolbar(),
        output = el('div', null, 'terminal-body'),
        footer = el('div', null, 'terminal-status'),
        state = el('span', null, 'terminal-state'),
        statusLeft = el('span', null, 'terminal-hint'),
        statusRight = el('span', null, 'terminal-tail'),
        source = el('span', null, 'terminal-source'),
        announce = el('span', null, 'visually-hidden'),
        jump = el('button', 'Jump to latest', 'terminal-jump');
    tools.classList.add('terminal-tools');
    // One indicator that means something: it reflects the real stream state instead
    // of three decorative window dots an operator will try to click.
    led.setAttribute('aria-hidden', 'true');
    bar.append(led, titleNode, barMeta);
    // A live region on the whole stream would read every polled server line aloud.
    // The structure stays semantic (role=log for the console, a labelled group for
    // the stream) while announcements go through the small region below.
    output.setAttribute('role', commands ? 'log' : 'group');
    output.setAttribute('aria-label', title);
    output.setAttribute('aria-live', 'off');
    announce.setAttribute('role', 'status');
    announce.setAttribute('aria-live', 'polite');
    output.tabIndex = 0;
    jump.type = 'button';
    jump.hidden = true;
    jump.setAttribute('aria-label', 'Jump to latest');
    footer.setAttribute('role', 'status');
    statusLeft.textContent = 'Ctrl+F search · Ctrl+L clear · End tail · Home top · p pause · Esc leave';
    footer.append(state, statusLeft, statusRight, source);
    node.append(bar, tools, output, announce, jump, footer);

    const search = input('Search output', '', 'search'),
        // COMMAND rows only exist on the Console, so Logs does not offer a filter
        // that can never match.
        severity = select('Severity', commands ? LEVELS : LEVELS.filter((l) => l !== 'COMMAND'), 'All'),
        count = el('small', null, 'terminal-count');
    search.placeholder = 'Search output';
    search.classList.add('terminal-search');
    search.title = 'Search the output (Ctrl+F)';
    if (initialSearch) search.value = initialSearch;
    if (LEVELS.includes(initialSeverity)) severity.value = initialSeverity;

    let paused = false,
        auto = true,
        rows = [],
        pending = [],
        frozen = null,
        fresh = 0,
        dropped = 0,
        capacity = 1000,
        retained = 0,
        file = null,
        rawMode = raw,
        lastRowAt = 0,
        fileRows = [],
        retainedCount = 0,
        lastPayload = null,
        clearing = new Set();

    const cache = new Map(),
        // Dividers are cached per day, so crossing midnight does not rebuild the
        // node (and its scroll anchor) on every poll.
        dividers = new Map();
    const pause = button('Pause', () => {
        paused = !paused;
        pause.setAttribute('aria-pressed', String(paused));
        // Remember exactly what was on screen, so "3 new" counts arrived lines
        // rather than the difference between two bounded buffers.
        frozen = paused ? new Set(rows.map(key)) : null;
        if (!paused) fresh = 0;
        pause.title = paused
            ? 'Show what has arrived since you paused'
            : 'Freeze the view; the stream keeps reading';
        if (!paused) update(pending);
        renderStatus();
    });
    pause.setAttribute('aria-pressed', 'false');
    pause.title = 'Freeze the view; the stream keeps reading';
    // Ctrl+L hides lines, so it must be possible to get them back: a cleared tail
    // that cannot be restored is a log viewer that lost the log.
    const undoClear = button('Restore cleared lines', () => unclear(), 'quiet sm');
    undoClear.hidden = true;
    undoClear.title = 'Put the lines cleared with Ctrl+L back on screen';
    const follow = button('Tail', () => setTail(!auto));
    follow.setAttribute('aria-pressed', 'true');
    follow.title = 'Follow the newest line';

    function setTail(value) {
        auto = value;
        follow.setAttribute('aria-pressed', String(auto));
        if (auto) {
            output.scrollTop = output.scrollHeight;
            jump.hidden = true;
        }
        renderStatus();
    }

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
        (commands || []).map((entry) => ({
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

    /** The exact line the server wrote to data/logs/latest.log, for raw mode and Copy. */
    const rawLine = (row) =>
        row.entry ? row.entry.command + '\n' + (row.entry.output ?? '') : row.line ?? row.message ?? '';

    function renderStatus() {
        const visible = [...rows, ...commandRows()].filter(matches).length,
            total = retainedCount || visible,
            away = output.scrollHeight - output.scrollTop - output.clientHeight > 50;
        // One place counts lines, and it says how many the filter hid rather than
        // counting and then erasing the number.
        text(
            barMeta,
            `${visible} ${visible === 1 ? 'line' : 'lines'}${
                visible !== total ? ` of ${total}` : ''
            }${paused ? ' · paused' : ''}${paused && fresh ? ` · ${fresh} new` : ''}`
        );
        text(
            count,
            `${visible} of ${total}${
                visible !== total ? ' shown' : ''
            }${dropped ? ` · ${dropped} older dropped` : ''}`
        );
        // The pill names what the pane is doing, not what the tail wants to do.
        text(
            statusRight,
            paused
                ? `paused${fresh ? ` · ${fresh} new` : ''}`
                : auto
                  ? 'tail'
                  : away
                    ? 'scrolled back'
                    : 'tail off'
        );
        statusRight.dataset.state = paused || !auto ? 'paused' : 'tail';
        undoClear.hidden = !clearing.size;
        text(
            undoClear,
            clearing.size === 1 ? 'Restore 1 cleared line' : `Restore ${clearing.size} cleared lines`
        );
        const bounds = [];
        if (dropped) bounds.push(`${retained} of ${capacity}, ${dropped} older dropped`);
        else if (retained) bounds.push(`${retained} buffered`);
        if (file?.path) {
            const size = file.bytes ? `${Math.round(file.bytes / 1024)} KB` : null;
            const stamp = file.modified ? clockTime(new Date(file.modified).toISOString()) : null;
            bounds.push(
                [file.path, size, stamp && `written ${stamp}`]
                    .filter(Boolean)
                    .join(' · ') + (file.exists === false ? ' (not written yet)' : '')
            );
        }
        text(source, bounds.join(' · '));
        // The console puts its connection text inside the state line, so the LED
        // has to look for the error class on either of them.
        const offline = state.classList.contains('error') || !!state.querySelector('.error');
        const recent = Date.now() - lastRowAt < 2500;
        led.dataset.state = offline
            ? 'offline'
            : paused
              ? 'paused'
              : auto
                ? recent
                    ? 'live'
                    : 'idle'
                : 'back';
        statusRight.title = file?.path ? 'The complete log lives in ' + file.path : '';
        // A notice appearing above the pane (or a second status row) moves its top
        // edge; refit so the prompt and source line stay on screen.
        scheduleFit();
    }

    /** Wrap every match of the search term, so a filtered line shows why it matched. */
    function painted(value) {
        const source = String(value ?? ''),
            term = search.value.trim();
        if (!term) return document.createTextNode(source);
        const lower = source.toLowerCase(),
            needle = term.toLowerCase(),
            frag = document.createDocumentFragment();
        let at = 0,
            hit = lower.indexOf(needle);
        while (hit !== -1) {
            if (hit > at) frag.append(document.createTextNode(source.slice(at, hit)));
            frag.append(el('mark', source.slice(hit, hit + needle.length)));
            at = hit + needle.length;
            hit = lower.indexOf(needle, at);
        }
        frag.append(document.createTextNode(source.slice(at)));
        return frag;
    }

    function lineFor(row, id) {
        const line = el('div', null, 'terminal-line level-' + row.level);
        const time = el('time', clockTime(row.timestamp), 'log-time');
        time.dateTime = row.timestamp;
        time.title = new Date(row.timestamp).toLocaleString();
        const level = el('span', row.level === 'COMMAND' ? 'cmd' : row.level, 'log-level');
        level.title = row.level;
        const thread = el('span', null, 'log-thread');
        thread.append(painted(row.thread || ''));
        thread.title = row.thread || '';
        const message = el('span', null, 'log-message');
        if (row.entry) {
            message.replaceChildren(el('span', `${prompt}$ ${row.entry.command}`, 'command'));
            // A command that has not answered yet shows a running marker instead of
            // an empty response block.
            if (row.entry.pending) message.append(el('span', 'running…', 'command-pending'));
            else message.append(el('pre', formatOutput(row.entry.output), 'command-response'));
        } else if (rawMode) message.textContent = row.line ?? row.message ?? '';
        else message.append(painted(row.message ?? row.line));
        if (!row.entry && rawMode) {
            // The file's own line already says `[time] [thread/LEVEL]:`, so the
            // gutter would print all three twice.
            line.classList.add('raw');
            line.append(message);
        } else line.append(time, level, thread, message);
        if (row.entry?.links?.length && onLink) {
            const links = el('span', null, 'command-links');
            for (const link of row.entry.links) {
                const go = el('button', link.label, 'link');
                go.type = 'button';
                go.onclick = () => onLink(link);
                links.append(go);
            }
            message.append(links);
        }
        line.title = new Date(row.timestamp).toLocaleString();
        line.dataset.sig = signature(row);
        return line;
    }

    /**
     * Everything that can change about a row once it is on screen. The console
     * mutates its pending entry in place when the answer lands; without this the
     * cached node kept saying "running…" forever.
     */
    const signature = (row) =>
        [
            rawMode ? 'r' : 'p',
            row.level,
            row.entry?.pending ? 'pending' : 'settled',
            row.entry?.error ? 'error' : '',
            row.entry ? String(row.entry.output).length : '',
            row.entry?.links?.length || 0,
            row.entry ? '' : (row.line ?? '') + '|' + (row.message ?? ''),
            row.thread ?? '',
            search.value.trim().toLowerCase()
        ].join('·');

    function display(force = false) {
        const retained_rows = [
                ...rows,
                ...commandRows().filter((row) => !clearing.has(key(row)))
            ].sort(
                (a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp)
            ),
            visible = retained_rows.filter(matches),
            atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 35;
        const anchor = [...output.children].find(
                (n) => n.offsetTop >= output.offsetTop + output.scrollTop
            ),
            before = anchor?.getBoundingClientRect().top;
        const occurrences = new Map(),
            desired = [];
        let day = '';
        for (const row of visible) {
            const stamp = new Date(row.timestamp);
            const dayStamp = stamp.toDateString();
            if (day && dayStamp !== day) {
                // A buffer that spans midnight is unreadable without the date, and
                // only the boundary needs one: the first row is today by default.
                let divider = dividers.get(dayStamp);
                if (!divider) {
                    divider = el('div', null, 'log-day');
                    divider.textContent = stamp.toLocaleDateString(undefined, {
                        weekday: 'short',
                        day: '2-digit',
                        month: 'short'
                    });
                    dividers.set(dayStamp, divider);
                }
                desired.push(divider);
            }
            day = dayStamp;
            const id = key(row),
                occurrence = occurrences.get(id) || 0;
            occurrences.set(id, occurrence + 1);
            const slot = id + ':' + occurrence;
            let line = cache.get(slot);
            if (line && line.dataset.sig !== signature(row)) line = null;
            if (!line) {
                line = lineFor(row, slot);
                cache.set(slot, line);
            }
            desired.push(line);
        }
        retainedCount = retained_rows.length;
        if (!desired.length) {
            const note = el('p', null, 'log-empty');
            if (retained_rows.length)
                note.textContent =
                    'No line matches this filter. Clear the search or choose another severity.';
            else if (clearing.size) {
                // Clearing hid the lines; saying "nothing has been logged" would be
                // a lie about a buffer that is still there.
                note.append(
                    el(
                        'span',
                        retained
                            ? `${retained} lines are buffered but cleared from view. `
                            : 'Lines are cleared from view. '
                    )
                );
                const again = el('button', 'Restore them', 'link');
                again.type = 'button';
                again.onclick = () => unclear();
                note.append(again);
            } else
                note.textContent =
                    'Nothing has been logged yet. Lines appear as the server writes them.';
            desired.push(note);
        }
        const wanted = new Set(desired);
        for (const child of [...output.children])
            if (!wanted.has(child) && !child.classList.contains('log-day')) child.remove();
        for (const child of [...output.children])
            if (child.classList.contains('log-day') && !wanted.has(child)) child.remove();
        desired.forEach((child, i) => {
            if (output.children[i] !== child)
                output.insertBefore(child, output.children[i] || null);
        });
        for (const [slot, line] of cache) if (!wanted.has(line)) cache.delete(slot);
        if (!auto && anchor?.isConnected && before != null)
            output.scrollTop += anchor.getBoundingClientRect().top - before;
        renderStatus();
        if (auto && (atBottom || force)) {
            output.scrollTop = output.scrollHeight;
            if (!output.isConnected)
                requestAnimationFrame(() => {
                    if (auto && output.isConnected) output.scrollTop = output.scrollHeight;
                });
        }
        jump.hidden = auto || atBottom;
        // The live pulse follows traffic, not a timer: settle it back once the
        // last line is a few seconds old.
        clearTimeout(settle);
        settle = setTimeout(renderStatus, 2600);
    }

    /**
     * Accepts the log payload ({events, capacity, dropped, retained, file}) or a
     * bare array. While paused the rows are frozen but the count of new lines is
     * tracked, so a paused tail reports how far behind it is instead of pretending
     * that nothing arrived.
     */
    function update(incoming) {
        const payload = Array.isArray(incoming) ? { events: incoming } : incoming || {};
        const live = payload.events || [];
        lastPayload = payload;
        // File history is older than the live buffer by construction, and a poll
        // that carried no file history must not erase it. The same physical line
        // arrives from both, so a loaded file row is dropped as soon as the live
        // buffer carries the identical text: one line, printed once.
        const seen = new Set(live.map(key)),
            spoken = new Set(
                live.map((row) => (row.line ?? row.message ?? '').trim()).filter(Boolean)
            );
        const events = [
            ...fileRows.filter(
                (row) =>
                    !seen.has(key(row)) && !spoken.has((row.line ?? row.message ?? '').trim())
            ),
            ...live
        ];
        if (typeof payload.capacity === 'number') capacity = payload.capacity;
        if (typeof payload.dropped === 'number') dropped = payload.dropped;
        if (typeof payload.retained === 'number') retained = payload.retained;
        if (payload.file) file = payload.file;
        pending = events;
        const current = new Set([...events.map(key), ...commandRows().map(key)]);
        clearing = new Set([...clearing].filter((k) => current.has(k)));
        const kept = events.filter((r) => !clearing.has(key(r)));
        if (paused) {
            fresh = frozen ? kept.filter((row) => !frozen.has(key(row))).length : 0;
            renderStatus();
            return;
        }
        rows = kept.slice(-capacity);
        fresh = 0;
        // The pulse follows the newest line's own timestamp, not the poll: a static
        // buffer must not keep claiming it is live.
        const newest = events.reduce(
            (newest, row) => Math.max(newest, Date.parse(row.timestamp) || 0),
            0
        );
        if (newest) lastRowAt = newest;
        display();
    }

    /** Filtering must not yank a reader who scrolled back up to the bottom. */
    search.oninput = severity.onchange = () => display();
    output.addEventListener('scroll', () => {
        const away = output.scrollHeight - output.scrollTop - output.clientHeight > 50;
        jump.hidden = !away;
        if (auto && away) setTail(false);
    });
    // Terminal keys: search, clear, tail, home, leave. Fields keep their own keys.
    node.addEventListener('keydown', (event) => {
        const typing =
            event.target instanceof HTMLInputElement ||
            event.target instanceof HTMLTextAreaElement ||
            event.target instanceof HTMLSelectElement;
        // Search and clear are how a terminal is used, so they work from the prompt
        // as well; the unmodified keys must stay with whatever field has focus.
        if ((event.ctrlKey || event.metaKey) && !event.altKey) {
            const shortcut = event.key.toLowerCase();
            if (shortcut === 'f') {
                event.preventDefault();
                search.focus();
                search.select();
                return;
            }
            if (shortcut === 'l' && event.target !== search) {
                event.preventDefault();
                clear();
                return;
            }
        }
        if (!event.ctrlKey && !event.metaKey) {
            if (typing) return;
            if (event.key === 'End') {
                event.preventDefault();
                setTail(true);
                output.scrollTop = output.scrollHeight;
            } else if (event.key === 'Home') {
                event.preventDefault();
                setTail(false);
                output.scrollTop = 0;
            } else if (event.key === 'p' || event.key === 'P') {
                event.preventDefault();
                pause.click();
            } else if (event.key === 'Escape') {
                output.blur();
            }
            return;
        }
    });
    jump.onclick = () => {
        setTail(true);
        output.scrollTop = output.scrollHeight;
        jump.hidden = true;
        renderStatus();
        output.focus();
    };

    function clear() {
        // Hiding, never deleting: the cleared keys are remembered so Ctrl+L is
        // reversible, and the console's own transcript (which lives in `commands`)
        // is kept, so Restore can bring it back.
        for (const row of [...pending, ...commandRows()]) clearing.add(key(row));
        frozen = null;
        fresh = 0;
        display(true);
    }

    function unclear() {
        clearing = new Set();
        if (lastPayload) update(lastPayload);
        else display(true);
    }

    const transcript = (rawOnly) =>
        [...rows, ...commandRows()]
            .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
            .filter(matches)
            .map((row) =>
                rawOnly
                    ? rawLine(row)
                    : `${row.timestamp} [${row.level}] ${row.thread ? row.thread + ' ' : ''}${
                          row.entry ? rawLine(row) : (row.message ?? row.line)
                      }`
            )
            .join('\n');

    // Secondary actions stay behind one menu: a tail needs Pause, Tail and Jump,
    // not nine equal buttons in a row.
    const menu = el('details', null, 'terminal-menu'),
        // `button` carries the control language; the summary only needs to lose its
        // disclosure marker and its list styling.
        menuToggle = el('summary', 'More', 'button secondary sm terminal-menu-toggle'),
        menuBody = el('div', null, 'terminal-menu-body');
    menuToggle.title = 'Copy, export, raw lines and file history';
    const rawToggle = button(rawMode ? 'Show parsed lines' : 'Show raw lines', () => {
        rawMode = !rawMode;
        text(rawToggle, rawMode ? 'Show parsed lines' : 'Show raw lines');
        rawToggle.setAttribute('aria-pressed', String(rawMode));
        cache.clear();
        display();
    });
    rawToggle.setAttribute('aria-pressed', String(rawMode));
    rawToggle.title = 'Render the exact text the server wrote to its log file';
    menuBody.append(
        rawToggle,
        button('Copy', () =>
            copy(transcript(rawMode), rawMode ? 'Raw lines copied.' : 'Visible output copied.')
        ),
        button('Export', () => download('tortodrone-log.txt', transcript(rawMode)))
    );
    if (onLoadFile) {
        const load = button('Load last 500 from file', async () => {
            load.disabled = true;
            try {
                const result = await onLoadFile(500);
                if (!result?.lines?.length) {
                    announce.textContent = 'The log file has no lines yet.';
                    return;
                }
                const stamp = new Date(result.modified || Date.now()).toISOString();
                // `[24-11-2026 05:29:49] [ServerMain/INFO]: message` — the thread
                // comes before the slash and the level after it.
                const entries = result.lines.map((line, position) => {
                    const match = /^\[([^\]]+)\]\s*\[([^/\]]+)\/([^\]]+)\]:?\s*(.*)$/.exec(line);
                    return {
                        id: `file-${position}-${line.length}`,
                        timestamp: match ? fileStamp(match[1]) : stamp,
                        level: (match?.[3] || 'INFO').trim().toUpperCase(),
                        thread: (match?.[2] || '').trim(),
                        message: match ? match[4] : line,
                        line,
                        fromFile: true
                    };
                });
                // Kept hold of, so the next poll (which only knows the live buffer)
                // cannot silently delete what was just loaded from disk.
                fileRows = entries;
                update({ events: pending, capacity, dropped, retained, file });
                announce.textContent = `Loaded ${entries.length} lines from ${result.path}.`;
            } catch (error) {
                announce.textContent = 'Could not read the log file: ' + error.message;
            } finally {
                load.disabled = false;
            }
        });
        menuBody.append(load);
    }
    menu.append(menuToggle, menuBody);
    tools.append(search, severity, pause, follow, menu, undoClear, count);

    /** Parse the file's own `[24-11-2026 04:53:11]` prefix into an instant. */
    function fileStamp(value) {
        const match = /(\d{2})-(\d{2})-(\d{4}) (\d{2}):(\d{2}):(\d{2})/.exec(value);
        if (!match) return new Date().toISOString();
        const [, day, month, year, hour, minute, second] = match;
        return new Date(
            Number(year),
            Number(month) - 1,
            Number(day),
            Number(hour),
            Number(minute),
            Number(second)
        ).toISOString();
    }

    /**
     * Fit the shell to the space the page actually leaves it.
     *
     * A fixed `calc(100dvh - 210px)` is a guess about the chrome above the pane,
     * and on a phone the guess is wrong by 80px: the console prompt lands below
     * the fold. Measuring the pane's own top is right at every width.
     */
    let fitting = 0,
        settle = 0,
        fittingFrame = 0;
    function scheduleFit() {
        if (!fill || fittingFrame) return;
        fittingFrame = requestAnimationFrame(() => {
            fittingFrame = 0;
            fit();
        });
    }
    function fit() {
        // A page mounts its node after this component returns, so the first frame
        // may arrive before the shell is in the document: wait for it instead of
        // measuring a detached element at 0,0.
        if (!node.isConnected) {
            if (fitting++ < 120) requestAnimationFrame(fit);
            return;
        }
        fitting = 0;
        const top = node.getBoundingClientRect().top;
        // The chrome (bar, tools, prompt, status) is fixed height, so the shell can
        // never be shorter than it without clipping the prompt: on a landscape
        // phone the page scrolls a little instead of hiding the cursor.
        const fixed = [...node.children]
            .filter((child) => !child.classList.contains('terminal-body'))
            .reduce((total, child) => total + child.offsetHeight, 0);
        const floor = Math.max(window.innerHeight < 520 ? 180 : 240, fixed + 56);
        node.style.blockSize = Math.max(floor, window.innerHeight - top - 18) + 'px';
        node.style.minHeight = '0';
    }
    if (fill) {
        node.classList.add('terminal-fill');
        window.addEventListener('resize', fit);
        requestAnimationFrame(fit);
    }

    renderStatus();
    return {
        node,
        fit,
        update,
        output,
        footer,
        state,
        redraw: () => display(),
        tools,
        clear,
        announce: (message) => {
            announce.textContent = message;
        },
        focusSearch: () => search.focus(),
        /** Re-apply a deep-linked filter without reloading the page. */
        filter({ severity: level, search: term } = {}) {
            if (level && LEVELS.includes(level) && severity.querySelector(`option[value="${level}"]`))
            severity.value = level;
            if (typeof term === 'string') search.value = term;
            display();
        },
        get paused() {
            return paused;
        },
        get raw() {
            return rawMode;
        },
        get fill() {
            return fill;
        }
    };
}
