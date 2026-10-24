import { el, text } from '../ui.js';
const NS = 'http://www.w3.org/2000/svg';
function svg(tag, attrs = {}) {
    const node = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    return node;
}
export function chart(title, unit, series) {
    const node = el('section', null, 'panel'),
        heading = el('h2', title),
        readout = el('div', 'Waiting for samples', 'chart-legend');
    const graph = svg('svg', {
        viewBox: '0 0 600 190',
        class: 'chart',
        role: 'img',
        tabindex: 0,
        'aria-label': title + ' history; use arrow keys to inspect samples'
    });
    const lines = svg('g'),
        paths = series.map((s, i) =>
            svg('path', { class: i ? 'series secondary-series' : 'series' })
        ),
        cursor = svg('line', {
            x1: 0,
            x2: 0,
            y1: 15,
            y2: 155,
            stroke: 'var(--muted)',
            visibility: 'hidden'
        });
    graph.append(lines, ...paths, cursor);
    node.append(heading, graph, readout);
    let samples = [],
        index = 0;
    function inspect(at) {
        index = Math.max(0, Math.min(samples.length - 1, at));
        const s = samples[index];
        if (!s) return;
        text(
            readout,
            `${new Date(s.time).toLocaleTimeString()} · ` +
                series
                    .map((v) => {
                        const n = v.value(s, index, samples);
                        return `${v.name}: ${Number.isFinite(n) && n >= 0 ? n.toLocaleString(undefined, { maximumFractionDigits: 1 }) + ' ' + unit : 'Unavailable'}`;
                    })
                    .join(' · ')
        );
        const x =
            45 +
            (samples.length === 1
                ? 0
                : (s.time - samples[0].time) / Math.max(1, samples.at(-1).time - samples[0].time)) *
                535;
        cursor.setAttribute('x1', x);
        cursor.setAttribute('x2', x);
        cursor.setAttribute('visibility', 'visible');
    }
    graph.onpointermove = (event) => {
        const rect = graph.getBoundingClientRect();
        inspect(Math.round(((event.clientX - rect.left) / rect.width) * (samples.length - 1)));
    };
    graph.onkeydown = (event) => {
        if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            inspect(
                event.key === 'Home'
                    ? 0
                    : event.key === 'End'
                      ? samples.length - 1
                      : index + (event.key === 'ArrowRight' ? 1 : -1)
            );
        }
    };
    return {
        node,
        update(values) {
            samples = values;
            const all = series
                .flatMap((s) => values.map((v, i) => s.value(v, i, values)))
                .filter((v) => Number.isFinite(v) && v >= 0);
            const rawMaximum = Math.max(unit === '%' ? 100 : 1, ...all) * (unit === '%' ? 1 : 1.12),
                maximum =
                    unit === '' || unit === 'events'
                        ? Math.max(2, Math.ceil(rawMaximum / 2) * 2)
                        : rawMaximum,
                grid = [];
            for (let i = 0; i < 3; i++) {
                const y = 15 + i * 70,
                    label = svg('text', { x: 0, y: y + 4 });
                label.textContent = (maximum * (1 - i / 2)).toLocaleString(undefined, {
                    maximumFractionDigits: 1
                });
                grid.push(
                    svg('line', { class: 'grid-line', x1: 45, x2: 580, y1: y, y2: y }),
                    label
                );
            }
            if (values.length)
                for (const [value, x, anchor] of [
                    [values[0], 45, 'start'],
                    [values.at(-1), 580, 'end']
                ]) {
                    const label = svg('text', { x, y: 181, 'text-anchor': anchor });
                    label.textContent = new Date(value.time).toLocaleTimeString([], {
                        hour: '2-digit',
                        minute: '2-digit'
                    });
                    grid.push(label);
                }
            lines.replaceChildren(...grid);
            series.forEach((s, at) => {
                let d = '',
                    previous,
                    connected = false;
                values.forEach((v, i) => {
                    const value = s.value(v, i, values);
                    if (!Number.isFinite(value) || value < 0) {
                        connected = false;
                        return;
                    }
                    const x =
                            45 +
                            (values.length === 1
                                ? 0
                                : (v.time - values[0].time) /
                                  Math.max(1, values.at(-1).time - values[0].time)) *
                                535,
                        y = 155 - (value / maximum) * 140;
                    d += `${connected && v.time - previous <= 15000 ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)} `;
                    previous = v.time;
                    connected = true;
                });
                paths[at].setAttribute('d', d);
            });
            if (!all.length) {
                text(readout, 'No measured samples available.');
                cursor.setAttribute('visibility', 'hidden');
            } else inspect(values.length - 1);
        }
    };
}
