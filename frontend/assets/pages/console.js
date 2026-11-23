import { api } from '../api.js';
import { el, button, input, label, text } from '../ui.js';
import { edit, field } from '../components/dialog.js';
import { logStream } from '../components/log-stream.js';
const history = [],
    outputHistory = [];
let commandDraft = '';
export function reset() {
    history.length = outputHistory.length = 0;
    commandDraft = '';
}
export async function mount(ctx) {
    const metadata = await ctx.request('console'),
        node = el('div', null, 'console-workspace'),
        stream = logStream({ commands: outputHistory }),
        feedback = el('small', 'Connected');
    const output = stream.output;
    output.setAttribute('aria-label', 'Console output');
    feedback.className = 'console-connection';
    const form = el('form', null, 'command-form'),
        command = input('Command', commandDraft, 'text', {
            maxLength: 128,
            required: true,
            autocomplete: 'off',
            spellcheck: false
        }),
        run = el('button', 'Run command');
    run.type = 'submit';
    command.placeholder = 'help';
    const completions = el('datalist');
    completions.id = 'console-commands';
    for (const name of [...metadata.read, ...metadata.actions]) {
        const option = el('option');
        option.value = name;
        completions.append(option);
    }
    command.setAttribute('list', completions.id);
    form.append(label('Command', command), run, completions);
    let index = history.length,
        draft = '',
        pending = false;
    function record(value, result, error = false) {
        outputHistory.push({ time: Date.now(), command: value, output: result.output, error });
        if (outputHistory.length > 50) outputHistory.shift();
        stream.redraw();
        text(feedback, error ? 'Command failed' : 'Connected');
    }
    command.oninput = () => {
        commandDraft = command.value;
    };
    command.onkeydown = (event) => {
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
        if (!value || pending) return;
        if (history.at(-1) !== value) {
            history.push(value);
            if (history.length > 100) history.shift();
        }
        index = history.length;
        if (metadata.actions.includes(value.split(/\s+/)[0])) {
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
                }
            );
            return;
        }
        pending = true;
        run.disabled = true;
        text(feedback, 'Executing…');
        try {
            const result = await api.consoleCommand(value);
            if (!ctx.alive()) return;
            record(value, result);
            if (command.value.trim() === value) command.value = commandDraft = '';
        } catch (error) {
            record(value, { output: error.message }, true);
            ctx.report(error);
        } finally {
            pending = false;
            run.disabled = false;
        }
    };
    const help = button('Help', async () => {
        if (pending) return;
        pending = true;
        run.disabled = true;
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
            run.disabled = false;
        }
    });
    stream.tools.prepend(help);
    stream.tools.append(feedback);
    node.append(stream.tools, output, form);
    async function refresh() {
        try {
            if (!stream.paused) stream.update(await ctx.request('logs'));
            text(feedback, stream.paused ? 'Display paused' : 'Connected');
            feedback.classList.remove('error');
        } catch (error) {
            text(feedback, 'Disconnected · ' + error.message);
            feedback.classList.add('error');
            throw error;
        }
    }
    await refresh();
    return {
        node,
        refresh,
        interval: 3000,
        dispose() {
            commandDraft = command.value;
        }
    };
}
