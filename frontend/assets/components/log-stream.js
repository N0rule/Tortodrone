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
/**
 * Chords and their verbs, as one row of bindings. The chord wears the same key face
 * the shell uses for Ctrl K, so the pane and the top bar spell a shortcut the same
 * way; the verb stays a word at the smaller caption size.
 */
function keyHint(keys = [], note = null) {
    const row = el('span', null, 'terminal-keys');
    for (const [chord, verb] of keys) {
        const binding = el('span', null, 'terminal-key');
        binding.append(el('kbd', chord, 'kbd'), el('span', verb, 'terminal-key-verb'));
        row.append(binding);
    }
    if (note) row.append(el('span', note, 'terminal-key-note'));
    return row;
}

export function logStream({
    commands = null,
    title = 'Server output',
    prompt = 'tortodrone',
    severity: initialSeverity = 'All',
    search: initialSearch = '',
    period: initialPeriod = 'All time',
    wrap: initialWrap = true,
    raw = false,
    fill = false,
    // The keys a pane answers to, as chords and their verbs. Six clauses strung on
    // middots read as one sentence about the pane; a chord in a key face and a word
    // after it reads as six things an operator can press.
    keys = [
        ['Ctrl F', 'search'],
        ['Ctrl L', 'clear'],
        ['End', 'tail'],
        ['Home', 'top'],
        ['P', 'pause'],
        ['Esc', 'leave']
    ],
    // The sentence that follows the chords, when a pane has one.
    note = null,
    onLoadFile = null,
    onFilter = null,
    // The Console answers commands; it has no tail to follow and nothing to pause, so
    // those two controls are not built there at all (the page's own hint said so).
    tail = true,
    // A page whose rows are its own answers, not the server's lines. Such a pane is a
    // terminal: it prints its rows as text instead of tabulating them, it carries no
    // counter in the bar and no source strip, because neither is a thing a terminal has.
    countAnswers = false,
    // What an empty pane is called. A tail says lines arrive as the server writes
    // them; a console holds answers to commands, and it printed the log's sentence
    // over a prompt that had simply never been used.
    emptyNote = 'Nothing has been logged yet. Lines appear as the server writes them.',
    // What the source strip is describing, when the pane is not the log itself.
    sourceLabel = null,
    // A console has no Tail control but still opens at its prompt, the way a
    // terminal does: without this it opened on the oldest server line in the buffer
    // and the prompt, the banner and the last answer were all below the fold.
    autoScroll = false,
    // Where to go next from what the pane shows. A warning in the log names a
    // subsystem and stops there; the pages that explain it belong beside the pane,
    // not in a sentence the operator has to translate into a route.
    extras = [],
    // The same destinations for a bar with no room for them: a phone in landscape has
    // a single row of controls 44px tall and a strip of three links beside it would
    // have to scroll sideways out of sight to be reached. They go in the menu that is
    // already there instead — one tap, nothing hidden.
    menuLinks = [],
    // A terminal emulator has no toolbar: its controls are chords and a find bar that
    // appears when asked for, over the output it is searching. A page that renders the
    // pane as the terminal itself passes this, and the row of fields stops being
    // permanent chrome above the screen.
    findBar = false,
    // Called when the find bar opens and closes, so the page can put the caret back on
    // its own prompt instead of leaving it nowhere.
    onFind = null
} = {}) {
    const node = el('div', null, 'terminal-shell'),
        // The first paint is measured a frame later, so the pane is kept invisible
        // until then: it used to appear at the CSS floor and grow 82px.
        unhide = () => {
            node.style.visibility = '';
        },
        bar = el('div', null, 'terminal-bar'),
        led = el('span', null, 'terminal-led'),
        titleNode = el('h2', title, 'terminal-title'),
        barMeta = el('span', null, 'terminal-bar-meta'),
        // "1 of 20", beside the field that made the marks: Enter steps through them.
        matchCount = el('span', null, 'terminal-match-count'),
        tools = toolbar(),
        output = el('div', null, 'terminal-body'),
        // The line after the last one: a page that puts its own prompt inside the
        // scrollback - a terminal keeps its cursor at the end of the buffer, not in a
        // strip under the screen - reserves this slot and fills it. display() rebuilds
        // the rows by index and removes children it did not draw, so the slot has to be
        // known to it or the prompt would be culled on the next paint.
        bodyTail = el('div', null, 'terminal-tail-slot'),
        footer = el('div', null, 'terminal-status'),
        state = el('span', null, 'terminal-state'),
        statusLeft = el('span', null, 'terminal-hint'),
        statusRight = el('span', null, 'terminal-tail'),
        source = el('span', null, 'terminal-source'),
        announce = el('span', null, 'visually-hidden'),
        jump = el('button', 'Jump to latest', 'terminal-jump');
    tools.classList.add('terminal-tools');
    node.style.visibility = 'hidden';
    // A missing frame loop must not leave a blank pane behind.
    setTimeout(unhide, 700);
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
    output.append(bodyTail);
    jump.type = 'button';
    jump.hidden = true;
    jump.setAttribute('aria-label', 'Jump to latest');
    footer.setAttribute('role', 'status');
    // The keys differ per page, so the caller owns the list: the Console has no tail
    // or pause, and advertising them there is a promise nothing keeps.
    statusLeft.append(keyHint(keys, note));
    footer.append(state, statusLeft, statusRight, source);
    node.append(bar, tools, output, announce, jump, footer);

    const search = input('Search output', '', 'search'),
        // COMMAND rows only exist on the Console, so Logs does not offer a filter
        // that can never match.
        severity = select('Severity', commands ? LEVELS : LEVELS.filter((l) => l !== 'COMMAND'), 'All');
    // A 1000-line buffer spans days on a quiet server, and severity plus free text
    // could not narrow it: "what happened in the last hour" meant reading timestamps.
    const period = select('Period', ['All time', 'Last 5 minutes', 'Last hour', 'Last 24 hours'], 'All time');
    period.title = 'Narrow the pane to lines newer than a window';
    search.placeholder = 'Search output';
    search.classList.add('terminal-search');
    search.title = 'Search the output (Ctrl+F)';
    if (initialSearch) search.value = initialSearch;
    // A deep link may name a level this page does not offer (COMMAND on Logs): the
    // select would then sit on "All" while the URL claimed a filter.
    const severityOffered = [...severity.options].some(
        (option) => option.value === initialSeverity
    );
    if (severityOffered) severity.value = initialSeverity;
    if ([...period.options].some((option) => option.value === initialPeriod))
        period.value = initialPeriod;

    let paused = false,
        // A page that reports its connection somewhere else than the status strip - the
        // console says it in the bar, which is always on screen - lifts the LED itself.
        offlineState = false,
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
        initialWrapMode = initialWrap !== false,
        lastRowAt = 0,
        fileRows = [],
        retainedCount = 0,
        lastPayload = null,
        clearing = new Set(),
        // A stack trace or a long JSON line is unreadable when it reflows; the toggle
        // swaps `pre-wrap` for one scrollable row, the way a terminal does.
        wrapMode = initialWrapMode,
        // A console shows its own answers first: the server log underneath is context,
        // and mixing the two buried every answer under live INFO chatter. (`source` is
        // already the status strip, hence `sourceMode`.)
        sourceMode = commands ? 'answers' : 'both';

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

    const PERIODS = {
        'Last 5 minutes': 5 * 60_000,
        'Last hour': 3_600_000,
        'Last 24 hours': 86_400_000
    };
    function matches(row) {
        // The gutter is painted, so search has to see it: typing a level name returned
        // "no line matches" over 500 rows of exactly that level.

        if (severity.value !== 'All' && row.level !== severity.value) return false;
        const span = PERIODS[period.value];
        if (span) {
            const at =
                typeof row.timestamp === 'number' ? row.timestamp : Date.parse(row.timestamp);
            // A line whose time cannot be parsed is never hidden by a window: it stays
            // visible, the same rule the audit trail follows.
            if (Number.isFinite(at) && Date.now() - at > span) return false;
        }
        const needle = search.value.toLowerCase();
        if (!needle) return true;
        // Search what a reader can see: the message (or the command and its
        // response), the thread and the level the gutter prints. Never the hidden raw
        // line, and never a column that is not on screen.
        const visible = row.entry ? `${row.entry.command} ${row.entry.output}` : row.message ?? row.line;
        return `${row.level ?? ''} ${row.thread ?? ''} ${visible}`.toLowerCase().includes(needle);
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

    /**
     * The rows this pane is showing: a console can put its own answers above the
     * server's lines, or show either alone. A log page always has both.
     */
    const allRows = () =>
        sourceMode === 'answers'
            ? commandRows()
            : sourceMode === 'log'
              ? rows
              : [...rows, ...commandRows()];

    // A shell prints `key: value` in aligned columns, not fifty lines of braces: the
    // pretty-printed dump made one `status` answer taller than the pane.
    function formatOutput(value, command = '') {
        const textValue = typeof value === 'string' ? value : String(value ?? '');
        const trimmed = textValue.trim();
        const structured =
            (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
            (trimmed.startsWith('[') && trimmed.endsWith(']'));
        if (!structured) return textValue;
        try {
            const parsed = JSON.parse(trimmed);
            // An empty answer used to print a lone dash under the prompt, which is a
            // punctuation mark where an answer should be: `players` with nobody on
            // said nothing an operator could read. It names the absence instead.
            if (Array.isArray(parsed) && !parsed.length)
                return command ? `(no ${command})` : '—';
            return align(parsed);
        } catch {
            /* keep the raw text when it is not valid JSON */
        }
        return textValue;
    }

    /**
     * One answer, printed whole. It used to fold after 24 lines behind a "show all"
     * link, which is a widget standing in the middle of a terminal: the output is the
     * answer, and a terminal does not ask whether to print the rest of it. The pane
     * scrolls and Ctrl+L clears, which is how the transcript above the prompt is
     * already handled.
     */
    function answerBody(entry) {
        const formatted = formatOutput(entry.output, entry.command),
            response = el('pre', null, 'command-response');
        // A find that filters the transcript but never marks the hit is half a find. The
        // answer is painted the same way a server line is, so `Ctrl+F player` shows the
        // word in the answer it matched instead of only hiding the rows around it.
        if (!search.value.trim()) {
            response.textContent = formatted;
            return response;
        }
        formatted.split('\n').forEach((line, at) => {
            if (at) response.append('\n');
            response.append(painted(line));
        });
        return response;
    }

    /**
     * A value that has to fit in one column. A nested object in a table cell used to
     * print `[object Object]`, which is the machine's word for the data, not the data:
     * `monsters` is a table of counts and reads as `awake 0, despawned 0, …`.
     */
    const inline = (value) => {
        if (value === null || value === undefined) return '—';
        if (typeof value !== 'object') return String(value);
        if (Array.isArray(value)) return value.length ? value.map(inline).join(', ') : '—';
        const entries = Object.entries(value);
        return entries.length
            ? entries.map(([key, item]) => `${key} ${inline(item)}`).join(', ')
            : '—';
    };

    /** The scalar as a terminal prints it: booleans bare, nothing a JSON parser needs. */
    const cell = (value) => {
        if (value === null || value === undefined) return '—';
        if (typeof value === 'object') return inline(value);
        return String(value);
    };

    /**
     * A shell prints `613 MiB`, not `643825664`: the same figures the Overview formats
     * were arriving as raw scalars, so one `status` answer was less readable than the
     * card it links to.
     */
    const human = (key, value) => {
        if (typeof value !== 'number' || !Number.isFinite(value)) return cell(value);
        const name = String(key).toLowerCase();
        // An instant is not a number anyone can read: `createdAt : 1,795,519,994,109` in
        // an answer column answered nothing about when the account was made. The suffix
        // test is on the name and the size test on the value, so a duration or a
        // `uptime` in seconds is never mistaken for one.
        if (/(at|time|date)$/.test(name) && value >= 1e11)
            return new Date(value).toISOString().slice(0, 16).replace('T', ' ');
        // `heapCommitted` and `physicalTotal` are byte counts whose names do not end in
        // `Bytes`: one `status` answer printed five nine-digit integers.
        if (/bytes?$|size$|heap|physical|memory/.test(name) && Math.abs(value) >= 1024) {
            const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
            let size = Math.abs(value),
                unit = 0;
            while (size >= 1024 && unit < units.length - 1) {
                size /= 1024;
                unit++;
            }
            return `${value < 0 ? '-' : ''}${size.toFixed(unit ? 1 : 0)} ${units[unit]}`;
        }
        if (/nanos|ns$/.test(name)) return `${(value / 1e6).toFixed(2)} ms`;
        if (/millis|ms$/.test(name)) return `${value.toFixed(1)} ms`;
        if (/cpu|percent|ratio$/.test(name) && Math.abs(value) <= 1)
            return `${(value * 100).toFixed(1)} %`;
        if (/seconds|elapsed|uptime$/.test(name)) {
            const total = Math.max(0, Math.round(value));
            const days = Math.floor(total / 86400),
                rest = total % 86400,
                hours = Math.floor(rest / 3600),
                minutes = Math.floor((rest % 3600) / 60);
            if (days) return `${days}d ${hours}h`;
            if (hours) return `${hours}h ${minutes}m`;
            if (minutes) return `${minutes}m ${total % 60}s`;
            return `${total}s`;
        }
        if (/count|total|used|free|available|max$/.test(name))
            return Math.round(value).toLocaleString('en-US');
        if (/fraction|load/.test(name)) return value.toFixed(2);
        return Number.isInteger(value) ? value.toLocaleString('en-US') : String(value);
    };

    /**
     * A list of records as a table with one header row, which is how a terminal prints
     * them: `ps`, a SQL client, anything that has more than one row to show. Nested
     * plain, four accounts printed `username: alice` five times over and the four rows
     * of data read as one wall of repeated keys with no seam between them.
     */
    function table(records, indent) {
        const columns = [...new Set(records.flatMap((record) => Object.keys(record)))],
            widths = columns.map((column) =>
                Math.max(
                    column.length,
                    ...records.map((record) => String(human(column, record[column])).length)
                )
            ),
            row = (cells) =>
                indent +
                cells
                    .map((value, at) => String(value).padEnd(widths[at]))
                    .join('  ')
                    .trimEnd();
        return [
            row(columns),
            row(widths.map((width) => '-'.repeat(width))),
            ...records.map((record) => row(columns.map((column) => human(column, record[column]))))
        ].join('\n');
    }

    /** Key/value pairs in two aligned columns, nesting by indentation. */
    function align(value, indent = '') {
        if (value === null || typeof value !== 'object') return indent + cell(value);
        if (Array.isArray(value)) {
            if (!value.length) return indent + '—';
            if (value.every((item) => item === null || typeof item !== 'object'))
                return indent + value.map(cell).join(', ');
            const records = value.every(
                (item) => item && typeof item === 'object' && !Array.isArray(item)
            );
            if (!records) return value.map((item) => align(item, indent)).join('\n');
            // A record with a nested object cannot be a row: the cell would be a
            // paragraph, and one long cell pushes every column after it off the line
            // (`scenes` carries a `monsters` bag of twelve counters). Those records
            // print as their own block, which is what `align` already knows how to do.
            const flat = value.every((record) =>
                Object.values(record).every((item) => item === null || typeof item !== 'object')
            );
            return flat
                ? table(value, indent)
                : value.map((record) => align(record, indent + '  ')).join('\n\n');
        }
        const entries = Object.entries(value);
        if (!entries.length) return indent + '—';
        const width = Math.max(...entries.map(([key]) => key.length));
        return entries
            .map(([key, item]) => {
                const label = indent + key.padEnd(width);
                const nested = item !== null && typeof item === 'object';
                // An empty bag has nothing to indent: `byType` printed as a dangling
                // colon with a lone dash under it, in a block where every other key was
                // `key : value` on one line, and the dash landed in a column of its own.
                const empty =
                    nested && (Array.isArray(item) ? !item.length : !Object.keys(item).length);
                if (nested && !empty) return `${label}:\n${align(item, indent + '  ')}`;
                return `${label}: ${empty ? '—' : human(key, item)}`;
            })
            .join('\n');
    }

    /** The exact line the server wrote to data/logs/latest.log, for raw mode and Copy. */
    const rawLine = (row) =>
        row.entry ? row.entry.command + '\n' + (row.entry.output ?? '') : row.line ?? row.message ?? '';

    /** A cleared row is still in the buffer; it is just not on screen any more. */
    const cleared = (row) => clearing.has(key(row));

    function renderStatus() {
        // One count, derived from what is painted: the bar, the empty note and the
        // Restore pill all read the same set, so Ctrl+L can no longer report 0, 12
        // and 14 lines for one screen.
        // A console's own rows are answers; the log lines it streams are the server's
        // words behind them. Counting both gave "13 answers" on a console with none.
        // An answer is a command's reply: the banner and the restore notice are lines
        // in the transcript, not answers, and counting them said "1 answer" for none.
        const answered = (row) => row.entry && row.entry.command && row.entry.command !== '';
        // Only a pane showing its own answers counts answers: in `Server log` mode the
        // rows have no entry, so the bar read "0 answers" over a full pane.
        const countingAnswers = countAnswers && sourceMode !== 'log',
            counted = countingAnswers ? allRows().filter(answered) : allRows(),
            visible = counted.filter((row) => matches(row) && !cleared(row)).length,
            total = countingAnswers ? counted.length
                : retainedCount || visible,
            away = output.scrollHeight - output.scrollTop - output.clientHeight > 50;
        // A console is not counted at: "3 answers" is a dashboard figure, and a terminal
        // has no dashboard. The page writes its own state ("Executing…", "Offline") into
        // this slot, so the console branch must also leave it alone rather than blanking
        // it on every paint.
        // A cleared screen says nothing about counts: the pill is the whole story.
        if (!countAnswers)
            text(
                barMeta,
                clearing.size
                    ? ''
                    : `${visible} ${visible === 1 ? 'line' : 'lines'}${
                          visible !== total ? ` of ${total}` : ''
                      }${paused ? ', paused' : ''}${paused && fresh ? `, ${fresh} new` : ''}`
            );
        // The pill names what the pane is doing, not what the tail wants to do — and a
        // pane with no tail (the Console) has no pill to fill: it printed the word
        // "tail" on a page whose own footer says there is nothing to follow.
        text(
            statusRight,
            !tail
                ? ''
                : paused
                  ? `paused${fresh ? `, ${fresh} new` : ''}`
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
        // Where the rest of the log lives is a fact about a log file, and a console is
        // not reading one: the buffer line described a screen the operator is not
        // looking at, so a console's source strip is empty and collapses with the rest.
        const bounds = [];
        if (!countAnswers) {
            if (dropped) bounds.push(`${retained} of ${capacity}, ${dropped} older dropped`);
            else if (retained) bounds.push(`${retained} buffered`);
            // A file load is drawn from its own rows, so the live buffer's count alone
            // described a pane that was showing five hundred lines.
            if (fileRows.length) bounds.push(`${fileRows.length} loaded from the file`);
            if (file?.path) {
                const size = file.bytes ? `${Math.round(file.bytes / 1024)} KB` : null;
                const name = file.path.split(/[\\/]/).pop();
                bounds.push(
                    name +
                        (size ? ` (${size})` : '') +
                        (file.exists === false ? ', not written yet' : '')
                );
            }
            // One phrase, not six clauses on middots: the source line said what the pane
            // is, how full its buffer is and where the file lives in a single run.
            text(
                source,
                [typeof sourceLabel === 'function' ? sourceLabel() : sourceLabel, ...bounds]
                    .filter(Boolean)
                    .join(', ')
            );
        } else text(source, '');
        // The console reports its connection in the bar, which is always on screen, so it
        // lifts the LED through `setOffline`; the strip's own error class stays readable
        // here for the pages that still say it there.
        const offline =
            offlineState || state.classList.contains('error') || !!state.querySelector('.error');
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
        // The severity filter is also the only place per-level counts can be read, so
        // it carries them: "3 ERROR among 1000" was invisible until you chose ERROR and
        // counted what was left. Counted over the rows the search and the period leave,
        // never over the severity filter itself, or every option would read 0 except the
        // chosen one. The period belongs in the count: without it the select offered
        // "ERROR (300)" over a pane holding the last five minutes and nothing else.
        const needle = search.value.trim().toLowerCase(),
            span = PERIODS[period.value],
            counts = new Map(),
            // The bar counts a console's answers and its severity filter counted every
            // row, so a fresh console read "0 answers" beside "All (1)" — the one extra
            // row being the banner, which says nothing about commands.
            onlyAnswers = countAnswers && sourceMode !== 'log';
        for (const row of allRows()) {
            if (cleared(row)) continue;
            if (onlyAnswers && !(row.entry && row.entry.command)) continue;
            if (span) {
                const at =
                    typeof row.timestamp === 'number'
                        ? row.timestamp
                        : Date.parse(row.timestamp);
                if (Number.isFinite(at) && Date.now() - at > span) continue;
            }
            if (
                needle &&
                !`${row.level ?? ''} ${row.thread ?? ''} ${
                    row.entry ? `${row.entry.command} ${row.entry.output}` : (row.message ?? row.line)
                }`
                    .toLowerCase()
                    .includes(needle)
            )
                continue;
            counts.set(row.level, (counts.get(row.level) || 0) + 1);
        }
        for (const option of severity.options) {
            const label = option.value === 'All' ? 'All' : option.value;
            const count = option.value === 'All' ? [...counts.values()].reduce((a, b) => a + b, 0) : (counts.get(option.value) || 0);
            const next = count ? `${label} (${count})` : label;
            if (option.textContent !== next) option.textContent = next;
        }
        // A notice appearing above the pane (or a second status row) moves its top
        // edge, and that is the only reason to resize: refitting on every status
        // repaint grew the pane under the operator's eyes on arrival (305px → 490px).
        // A terminal has no second status bar: a console's keys are in its banner and
        // its connection state in the bar above, so its strip is only there when
        // something is written into it. An empty one was a rule and 22px of padding
        // under the prompt, saying nothing.
        footer.hidden =
            !statusLeft.textContent.trim() &&
            !statusRight.textContent.trim() &&
            !source.textContent.trim() &&
            !state.textContent.trim();
        const chrome = [...node.children]
            .filter((child) => !child.classList.contains('terminal-body'))
            .reduce((total, child) => total + child.offsetHeight, 0);
        if (chrome !== lastChrome) {
            lastChrome = chrome;
            scheduleFit();
        }
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

    /**
     * One row of the pane. A server line is tabulated, because a log is a table of
     * time, level, thread and message and the columns are how you read it. An answer is
     * not: a terminal prints `tortodrone$ accounts` and its output under it, and the
     * gutter this pane used to put in front of every command - `22:17:53  CMD` - was
     * the last piece of log viewer left in the console.
     */
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
        if (row.entry?.notice) {
            // A shell prints its candidate list under the prompt without repeating the
            // prompt line; the transcript is the record of what was issued.
            line.classList.add('notice');
            message.replaceChildren(answerBody(row.entry));
            line.append(message);
            line.classList.add('printed');
            line.title = new Date(row.timestamp).toLocaleString();
            line.dataset.sig = signature(row);
            return line;
        }
        if (row.entry) {
            message.replaceChildren(el('span', `${prompt}$ ${row.entry.command}`, 'command'));
            // A command that has not answered yet shows a running marker instead of
            // an empty response block.
            if (row.entry.pending) message.append(el('span', 'running…', 'command-pending'));
            else message.append(answerBody(row.entry));
        } else if (rawMode) message.textContent = row.line ?? row.message ?? '';
        else message.append(painted(row.message ?? row.line));
        if (row.entry) {
            line.classList.add('printed');
            line.append(message);
        } else if (rawMode) {
            // The file's own line already says `[time] [thread/LEVEL]:`, so the
            // gutter would print all three twice.
            line.classList.add('raw');
            line.append(message);
        } else line.append(time, level, thread, message);
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
            row.entry?.notice ? 'n' : '',
            row.level,
            row.entry?.pending ? 'pending' : 'settled',
            row.entry?.error ? 'error' : '',
            row.entry ? String(row.entry.output).length : '',
            row.entry ? '' : (row.line ?? '') + '|' + (row.message ?? ''),
            row.thread ?? '',
            search.value.trim().toLowerCase()
        ].join('·');

    // One beat of arrival, then gone. The class is dropped on a timer rather than on
    // `animationend`, because a reader who asked for reduced motion gets no animation
    // to end and the mark would stay on the row for the rest of the session.
    function markArrival(line) {
        line.classList.add('is-new');
        setTimeout(() => line.classList.remove('is-new'), 200);
    }

    let firstPaint = true;
    function display(force = false) {
        // The first paint always lands at the end: a pane that opens above its own
        // prompt makes the operator scroll before they can type.
        if (firstPaint) {
            firstPaint = false;
            if (autoScroll) force = true;
        }
        // Cleared means cleared: filtering only the console's own rows left a paused
        // pane showing twelve lines under the words "0 lines of 12".
        const retained_rows = allRows()
            .filter((row) => !clearing.has(key(row)))
            .sort(
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
                // A line that has just arrived is said so once: the newest row at the
                // end of a full pane is otherwise indistinguishable from the one above
                // it, and an operator watching a quiet tail could not tell whether the
                // tail was working. The first paint has nothing to announce.
                if (!firstPaint) markArrival(line);
            }
            desired.push(line);
        }
        retainedCount = retained_rows.length;
        if (!desired.length) {
            const note = el('p', null, 'log-empty');
            if (retained_rows.length)
                note.textContent = findBar
                    ? // The console keeps its search field behind Ctrl+F, so a sentence
                      // naming a search box points at nothing on this screen; the bar's
                      // filter chip is what is there to clear. It stays inside one line of
                      // the 78ch reading measure that every paragraph carries.
                      'No line matches this filter. Clear it in the bar, or press Ctrl F.'
                    : 'No line matches this filter. Clear the search or choose another severity.';
            else if (clearing.size) {
                // Clearing hid the lines; saying "nothing has been logged" would be
                // a lie about a buffer that is still there.
                note.append(
                    el('span', `${clearing.size} ${clearing.size === 1 ? 'line is' : 'lines are'} cleared from view. `)
                );
                const again = el('button', 'Restore them', 'link');
                again.type = 'button';
                again.onclick = () => unclear();
                note.append(again);
            } else note.textContent = emptyNote;
            desired.push(note);
        }
        const wanted = new Set(desired);
        for (const child of [...output.children])
            if (!wanted.has(child) && !child.classList.contains('log-day') && child !== bodyTail)
                child.remove();
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
        if ((auto || autoScroll) && (atBottom || force)) {
            output.scrollTop = output.scrollHeight;
            if (!output.isConnected)
                requestAnimationFrame(() => {
                    if (auto && output.isConnected) output.scrollTop = output.scrollHeight;
                });
        }
        jump.hidden = auto || atBottom;
        // A terminal does not wrap a table, it scrolls sideways; a pane that has more
        // line to the right than it can show has to say so, or a cell cut at the edge
        // reads as a table that broke.
        node.classList.toggle('is-wide', output.scrollWidth - output.clientWidth > 4);
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
    /**
     * Enter steps the search matches, and the field names which one is in view: a
     * terminal's own search walks its hits, and "20 marks painted somewhere in the
     * buffer" is not an answer to "where is the next one".
     */
    let matchIndex = -1;
    function stepMatch(step) {
        const marks = [...output.querySelectorAll('mark')];
        if (!marks.length) return;
        matchIndex = (matchIndex + step + marks.length) % marks.length;
        for (const mark of marks) mark.classList.toggle('mark-current', false);
        const current = marks[matchIndex];
        current.classList.add('mark-current');
        current.scrollIntoView({ block: 'center' });
        text(
            matchCount,
            `${matchIndex + 1} of ${marks.length}`
        );
    }
    search.onkeydown = (event) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            // A new term starts at its first hit, the way a search box does.
            if (!output.querySelector('mark.mark-current')) matchIndex = -1;
            stepMatch(1);
            return;
        }
        // The hint says Esc leaves; from the search field that means back to the pane.
        if (event.key === 'Escape') {
            event.preventDefault();
            output.focus({ preventScroll: true });
        }
    };
    const applyFilters = () => {
        matchIndex = -1;
        text(matchCount, '');
        display();
        // The view in front of the operator is what they copy or bookmark, so the
        // address follows the filters instead of only being read from.
        if (onFilter)
            onFilter({
                severity: severity.value,
                search: search.value,
                period: period.value,
                raw: rawMode,
                wrap: wrapMode
            });
    };
    // Typing a term repaints and rewrites the address; per keystroke over a 1000-line
    // buffer that is a rebuild of every row on every letter. A select is a deliberate
    // act and applies at once.
    let filterTimer = 0;
    search.oninput = () => {
        clearTimeout(filterTimer);
        filterTimer = setTimeout(applyFilters, 120);
    };
    severity.onchange = period.onchange = applyFilters;
    output.addEventListener('scroll', () => {
        const away = output.scrollHeight - output.scrollTop - output.clientHeight > 50;
        jump.hidden = !away;
        if (auto && away) setTail(false);
        // Home leaves the pane at the top with the tail still on, so the pill lies
        // until the next poll repaints it. It repaints now.
        else renderStatus();
    });
    // The terminal keys only work on a focused pane, and a page that opens with a
    // live stream in front of you should answer Ctrl+L without a click first.
    // The page hands the node to the document after mount, so the first frame may
    // still be detached: try again on the next one before giving up.
    const grabFocus = (attempt = 0) => {
        if (!node.isConnected || document.activeElement !== document.body) return;
        output.focus({ preventScroll: true });
        if (document.activeElement !== output && attempt < 3) grabFocus(attempt + 1);
    };
    if (typeof requestAnimationFrame === 'function')
        requestAnimationFrame(() => requestAnimationFrame(() => grabFocus()));
    // Terminal keys: search, clear, tail, home, leave. Fields keep their own keys.
    const onKeydown = (event) => {
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
                // On a find-bar pane the field is hidden until this keystroke; focusing
                // an element nobody can see moved the caret into the dark.
                if (findBar) setFind(true);
                else {
                    search.focus();
                    search.select();
                }
                return;
            }
            if (shortcut === 'c' && event.shiftKey) {
                const selected = window.getSelection()?.toString() ?? '';
                if (!selected.trim()) return;
                event.preventDefault();
                copy(selected, 'Selection copied.');
                return;
            }
            if (shortcut === 'l') {
                // Ctrl+F lands in the search field, so Ctrl+L has to work there too:
                // skipping it there made the key dead exactly where the previous
                // keystroke put the operator.
                event.preventDefault();
                clear();
                return;
            }
        }
        if (!event.ctrlKey && !event.metaKey) {
            // Escape is the terminal's way out of a find bar, and it is pressed while the
            // caret is in the bar's own field - so it is answered before the typing guard.
            if (findBar && event.key === 'Escape' && !tools.hidden) {
                event.preventDefault();
                setFind(false);
                return;
            }
            if (typing) return;
            if (event.key === 'End') {
                event.preventDefault();
                setTail(true);
                output.scrollTop = output.scrollHeight;
            } else if (event.key === 'Home') {
                event.preventDefault();
                setTail(false);
                output.scrollTop = 0;
            } else if ((event.key === 'p' || event.key === 'P') && tail) {
                // Only a pane with a Pause control binds `p`: the Console has neither
                // the control nor the hint, and it was pausing itself with no way back.
                event.preventDefault();
                pause.click();
            } else if (event.key === 'Escape') {
                // "Esc leave" moved focus nowhere: leaving a pane means the page
                // heading, not a blur that leaves the caret nowhere.
                output.blur();
                const heading = document.querySelector('#title');
                if (heading) heading.focus({ preventScroll: true });
            }
            return;
        }
    };
    node.addEventListener('keydown', onKeydown);
    // A page that opens with a live stream should answer Ctrl+L without a click first.
    // When nothing else holds focus the keys are the page's, so they are handled here
    // too, with the same typing guard as inside the pane.
    // A page that opens with a live stream should answer Ctrl+L without a click first.
    // The shell focuses its heading on arrival, so "nothing interactive is focused" is
    // the condition, not "the body is focused"; a field or an open dialog keeps its keys.
    const nothingFocused = () => {
        const active = document.activeElement;
        if (!active || active === document.body) return true;
        return /^(H[1-6]|MAIN|SECTION|ARTICLE|P|DIV)$/.test(active.tagName);
    };
    // Held by name: this listener lives on `document`, so a page that is left behind
    // keeps answering Ctrl+L for a pane that no longer exists. Every visit to Logs or
    // Console used to add one more copy, each of them running a tag-name test on every
    // keystroke in the shell.
    const onPageKeydown = (event) => {
        if (!node.isConnected || event.defaultPrevented) return;
        if (document.querySelector('dialog[open]')) return;
        if (!nothingFocused()) return;
        onKeydown(event);
    };
    document.addEventListener('keydown', onPageKeydown);
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
        // Only what is on screen: adding the whole pending buffer made the pill
        // count lines the pane had never shown.
        for (const row of allRows()) clearing.add(key(row));
        frozen = null;
        fresh = 0;
        display(true);
    }

    function unclear() {
        clearing = new Set();
        if (lastPayload) update(lastPayload);
        else display(true);
    }

    /**
     * What the pane is showing, as text. Cleared rows are excluded: `matches` only
     * ever filtered the *filter*, so Copy and Export handed back lines the operator
     * had explicitly cleared while the toast claimed "visible output copied".
     */
    const transcriptBody = (rawOnly) =>
        allRows()
            .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
            .filter((row) => matches(row) && !cleared(row))
            .map((row) =>
                rawOnly
                    ? rawLine(row)
                    : `${row.timestamp} [${row.level}] ${row.thread ? row.thread + ' ' : ''}${
                          row.entry ? rawLine(row) : (row.message ?? row.line)
                      }`
            )
            .join('\n');

    /** An export is a file someone else reads, so it says what was filtered out. */
    const transcriptFile = (rawOnly) => {
        const header = [
            `# ${title}`,
            `# exported ${new Date().toISOString()}`,
            `# severity=${severity.value} search=${search.value.trim() || '(none)'}`,
            `# showing=${sourceMode} wrap=${wrapMode ? 'on' : 'off'}`,
            `# capacity=${capacity} retained=${retained}${
                dropped ? ` dropped=${dropped}` : ''
            }${clearing.size ? ` cleared=${clearing.size}` : ''}`,
            file?.path ? `# file=${file.path}` : null
        ]
            .filter(Boolean)
            .join('\n');
        return header + '\n' + transcriptBody(rawOnly);
    };

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
        // The pane and the address have to agree: both toggles used to be read from
        // the URL and never written back to it.
        if (onFilter)
            onFilter({
                severity: severity.value,
                search: search.value,
                period: period.value,
                raw: rawMode,
                wrap: wrapMode
            });
    });
    rawToggle.setAttribute('aria-pressed', String(rawMode));
    rawToggle.title = 'Render the exact text the server wrote to its log file';
    // Clear lives here as well as on Ctrl+L: on a phone the pane's hint line (the only
    // place the shortcut was written) is hidden, so there was no way to clear at all.
    const clearButton = button('Clear the pane', () => clear());
    clearButton.title = 'Hide everything on screen (Ctrl+L); Restore brings it back';
    const wrapToggle = button(wrapMode ? 'No wrapping' : 'Wrap long lines', () => {
        wrapMode = !wrapMode;
        text(wrapToggle, wrapMode ? 'No wrapping' : 'Wrap long lines');
        wrapToggle.setAttribute('aria-pressed', String(!wrapMode));
        output.classList.toggle('no-wrap', !wrapMode);
        cache.clear();
        display();
        if (onFilter)
            onFilter({
                severity: severity.value,
                search: search.value,
                period: period.value,
                raw: rawMode,
                wrap: wrapMode
            });
    });
    wrapToggle.setAttribute('aria-pressed', String(!wrapMode));
    wrapToggle.title = 'Show long lines as one scrollable row instead of reflowing them';
    output.classList.toggle('no-wrap', !wrapMode);
    menuBody.append(clearButton, wrapToggle);
    if (commands) {
        const wedge = select('Show', ['Answers', 'Server log', 'Both'], 'Answers');
        wedge.title = 'Answers to your commands, the server log behind them, or both';
        wedge.onchange = () => {
            sourceMode =
                wedge.value === 'Answers' ? 'answers' : wedge.value === 'Server log' ? 'log' : 'both';
            matchIndex = -1;
            text(matchCount, '');
            display(true);
        };
        menuBody.append(wedge);
    }
    menuBody.append(
        rawToggle,
        button('Copy', () => {
            const body = transcriptBody(rawMode);
            const lines = body ? body.split('\n').length : 0;
            copy(
                body,
                lines
                    ? `${lines} ${lines === 1 ? 'line' : 'lines'} copied${
                          clearing.size ? ', cleared lines left out' : ''
                      }.`
                    : 'Nothing to copy.'
            );
        }),
        button('Export', () =>
            download(
                `tortodrone-${commands ? 'console' : 'log'}-${new Date()
                    .toISOString()
                    .slice(0, 19)
                    .replace(/[:T]/g, '-')}.txt`,
                transcriptFile(rawMode)
            )
        )
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
                // The pane is the only channel a sighted operator reads here, and a
                // button that visibly does nothing reads as broken.
                announce.textContent = 'Could not read the log file: ' + error.message;
                text(state, 'Could not read the log file: ' + error.message);
                state.classList.add('error');
            } finally {
                load.disabled = false;
            }
        });
        menuBody.append(load);
    }
    if (menuLinks.length) {
        // First in the menu, not last: this group exists because a short viewport has
        // no room for it beside the pane, and the menu is taller than such a screen —
        // a destination at the bottom of it is a destination nobody reaches.
        const group = el('div', null, 'terminal-menu-links');
        group.append(el('span', 'Related', 'terminal-menu-label'));
        for (const [label, onSelect, title] of menuLinks) {
            const go = button(label, onSelect, 'link');
            if (title) go.title = title;
            group.append(go);
        }
        menuBody.prepend(group);
    }
    menu.append(menuToggle, menuBody);
    tools.append(
        search,
        matchCount,
        severity,
        ...(tail ? [period] : []),
        ...(tail ? [pause, follow] : []),
        menu,
        undoClear,
        ...extras
    );
    if (findBar) {
        // A terminal emulator has no toolbar: it has a screen and a find bar that opens
        // over it. The fields are the same controls the Logs page keeps on screen, only
        // out of the way until the operator asks, so neither page learned a second way
        // to filter the pane.
        tools.classList.add('terminal-find');
        tools.hidden = true;
        const findLabel = el('span', 'find', 'terminal-find-label');
        findLabel.setAttribute('aria-hidden', 'true');
        tools.prepend(findLabel);
        // The window's own controls, not the find bar's: Copy, Export, the raw toggle
        // and Restore-cleared belong where the bar lives, or Ctrl+L would hide the only
        // way to put the cleared lines back.
        bar.append(undoClear, menu);
    }
    /** Open or close the find bar, which is the whole of this pane's chrome. */
    function setFind(open) {
        if (!findBar) return;
        tools.hidden = !open;
        if (open) {
            search.focus();
            search.select();
        }
        // The bar is chrome above the body, so the pane refits when it comes or goes.
        scheduleFit();
        onFind?.(open);
    }

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
        lastChrome = -1,
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
        // The pane's place in the document, not its place in the viewport: measuring
        // the latter resized the terminal by 3px every time the page scrolled.
        const top = node.getBoundingClientRect().top + window.scrollY;
        // The chrome (bar, tools, prompt, status) is fixed height, so the shell can
        // never be shorter than it without clipping the prompt: on a landscape
        // phone the page scrolls a little instead of hiding the cursor.
        const fixed = [...node.children]
            .filter((child) => !child.classList.contains('terminal-body'))
            .reduce((total, child) => total + child.offsetHeight, 0);
        const floor = Math.max(window.innerHeight < 520 ? 180 : 240, fixed + 56);
        node.style.blockSize = Math.max(floor, window.innerHeight - top - 18) + 'px';
        node.style.minHeight = '0';
        // Measured on the frame it mounts, the shell grew 82px under the operator's
        // eyes (the CSS floor first, this height second). Nothing is painted until the
        // first measurement lands, so the pane only ever appears at its real size.
        if (node.style.visibility) node.style.visibility = '';
    }
    let frame = 0;
    if (fill) {
        node.classList.add('terminal-fill');
        window.addEventListener('resize', fit);
        frame = requestAnimationFrame(fit);
    }

    renderStatus();
    // `#logs?severity=COMMAND` is a level this page does not offer: the select falls back
    // to All, so the address must stop claiming a filter that is not shown.
    if (initialSeverity && !severityOffered && onFilter)
        queueMicrotask(() => onFilter({ severity: severity.value, search: search.value }));
    return {
        node,
        fit,
        update,
        output,
        // Where a page puts the line after the last one; the prompt goes here.
        bodyTail,
        setFind,
        footer,
        state,
        /** Take the page-level listeners off with the page that owns them. */
        dispose() {
            document.removeEventListener('keydown', onPageKeydown);
            window.removeEventListener('resize', fit);
            if (frame) cancelAnimationFrame(frame);
            frame = 0;
        },
        redraw: () => display(),
        tools,
        /** The bar's own slot, where a page that reports state in the bar writes it. */
        barMeta,
        /**
         * Say the pane is offline without giving it a status strip to say it in: the
         * console's connection state lives in the bar, and the LED has to follow it.
         */
        setOffline(value) {
            if (offlineState === !!value) return;
            offlineState = !!value;
            renderStatus();
        },
        clear,
        announce: (message) => {
            announce.textContent = message;
        },
        focusSearch: () => search.focus(),
        /** Re-apply a deep-linked filter without reloading the page. */
        filter({ severity: level, search: term, period: span, raw, wrap } = {}) {
            if (level && LEVELS.includes(level) && severity.querySelector(`option[value="${level}"]`))
            severity.value = level;
            if (typeof term === 'string') search.value = term;
            if (span && [...period.options].some((option) => option.value === span))
                period.value = span;
            // Raw and wrap are panes, not filters, but they are in the address: a link
            // that says raw has to arrive raw.
            if (typeof raw === 'boolean' && raw !== rawMode) {
                rawMode = raw;
                text(rawToggle, rawMode ? 'Show parsed lines' : 'Show raw lines');
                rawToggle.setAttribute('aria-pressed', String(rawMode));
            }
            if (typeof wrap === 'boolean' && wrap !== wrapMode) {
                wrapMode = wrap;
                text(wrapToggle, wrapMode ? 'No wrapping' : 'Wrap long lines');
                wrapToggle.setAttribute('aria-pressed', String(!wrapMode));
                output.classList.toggle('no-wrap', !wrapMode);
            }
            cache.clear();
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
