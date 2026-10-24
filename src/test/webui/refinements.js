/** Browser regressions. Copy beside a disposable fixture's api.js; never ship this module. */
import { api } from './api.js';
import { modifierControls, statPanel } from './components/gear-editor.js';
import { gearProgress, variantEffects } from './components/items.js';
const check = (value, message) => {
    if (!value) throw new Error(message);
};
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(predicate) {
    const end = Date.now() + 10000;
    while (!predicate()) {
        if (Date.now() > end) throw new Error('Preview timed out');
        await wait(25);
    }
}
function change(control, value) {
    control.value = value;
    control.dispatchEvent(new Event('input', { bubbles: true }));
}
export async function run({ disposable = false } = {}) {
    check(
        disposable && ['127.0.0.1', 'localhost'].includes(location.hostname),
        'Disposable fixture required'
    );
    const catalogue = await api.request('catalogue', { search: 'Owlite Shield', size: 24 });
    const def = catalogue.entries.find((d) => d.progression && d.levels > 1 && d.variants.length);
    check(def, 'Progressing shield fixture required');
    const results = [],
        panel = statPanel();
    panel.update([
        { label: 'Normal defense', value: 18 },
        { label: 'Shield health', value: 50 }
    ]);
    const row = panel.node.firstChild,
        number = row.children[1];
    panel.update([
        { label: 'Normal defense', value: 21 },
        { label: 'Shield health', value: 50 }
    ]);
    check(
        panel.node.firstChild === row && row.children[1] === number && number.textContent === '21',
        'Stats reuse rows and value nodes'
    );
    panel.update([{ label: 'Shield health', value: 50 }]);
    check(
        !panel.node.contains(row) && panel.node.children.length === 1,
        'Only obsolete properties are removed'
    );
    results.push('keyed stat reconciliation');
    const editor = modifierControls(def),
        host = document.createElement('section');
    host.append(editor.node);
    document.body.append(host);
    const control = (name) => host.querySelector(`[aria-label="${name}"]`);
    const original = api.request;
    let release,
        intercept = false,
        stale;
    try {
        await until(() => host.textContent.includes('Draft is valid.'));
        const stats = host.querySelector('.item-properties'),
            rows = [...stats.children],
            valueNodes = rows.map((r) => r.children[1]);
        check(
            getComputedStyle(stats.firstChild).display === 'grid',
            'Stat labels and values use aligned columns'
        );
        const controls = [...host.querySelectorAll('.gear-control')].map((n) =>
            n.getBoundingClientRect()
        );
        check(
            Math.abs(controls[0].width - controls[1].width) < 2 &&
                (innerWidth > 600
                    ? Math.abs(controls[0].top - controls[1].top) < 2
                    : controls[1].top > controls[0].bottom),
            'Level and Heat controls align responsively'
        );
        const observer = new MutationObserver((changes) => {
            check(
                !changes.some((c) =>
                    [...c.removedNodes].some((n) => n === stats || rows.includes(n))
                ),
                'Modifier update removed valid stats'
            );
        });
        observer.observe(host, { childList: true, subtree: true });
        api.request = async function (...args) {
            const response = await original.apply(this, args);
            if (intercept && args[0] === 'item-preview') {
                intercept = false;
                stale = response;
                await new Promise((resolve) => {
                    release = resolve;
                });
            }
            return response;
        };
        intercept = true;
        change(control('Level slider'), 2);
        check(
            control('Level').value === '2' && editor.values().level === 1,
            'Integer level slider and stored offset'
        );
        await until(() => !!release);
        check(
            rows.every((r, i) => r === stats.children[i] && r.children[1] === valueNodes[i]),
            'Stats stay visible while pending'
        );
        change(control('Level slider'), 3);
        change(control('Heat'), 37.5);
        const precise = control('Heat percentage');
        precise.focus();
        check(
            precise.value === '37.5' && editor.values().heat === 0.375,
            'Heat slider maps percentage to native fraction'
        );
        await until(() => host.textContent.includes('Draft is valid.'));
        const current = stats.textContent;
        release();
        await wait(80);
        check(
            stats.textContent === current && stale && document.activeElement === precise,
            'Late response ignored without losing focus'
        );
        change(precise, 12.345);
        check(
            Math.abs(editor.values().heat - 0.12345) < 1e-8,
            'Precise Heat entry retains precision'
        );
        await until(() => host.textContent.includes('Draft is valid.'));
        check(
            rows.every((r, i) => r === stats.children[i] && r.children[1] === valueNodes[i]),
            'Live stats reuse existing nodes'
        );
        change(control('Level slider'), def.levels);
        check(
            control('Heat').disabled && precise.disabled && editor.values().heat === 0,
            'Maximum level disables and clears Heat'
        );
        await until(() => host.textContent.includes('Draft is valid.'));
        const uv = host.querySelector('.uv-picker');
        uv.open = true;
        const choice = host.querySelector('.variant-option:not(:disabled)');
        check(
            choice && choice.querySelector('.variant-effect img'),
            'UV picker has circular symbols'
        );
        choice.click();
        await until(() => host.textContent.includes('Draft is valid.'));
        check(
            choice.getAttribute('aria-pressed') === 'true' &&
                editor.values().variants === choice.dataset.variant,
            'UV selection updates shared preview'
        );
        check(
            host.querySelectorAll('.gear-card .variant-effect').length === 1,
            'Single selected effect representation'
        );
        change(control('Search Unique Variants'), 'no-such-effect');
        check(
            [...uv.querySelectorAll('.variant-option')].every((n) => n.hidden),
            'UV search filters choices'
        );
        change(control('Search Unique Variants'), '');
        host.querySelector('.variant-remove').click();
        await until(() => host.textContent.includes('Draft is valid.'));
        check(
            !editor.values().variants && choice.getAttribute('aria-pressed') === 'false',
            'UV removal clears selection'
        );
        const category = control('Effect category');
        category.value = choice.dataset.category;
        category.dispatchEvent(new Event('change'));
        check(
            [...uv.querySelectorAll('.variant-option')]
                .filter((n) => !n.hidden)
                .every((n) => n.dataset.category === category.value),
            'UV category filter'
        );
        category.value = '';
        category.dispatchEvent(new Event('change'));
        for (let count = 0; count < 3; count++) {
            const next = host.querySelector('.variant-option:not(:disabled)[aria-pressed="false"]');
            check(next, 'Compatible variant available within the limit');
            next.click();
            await until(() => host.textContent.includes('Draft is valid.'));
        }
        check(
            !host.querySelector('.variant-option:not(:disabled)[aria-pressed="false"]') &&
                editor.values().variants.split('\n').length === 3,
            'Server compatibility enforces maximum UV count'
        );
        check(
            [...uv.querySelectorAll('.variant-option:disabled')].every((n) =>
                n.title.includes('Incompatible')
            ),
            'Disabled variants explain restrictions'
        );
        observer.disconnect();
        const max = gearProgress(def, { level: def.levels - 1, heatProgress: 0 });
        check(
            !max.querySelector('progress') && max.textContent.includes('Maximum'),
            'Max level cards omit empty Heat meters'
        );
        const duplicates = variantEffects(def, [
            { name: choice.dataset.variant },
            { name: choice.dataset.variant }
        ]);
        check(duplicates.children.length === 2, 'Distinct repeated effect rows remain distinct');
        results.push(
            'Level and precise Heat controls',
            'pending and stale preview reconciliation',
            'input focus preservation',
            'UV search, category, selection and removal',
            'server compatibility and three-UV limit',
            'maximum-level equipment',
            'distinct UV entries'
        );
        return results;
    } finally {
        release?.();
        api.request = original;
        editor.dispose();
        host.remove();
    }
}

export async function runTerminal({ disposable = false } = {}) {
    check(
        disposable && ['127.0.0.1', 'localhost'].includes(location.hostname),
        'Disposable fixture required'
    );
    const { logStream } = await import('./components/log-stream.js');
    const commands = [{ time: Date.now(), command: 'help', output: 'Supported commands only' }];
    const stream = logStream({ commands }),
        input = document.createElement('input'),
        host = document.createElement('section');
    host.append(stream.node, input);
    document.body.append(host);
    const rows = Array.from({ length: 1100 }, (_, i) => ({
        timestamp: new Date(Date.now() - 20000 + i).toISOString(),
        thread: 'fixture',
        level: i % 2 ? 'INFO' : 'WARN',
        message: 'Fixture line ' + i,
        line: 'Fixture line ' + i
    }));
    const click = (name) =>
        [...host.querySelectorAll('button')].find((n) => n.textContent === name).click();
    try {
        stream.update(rows);
        check(stream.output.children.length === 1001, 'Logs and command history are bounded');
        check(
            host.querySelectorAll('.terminal').length === 1 &&
                stream.output.textContent.includes('> help'),
            'One terminal contains commands and live output'
        );
        const line = stream.output.children[2];
        click('Autoscroll on');
        stream.output.scrollTop = 80;
        const anchor = [...stream.output.children].find(
            (n) => n.getBoundingClientRect().top >= stream.output.getBoundingClientRect().top + 2
        );
        const before = anchor.getBoundingClientRect().top;
        input.focus();
        input.value = 'unfinished command';
        const next = {
            timestamp: new Date().toISOString(),
            thread: 'fixture',
            level: 'ERROR',
            message: '<script>text only</script>',
            line: '<script>text only</script>'
        };
        stream.update([...rows.slice(1), next]);
        check(
            line.isConnected &&
                document.activeElement === input &&
                input.value === 'unfinished command',
            'Incremental output preserves rows, input and focus'
        );
        check(
            Math.abs(anchor.getBoundingClientRect().top - before) < 2,
            'Manual scroll position survives append'
        );
        check(!stream.output.querySelector('script'), 'Log content stays plain text');
        click('Pause');
        stream.update([
            ...rows,
            next,
            { ...next, message: 'Paused addition', line: 'Paused addition' }
        ]);
        check(!stream.output.textContent.includes('Paused addition'), 'Paused output stays frozen');
        click('Resume');
        check(stream.output.textContent.includes('Paused addition'), 'Resume displays latest logs');
        const search = host.querySelector('[aria-label="Search output"]');
        change(search, 'help');
        check(stream.output.children.length === 1, 'Unified search includes command responses');
        change(search, '');
        const severity = host.querySelector('[aria-label="Severity"]');
        severity.value = 'ERROR';
        severity.dispatchEvent(new Event('change'));
        check(
            [...stream.output.children].every((n) => n.classList.contains('ERROR')),
            'Severity filter'
        );
        severity.value = 'All';
        severity.dispatchEvent(new Event('change'));
        click('Clear display');
        stream.update([
            ...rows,
            next,
            { ...next, message: 'Paused addition', line: 'Paused addition' }
        ]);
        check(
            stream.output.children.length === 0 && commands.length === 0,
            'Clear suppresses retained logs and commands'
        );
        return [
            'single terminal',
            'bounded incremental rows',
            'typing and focus preservation',
            'manual scroll preservation',
            'pause and resume',
            'search and severity',
            'clear display',
            'plain-text log rendering'
        ];
    } finally {
        host.remove();
    }
}
