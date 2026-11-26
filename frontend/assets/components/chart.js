import { el, text, time } from '../ui.js';
const NS = 'http://www.w3.org/2000/svg';
let chartSequence = 0;
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
/**
 * The next round number above `value`, at one of the steps a person counts in. An
 * axis that ends at 1,888.8 is the raw sample plus 12%, printed.
 * @param {number} value
 * @returns {number}
 */
export function niceMaximum(value) {
    if (!Number.isFinite(value) || value <= 0) return 1;
    const magnitude = Math.pow(10, Math.floor(Math.log10(value)));
    const scaled = value / magnitude;
    const step = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 2.5 ? 2.5 : scaled <= 5 ? 5 : 10;
    return step * magnitude;
}
/** A tick is the maximum split in two, so it inherits at most one awkward digit. */
export function roundTick(value) {
    if (!Number.isFinite(value)) return 0;
    return value >= 100 ? Math.round(value) : Math.round(value * 10) / 10;
}
/**
 * The word beside a reading, inflected for the number it sits next to. A legend that
 * prints "1 Scenes" is the one place the app stops agreeing with itself about how it
 * counts things, and the value arrives after the label is built, so the label has to be
 * rewritten rather than written once.
 * @param {{ name: string, one?: string }} s
 * @param {string} unit
 * @param {number} n
 * @returns {string}
 */
function legendWord(s, unit, n) {
    const word = s.one && Math.round(n) === 1 ? s.one : s.name;
    return word + (unit.startsWith('events') ? ' / sample' : '');
}
/**
 * A monotone cubic through every measured point. The reference chart is drawn as a
 * smooth area, not a polyline: one sample every five seconds made the old line look
 * like a staircase even when the server was steady. Fritsch-Carlson tangents keep the
 * curve inside the values it connects, so smoothing can never invent a negative CPU
 * reading or make a peak taller than the sample under it.
 * @param {{x: number, y: number}[]} points
 * @returns {string}
 */
export function smoothPath(points) {
    if (!points.length) return '';
    if (points.length === 1) return `M${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`;
    const n = points.length,
        delta = new Array(n - 1),
        tangent = new Array(n);
    for (let i = 0; i < n - 1; i++)
        delta[i] = (points[i + 1].y - points[i].y) / Math.max(1e-9, points[i + 1].x - points[i].x);
    tangent[0] = delta[0];
    tangent[n - 1] = delta[n - 2];
    for (let i = 1; i < n - 1; i++) tangent[i] = (delta[i - 1] + delta[i]) / 2;
    for (let i = 0; i < n - 1; i++) {
        if (delta[i] === 0) {
            tangent[i] = 0;
            tangent[i + 1] = 0;
            continue;
        }
        const a = tangent[i] / delta[i],
            b = tangent[i + 1] / delta[i],
            sum = a * a + b * b;
        if (sum <= 9) continue;
        const scale = 3 / Math.sqrt(sum);
        tangent[i] = scale * a * delta[i];
        tangent[i + 1] = scale * b * delta[i];
    }
    let d = `M${points[0].x.toFixed(2)},${points[0].y.toFixed(2)}`;
    for (let i = 0; i < n - 1; i++) {
        const span = (points[i + 1].x - points[i].x) / 3;
        d +=
            ` C${(points[i].x + span).toFixed(2)},${(points[i].y + tangent[i] * span).toFixed(2)}` +
            ` ${(points[i + 1].x - span).toFixed(2)},${(points[i + 1].y - tangent[i + 1] * span).toFixed(2)}` +
            ` ${points[i + 1].x.toFixed(2)},${points[i + 1].y.toFixed(2)}`;
    }
    return d;
}

export function chart(title, unit, series) {
    const slug = 'chart-' + title.toLowerCase().replace(/[^a-z0-9]+/g, '-'),
        node = el('section', null, 'panel'),
        heading = el('h2', title),
        readout = el('div', 'Waiting for samples', 'chart-legend'),
        current = el('div', null, 'chart-current'),
        currentValues = series.map((s, i) => {
            const row = el('div', null, i ? 'secondary-value' : ''),
                value = el('strong', '—'),
                // A key: the number, the series' own colour and the word for it. Two
                // lines of muted text over two lines of graph named neither, so a
                // reader could see there were two series and not which was which.
                swatch = el('span', null, i ? 'chart-swatch secondary-swatch' : 'chart-swatch'),
                word = el('small', legendWord(s, unit, NaN));
            swatch.setAttribute('aria-hidden', 'true');
            row.append(swatch, value, word);
            current.append(row);
            return { value, word };
        });
    heading.id = slug + '-heading';
    readout.id = slug + '-readout';
    // Only keyboard inspection announces: pointer movement would flood a reader.
    readout.setAttribute('aria-live', 'off');
    const graph = svg('svg', {
        viewBox: '0 0 600 230',
        class: 'chart',
        role: 'img',
        tabindex: 0,
        'aria-label': title + ' history; use arrow keys to inspect samples',
        'aria-describedby': readout.id,
        'aria-labelledby': heading.id
    });
    const id = 'chart-' + ++chartSequence,
        defs = svg('defs'),
        gradients = series.map((s, i) => {
            const gradient = svg('linearGradient', {
                id: `${id}-fill-${i}`,
                x1: '0',
                y1: '0',
                x2: '0',
                y2: '1',
                class: i ? 'chart-fill secondary-fill' : 'chart-fill'
            });
            gradient.append(
                svg('stop', { offset: '0%', 'stop-opacity': i ? '0.20' : '0.42' }),
                svg('stop', { offset: '100%', 'stop-opacity': '0' })
            );
            return gradient;
        });
    defs.append(...gradients);
    const grid = svg('g'),
        areas = series.map((s, i) =>
            svg('path', {
                class: i ? 'area secondary-area' : 'area',
                fill: `url(#${id}-fill-${i})`
            })
        ),
        lines = svg('g'),
        paths = series.map((s, i) =>
            svg('path', { class: i ? 'series secondary-series' : 'series' })
        ),
        cursor = svg('line', { class: 'chart-cursor', y1: 0, y2: 0, visibility: 'hidden' }),
        hoverDots = series.map((s, i) =>
            svg('circle', {
                class: i ? 'hover-dot secondary-dot' : 'hover-dot',
                r: '4',
                visibility: 'hidden'
            })
        ),
        endDots = series.map((s, i) =>
            svg('circle', {
                class: i ? 'end-dot secondary-dot' : 'end-dot',
                r: '3',
                visibility: 'hidden'
            })
        );
    lines.append(...paths);
    graph.append(defs, grid, ...areas, lines, cursor, ...hoverDots, ...endDots);
    node.append(heading, current, graph, readout);
    let samples = [],
        index = 0,
        inspecting = false,
        width = 600;
    const layout = () => {
        const left = 54,
            right = Math.max(left + 80, width - 16),
            top = 16,
            bottom = 198,
            plotWidth = right - left,
            plotHeight = bottom - top;
        return { left, right, top, bottom, plotWidth, plotHeight };
    };
    /** Measured points in plot coordinates, split wherever a sample gap is too wide. */
    function segmentsFor(s, maximum) {
        if (!samples.length) return [];
        const { left, bottom, plotWidth, plotHeight } = layout(),
            span = Math.max(1, samples.at(-1).time - samples[0].time),
            groups = [];
        let group = [],
            previous = null;
        samples.forEach((v, i) => {
            const value = s.value(v, i, samples);
            if (!Number.isFinite(value) || value < 0) {
                if (group.length) groups.push(group);
                group = [];
                previous = null;
                return;
            }
            if (previous != null && v.time - previous > 15000) {
                if (group.length) groups.push(group);
                group = [];
            }
            const x =
                samples.length === 1
                    ? left
                    : left + ((v.time - samples[0].time) / span) * plotWidth;
            group.push({ x, y: bottom - (value / maximum) * plotHeight, value, time: v.time });
            previous = v.time;
        });
        if (group.length) groups.push(group);
        return groups;
    }
    function paint() {
        const { left, right, top, bottom, plotWidth, plotHeight } = layout();
        graph.setAttribute('viewBox', `0 0 ${width} 230`);
        const all = series
            .flatMap((s) => samples.map((v, i) => s.value(v, i, samples)))
            .filter((v) => Number.isFinite(v) && v >= 0);
        const rawMaximum = Math.max(unit === '%' ? 100 : 1, ...all) * (unit === '%' ? 1 : 1.12),
            // A percentage has a fixed ceiling and quarters it; every other reading
            // divides its ceiling into three round steps (3,000 / 2,000 / 1,000 / 0),
            // never the 1,667 that a coarse maximum cut into thirds used to print.
            // Counts step by whole samples so two players never draw a 15-unit axis.
            integer = unit === '' || unit.startsWith('events'),
            maximum =
                unit === '%'
                    ? 100
                    : (integer
                          ? Math.max(1, Math.ceil(rawMaximum / 3))
                          : niceMaximum(Math.max(1, rawMaximum / 3))) * 3,
            tickCount = unit === '%' ? 5 : 4;
        paintedMaximum = maximum;
        const ticks = [];
        for (let i = 0; i < tickCount; i++) {
            const fraction = i / (tickCount - 1),
                y = top + fraction * plotHeight,
                label = svg('text', {
                    x: left - 10,
                    y: y + 4,
                    'text-anchor': 'end',
                    class: 'axis-label'
                });
            label.textContent = roundTick(maximum * (1 - fraction)).toLocaleString(undefined, {
                maximumFractionDigits: maximum < 10 ? 1 : 0
            });
            ticks.push(
                svg('line', {
                    class: 'grid-line',
                    x1: left,
                    x2: right,
                    y1: y.toFixed(2),
                    y2: y.toFixed(2)
                }),
                label
            );
        }
        if (samples.length) {
            const positions = [0, 0.25, 0.5, 0.75, 1];
            let previousLabel = '';
            for (const fraction of positions) {
                const at =
                        samples.length === 1
                            ? samples[0].time
                            : samples[0].time +
                              fraction * (samples.at(-1).time - samples[0].time),
                    label = svg('text', {
                        x: (left + fraction * plotWidth).toFixed(2),
                        y: 222,
                        'text-anchor':
                            fraction === 0 ? 'start' : fraction === 1 ? 'end' : 'middle',
                        class: 'axis-label'
                    });
                label.textContent = time(at);
                if (label.textContent === previousLabel) continue;
                previousLabel = label.textContent;
                ticks.push(label);
            }
        }
        grid.replaceChildren(...ticks);
        series.forEach((s, at) => {
            const groups = segmentsFor(s, maximum);
            let linePath = '',
                areaPath = '';
            for (const points of groups) {
                linePath += smoothPath(points);
                if (points.length > 1)
                    areaPath +=
                        smoothPath(points) +
                        ` L${points.at(-1).x.toFixed(2)},${bottom}` +
                        ` L${points[0].x.toFixed(2)},${bottom} Z`;
            }
            paths[at].setAttribute('d', linePath);
            areas[at].setAttribute('d', areaPath);
            const last = groups.at(-1)?.at(-1);
            endDots[at].setAttribute('cx', last ? last.x.toFixed(2) : 0);
            endDots[at].setAttribute('cy', last ? last.y.toFixed(2) : 0);
            endDots[at].setAttribute('visibility', last ? 'visible' : 'hidden');
        });
        cursor.setAttribute('y1', top);
        cursor.setAttribute('y2', bottom);
        inspect(
            samples.length
                ? inspecting && index >= 0
                    ? index
                    : samples.length - 1
                : -1,
            inspecting
        );
        if (!samples.length) {
            text(readout, 'No measured samples available.');
            cursor.setAttribute('visibility', 'hidden');
            hoverDots.forEach((dot) => dot.setAttribute('visibility', 'hidden'));
        }
    }
    function inspect(at, active = false) {
        index = Math.max(0, Math.min(samples.length - 1, at));
        const s = samples[index];
        if (!s) return;
        // At rest the values already stand in the header, so the line under the graph
        // says only which sample it is. Under the pointer or the arrow keys it names
        // the sample's own readings, which is the one thing the header cannot say.
        const atLatest = index === samples.length - 1;
        text(
            readout,
            atLatest && !inspecting
                ? `Latest sample at ${time(s.time)}`
                : `${time(s.time)} · ` +
                      series
                          .map((v) => {
                              const n = v.value(s, index, samples);
                              return `${v.name}: ${Number.isFinite(n) && n >= 0 ? n.toLocaleString(undefined, { maximumFractionDigits: 1 }) + ' ' + unit : 'Unavailable'}`;
                          })
                          .join(' · ')
        );
        const { left, bottom, plotWidth, plotHeight } = layout(),
            span = Math.max(1, samples.at(-1).time - samples[0].time),
            x =
                samples.length === 1
                    ? left
                    : left + ((s.time - samples[0].time) / span) * plotWidth;
        cursor.setAttribute('x1', x.toFixed(2));
        cursor.setAttribute('x2', x.toFixed(2));
        cursor.setAttribute('visibility', active ? 'visible' : 'hidden');
        const values = series.flatMap((v, i) => {
            const n = v.value(s, index, samples);
            return Number.isFinite(n) && n >= 0
                ? [{ n, dot: hoverDots[i], py: bottom - (n / currentMaximum()) * plotHeight }]
                : [{ dot: hoverDots[i], py: 0 }];
        });
        for (const { n, dot, py } of values) {
            dot.setAttribute('cx', x.toFixed(2));
            dot.setAttribute('cy', py.toFixed(2));
            dot.setAttribute('visibility', active && Number.isFinite(n) ? 'visible' : 'hidden');
        }
    }
    /** The axis maximum the last paint used; hover dots must sit on the same scale. */
    let paintedMaximum = 1;
    function currentMaximum() {
        return paintedMaximum;
    }
    graph.onpointermove = (event) => {
        if (!samples.length) return;
        inspecting = true;
        readout.setAttribute('aria-live', 'off');
        const { left, plotWidth } = layout(),
            x = new DOMPoint(event.clientX, event.clientY).matrixTransform(
                graph.getScreenCTM().inverse()
            ).x;
        const time =
            samples[0].time +
            Math.max(0, Math.min(1, (x - left) / plotWidth)) *
                (samples.at(-1).time - samples[0].time);
        inspect(nearestSample(samples, time), true);
    };
    graph.onpointerleave = graph.onblur = () => {
        inspecting = false;
        inspect(samples.length - 1, false);
        readout.setAttribute('aria-live', 'off');
    };
    graph.onkeydown = (event) => {
        if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
            event.preventDefault();
            inspecting = true;
            readout.setAttribute('aria-live', 'polite');
            inspect(
                event.key === 'Home'
                    ? 0
                    : event.key === 'End'
                      ? samples.length - 1
                      : index + (event.key === 'ArrowRight' ? 1 : -1),
                true
            );
        }
    };
    // The SVG is measured in CSS pixels, so labels keep the ramp's 10.5px even when
    // the two-column grid hands one card more width than the other.
    if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver((entries) => {
            const next = Math.max(360, Math.round(entries[0].contentRect.width));
            if (Math.abs(next - width) < 1) return;
            width = next;
            paint();
        });
        observer.observe(graph);
    }
    return {
        node,
        update(values) {
            samples = values;
            index = Math.max(0, Math.min(samples.length - 1, index));
            series.forEach((s, i) => {
                const n = values.length ? s.value(values.at(-1), values.length - 1, values) : NaN;
                text(
                    currentValues[i].value,
                    Number.isFinite(n) && n >= 0
                        ? n.toLocaleString(undefined, {
                              maximumFractionDigits:
                                  unit === '' || unit.startsWith('events') ? 0 : 1
                          }) + (unit && !unit.startsWith('events') ? ' ' + unit : '')
                        : 'Unavailable'
                );
                text(currentValues[i].word, legendWord(s, unit, n));
            });
            paint();
        }
    };
}
