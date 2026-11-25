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

export function reset() {
    history.length = outputHistory.length = 0;
    commandDraft = '';
    try {
        sessionStorage.removeItem(TRANSCRIPT_KEY);
    } catch {
        /* nothing to clear */
    }
}
/**
 * The verbs that change server state, held statically rather than read from the command
 * list. The list is a server read, and a failed read used to leave `metadata.actions`
 * empty — which sent `shutdown` and `restart` straight past the confirmation dialog to
 * `AdminConsole`, whose answer is "Repeat the exact command to confirm", a refusal the
 * page had no field for. The dialog is the thing that cannot depend on the read.
 */
const STATEFUL = new Set(['start', 'stop', 'restart', 'kick', 'shutdown']);

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
    // A find that outlives its own bar: Escape closes the bar and keeps the term, so the
    // screen stayed filtered with nothing on it saying why - and the next answer landed
    // behind the filter that was hiding it. The bar names what is being hidden, and one
    // click drops it.
    const filterChip = el('button', null, 'console-filter'),
        filterName = el('span', null, 'console-filter-term');
    filterChip.type = 'button';
    filterChip.append(filterName, el('span', '\u00d7', 'console-filter-drop'));
    filterChip.hidden = true;
    filterChip.title = 'The find bar is closed and still filtering the screen. Clear it.';
    // The view in front of the operator is what they copy or bookmark, so the address
    // follows the filters instead of only being read from.
    const writeAddress = ({ severity, search }) => {
            const params = new URLSearchParams();
            if (severity && severity !== 'All') params.set('severity', severity);
            if (search) params.set('search', search);
            const hash = '#console' + (params.size ? '?' + params : '');
            // `history` in this module is the prompt's ↑ list, so the browser's history
            // is named in full.
            if (location.hash !== hash) window.history.replaceState(null, '', hash);
        },
        showFilter = (severity, search) => {
            // Both axes, because a view is filtered by both: the chip said "filter zzz"
            // while a level was also hiding every answer, and pointed at half the cause.
            const term = [
                // The level as the find bar's own select spells it: one value, one
                // spelling, whether it is read in the chip or in the control.
                severity && severity !== 'All' ? severity : '',
                search ? '\u201c' + search + '\u201d' : ''
            ]
                .filter(Boolean)
                .join(' ');
            filterChip.hidden = !term;
            filterName.textContent = term ? 'filter ' + term : '';
        };
    let view = {
        severity: ctx.params.get('severity') || 'All',
        search: ctx.params.get('search') || ''
    };
    const stream = logStream({
            commands: outputHistory,
            // The same separator the banner below prints: one pane, one voice.
            title: 'tortodrone · console',
            fill: true,
            // A console has no tail to follow and nothing to freeze, but it still
            // opens at the prompt: the newest line and the banner belong on screen.
            tail: false,
            autoScroll: true,
            // The rows this page owns are answers; the log lines behind them are the
            // server's words. Counting both said "13 answers" with none issued.
            countAnswers: true,
            // A console holds answers, so its blank pane is a prompt nobody has
            // used yet — the log's own sentence described the wrong thing entirely.
            emptyNote:
                'No answer has been printed yet. Type a command below, or type help for the list.',
            // A terminal has no key legend under its cursor: the banner above names the
            // chords once, and they scroll away with the banner, the way a shell's own
            // help does. A permanent row of chords is chrome, and this page is not.
            keys: [],
            severity: ctx.params.get('severity') || 'All',
            search: ctx.params.get('search') || '',
            // The filter is part of the view: writing it into the address is what lets
            // an operator hand a colleague the exact console they are reading.
            onFilter: ({ severity, search }) => {
                view = { severity, search };
                writeAddress({ severity, search });
                showFilter(severity, search);
            },
            // The pane is the terminal, so its filters are not a toolbar parked above
            // the screen: Ctrl+F opens a find bar over the output, the way a terminal
            // emulator searches its own scrollback.
            findBar: true,
            onFind: (open) => {
                // Closing the find bar puts the caret back on the prompt; it had been
                // left on a field that is no longer on screen.
                if (!open) command.focus();
            }
        }),
        // What the pane is doing, and only while it is doing it. It sat in a status
        // strip under the prompt, which a terminal does not have; the bar above the
        // screen says it instead, beside the light that means the same thing.
        feedback = el('small', listError ? 'Command list unavailable' : '');
    const output = stream.output;
    output.setAttribute('aria-label', 'Console output');
    feedback.className = 'console-state';
    const form = el('form', null, 'console-prompt'),
        // Not `required`: the browser painted the empty prompt with the invalid-field
        // ring (a red box around a cursor), and an empty prompt is not a mistake - Enter
        // on it does nothing, which `onsubmit` already handles.
        command = input('Command', commandDraft, 'text', {
            maxLength: 128,
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
    // Short, because the prompt is now a line in the buffer: the full sentence was
    // clipped mid-word at 390px, and the footer already lists Enter, Tab and the rest.
    command.placeholder = 'Type a command';
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
        if (outputHistory.length > 50) outputHistory.shift();
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
    // A fresh console opened on a blank pane, with the ten commands and the keys all
    // somewhere else. A shell prints a banner; this one prints one too, and a real
    // terminal's banner is the only place its chords are written down - they scroll
    // away with the transcript instead of being a legend nailed under the screen.
    if (!outputHistory.length) {
        // A touch screen has no Tab, no arrows and no Ctrl, so those chords are not
        // named on one: `help` is how the commands are found there.
        const chords =
            typeof matchMedia === 'function' && matchMedia('(pointer: fine)').matches
                ? '\nTab completes · ↑↓ history · Ctrl R recall · Ctrl F find · Ctrl L clear'
                : '';
        outputHistory.push({
            time: Date.now(),
            command: '',
            notice: true,
            output:
                'tortodrone console · ' +
                words.length +
                ' commands\n' +
                'Reads print an answer; writes ask you to repeat them first.' +
                chords
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
        if (pendingEcho) {
            pendingEcho.output = result.output;
            pendingEcho.error = error;
            pendingEcho.pending = false;
        } else {
            outputHistory.push({
                time: Date.now(),
                command: value,
                output: result.output,
                error
            });
        }
        if (!pendingEcho && history.at(-1) !== value) history.push(value);
        if (history.length > 100) history.shift();
        index = history.length;
        if (outputHistory.length > 50) outputHistory.shift();
        persist();
        if (!paint) return;
        stream.redraw();
        // Announced once, briefly: the stream itself is not a live region, so a
        // screen reader hears the answer and not every polled server line.
        stream.announce(
            `${value}: ${error ? 'failed' : 'done'}. ${String(result.output || '').slice(0, 200)}`
        );
        feedback.textContent = error ? 'Command failed' : '';
        feedback.classList.toggle('error', error);
    }
    /** Echo the prompt line before the server answers, the way a shell does. */
    function echo(value) {
        outputHistory.push({ time: Date.now(), command: value, output: '', error: false, pending: true });
        if (outputHistory.length > 50) outputHistory.shift();
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
            feedback.textContent = '';
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
                feedback.textContent = '';
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
            feedback.textContent = '';
            return;
        }
        if (event.key === 'Tab') {
            // Shift+Tab is the way out: the prompt kept the keystroke to list or complete
            // commands and never let it go, so the bar's menu and the sidebar were
            // unreachable without a mouse.
            if (event.shiftKey) return;
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
            if (!completion.list.length) {
                // With the command list unreadable there is nothing to complete, and a
                // bare Tab would move focus out of the prompt. Say why instead.
                if (!words.length) {
                    event.preventDefault();
                    notice(
                        'The command list could not be read, so there is nothing to complete. The audit trail records every command that ran.'
                    );
                }
                return;
            }
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
        // A filter left over from a closed find bar would hide the answer to the command
        // about to run - the row arrives filtered out and the prompt looks like it ate
        // it. Issuing a command restores the whole screen first.
        clearFind();
        // The static set is the guarantee; the server's own list is joined in so a verb
        // this page has not learned yet still gets its confirmation. The server refuses
        // a write without it, so being generous here costs nothing.
        if (STATEFUL.has(value.split(/\s+/)[0]) || metadata.actions.includes(value.split(/\s+/)[0])) {
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
    // The pane's state is said in the bar, beside the light that means the same thing:
    // a terminal has no status strip under its last line, and the strip this page used
    // to carry was 26px of rule saying nothing until a command went out.
    stream.barMeta.append(feedback, filterChip);
    // Dropping the filter from the bar keeps the caret on the prompt: the chip is the
    // only thing a closed find bar leaves on screen, and using it must not strand focus
    // on a control that has just been hidden. Both axes go, because the bar's chip names
    // both: a level left behind would keep eating answers it does not name.
    const clearFind = () => {
        view = { severity: 'All', search: '' };
        stream.filter(view);
        writeAddress(view);
        showFilter(view.severity, view.search);
        command.focus();
    };
    filterChip.onclick = clearFind;
    // A console opened filtered - `#console?search=zzz` handed over by a colleague, or an
    // Escape that closed the bar but kept the term - says so before anything is typed.
    showFilter(view.severity, view.search);
    const prompt = el('div', null, 'console-prompt-block');
    prompt.append(form);
    // The cursor stands on the line after the last one, inside the scrollback: a shell
    // keeps its prompt at the end of the buffer, so scrolling up into history scrolls
    // the prompt away with it. In a strip under the body the prompt stayed pinned while
    // the answers it belonged to scrolled, which is a chat window, not a terminal.
    stream.bodyTail.append(prompt);
    const node = stream.node;
    node.classList.add('console-workspace');
    // A terminal puts the caret where you click it. Clicking the screen used to focus
    // the read-only body, so the next keystroke went nowhere and the operator had to
    // aim at a prompt that is a single line at the bottom of a 600px pane.
    output.addEventListener('click', (event) => {
        // A control in the transcript, or a selection being dragged, keeps its click.
        if (event.target.closest('a, button, input, select, textarea, summary, details')) return;
        if (!window.getSelection()?.isCollapsed) return;
        command.focus();
    });
    // No prompt height to measure any more: the prompt is a line in the body, so it is
    // part of the pane's own scroll height instead of chrome the jump pill has to clear.
    let failures = 0;
    async function refresh() {
        try {
            // A paused stream still reads; it just stops painting new rows.
            stream.update(await ctx.request('logs'));
            failures = 0;
            // A healthy read says nothing here: the top bar's pill is the connection
            // claim, it ages, and a second one beside the prompt could contradict it.
            if (!pending) text(feedback, stream.paused ? 'Display paused · still reading' : '');
            // The light follows the state, and the state is no longer in the strip the
            // LED used to read, so it is told.
            if (feedback.classList.contains('error')) {
                feedback.classList.remove('error');
                stream.setOffline(false);
            }
        } catch (error) {
            failures++;
            // One word for one state, and no raw TypeError text.
            text(feedback, 'Offline · reconnecting');
            feedback.classList.add('error');
            stream.setOffline(true);
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
        /**
         * The sidebar's own Console entry points at `#console`, and a link to
         * `#console?search=…` points at a filtered one: the address is the view, so a
         * visit that changes only the parameters has to reach the pane. Without this the
         * screen kept a filter the address no longer claimed, and a colleague handed that
         * address got a different screen than the one it was copied from.
         */
        navigate(params) {
            const next = {
                severity: params.get('severity') || 'All',
                search: params.get('search') || ''
            };
            if (next.severity === view.severity && next.search === view.search) return;
            view = next;
            stream.filter(next);
            showFilter(view.severity, view.search);
            command.focus();
        },
        // A dead server is not polled every three seconds forever.
        get interval() {
            return failures ? Math.min(3000 * 2 ** failures, 60000) : 3000;
        },
        dispose() {
            commandDraft = command.value;
            // The terminal's own page-level keys go with the terminal.
            stream.dispose?.();
        }
    };
}
