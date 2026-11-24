import { symbol } from './symbols.js';
/**
 * Transient notifications. One region owns every toast so assistive technology
 * hears each message exactly once, and the region is created on first use so a
 * page that never reports anything adds no empty live region to the DOM.
 */
let region = null;
const limit = 4;
const timers = new WeakMap();
const defaults = { success: 6000, info: 7000, error: 12000 };

function build(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = String(text);
    if (className) node.className = className;
    return node;
}

function container() {
    if (region?.isConnected) return region;
    region = build('div', null, 'toasts');
    region.id = 'toasts';
    region.setAttribute('role', 'region');
    region.setAttribute('aria-label', 'Notifications');
    document.body.append(region);
    return region;
}

function arm(node, delay) {
    clearTimeout(timers.get(node));
    if (delay > 0) timers.set(node, setTimeout(() => dismiss(node), delay));
}

function hold(node) {
    clearTimeout(timers.get(node));
}

/** @param {Element} node */
export function dismiss(node) {
    if (!node?.isConnected) return;
    clearTimeout(timers.get(node));
    node.classList.add('leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
    // Reduced-motion users get no animation event; remove on the next frame instead.
    requestAnimationFrame(() => {
        if (node.isConnected && !node.getAnimations?.().length) node.remove();
    });
}

/**
 * Announce a message. `type` selects the tone and the expiry; `detail` adds a
 * secondary line, `action` an optional button that also dismisses the toast.
 * @param {string} message
 * @param {{type?: 'success'|'info'|'error', detail?: string, action?: {label: string, run: () => void}, timeout?: number}} [options]
 */
export function toast(message, options = {}) {
    const type = options.type ?? 'info';
    const node = build('div', null, 'toast toast-' + type);
    node.setAttribute('role', type === 'error' ? 'alert' : 'status');
    const body = build('div', null, 'toast-body');
    body.append(build('p', message, 'toast-message'));
    if (options.detail) body.append(build('p', options.detail, 'toast-detail'));
    // Client art, not a text multiplication sign, so the control matches the shell.
    const close = build('button', null, 'quiet toast-dismiss');
    close.type = 'button';
    close.setAttribute('aria-label', 'Dismiss notification');
    close.append(symbol('close'));
    close.onclick = () => dismiss(node);
    node.append(body, close);
    if (options.action) {
        const action = build('button', options.action.label, 'secondary toast-action');
        action.type = 'button';
        action.onclick = () => {
            dismiss(node);
            options.action.run();
        };
        body.append(action);
    }
    node.onmouseenter = node.onfocusin = () => hold(node);
    node.onmouseleave = node.onfocusout = () => arm(node, defaults[type]);
    node.onkeydown = (event) => {
        if (event.key === 'Escape') {
            event.stopPropagation();
            dismiss(node);
        }
    };
    const host = container();
    host.prepend(node);
    while (host.children.length > limit) host.lastElementChild.remove();
    arm(node, options.timeout ?? defaults[type]);
    return node;
}
