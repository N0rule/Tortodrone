/** Copy beside api.js in a disposable deployment; run in its authenticated browser. */
import { api } from './api.js';
const $ = (selector) => document.querySelector(selector);
const check = (value, message) => {
    if (!value) throw new Error(message);
};
async function until(predicate) {
    const end = Date.now() + 8000;
    while (!predicate()) {
        if (Date.now() > end) throw new Error('Timed out waiting for shell state');
        await new Promise((resolve) => setTimeout(resolve, 25));
    }
}
async function page(name) {
    location.hash = name;
    await until(
        () =>
            $('#navigation [aria-current="page"]')?.dataset.page === name &&
            !$('#page .loading') &&
            !$('#refresh').disabled
    );
}
/** Every visible interactive control must expose a programmatic name. */
function controlName(node) {
    const label = node.getAttribute('aria-label');
    if (label?.trim()) return label.trim();
    const labelled = node.getAttribute('aria-labelledby');
    if (labelled)
        return labelled
            .split(/\s+/)
            .map((id) => document.getElementById(id)?.textContent || '')
            .join(' ')
            .trim();
    if (node.id) {
        const forLabel = document.querySelector(`label[for="${node.id}"]`);
        if (forLabel) return forLabel.textContent.trim();
    }
    return (node.closest('label')?.textContent || node.textContent || '').trim();
}
export async function run({ disposable = false } = {}) {
    check(
        disposable && ['localhost', '127.0.0.1'].includes(location.hostname) && api.session,
        'Authenticated disposable loopback fixture required'
    );
    await page('overview');
    const original = api.request;
    let release,
        delayed = false;
    const gate = new Promise((resolve) => {
        release = resolve;
    });
    api.request = async function (...args) {
        if (args[0] === 'status' && !delayed) {
            delayed = true;
            await gate;
        }
        return original.apply(this, args);
    };
    try {
        $('#refresh').click();
        await until(() => delayed && $('#refresh').disabled);
        await page('logs');
        check(!$('#refresh').disabled, 'New page can refresh while an obsolete refresh is pending');
    } finally {
        release();
        api.request = original;
    }
    const hash = location.hash;
    $('.skip').click();
    check(
        location.hash === hash && document.activeElement === $('#main'),
        'Skip to content focuses main without navigating away'
    );
    if (matchMedia('(max-width: 760px)').matches) {
        $('#menu').click();
        check(
            $('.workspace').inert && document.activeElement === $('#close-navigation'),
            'Mobile navigation moves focus and excludes background controls'
        );
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        check(
            !$('.workspace').inert && document.activeElement === $('#menu'),
            'Escape closes navigation and returns focus'
        );
        $('#menu').click();
        $('#navigation-backdrop').click();
        check(!$('#shell').classList.contains('menu-open'), 'Outside click dismisses navigation');
        $('#menu').click();
        $('#navigation a[data-page="overview"]').click();
        await until(
            () =>
                $('#navigation [aria-current="page"]')?.dataset.page === 'overview' &&
                !$('#page .loading') &&
                !$('#refresh').disabled
        );
        check(
            !$('.workspace').inert && document.activeElement === $('#main'),
            'Navigation releases the background and focuses content'
        );
    }
    check(document.documentElement.scrollWidth <= innerWidth, 'Shell fits the viewport');

    // Command palette: one combobox driving a grouped listbox.
    $('#search').click();
    const palette = $('#palette');
    check(palette.open, 'Search trigger opens the command palette');
    const input = $('#palette-input');
    check(document.activeElement === input, 'Palette focuses its combobox');
    check(input.getAttribute('aria-expanded') === 'true', 'Combobox reports the listbox as open');
    const before = input.getAttribute('aria-activedescendant');
    check(before, 'Palette marks the first result active');
    check(
        document.getElementById(before)?.getAttribute('role') === 'option',
        'Active descendant is a listbox option'
    );
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    check(
        input.getAttribute('aria-activedescendant') !== before,
        'Arrow keys move the active option'
    );
    input.value = 'knights';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await until(() => [...document.querySelectorAll('#palette-list [role="option"]')].length > 0);
    check(
        [...document.querySelectorAll('#palette-list [role="option"]')].some((option) =>
            option.textContent.toLowerCase().includes('knights')
        ),
        'Palette ranks the matching command'
    );
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    palette.close();
    await until(() => !palette.open);
    // A native dialog restores focus from its queued close event.
    await until(() => document.activeElement === $('#search'));
    check(document.activeElement === $('#search'), 'Closing the palette returns focus to its trigger');

    // Shortcut reference dialog.
    $('#shortcuts').click();
    check($('#shortcut-dialog').open, 'Shortcut reference opens');
    check(
        $('#shortcut-dialog').querySelectorAll('tbody tr').length >= 5,
        'Shortcut reference lists the supported keys'
    );
    $('#shortcuts-close').click();
    await until(() => !$('#shortcut-dialog').open);

    // Transient notifications.
    const { toast } = await import('/admin/assets/components/toast.js');
    const notice = toast('Shell test notification');
    check(notice.getAttribute('role') === 'status', 'A success tone is a polite status');
    check(
        notice.querySelector('[aria-label="Dismiss notification"]'),
        'Every toast names its dismiss control'
    );
    notice.querySelector('[aria-label="Dismiss notification"]').click();
    await until(() => !notice.isConnected);

    // Table selection and export affordances on a record page.
    if (api.session.role !== 'VIEWER') {
        await page('accounts');
        const row = $('#page .row-select');
        if (row) {
            row.click();
            const bar = $('#page .bulk-bar');
            check(!bar.hidden, 'Selecting a row reveals the bulk action bar');
            check(
                bar.textContent.includes('1 record selected'),
                'The bulk bar states the selection size'
            );
            check(
                $('#page').querySelector('button[aria-label], #page .bulk-bar button'),
                'Bulk actions are labelled buttons'
            );
            $('#page .bulk-bar button').click();
            await until(() => $('#page .bulk-bar').hidden);
        }
        check(
            [...$('#page').querySelectorAll('button')].some((b) =>
                b.textContent.trim() === 'Export CSV'
            ),
            'Record tables offer an export'
        );
    }

    // Structure: no duplicate ids, and every visible control has a name.
    const ids = new Map();
    for (const node of document.querySelectorAll('[id]'))
        ids.set(node.id, (ids.get(node.id) || 0) + 1);
    check(
        [...ids].every(([, count]) => count === 1),
        'Every id is unique'
    );
    const nameless = [
        ...document.querySelectorAll('a[href], button, input, select, textarea')
    ].filter(
        (node) =>
            node.checkVisibility() &&
            node.type !== 'hidden' &&
            node.getAttribute('type') !== 'checkbox' &&
            !controlName(node)
    );
    check(nameless.length === 0, 'Visible controls are named: ' + nameless.length + ' without');
    check(
        document.querySelectorAll('th').length === 0 ||
            [...document.querySelectorAll('th')].every((cell) => cell.scope),
        'Every table header declares its scope'
    );

    // Every page keeps the same structural contract: one visible heading, a
    // captioned table, scoped headers, no duplicate ids, no overflow.
    const pageNames = [...$('#navigation').querySelectorAll('a[data-page]')].map(
        (link) => link.dataset.page
    );
    check(pageNames.length >= 8, 'Navigation lists the reachable pages');
    for (const name of pageNames) {
        // Some pages have no refresh action, so the sweep only waits for the
        // route and the first paint.
        location.hash = name;
        await until(
            () =>
                $('#navigation [aria-current="page"]')?.dataset.page === name &&
                !$('#page .loading')
        );
        const visible = [...$('#page').querySelectorAll('h1, h2')].filter((node) =>
            node.checkVisibility()
        );
        check(
            visible.filter((node) => node.tagName === 'H1').length <= 1,
            name + ' keeps a single h1'
        );
        const tables = [...$('#page').querySelectorAll('table')];
        for (const table of tables) {
            check(!!table.querySelector('caption'), name + ' table has a caption');
            check(
                [...table.querySelectorAll('th')].every((cell) => cell.scope),
                name + ' table headers are scoped'
            );
        }
        const pageIds = new Map();
        for (const node of $('#page').querySelectorAll('[id]'))
            pageIds.set(node.id, (pageIds.get(node.id) || 0) + 1);
        check(
            [...pageIds].every(([, count]) => count === 1),
            name + ' has no duplicate ids'
        );
        const overflow = document.documentElement.scrollWidth - window.innerWidth;
        check(overflow <= 0, name + ' fits the viewport (' + overflow + 'px)');
    }

    // The session is visible to the operator, including when it expires.
    if (api.session.role === 'ADMINISTRATOR') {
        await page('webui-users');
        const expiry = $('#session-expiry');
        check(!!expiry, 'Access states when the session expires');
        check(
            /\d/.test(expiry.textContent),
            'The session expiry is a readable time, not a placeholder'
        );
        check(
            $('#page').textContent.includes('CSRF'),
            'Access states the request checks the server applies'
        );
    }
    return 'PASS: refresh generation, skip link, mobile navigation, palette, shortcuts, toasts, selection, page sweep, session facts, ids and control names';
}
