import { symbol } from './components/symbols.js';
import { href } from './router.js';
import { toast } from './components/toast.js';
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
/* Label-to-icon is only a fallback: callers that care name the art themselves.
   Read actions get read art, so "Inspect" is never drawn with a pencil. */
function guessIcon(label) {
    if (/^(Edit|Change)\b/.test(label)) return 'edit';
    if (/^(Add|Grant|New)\b/.test(label)) return 'add';
    if (/^(Save|Equip)\b/.test(label)) return 'save';
    if (/^(Remove|Unequip|Delete|Revoke)\b/.test(label)) return 'remove';
    if (/^(Inspect|View|Details|Review)\b/.test(label)) return 'details';
    return null;
}
/* A disabled control explains itself, out loud and on screen. The reason rides on
   the button's title, on a note beside it, and on its aria-describedby, so a
   hover, a glance and a screen reader all say what is missing. The note sits
   outside the button so it cannot leak into the accessible name; base.css hides
   it again inside table cells, where a caption per row would wreck the columns. */
let reasonSeq = 0;
/* One note per control, found by its own id rather than by module bookkeeping: a
   note written by an earlier render of the same control is swept away instead of
   piling up beside it, and a reason that no longer applies cannot outlive the
   state change that lifted it. */
function explain(node, why) {
    if (!node.dataset.reasonId) node.dataset.reasonId = String(++reasonSeq);
    const id = 'control-reason-' + node.dataset.reasonId;
    if (!why) {
        node.removeAttribute('title');
        node.removeAttribute('aria-describedby');
        node.__reasonNote?.remove();
        node.__reasonNote = null;
        node.ownerDocument?.getElementById(id)?.remove();
        const stale = node.parentNode?.querySelector?.(`[id="${id}"]`);
        stale?.remove();
        return;
    }
    node.setAttribute('title', why);
    // The note is a sibling of its control, so the node's own parent is the first
    // place to look: a page built as a detached fragment is not reachable by id
    // from the document, and a second lookup there minted a second copy of the same
    // sentence until one row carried the reason twice.
    let note =
        node.__reasonNote ||
        node.parentNode?.querySelector?.(`[id="${id}"]`) ||
        node.ownerDocument?.getElementById(id) ||
        null;
    if (!note) {
        note = el('span', why, 'unavailable-reason');
        note.id = id;
        // Held on the control itself: the note is written before the control is in
        // the document, and a second lookup from the document then made a second
        // copy of the same sentence.
        node.__reasonNote = note;
    } else if (note.textContent !== why) {
        note.textContent = why;
    }
    // The note is described straight away, even while the page it belongs to is
    // still a detached fragment: `isConnected` is false for a subtree that has not
    // been inserted yet, and a page built in one task and mounted in the next would
    // otherwise never announce its reason.
    node.setAttribute('aria-describedby', note.id);
    // Most callers keep the reason off screen (base.css clips it inside a table
    // cell); a caller with no other channel asks for it shown.
    note.classList.toggle('shown', node.dataset.reasonShown === 'true');
    // It follows its own control, not the end of the row: an operator reading
    // "The listener is already running." must not find it beside Shut down.
    const place = () => {
        if (node.getAttribute('title') !== why) return;
        if (!note.isConnected && node.parentNode) node.parentNode.insertBefore(note, node.nextSibling);
    };
    place();
    if (!note.isConnected) {
        queueMicrotask(place);
        if (!note.isConnected && typeof requestAnimationFrame === 'function')
            requestAnimationFrame(place);
    }
}
export function button(label, action, className = 'secondary', available, options = {}) {
    const settings = typeof options === 'string' ? { icon: options } : options;
    const node = el('button', label, className);
    node.type = 'button';
    const icon = settings.icon === undefined ? guessIcon(label) : settings.icon;
    // No client art for this job means no icon, never a drawn stand-in.
    const art = icon ? symbol(icon) : null;
    if (art) node.prepend(art);
    if (settings.showReason) node.dataset.reasonShown = 'true';
    if (available) {
        // `available` answers true when the action may run. A string answer means
        // "no, because …", and an explicit reason overrides it. `unavailable`
        // names the usual blocker for a control that is switched off by context.
        node.refreshAvailability = (reason) => {
            const answer = available();
            const usable = typeof answer === 'string' ? false : Boolean(answer);
            node.disabled = !usable;
            explain(
                node,
                usable ? '' : reason || (typeof answer === 'string' ? answer : settings.unavailable)
            );
        };
        node.refreshAvailability();
    }
    // A pending control shows a spinner and refuses further clicks, so a slow
    // write cannot be submitted twice. It keeps its colour: see base.css.
    node.setBusy = (busy) => {
        if (busy) node.setAttribute('aria-busy', 'true');
        else node.removeAttribute('aria-busy');
        node.disabled = !!busy;
    };
    node.addEventListener('click', async () => {
        if (node.getAttribute('aria-busy') === 'true') return;
        try {
            await action();
        } catch (error) {
            report(error);
        }
    });
    return node;
}
/**
 * A drill-down as a real link. Same look as the text link it replaces, but the
 * href is a route, so the browser owns middle-click, new tabs and copying.
 */
export function routeLink(label, name, values = {}, className = 'link') {
    const node = el('a', label, className);
    node.href = href(name, values);
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
    for (const [key, value] of entries) {
        const description = el('dd');
        // A Node value lets a caller keep a live element (fingerprint, expiry)
        // inside the description list instead of a stringified copy.
        if (value instanceof Node) description.append(value);
        else description.textContent = String(value ?? 'Unavailable');
        node.append(el('dt', key), description);
    }
    return node;
}
export const number = (value) => Number(value ?? 0).toLocaleString();
export const compact = (value) =>
    new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(
        value
    );
const preferredLocale = (() => {
    if (typeof navigator === 'undefined') return 'en-GB';
    const languages = navigator.languages?.length ? navigator.languages : [navigator.language];
    const language = languages[0] || 'en-GB';
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    // Browsers often report en-US regardless of the machine region. Outside the
    // Americas, prefer the day-first/24-hour form the user asked for.
    return /^en[-_]US$/i.test(language) && !zone.startsWith('America/') ? 'en-GB' : language;
})();
const dateFormat = new Intl.DateTimeFormat(preferredLocale, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
});
const calendarFormat = new Intl.DateTimeFormat(preferredLocale, {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric'
});
const timeFormat = new Intl.DateTimeFormat(preferredLocale, {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
});
// Log lines carry their own HH:MM:SS, so the terminal clock keeps seconds and
// can order rows inside one minute.
const clockFormat = new Intl.DateTimeFormat(preferredLocale, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
});
export const date = (value) => (value ? dateFormat.format(new Date(value)) : '—');
export const time = (value) => (value ? timeFormat.format(new Date(value)) : '—');
export const clock = (value) => (value ? clockFormat.format(new Date(value)) : '—');
export const calendarDate = (value = Date.now()) => calendarFormat.format(new Date(value));
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
    if (error?.status === 401) {
        document.dispatchEvent(new Event('session-expired'));
        return;
    }
    // An ApiError carries a sentence the server wrote for an operator. Anything
    // else is a bug in this bundle: its message ("Cannot read properties of
    // undefined…") tells the operator nothing he can act on, so say what happened
    // in words and keep the stack for the console.
    if (typeof error?.status === 'number') {
        notify(error.message || 'Operation unavailable', true);
        return;
    }
    if (error) console.error('Unexpected WebUI failure', error);
    notify('The WebUI could not finish that action. Nothing was changed; reload the page and try again.', true);
}
export function changed(message = 'Changes saved.') {
    // A transient confirmation, so the sticky page notice stays reserved for
    // errors the operator must act on.
    toast(message, { type: 'success' });
    document.dispatchEvent(new Event('data-changed'));
}
/** Copy text and confirm once, with a fallback for browsers without the async API. */
export async function copy(value, message = 'Copied to the clipboard.') {
    if (await writeClipboard(value)) {
        toast(message, { type: 'success' });
        return;
    }
    toast('This browser blocked clipboard access. Select the text and copy it manually.', {
        type: 'error'
    });
}
/**
 * The async clipboard API only exists in a secure context, so a documented plain
 * HTTP LAN setup would silently lose every copy button. Fall back to the legacy
 * selection command, which still works there.
 */
async function writeClipboard(value) {
    try {
        if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(value);
            return true;
        }
    } catch {
        /* fall through to the legacy path */
    }
    try {
        const field = el('textarea');
        field.value = value;
        field.setAttribute('readonly', '');
        field.style.position = 'fixed';
        field.style.insetBlockStart = '-1000px';
        document.body.append(field);
        field.select();
        const copied = document.execCommand('copy');
        field.remove();
        return copied;
    } catch {
        return false;
    }
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
        const art = symbol(id);
        if (art) b.prepend(art);
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

/* ---------------------------------------------------------------------------
   Shell chrome. The static markup carries labels only; the art comes from the
   client symbol pack, so a shell control never falls back to a text glyph.
   --------------------------------------------------------------------------- */
export function paintShell() {
    for (const [selector, name] of [
        ['#close-dialog', 'close'],
        ['#shortcuts-close', 'close'],
        ['#shortcuts', 'keyboard'],
        ['#close-navigation', 'close']
    ]) {
        const node = $(selector);
        const art = node && !node.querySelector('.ui-icon') ? symbol(name) : null;
        if (art) node.prepend(art);
    }
    paintBrand();
    syncAppearances();
}
/**
 * The brand is one piece of client art in three places: both wordmarks and the tab
 * icon. A pack with no brand art leaves the words alone rather than standing a
 * drawing in for them, so this runs again once the pack has loaded.
 */
export function paintBrand() {
    const mark = symbol('brand');
    if (!mark) return;
    for (const slot of document.querySelectorAll('.brand-mark'))
        if (!slot.querySelector('.ui-icon')) slot.append(mark.cloneNode(true));
    const image = mark.querySelector('image');
    const favicon = $('#favicon');
    if (favicon && image) favicon.href = image.getAttribute('href');
}

/* The appearance toggles name the action they perform, so the pressed state is
   the only place the current setting is announced. preferences.js paints
   data-theme/data-density before styles, so that is the source of truth. */
const appearances = [
    ['#theme', 'theme', 'light'],
    ['#density', 'density', 'compact']
];
// These two are switches, not toggles: the label names the setting ("Light theme")
// and `aria-checked` says whether it is on, so the button can never display the
// opposite of its own state.
function syncAppearances() {
    for (const [selector, key, on] of appearances) {
        const node = $(selector);
        if (node) {
            node.setAttribute('role', 'switch');
            node.setAttribute('aria-checked', String(document.documentElement.dataset[key] === on));
        }
    }
}
// The regression programs import this module in Node for its pure helpers, so
// nothing here may touch the DOM at import time outside a browser.
if (typeof document !== 'undefined') {
    paintShell();
    new MutationObserver(syncAppearances).observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['data-theme', 'data-density']
    });
    document.addEventListener('symbols-ready', paintBrand);
}
