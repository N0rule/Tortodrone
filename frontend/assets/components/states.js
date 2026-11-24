import { el, button } from '../ui.js';
import { symbol } from './symbols.js';

/**
 * Placeholder blocks shown while a page's first read is in flight. They keep the
 * final layout height so the page does not jump when data arrives, and they are
 * `aria-busy` so assistive technology reports a pending update rather than
 * reading a half-built screen.
 * @param {{rows?: number, columns?: number, label?: string}} [options]
 */
export function skeleton({ rows = 5, columns = 4, label = 'Loading records' } = {}) {
    const node = el('div', null, 'skeleton');
    node.setAttribute('aria-busy', 'true');
    node.setAttribute('aria-live', 'polite');
    node.setAttribute('aria-label', label);
    const head = el('div', null, 'skeleton-row skeleton-head');
    for (let i = 0; i < columns; i++) head.append(el('span', null, 'skeleton-bar'));
    node.append(head);
    for (let r = 0; r < rows; r++) {
        const row = el('div', null, 'skeleton-row');
        for (let c = 0; c < columns; c++) {
            const bar = el('span', null, 'skeleton-bar');
            bar.style.setProperty('--skeleton-split', `${100 - ((r * 13 + c * 29) % 45)}%`);
            row.append(bar);
        }
        node.append(row);
    }
    node.append(el('span', label, 'visually-hidden'));
    return node;
}

/**
 * A page wait state for content that is not a table.
 * @param {string} [label]
 */
export function waiting(label = 'Loading…') {
    const node = el('div', null, 'waiting');
    node.setAttribute('aria-busy', 'true');
    node.append(el('span', null, 'spinner'), el('span', label));
    return node;
}

/**
 * Explain an empty result and offer the next step. Every empty region should
 * carry an action, so the operator is never left with a dead end.
 * @param {{title: string, message?: string, action?: {label: string, run: () => void}, icon?: string, compact?: boolean}} options
 */
export function emptyState({ title, message = '', action, icon = 'details', compact = false }) {
    const node = el('div', null, 'empty-state' + (compact ? ' compact' : ''));
    node.append(symbol(icon));
    node.append(el('p', title, 'empty-title'));
    if (message) node.append(el('p', message, 'empty-message'));
    if (action) node.append(button(action.label, action.run));
    return node;
}

/**
 * A failed read with a retry affordance, instead of a bare error string.
 * @param {{error: Error, retry?: () => void, title?: string}} options
 */
export function errorState({ error, retry, title = 'That view could not be loaded' }) {
    const node = el('div', null, 'empty-state error-state');
    node.setAttribute('role', 'alert');
    node.append(symbol('warning'));
    node.append(el('p', title, 'empty-title'));
    node.append(el('p', error?.message || 'The server did not answer.', 'empty-message'));
    if (retry) node.append(button('Try again', retry));
    return node;
}
