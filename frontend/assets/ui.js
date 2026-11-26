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
/* One rule for art on a control: the picture marks the action that cannot be undone.
   Matching a verb was a lottery — "Revoke" wore client art while "Create WebUI
   identity" and "Export CSV" beside it wore none, so the column looked half painted
   and the paint promised nothing. A word is always enough for a control that can be
   taken back. */
function guessIcon(label) {
    // Settle is not a removal. It borrowed the ✕ because the word begins with an S, and
    // on the auctions page "✕ Settle expired" sat beside "✕ Remove pack" doing the
    // opposite kind of job. The pack has no art for paying a listing out, so the control
    // carries its label and no icon — the rule this file already follows.
    if (/^(Remove|Unequip|Delete|Revoke)\b/.test(label)) return 'remove';
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
    // The note follows its own control instead of the whole row or toolbar. Revealing
    // every note in a row on hover painted two overlapping boxes and re-laid the
    // toolbar, which is what made a button jump under the pointer. Focus is the one
    // channel that names which control asked: a pointer already gets the `title`.
    if (!node.__reasonBound) {
        node.__reasonBound = true;
        const active = (on) => {
            const current = node.__reasonNote;
            if (!current) return;
            if (on) current.dataset.active = 'true';
            else delete current.dataset.active;
        };
        node.addEventListener('focus', () => active(true));
        node.addEventListener('blur', () => active(false));
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
            // `aria-disabled` rather than `disabled`: a natively disabled button
            // cannot take focus, so the reason below it was readable with a mouse and
            // invisible to a keyboard. The click handler refuses when the flag is set.
            if (usable) {
                node.removeAttribute('aria-disabled');
                node.disabled = false;
            } else {
                node.setAttribute('aria-disabled', 'true');
                node.disabled = false;
            }
            node.dataset.usable = usable ? 'true' : 'false';
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
        if (busy) node.setAttribute('aria-disabled', 'true');
        else if (node.dataset.usable !== 'false') node.removeAttribute('aria-disabled');
    };
    node.addEventListener('click', async () => {
        if (node.getAttribute('aria-busy') === 'true') return;
        // A control that cannot act keeps its reason on screen and stays focusable;
        // the click is refused here instead of by the attribute. A press says the
        // reason out loud too: a title is hover-only, and a phone never hovers.
        if (node.dataset.usable === 'false') {
            const reason =
                node.dataset.reason ||
                node.title ||
                'That action is not available on this server yet.';
            toast(reason, { type: 'error' });
            (node.getAttribute('aria-describedby') || '')
                .split(/\s+/)
                .filter(Boolean)
                .forEach((id) => document.getElementById(id)?.setAttribute('data-said', 'true'));
            return;
        }
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
/**
 * A scene id names a place, not a label: "%town:m.haven_town_square" is the key the
 * server uses, and an operator should read "Haven Town Square" here, on the overview
 * and in a table cell alike.
 */
export function sceneLabel(name) {
    const id = String(name || '')
        .replace(/^%/, '')
        .replace(/^[a-z]+:/, '')
        .replace(/^[a-z]\./, '');
    const words = id.split(/[_\s.]+/).filter(Boolean);
    if (!words.length) return String(name || '');
    return words.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}
export function routeLink(label, name, values = {}, className = 'link') {
    const node = el('a', label, className);
    node.href = href(name, values);
    return node;
}
/**
 * Bring a block into view once it is actually in the document.
 *
 * A page is built as a detached fragment and attached after `mount()` returns, so a
 * deep link that scrolls in the same tick moves nothing at all — the one thing a
 * shared link has to do.
 */
export function landOn(node, block = 'start', frames = 30) {
    const attempt = (left) => {
        if (!node) return;
        if (node.isConnected) {
            node.scrollIntoView({ block });
            // The shell swaps the page's children in after `mount()` returns, and that
            // replacement resets the scroll position: the landing is asserted again on
            // the next frame, which is the one the operator actually sees.
            if (typeof requestAnimationFrame === 'function')
                requestAnimationFrame(() => {
                    if (node.isConnected) node.scrollIntoView({ block });
                });
            return;
        }
        if (left > 0 && typeof requestAnimationFrame === 'function')
            requestAnimationFrame(() => attempt(left - 1));
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => attempt(frames));
    else attempt(0);
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
    // No trailing space and no empty modifier: a state with no tone is the plain form,
    // and `class="badge "` made that look like a modifier that failed to arrive.
    return el('span', value, ['badge', className].filter(Boolean).join(' '));
}
/** One spelling of a stored role wherever the WebUI prints one. */
export function roleName(value) {
    return (
        { ADMINISTRATOR: 'Administrator', MODERATOR: 'Moderator', VIEWER: 'Viewer' }[
            String(value || '').toUpperCase()
        ] || String(value || 'Unavailable')
    );
}
/**
 * The role chip used by the sidebar, the Preferences page and WebUI Access. One shape,
 * one casing and one tone per role: administrator purple, moderator cyan, viewer
 * neutral. The stored enum lives in `data-role` so the stylesheet does not have to
 * match on display text.
 */
export function roleBadge(value) {
    const upper = String(value || '').toUpperCase();
    const node = badge(roleName(upper).toLowerCase(), 'role');
    node.dataset.role = upper;
    node.title = roleName(upper);
    return node;
}
/**
 * In-game authority wears the same pill as WebUI administration: the owner asked for
 * the Game Master tag to read like "Administrator", not like a second badge shape.
 */
export function gameMasterBadge() {
    const node = badge('game master', 'role');
    node.dataset.role = 'GAME_MASTER';
    node.title = 'Game Master';
    return node;
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
/**
 * Dates follow the browser, not this bundle. The viewer already configured a region,
 * a clock and a time zone in their operating system; `undefined` asks Intl for exactly
 * those. There is deliberately no forced `en-GB` and no forced `h23`: a browser set to
 * `en-US` reads `07:17 PM`, one set to `en-GB` reads `19:17`, and the header agrees
 * with every row. No formatter appends a zone name — the whole WebUI is local time,
 * and repeating "EET" on every cell was noise, not information.
 */
const dateFormat = new Intl.DateTimeFormat(undefined, {
    dateStyle: 'short',
    timeStyle: 'short'
});
const calendarFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'short' });
// Used where a *sample* time is read out loud (the chart's inspection readout and its
// axis). Same system clock, no zone suffix.
const timeFormat = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });
// Log lines carry their own HH:MM:SS, so the terminal clock keeps seconds and
// can order rows inside one minute.
const clockFormat = new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
});
export const date = (value) => (value ? dateFormat.format(new Date(value)) : '—');
export const time = (value) => (value ? timeFormat.format(new Date(value)) : '—');
export const clock = (value) => (value ? clockFormat.format(new Date(value)) : '—');
export const calendarDate = (value = Date.now()) => calendarFormat.format(new Date(value));
// A trail can hold several events inside one minute, so its stamp carries seconds:
// five rows reading the same minute left the operator unable to read the order the
// table was already sorted by. Same locale, same clock, one more part.
const stampFormat = new Intl.DateTimeFormat(undefined, {
    dateStyle: 'short',
    timeStyle: 'medium'
});
export const stamp = (value) => (value ? stampFormat.format(new Date(value)) : '—');
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
export function report(error, { read = false } = {}) {
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
    // A request that never got an answer is not a request that did nothing: a write
    // can have committed and lost its reply, so the sentence must not invite a
    // re-submit into a possible double write.
    const lost =
        error?.name === 'TypeError' || /fetch|network/i.test(String(error?.message || ''));
    // A poll that failed wrote nothing, so promising a possible write is a lie the
    // operator would act on. Reads get a sentence true of reads.
    notify(
        read
            ? lost
                ? 'The server did not answer, so this view may be out of date. Nothing was changed; the next refresh retries on its own.'
                : 'The WebUI could not read that view; reload the page and try again.'
            : lost
              ? 'The server did not answer. The change may or may not have been stored: reopen the record and read its state before saving again.'
              : 'The WebUI could not finish that action; reload the page and try again.',
        true
    );
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
let tabSeq = 0;
export function tabs(options, value, onChange) {
    const root = el('div', null, 'tabs');
    root.setAttribute('role', 'tablist');
    // A tab that does not say which region it controls is a button wearing a tab's
    // role: a screen reader announces "tab, 1 of 3" and then has nowhere to send the
    // operator. Each strip owns one region — both callers swap that region's children
    // rather than stacking hidden panels — so the panel id is minted once per strip
    // and its label follows the selected tab.
    const group = 'tabs-' + ++tabSeq;
    const panelId = group + '-panel';
    let panel = null;
    /**
     * Paint a selection without telling the page about it.
     *
     * The panel's `aria-labelledby` is minted once, so a page that wrote `aria-selected`
     * itself (Depot's `paintTabs`, Knights' deep-link switch) left the region labelled by
     * the tab selected at construction: `#depot?tab=promotions` announced a *Offers* panel
     * over Promotions. Selection is one operation, and this is it.
     */
    function setSelected(id) {
        for (const b of root.children) {
            const selected = b.dataset.tab === id;
            b.setAttribute('aria-selected', String(selected));
            b.tabIndex = selected ? 0 : -1;
        }
        if (panel) panel.setAttribute('aria-labelledby', group + '-' + id);
    }
    function choose(id, focus = false) {
        setSelected(id);
        if (focus) {
            for (const b of root.children) if (b.dataset.tab === id) b.focus();
        }
        onChange(id);
    }
    root.setSelected = setSelected;
    for (const [id, title] of options) {
        const b = button(title, () => choose(id));
        const art = symbol(id);
        if (art) b.prepend(art);
        b.dataset.tab = id;
        b.id = group + '-' + id;
        b.setAttribute('role', 'tab');
        b.setAttribute('aria-controls', panelId);
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
    /**
     * Name the region the strip switches, once per caller. `tabindex="0"` is what the
     * tab pattern asks for: a panel whose content is all read-only text would
     * otherwise be unreachable from the keyboard, and a tab stop on a region is
     * cheaper than a dead end. Returns the node so callers can keep using
     * `replaceChildren` on it.
     */
    root.bindPanel = (node) => {
        if (!node) return panel;
        node.id = panelId;
        node.setAttribute('role', 'tabpanel');
        node.setAttribute('aria-labelledby', group + '-' + (value ?? options[0][0]));
        node.tabIndex = 0;
        panel = node;
        return node;
    };
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
}
/**
 * The brand is one piece of client art in three places: both wordmarks and the tab
 * icon. A pack with no brand art leaves the words alone rather than standing a
 * drawing in for them, so this runs again once the pack has loaded.
 */
export function paintBrand() {
    const mark = symbol('brand');
    if (!mark) return;
    /* Each slot gets a symbol of its own. A clone carries the original's filter id, and
       a duplicate id resolves to whichever copy the document reaches first: the login
       card's, whose subtree is hidden once the operator signs in. A filter inside a
       hidden subtree does not render, so the sidebar's mark fell back to the raw white
       client art — invisible against the console's own surface. */
    for (const slot of document.querySelectorAll('.brand-mark'))
        if (!slot.querySelector('.ui-icon')) slot.append(symbol('brand'));
    const image = mark.querySelector('image');
    const favicon = $('#favicon');
    if (favicon && image) favicon.href = image.getAttribute('href');
}

// The regression programs import this module in Node for its pure helpers, so
// nothing here may touch the DOM at import time outside a browser.
if (typeof document !== 'undefined') {
    paintShell();
    document.addEventListener('symbols-ready', paintBrand);
}
