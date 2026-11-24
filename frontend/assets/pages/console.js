import { api } from '../api.js';
import { el, button, input, text } from '../ui.js';
import { edit, field } from '../components/dialog.js';
import { logStream } from '../components/log-stream.js';

const history = [],
    outputHistory = [];
let commandDraft = '';

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
}
export async function mount(ctx) {
    const metadata = await ctx.request('console'),
        stream = logStream({
            commands: outputHistory,
            title: 'tortodrone — console',
            fill: true,
            onLink: (link) => ctx.go(link.page, link.params || {})
        }),
        feedback = el('small', 'Connected');
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
        run = el('button', 'Run', 'primary'),
        words = [...metadata.read, ...metadata.actions];
    run.type = 'submit';
    // Mobile keyboards capitalise the first word and the server refuses `Status`.
    command.setAttribute('autocapitalize', 'off');
    command.setAttribute('enterkeyhint', 'send');
    command.placeholder = 'help';
    command.classList.add('console-input');
    // One completion mechanism, and it is the one a terminal user already knows:
    // Tab. A native <datalist> popup would compete with it and cannot be styled.
    const hint = el('p', null, 'console-hint');
    hint.textContent =
        'Tab completes a command · ↑ ↓ history · ' +
        metadata.read.join(' · ') +
        ' · ' +
        metadata.actions.map((name) => (name === 'kick' ? 'kick <session>' : name)).join(' · ') +
        ' (asked to confirm)';
    const promptLabel = el('span', 'tortodrone$', 'console-prompt-label');
    promptLabel.setAttribute('aria-hidden', 'true');
    form.append(promptLabel, command, run);
    let index = history.length,
        draft = '',
        pending = false;
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
        if (outputHistory.length > 50) outputHistory.shift();
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
        if (outputHistory.length > 50) outputHistory.shift();
        if (!paint) return;
        stream.redraw();
    }
    command.oninput = () => {
        commandDraft = command.value;
    };
    command.onkeydown = (event) => {
        if (event.ctrlKey && event.key.toLowerCase() === 'l') {
            event.preventDefault();
            stream.clear();
            return;
        }
        if (event.ctrlKey && event.key.toLowerCase() === 'c') {
            event.preventDefault();
            if (pending) {
                stream.announce('A command is already running; its answer will arrive.');
                return;
            }
            command.value = commandDraft = '';
            text(feedback, 'Connected');
            return;
        }
        if (event.key === 'Tab' && command.value.trim() && !command.value.includes(' ')) {
            // Complete a command name in place; repeated Tab cycles matches.
            const prefix = command.value.trim();
            const matches = words.filter((name) => name.startsWith(prefix));
            if (matches.length) {
                event.preventDefault();
                const next =
                    matches.indexOf(command.value) === matches.length - 1
                        ? matches[0]
                        : (matches.find((name) => name > command.value) ?? matches[0]);
                command.value = commandDraft = next;
                command.setSelectionRange(next.length, next.length);
            }
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
            stream.announce('A command is already running; wait for its answer.');
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
                { submit: 'Run ' + value.split(/\s+/)[0], variant: 'danger' }
            );
            return;
        }
        pending = true;
        run.setBusy(true);
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
            // The prompt already printed the refusal; a second global notice for the
            // same refusal is noise, so only a broken server is reported globally.
            if (error.status >= 500) ctx.report(error);
        } finally {
            pending = false;
            run.setBusy(false);
        }
    };
    const help = button('Help', async () => {
        if (pending) return;
        pending = true;
        run.setBusy(true);
        try {
            const result = await api.consoleCommand('help');
            if (ctx.alive()) record('help', result);
        } catch (error) {
            if (ctx.alive()) {
                record('help', { output: error.message }, true);
                ctx.report(error);
            }
        } finally {
            pending = false;
            run.setBusy(false);
        }
    });
    stream.tools.prepend(help);
    stream.state.append(feedback);
    const prompt = el('div', null, 'console-prompt-block');
    prompt.append(form, hint);
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
    if (typeof ResizeObserver === 'function') new ResizeObserver(measurePrompt).observe(prompt);
    else window.addEventListener('resize', measurePrompt);
    let failures = 0;
    async function refresh() {
        try {
            // A paused stream still reads; it just stops painting new rows.
            stream.update(await ctx.request('logs'));
            failures = 0;
            if (!pending)
                text(feedback, stream.paused ? 'Display paused · still reading' : 'Connected');
            feedback.classList.remove('error');
        } catch (error) {
            failures++;
            text(feedback, 'Disconnected · ' + error.message);
            feedback.classList.add('error');
            throw error;
        }
    }
    await refresh();
    return {
        node,
        refresh,
        // A dead server is not polled every three seconds forever.
        get interval() {
            return failures ? Math.min(3000 * 2 ** failures, 60000) : 3000;
        },
        dispose() {
            commandDraft = command.value;
        }
    };
}
