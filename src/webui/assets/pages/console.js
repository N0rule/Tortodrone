import { api } from '../api.js';
import { el, toolbar, button, input, label, text, download } from '../ui.js';
import { edit, field } from '../components/dialog.js';
import { logStream } from '../components/log-stream.js';
const history = [],
    outputHistory = [];
export async function mount(ctx) {
    const metadata = await ctx.request('console'),
        node = el('div', null, 'stack'),
        output = el('div', null, 'terminal'),
        stream = logStream(),
        feedback = el('small', 'Connected');
    output.setAttribute('role', 'log');
    output.setAttribute('aria-label', 'Command results');
    output.setAttribute('aria-live', 'polite');
    const form = el('form', null, 'command-form'),
        command = input('Command', '', 'text', {
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
        auto = true,
        pending = false;
    function renderEntry(entry) {
        const group = el('div'),
            line = el(
                'div',
                `${new Date(entry.time).toLocaleTimeString()} > ${entry.command}`,
                'command'
            );
        group.append(line, el('pre', entry.output));
        output.append(group);
        while (output.children.length > 50) output.firstChild.remove();
    }
    for (const entry of outputHistory) renderEntry(entry);
    const scroll = button('Autoscroll on', () => {
        auto = !auto;
        text(scroll, auto ? 'Autoscroll on' : 'Autoscroll off');
        if (auto) output.scrollTop = output.scrollHeight;
    });
    output.onscroll = () => {
        if (auto && output.scrollHeight - output.scrollTop - output.clientHeight > 50) {
            auto = false;
            text(scroll, 'Autoscroll off');
        }
    };
    function record(value, result) {
        const entry = { time: Date.now(), command: value, output: result.output };
        outputHistory.push(entry);
        if (outputHistory.length > 50) outputHistory.shift();
        renderEntry(entry);
        if (auto) output.scrollTop = output.scrollHeight;
        text(feedback, 'Command completed');
    }
    command.onkeydown = (event) => {
        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
            event.preventDefault();
            if (index === history.length) draft = command.value;
            index = Math.max(
                0,
                Math.min(history.length, index + (event.key === 'ArrowUp' ? -1 : 1))
            );
            command.value = index === history.length ? draft : history[index];
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
                    if (command.value.trim() === value) command.value = '';
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
            if (command.value.trim() === value) command.value = '';
        } catch (error) {
            text(feedback, error.message);
            ctx.report(error);
        } finally {
            pending = false;
            run.disabled = false;
        }
    };
    node.append(
        toolbar(
            button('Help', async () => record('help', await api.consoleCommand('help'))),
            scroll,
            button('Clear results', () => {
                outputHistory.length = 0;
                output.replaceChildren();
            }),
            button('Export results', () =>
                download(
                    'tortodrone-console.txt',
                    outputHistory
                        .map((r) => `${new Date(r.time).toISOString()} > ${r.command}\n${r.output}`)
                        .join('\n')
                )
            ),
            feedback
        ),
        output,
        form,
        el('h2', 'Live server output'),
        stream.node
    );
    async function refresh() {
        if (!stream.paused) stream.update(await ctx.request('logs'));
        text(feedback, pending ? 'Executing…' : 'Connected');
    }
    await refresh();
    return { node, refresh, interval: 3000 };
}
