import { el, text } from '../ui.js';
const NS = 'http://www.w3.org/2000/svg';
export function nearestSample(samples, time) {
    if (!samples.length) return -1;
    let low = 0,
        high = samples.length - 1;
    while (low < high) {
        const middle = Math.floor((low + high) / 2);
        if (samples[middle].time < time) low = middle + 1;
        else high = middle;
    }
    return low > 0 && Math.abs(samples[low - 1].time - time) <= Math.abs(samples[low].time - time)
        ? low - 1
        : low;
}
function svg(tag, attrs = {}) {
    const node = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    return node;
}
export function chart(title, unit, series) {
    const node = el('section', null, 'panel'),
        heading = el('h2', title),
        readout = el('div', 'Waiting for samples', 'chart-legend'),
        current = el('div', null, 'chart-current'),
        currentValues = series.map((s, i) => {
            const row = el('div', null, i ? 'secondary-value' : ''),
                value = el('strong', '—');
            row.append(value, el('small', s.name + (unit.startsWith('events') ? ' / sample' : '')));
            current.append(row);
            return value;
        });
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
    node.append(heading, current, graph, readout);
    let samples = [],
        index = 0,
        inspecting = false;
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
        if (!samples.length) return;
        inspecting = true;
        const x = new DOMPoint(event.clientX, event.clientY).matrixTransform(
            graph.getScreenCTM().inverse()
        ).x;
        const time =
            samples[0].time +
            Math.max(0, Math.min(1, (x - 45) / 535)) * (samples.at(-1).time - samples[0].time);
        inspect(nearestSample(samples, time));
    };
    graph.onpointerleave = graph.onblur = () => {
        inspecting = false;
        inspect(samples.length - 1);
    };
    graph.onkeydown = (event) => {
        if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            inspecting = true;
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
            const inspectedTime = samples[index]?.time;
            samples = values;
            series.forEach((s, i) => {
                const n = values.length ? s.value(values.at(-1), values.length - 1, values) : NaN;
                text(
                    currentValues[i],
                    Number.isFinite(n) && n >= 0
                        ? n.toLocaleString(undefined, {
                              maximumFractionDigits:
                                  unit === '' || unit.startsWith('events') ? 0 : 1
                          }) + (unit && !unit.startsWith('events') ? ' ' + unit : '')
                        : 'Unavailable'
                );
            });
            const all = series
                .flatMap((s) => values.map((v, i) => s.value(v, i, values)))
                .filter((v) => Number.isFinite(v) && v >= 0);
            const rawMaximum = Math.max(unit === '%' ? 100 : 1, ...all) * (unit === '%' ? 1 : 1.12),
                maximum =
                    unit === '' || unit.startsWith('events')
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
            } else
                inspect(
                    inspecting && inspectedTime != null
                        ? nearestSample(values, inspectedTime)
                        : values.length - 1
                );
        }
    };
}
