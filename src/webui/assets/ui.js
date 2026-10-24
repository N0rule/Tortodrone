export const $ = (selector) => document.querySelector(selector);
export function el(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
}
export function text(node, value) {
    const next = String(value ?? '—');
    if (node.textContent !== next) node.textContent = next;
}
export function button(label, action, className = 'secondary', available) {
    const node = el('button', label, className);
    node.type = 'button';
    if (available) {
        node.refreshAvailability = () => {
            node.disabled = !available();
        };
        node.refreshAvailability();
    }
    node.addEventListener('click', async () => {
        try {
            await action();
        } catch (error) {
            report(error);
        }
    });
    return node;
}
export function toolbar(...nodes) {
    const node = el('div', null, 'toolbar');
    node.append(...nodes);
    return node;
}
export function panel(title, ...nodes) {
    const node = el('section', null, 'panel');
    if (title) node.append(el('h2', title));
    node.append(...nodes);
    return node;
}
export function details(title, value) {
    const node = el('details');
    node.append(el('summary', title), el('pre', JSON.stringify(value, null, 2)));
    return node;
}
export function badge(value, className = '') {
    return el('span', value, 'badge ' + className);
}
export function status(value, online = false) {
    return el('span', value, 'status' + (online ? ' online' : ''));
}
export function input(label, value = '', type = 'text', constraints = {}) {
    const control = el(type === 'textarea' ? 'textarea' : 'input');
    if (type !== 'textarea') control.type = type;
    if (type === 'checkbox') control.checked = !!value;
    else control.value = value;
    Object.assign(control, constraints);
    control.setAttribute('aria-label', label);
    return control;
}
export function select(label, options, value = '') {
    const node = el('select');
    node.setAttribute('aria-label', label);
    for (const option of options) {
        const [id, title] = Array.isArray(option) ? option : [option, option];
        const child = el('option', title);
        child.value = id;
        node.append(child);
    }
    node.value = value;
    return node;
}
export function label(title, control) {
    const node = el('label', title);
    node.append(control);
    if (control.type === 'checkbox') node.className = 'check';
    return node;
}
export function facts(entries) {
    const node = el('dl');
    for (const [key, value] of entries)
        node.append(el('dt', key), el('dd', value ?? 'Unavailable'));
    return node;
}
export const number = (value) => Number(value ?? 0).toLocaleString();
export const compact = (value) =>
    new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(
        value
    );
export const date = (value) => (value ? new Date(value).toLocaleString() : '—');
export function duration(seconds) {
    seconds = Math.max(0, seconds);
    return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m ${Math.floor(seconds % 60)}s`;
}
export function localDate(time) {
    const d = new Date(time);
    return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
export function heat(value) {
    const node = el('progress', null, 'heat');
    node.max = 1;
    node.value = value;
    node.title = `${(value * 100).toFixed(1)}% Heat`;
    node.setAttribute('aria-label', node.title);
    return node;
}
export function notify(message, error = false) {
    const node = $('#notice');
    text(node, message);
    node.hidden = false;
    node.classList.toggle('error', error);
}
export function report(error) {
    if (error.status === 401) document.dispatchEvent(new Event('session-expired'));
    else notify(error.message || 'Operation unavailable', true);
}
export function changed(message = 'Changes saved.') {
    notify(message);
    document.dispatchEvent(new Event('data-changed'));
}
export function download(name, content, type = 'text/plain') {
    const url = URL.createObjectURL(new Blob([content], { type }));
    const a = el('a');
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function tabs(options, value, onChange) {
    const root = el('div', null, 'tabs');
    root.setAttribute('role', 'tablist');
    function choose(id, focus = false) {
        for (const b of root.children) {
            const selected = b.dataset.tab === id;
            b.setAttribute('aria-selected', selected);
            b.tabIndex = selected ? 0 : -1;
            if (selected && focus) b.focus();
        }
        onChange(id);
    }
    for (const [id, title] of options) {
        const b = button(title, () => choose(id));
        b.dataset.tab = id;
        b.setAttribute('role', 'tab');
        b.setAttribute('aria-selected', id === value);
        b.tabIndex = id === value ? 0 : -1;
        root.append(b);
        b.onkeydown = (event) => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
            event.preventDefault();
            const index = options.findIndex(([key]) => key === b.dataset.tab);
            const next =
                event.key === 'Home'
                    ? 0
                    : event.key === 'End'
                      ? options.length - 1
                      : (index + (event.key === 'ArrowRight' ? 1 : -1) + options.length) %
                        options.length;
            choose(options[next][0], true);
        };
    }
    return root;
}
