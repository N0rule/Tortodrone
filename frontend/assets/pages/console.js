import { api } from '../api.js';
import { el, input, text } from '../ui.js';
import { edit, field } from '../components/dialog.js';
import { logStream } from '../components/log-stream.js';

const history = [],
    outputHistory = [];
let commandDraft = '';

/**
 * A reload in the middle of a state-changing command used to erase the echo, the
 * answer and the whole ↑ history. The transcript is the operator's only on-screen
 * evidence that the command was issued, so it survives the document in
 * sessionStorage, marked with the time each command went out.
 */
const TRANSCRIPT_KEY = 'tortodrone-console-transcript';
/** Answers the fifty-row transcript has had to drop since this page was opened. */
let droppedAnswers = 0;
function persist() {
    try {
        sessionStorage.setItem(
            TRANSCRIPT_KEY,
            JSON.stringify({ history: history.slice(-50), rows: outputHistory.slice(-50) })
        );
    } catch {
        /* private browsing keeps the session copy only */
    }
}
function restore() {
    try {
        const saved = JSON.parse(sessionStorage.getItem(TRANSCRIPT_KEY) || 'null');
        if (!saved) return false;
        for (const row of saved.rows || [])
            if (row && typeof row.command === 'string') outputHistory.push(row);
        for (const value of saved.history || [])
            if (typeof value === 'string') history.push(value);
        return outputHistory.length > 0;
    } catch {
        return false;
    }
}

/** Where each read command's answer lives as a page, so output is not a dead end. */
const RELATED = {
    status: [{ label: 'Open overview', page: 'overview' }],
    players: [{ label: 'Open live sessions', page: 'players' }],
    scenes: [{ label: 'Open scenes', page: 'overview' }],
    accounts: [{ label: 'Open accounts', page: 'accounts' }],
    help: [{ label: 'Open settings', page: 'settings' }]
};

export function reset() {
    history.length = outputHistory.length = 0;
    commandDraft = '';
    try {
        sessionStorage.removeItem(TRANSCRIPT_KEY);
    } catch {
        /* nothing to clear */
    }
}
export async function mount(ctx) {
    // The prompt is the console: a failed command list must not replace the page an
    // operator reaches for when the server is misbehaving with an error card.
    let listError = null,
        metadata = { read: [], actions: [] };
    try {
        metadata = await ctx.request('console');
    } catch (error) {
        listError = error;
    }
    const stream = logStream({
            commands: outputHistory,
            title: 'tortodrone — console',
            fill: true,
            // A console has no tail to follow and nothing to freeze, but it still
            // opens at the prompt: the newest line and the banner belong on screen.
            tail: false,
            autoScroll: true,
            // The rows this page owns are answers; the log lines behind them are the
            // server's words. Counting both said "13 answers" with none issued.
            countAnswers: true,
            sourceLabel: () =>
                droppedAnswers
                    ? `server log · ${droppedAnswers} older ${
                          droppedAnswers === 1 ? 'answer' : 'answers'
                      } dropped`
                    : 'server log',
            countNoun: ['answer', 'answers'],
            severity: ctx.params.get('severity') || 'All',
            search: ctx.params.get('search') || '',
            // The filter is part of the view: writing it into the address is what lets
            // an operator hand a colleague the exact console they are reading.
            onFilter: ({ severity, search }) => {
                const params = new URLSearchParams();
                if (severity && severity !== 'All') params.set('severity', severity);
                if (search) params.set('search', search);
                const hash = '#console' + (params.size ? '?' + params : '');
                // `history` in this module is the prompt's ↑ list, so the browser's
                // history is named in full.
                if (location.hash !== hash) window.history.replaceState(null, '', hash);
            },
            // The Console has no tail to follow and nothing to pause: advertising
            // those keys here promised behaviour this page does not have.
            hint: 'Enter runs · Tab completes · ↑ ↓ history · Ctrl+R history search · Ctrl+F search · Ctrl+L clear · help lists the commands',
            onLink: (link) => ctx.go(link.page, link.params || {})
        }),
        feedback = el('small', listError ? 'Command list unavailable' : 'Connected');
    const output = stream.output;
    output.setAttribute('aria-label', 'Console output');
    feedback.className = 'console-connection';
    const form = el('form', null, 'console-prompt'),
        command = input('Command', commandDraft, 'text', {
            maxLength: 128,
            required: true,
            autocomplete: 'off',
            autocapitalize: 'off',
            autocorrect: 'off',
            spellcheck: false
        }),
        words = [...metadata.read, ...metadata.actions];
    // Enter submits, the way it does in a shell: no button beside the prompt. The
    // form still carries the busy flag so the field reports what it is doing.
    const busy = (value) => {
        if (value) form.setAttribute('aria-busy', 'true');
        else form.removeAttribute('aria-busy');
    };
    // Mobile keyboards capitalise the first word and the server refuses `Status`.
    command.setAttribute('autocapitalize', 'off');
    command.setAttribute('enterkeyhint', 'send');
    command.placeholder = 'Type a command, or press Tab';
    command.classList.add('console-input');
    const promptLabel = el('span', 'tortodrone$', 'console-prompt-label');
    promptLabel.setAttribute('aria-hidden', 'true');
    form.append(promptLabel, command);
    // A command list that failed to load is said once, in the transcript, instead of
    // replacing the prompt with an error card.
    if (listError)
        outputHistory.push({
            time: Date.now(),
            command: 'help',
            output:
                'The list of commands could not be loaded' +
                (listError.message ? ` (${listError.message})` : '') +
                '. A command still runs once the server answers.',
            error: true
        });
    /** Write a line into the transcript that is not an answer to a command. */
    const notice = (message, kind = true) => {
        outputHistory.push({ time: Date.now(), command: '', output: message, notice: kind });
        if (outputHistory.length > 50) {
            outputHistory.shift();
            droppedAnswers++;
        }
        stream.redraw();
    };
    // The transcript outlives the document: a reload mid-command keeps the echo and
    // the answers, and says so, instead of dropping the only evidence it happened.
    if (restore()) {
        // One notice per document: it is restored with the transcript, so re-saving it
        // on every reload stacked a wall of identical lines that pushed the answers it
        // was explaining out of the fifty-entry buffer.
        for (let index = outputHistory.length - 1; index >= 0; index--)
            if (outputHistory[index].notice === 'restore') outputHistory.splice(index, 1);
        notice(
            'Answers issued before this page was reloaded are kept below. A command whose answer arrived while the page was gone shows only its echo — the audit trail has the outcome.',
            'restore'
        );
        persist();
    }
    // A fresh console opened on a blank pane, with the ten commands, the Tab hint and
    // the three pages that explain an answer all somewhere else. A shell prints a
    // banner; this one prints one too, and its links are the answer to "now what".
    if (!outputHistory.length) {
        outputHistory.push({
            time: Date.now(),
            command: '',
            notice: true,
            output:
                'tortodrone console · ' +
                words.length +
                ' commands\n' +
                'Type a command and press Enter. help lists every command; Tab completes a name.\n' +
                'Reads print an answer; writes ask for their argument first.',
            links: [
                { label: 'Accounts', page: 'accounts' },
                { label: 'Audit trail', page: 'audit' },
                { label: 'Server log', page: 'logs' }
            ]
        });
        stream.redraw();
    }
    let index = history.length,
        draft = '',
        pending = false,
        // Which command is out. "A command is already running" is not an answer when
        // the prompt is blank and the operator has forgotten what they sent.
        running = '';
    function record(value, result, error = false, paint = true) {
        // Replace the echoed prompt line when one is still pending, so a slow
        // command shows its prompt immediately and never double-prints. The stream
        // notices the mutation through a row signature, not an object identity.
        let pendingEcho = null;
        for (let index = outputHistory.length - 1; index >= 0; index--) {
            const entry = outputHistory[index];
            if (entry.pending && entry.command === value) {
                pendingEcho = entry;
                break;
            }
        }
        const links = RELATED[value.split(/\s+/)[0]] || [];
        if (pendingEcho) {
            pendingEcho.output = result.output;
            pendingEcho.error = error;
            pendingEcho.pending = false;
            pendingEcho.links = links;
        } else {
            outputHistory.push({
                time: Date.now(),
                command: value,
                output: result.output,
                error,
                links
            });
        }
        if (!pendingEcho && history.at(-1) !== value) history.push(value);
        if (history.length > 100) history.shift();
        index = history.length;
        if (outputHistory.length > 50) {
            outputHistory.shift();
            droppedAnswers++;
        }
        persist();
        if (!paint) return;
        stream.redraw();
        // Announced once, briefly: the stream itself is not a live region, so a
        // screen reader hears the answer and not every polled server line.
        stream.announce(
            `${value}: ${error ? 'failed' : 'done'}. ${String(result.output || '').slice(0, 200)}`
        );
        text(feedback, error ? 'Command failed' : 'Connected');
        feedback.classList.toggle('error', error);
    }
    /** Echo the prompt line before the server answers, the way a shell does. */
    function echo(value) {
        outputHistory.push({ time: Date.now(), command: value, output: '', error: false, pending: true });
        if (outputHistory.length > 50) {
            outputHistory.shift();
            droppedAnswers++;
        }
        stream.redraw();
    }
    let completion = { list: [], at: -1 };
    // Session ids, read once and kept: they arrive with `status`, and re-reading them on
    // every Tab would line up a request behind each keystroke.
    let sessions = null;
    async function completeSession(prefix) {
        if (sessions === null) {
            text(feedback, 'Reading sessions…');
            try {
                const status = await ctx.request('status');
                sessions = (status.sessions || []).map((row) => String(row.id));
            } catch {
                sessions = [];
            }
            text(feedback, 'Connected');
        }
        const matches = sessions.filter((id) => id.startsWith(prefix.toLowerCase()));
        if (!matches.length) {
            notice(
                sessions.length
                    ? `No live session starts with “${prefix}”. Type a session id, or run players to list them.`
                    : 'No player session is connected, so there is no id to complete.'
            );
            return;
        }
        if (completion.at === -1 || !matches.includes(completion.list[completion.at])) {
            completion = { list: matches, at: -1 };
        }
        if (completion.list.length > 1 && completion.at === completion.list.length - 1) {
            notice(completion.list.map((id) => 'kick ' + id).join('   '));
            return;
        }
        completion.at = (completion.at + 1) % completion.list.length;
        const next = `kick ${completion.list[completion.at]}`;
        command.value = commandDraft = next;
        command.setSelectionRange(next.length, next.length);
    }
    // Reverse history search. Ctrl+R is the reflex a terminal user has, and reaching
    // the browser with it reloads the page and loses whatever was being typed.
    let scanning = null;
    function reverseSearch() {
        if (!history.length) {
            stream.announce('No command history yet.');
            return;
        }
        if (!scanning)
            scanning = { term: command.value.trim().toLowerCase(), at: history.length };
        for (let at = scanning.at - 1; at >= 0; at--) {
            if (!scanning.term || history[at].toLowerCase().includes(scanning.term)) {
                scanning.at = at;
                command.value = commandDraft = history[at];
                command.setSelectionRange(history[at].length, history[at].length);
                text(feedback, `(reverse-i-search) “${scanning.term}”: ${history[at]}`);
                return;
            }
        }
        // Stay where the search stopped instead of wrapping silently: a shell says
        // "no match", and a prompt that jumps back to the newest line is a surprise.
        scanning.at = 0;
        text(feedback, `(reverse-i-search) “${scanning.term}”: no older match`);
    }
    command.oninput = () => {
        commandDraft = command.value;
        completion = { list: [], at: -1 };
        scanning = null;
    };
    command.onkeydown = async (event) => {
        if (event.ctrlKey && event.key.toLowerCase() === 'l') {
            event.preventDefault();
            stream.clear();
            return;
        }
        if (event.key === 'Escape') {
            // A terminal's Esc clears the prompt it is standing on; from a reverse
            // search it first puts back what was typed before the search started.
            event.preventDefault();
            if (scanning) {
                scanning = null;
                text(feedback, 'Connected');
            }
            command.value = commandDraft = '';
            completion = { list: [], at: -1 };
            return;
        }
        if (event.ctrlKey && event.key.toLowerCase() === 'r') {
            event.preventDefault();
            reverseSearch();
            return;
        }
        if (event.ctrlKey && event.key.toLowerCase() === 'c' && !event.shiftKey) {
            // A selection means the operator is copying; only an empty prompt is a
            // cancel, the way a shell's Ctrl+C works. Shift is excluded because
            // Ctrl+Shift+C is the terminal's copy-selection chord, and cancelling on it
            // destroyed a half-typed command at the same moment it copied.
            if (command.selectionStart !== command.selectionEnd) return;
            event.preventDefault();
            if (pending) {
                stream.announce(
                    running
                        ? `Still waiting for “${running}”; its answer will land in the transcript.`
                        : 'A command is already running; its answer will arrive.'
                );
                return;
            }
            command.value = commandDraft = '';
            text(feedback, 'Connected');
            return;
        }
        if (event.key === 'Tab') {
            // Complete a command name in place and keep cycling the same match set:
            // recomputing the prefix from the completed word left one candidate, so
            // Tab could never reach the second match.
            const typed = command.value.trim();
            if (!typed) {
                // A shell lists its commands on an empty prompt; this page has ten and
                // used to answer the keystroke with nothing at all.
                event.preventDefault();
                notice(words.join('  '));
                return;
            }
            if (typed.includes(' ')) {
                const [verb, ...argument] = typed.split(/\s+/);
                event.preventDefault();
                // `kick` is the one command with an argument, and the page can read the
                // session ids it needs: the server names them in `status`. Completing them
                // beats the old notice that told the operator to go and look.
                if (verb === 'kick' && metadata.actions.includes('kick')) {
                    await completeSession(argument[0] || '');
                    return;
                }
                notice(
                    'Tab completes command names. ' +
                        verb +
                        ' takes an argument the page cannot guess; help lists the syntax.'
                );
                return;
            }
            if (!completion.list.includes(typed)) {
                const prefix = typed.toLowerCase();
                completion = {
                    list: words.filter((name) => name.toLowerCase().startsWith(prefix)),
                    at: -1
                };
            }
            if (!completion.list.length) return;
            event.preventDefault();
            // A second Tab on a sole candidate prints the ambiguous set, the way a
            // shell lists them, instead of cycling invisibly.
            if (completion.list.length > 1 && completion.at === completion.list.length - 1) {
                notice(completion.list.join('  '));
                return;
            }
            completion.at = (completion.at + 1) % completion.list.length;
            const next = completion.list[completion.at];
            command.value = commandDraft = next;
            command.setSelectionRange(next.length, next.length);
            return;
        }
        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
            event.preventDefault();
            if (index === history.length) draft = command.value;
            index = Math.max(
                0,
                Math.min(history.length, index + (event.key === 'ArrowUp' ? -1 : 1))
            );
            command.value = index === history.length ? draft : history[index];
            commandDraft = command.value;
            command.setSelectionRange(command.value.length, command.value.length);
        }
    };
    form.onsubmit = async (event) => {
        event.preventDefault();
        const value = command.value.trim();
        if (pending) {
            stream.announce(
                running
                    ? `“${running}” is still running; Enter again once its answer lands.`
                    : 'A command is already running; wait for its answer.'
            );
            return;
        }
        if (!value) return;
        if (metadata.actions.includes(value.split(/\s+/)[0])) {
            // A cancelled confirmation never enters ↑ history: nothing ran.
            edit(
                'Confirm command · ' + value,
                'console',
                [
                    field('commandConfirmation', 'Repeat ' + value),
                    ...(value === 'shutdown' ? [field('shutdown', 'Type SHUT DOWN PROCESS')] : [])
                ],
                { command: value },
                'This command changes server state.',
                (result) => {
                    record(value, result);
                    if (command.value.trim() === value) command.value = commandDraft = '';
                },
                {
                    submit: 'Run ' + value.split(/\s+/)[0],
                    variant: 'danger',
                    guardDirty: false
                }
            );
            return;
        }
        scanning = null;
        pending = true;
        running = value;
        busy(true);
        // Record the command when it is submitted, not when its answer lands: the
        // echo already exists by then, so the old guard dropped every command and
        // ↑ showed nothing.
        if (history.at(-1) !== value) history.push(value);
        if (history.length > 100) history.shift();
        index = history.length;
        echo(value);
        text(feedback, 'Executing…');
        try {
            const result = await api.consoleCommand(value);
            // The answer still belongs in the transcript even if another page is on
            // screen now: an echo stuck on "running…" is a lie about the server.
            record(value, result, false, ctx.alive());
            if (command.value.trim() === value) command.value = commandDraft = '';
        } catch (error) {
            record(value, { output: error.message }, true, ctx.alive());
            // A refusal clears the prompt exactly like a success does: the command is
            // in ↑ and in the transcript, so leaving it in the field made the two
            // outcomes behave differently for no reason.
            command.value = commandDraft = '';
            // The prompt already printed the refusal; a second global notice for the
            // same refusal is noise, so only a broken server is reported globally.
            if (error.status >= 500) ctx.report(error);
        } finally {
            pending = false;
            running = '';
            // A kick changes the roster, so a cache from before it may name a dead session.
            sessions = null;
            busy(false);
        }
    };
    stream.state.append(feedback);
    const prompt = el('div', null, 'console-prompt-block');
    prompt.append(form);
    stream.node.insertBefore(prompt, stream.footer);
    const node = stream.node;
    node.classList.add('console-workspace');
    // The jump pill must sit above the prompt, whatever the prompt's height is.
    const measurePrompt = () => {
        node.style.setProperty('--prompt-height', prompt.offsetHeight + 'px');
        // The prompt changes the space the stream needs, so refit with it.
        stream.fit();
    };
    measurePrompt();
    // Kept so the page can let go of it: a new observer per visit measured a prompt
    // that had already been removed, and the old one was never disconnected.
    const promptObserver =
        typeof ResizeObserver === 'function' ? new ResizeObserver(measurePrompt) : null;
    if (promptObserver) promptObserver.observe(prompt);
    else window.addEventListener('resize', measurePrompt);
    let failures = 0;
    async function refresh() {
        try {
            // A paused stream still reads; it just stops painting new rows.
            stream.update(await ctx.request('logs'));
            failures = 0;
            if (!pending)
                text(feedback, stream.paused ? 'Display paused · still reading' : 'Connected');
            // The LED reads this class, so the pane has to repaint when it is lifted:
            // otherwise the light stayed red under the word "Connected".
            if (feedback.classList.contains('error')) {
                feedback.classList.remove('error');
                stream.redraw();
            }
        } catch (error) {
            failures++;
            // One word for one state, and no raw TypeError text: the LED reads the
            // error class, so it has to be repainted to stop showing green.
            text(feedback, 'Offline · reconnecting');
            feedback.classList.add('error');
            stream.redraw();
            // A 401 is not a flaky server: every command from here prints "Sign in
            // required" into the transcript, so the pane offers the way back.
            if (error.status === 401 && ctx.reauth && !feedback.parentElement.querySelector('.console-reauth')) {
                const again = el('button', 'Sign in again', 'link console-reauth');
                again.type = 'button';
                again.onclick = () => ctx.reauth();
                feedback.after(again);
            }
            throw error;
        }
    }
    await refresh();
    // The prompt is the reason the page exists; a page that opens with the keys
    // nowhere near it asks for a click before it can be used.
    const claim = (attempt = 0) => {
        if (!node.isConnected) return;
        const active = document.activeElement;
        const taken = active && active !== document.body && active !== command && !/^H[1-6]$/.test(active.tagName);
        if (taken) return;
        command.focus();
        if (document.activeElement !== command && attempt < 3)
            requestAnimationFrame(() => claim(attempt + 1));
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => claim());
    return {
        node,
        refresh,
        // A dead server is not polled every three seconds forever.
        get interval() {
            return failures ? Math.min(3000 * 2 ** failures, 60000) : 3000;
        },
        dispose() {
            commandDraft = command.value;
            promptObserver?.disconnect();
            if (!promptObserver) window.removeEventListener('resize', measurePrompt);
            // The terminal's own page-level keys go with the terminal.
            stream.dispose?.();
        }
    };
}
