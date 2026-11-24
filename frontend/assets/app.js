import { symbol as uiSymbol } from './components/symbols.js';
import { api } from './api.js';
import { $, el, button, text, notify, report, calendarDate } from './ui.js';
import { route, go } from './router.js';
import { loadAssets } from './components/items.js';
import { dialogDirty, closeDialog } from './components/dialog.js';
import { createPalette } from './components/palette.js';
import { toast } from './components/toast.js';
import { skeleton, errorState } from './components/states.js';
import * as prefs from './prefs.js';
import * as overview from './pages/overview.js';
import * as players from './pages/players.js';
import * as accounts from './pages/accounts.js';
import * as knights from './pages/knights.js';
import * as depot from './pages/depot.js';
import * as auctions from './pages/auctions.js';
import * as news from './pages/news.js';
import * as mailing from './pages/mailing.js';
import * as content from './pages/content.js';
import * as consolePage from './pages/console.js';
import * as logs from './pages/logs.js';
import * as access from './pages/access.js';
import * as audit from './pages/audit.js';
import * as settings from './pages/settings.js';

/** `rank` is the lowest role that may see the page: 0 viewer, 1 moderator, 2 administrator. */
const pages = [
    {
        name: 'overview',
        title: 'Overview',
        section: 'Monitor',
        rank: 0,
        module: overview,
        description: 'Server state, live load and the warnings worth acting on.'
    },
    {
        name: 'players',
        title: 'Online Players',
        section: 'Monitor',
        rank: 0,
        module: players,
        description: 'Live sessions and scene population.'
    },
    {
        name: 'logs',
        title: 'Logs',
        section: 'Monitor',
        rank: 0,
        module: logs,
        description: 'Server output with severity and text filters.'
    },
    {
        name: 'accounts',
        title: 'Accounts',
        section: 'People',
        rank: 1,
        module: accounts,
        description: 'Create accounts, reset credentials and control game-master rights.'
    },
    {
        name: 'knights',
        title: 'Knights',
        section: 'People',
        rank: 1,
        module: knights,
        description: 'Progress, currency, gear, accessories and Battle Sprites.'
    },
    {
        name: 'depot',
        title: 'Supply Depot',
        section: 'Economy',
        rank: 2,
        module: depot,
        description: 'Stocked offers, promotions and the effective catalogue.'
    },
    {
        name: 'auctions',
        title: 'Auction House',
        section: 'Economy',
        rank: 2,
        module: auctions,
        description: 'Escrowed listings, expired stock and pending deliveries.'
    },
    {
        name: 'mailing',
        title: 'Player Mail',
        section: 'Content',
        rank: 2,
        module: mailing,
        description: 'Campaigns, direct mail and the new-knight welcome letter.'
    },
    {
        name: 'news',
        title: 'Uplink News',
        section: 'Content',
        rank: 2,
        module: news,
        description: 'Banners, articles and the welcome chat message.'
    },
    {
        name: 'content',
        title: 'Content Catalogue',
        section: 'Content',
        rank: 2,
        module: content,
        description: 'Validated items, maps and merchant stock.'
    },
    {
        name: 'console',
        title: 'Console',
        section: 'Operations',
        rank: 2,
        module: consolePage,
        description: 'Run recovery commands with confirmation for state changes.'
    },
    {
        name: 'audit',
        title: 'Audit Trail',
        section: 'Operations',
        rank: 2,
        module: audit,
        description: 'Every privileged request with its recorded outcome.'
    },
    {
        name: 'webui-users',
        title: 'WebUI Access',
        section: 'Operations',
        rank: 2,
        module: access,
        description: 'Identities, roles and session revocation.'
    },
    {
        name: 'settings',
        title: 'Preferences',
        section: 'Operations',
        rank: 0,
        module: settings,
        description: 'Appearance, data display and live-update behaviour for this browser.'
    }
];
const sections = ['Monitor', 'People', 'Economy', 'Content', 'Operations'];

let generation = 0,
    current,
    controller,
    timer,
    refreshJob,
    pageRequests,
    acceptedHash = location.hash,
    setupRequired = false,
    expiryWarned = false;

const rank = () => ['VIEWER', 'MODERATOR', 'ADMINISTRATOR'].indexOf(api.session?.role);
const mobile = matchMedia('(max-width: 760px)');
const pageFor = (name) => pages.find((page) => page.name === name && page.rank <= rank());

function announce(message) {
    text($('#page-status'), message);
}

function menu(open, restoreFocus = false) {
    open = open && mobile.matches;
    $('#shell').classList.toggle('menu-open', open);
    $('#menu').setAttribute('aria-expanded', String(open));
    $('.workspace').inert = open;
    if (open) $('#close-navigation').focus();
    else if (restoreFocus) $('#menu').focus();
}

function connection(value, online = true) {
    text($('#connection'), value);
    $('#connection').classList.toggle('online', online);
}

function clock() {
    const now = new Date();
    text(
        $('#clock'),
        prefs.read('clock') === '12-hour'
            ? now.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' })
            : calendarDate(now)
    );
    $('#clock').dateTime = now.toISOString();
}

async function refresh() {
    if (!api.session || !controller?.refresh) return;
    if (refreshJob?.id === generation) {
        refreshJob.repeat = true;
        return refreshJob.promise;
    }
    const id = generation,
        target = controller,
        job = { id, repeat: false };
    refreshJob = job;
    $('#refresh').disabled = true;
    job.promise = (async () => {
        try {
            await target.refresh();
            if (id === generation && current !== 'overview' && current !== 'players')
                connection('Connected');
        } catch (error) {
            if (id === generation && error.name !== 'AbortError') {
                connection('Reconnecting', false);
                report(error);
            }
        } finally {
            if (id === generation) $('#refresh').disabled = false;
            if (refreshJob === job) {
                refreshJob = null;
                if (job.repeat && id === generation) refresh();
            }
        }
    })();
    return job.promise;
}

function poll() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
        const id = generation;
        if (api.session && !document.hidden) await refresh();
        if (api.session && id === generation) poll();
    }, (controller?.interval || 15000) * prefs.read('pollScale'));
}

function renderNavigation() {
    const nav = $('#navigation');
    nav.replaceChildren(
        ...sections.flatMap((section) => {
            const visible = pages.filter((page) => page.section === section && page.rank <= rank());
            if (!visible.length) return [];
            const group = el('div', null, 'nav-section');
            // A labelled group keeps the page heading as the only heading in the
            // shell, so the outline starts at h1 and screen readers still hear the
            // section name for each run of links.
            const heading = el('p', section, 'nav-heading');
            heading.id = 'nav-' + section.toLowerCase().replace(/\s+/g, '-');
            group.append(heading);
            const list = el('div', null, 'nav-list');
            list.setAttribute('role', 'group');
            list.setAttribute('aria-labelledby', heading.id);
            for (const page of visible) {
                const link = el('a');
                const icon = uiSymbol(page.name);
                icon.setAttribute('aria-hidden', 'true');
                link.append(icon, el('span', page.title));
                link.href = '#' + page.name;
                link.dataset.page = page.name;
                list.append(link);
            }
            group.append(list);
            return [group];
        })
    );
}

async function navigate() {
    if (!api.session) return;
    const next = route(),
        spec = pageFor(next.name);
    if (!spec) {
        go('overview');
        return;
    }
    if (
        current &&
        next.name !== current &&
        controller?.isDirty?.() &&
        !window.confirm('Discard the unsaved changes on this page?')
    ) {
        location.hash = acceptedHash;
        return;
    }
    if (location.hash !== acceptedHash && !closeDialog()) {
        location.hash = acceptedHash;
        return;
    }
    acceptedHash = location.hash;
    const fromMenu = $('#shell').classList.contains('menu-open');
    menu(false);
    if (fromMenu) $('#main').focus();
    for (const a of $('#navigation').querySelectorAll('a'))
        a.setAttribute('aria-current', a.dataset.page === next.name ? 'page' : 'false');
    text($('#title'), spec.title);
    text($('#page-section'), spec.section);
    text($('#page-description'), spec.description ?? '');
    document.title = spec.title + ' · Tortodrone';
    $('#page').setAttribute('aria-label', spec.title);
    announce(spec.title + ' page');
    if (current === next.name && controller) {
        if (controller.navigate) {
            try {
                await controller.navigate(next.params);
            } catch (error) {
                if (error.name !== 'AbortError') report(error);
            }
        }
        return;
    }
    const id = ++generation;
    $('#refresh').disabled = true;
    pageRequests?.abort();
    const requests = new AbortController();
    pageRequests = requests;
    refreshJob = null;
    controller?.dispose?.();
    controller = null;
    current = next.name;
    clearTimeout(timer);
    const placeholder = skeleton({ rows: 5, columns: 4, label: 'Loading ' + spec.title });
    // `loading` keeps the documented acceptance contract that waits for the
    // placeholder to disappear before it inspects a page.
    placeholder.classList.add('loading');
    $('#page').setAttribute('aria-busy', 'true');
    $('#page').replaceChildren(placeholder);
    $('#notice').hidden = true;
    const ctx = {
        params: next.params,
        admin: rank() === 2,
        moderator: rank() >= 1,
        go,
        notify,
        report,
        refresh,
        connection,
        toast,
        announce,
        alive: () => generation === id && !!api.session,
        request: async (...args) => {
            const value = await api.request(args[0], args[1], false, requests.signal);
            if (generation !== id || !api.session)
                throw new DOMException('Page changed', 'AbortError');
            return value;
        }
    };
    try {
        const mounted = await spec.module.mount(ctx);
        if (!ctx.alive()) {
            mounted.dispose?.();
            return;
        }
        controller = mounted;
        $('#page').setAttribute('aria-busy', 'false');
        $('#page').replaceChildren(mounted.node);
        $('#refresh').disabled = !mounted.refresh;
        connection('Connected');
        // Keyboard and screen-reader users land on the new page's heading, not
        // on a stale control from the previous page.
        if (!fromMenu && document.activeElement === document.body) $('#title').focus();
        poll();
    } catch (error) {
        if (ctx.alive() && error.name !== 'AbortError') {
            $('#page').setAttribute('aria-busy', 'false');
            $('#page').replaceChildren(
                errorState({
                    error,
                    title: spec.title + ' could not be loaded',
                    retry: () => {
                        current = null;
                        return navigate();
                    }
                })
            );
            report(error);
        }
    }
}

function signedIn() {
    generation++;
    current = null;
    $('#login').hidden = true;
    $('#shell').hidden = false;
    text($('#identity'), api.session.username + ' · ' + api.session.role.toLowerCase());
    $('#identity').title = api.session.username + ' (' + api.session.role + ')';
    renderNavigation();
    expiryWarned = false;
    navigate();
}

async function signedOut() {
    generation++;
    menu(false);
    pageRequests?.abort();
    refreshJob = null;
    controller?.dispose?.();
    consolePage.reset();
    controller = null;
    api.session = null;
    current = null;
    clearTimeout(timer);
    $('#shell').hidden = true;
    $('#login').hidden = false;
    $('#page').replaceChildren();
    closeDialog(true);
    const form = $('#login-form'),
        submit = form.querySelector('button');
    submit.disabled = true;
    try {
        const setup = await api.request('setup');
        if (api.session) return;
        setupRequired = setup.required;
        text($('#login-title'), setupRequired ? 'Create administrator' : 'Sign in');
        text(
            $('#login-description'),
            setupRequired ? 'Enter the one-time code from the server console.' : 'WebUI access'
        );
        $('#setup-fields').hidden = !setupRequired;
        for (const key of ['passwordConfirmation', 'setupCode']) {
            form.elements[key].disabled = !setupRequired;
            form.elements[key].required = setupRequired;
        }
        form.elements.password.minLength = setupRequired ? 8 : 0;
        form.elements.password.autocomplete = setupRequired ? 'new-password' : 'current-password';
        text(submit, setupRequired ? 'Create administrator' : 'Sign in');
        text(
            $('#login-help'),
            setupRequired
                ? 'Use the setup code shown by this server process.'
                : 'Recover access in the server console: webui password <username>'
        );
    } catch (error) {
        text($('#login-error'), error.message);
    } finally {
        submit.disabled = false;
    }
}

$('#login-form').onsubmit = async (event) => {
    event.preventDefault();
    const form = event.currentTarget,
        submit = form.querySelector('button');
    submit.disabled = true;
    text($('#login-error'), '');
    try {
        if (setupRequired) {
            if (form.elements.password.value !== form.elements.passwordConfirmation.value)
                throw new Error('Passwords do not match.');
            await api.setup(
                form.elements.username.value,
                form.elements.password.value,
                form.elements.passwordConfirmation.value,
                form.elements.setupCode.value.trim()
            );
        } else await api.login(form.elements.username.value, form.elements.password.value);
        form.reset();
        signedIn();
    } catch (error) {
        text($('#login-error'), error.message);
    } finally {
        submit.disabled = false;
    }
};

$('#logout').onclick = async () => {
    if (controller?.isDirty?.() && !window.confirm('Discard the unsaved changes and sign out?'))
        return;
    if (!closeDialog()) return;
    try {
        await api.logout();
        await signedOut();
    } catch (error) {
        report(error);
    }
};

$('#refresh').prepend(uiSymbol('refresh'));
$('#refresh').onclick = refresh;
$('#menu').onclick = () => {
    menu(!$('#shell').classList.contains('menu-open'));
};
$('#close-navigation').onclick = $('#navigation-backdrop').onclick = () => menu(false, true);
mobile.addEventListener('change', () => menu(false));
document.addEventListener('keydown', (event) => {
    if (!$('#shell').classList.contains('menu-open')) return;
    if (event.key === 'Escape') {
        event.preventDefault();
        menu(false, true);
    } else if (event.key === 'Tab') {
        const controls = [...$('#sidebar').querySelectorAll('a[href], button:not(:disabled)')].filter(
            (node) => node.checkVisibility()
        );
        const first = controls[0],
            last = controls.at(-1);
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }
});
$('.skip').onclick = (event) => {
    event.preventDefault();
    menu(false);
    $('#main').focus();
};

/* Appearance shortcuts stay in the shell footer; preferences own the values. */
for (const [key, values, labels] of [
    ['theme', ['dark', 'light'], ['Light theme', 'Dark theme']],
    ['density', ['comfortable', 'compact'], ['Compact view', 'Comfortable view']]
]) {
    $('#' + key).onclick = () => {
        const value = values.find((v) => v !== prefs.read(key));
        prefs.write(key, value);
        text($('#' + key), labels[values.indexOf(prefs.read(key))]);
    };
}
function preferences() {
    text($('#theme'), prefs.read('theme') === 'dark' ? 'Light theme' : 'Dark theme');
    text($('#density'), prefs.read('density') === 'comfortable' ? 'Compact view' : 'Comfortable view');
}
prefs.subscribe(() => preferences());

const palette = createPalette({
    entries: () => [
        ...pages
            .filter((page) => page.rank <= rank())
            .map((page) => ({
                id: 'page-' + page.name,
                title: page.title,
                hint: page.section,
                group: 'Page',
                run: () => go(page.name)
            })),
        {
            id: 'refresh',
            title: 'Refresh this page',
            hint: 'Action',
            group: 'Action',
            run: () => refresh()
        },
        {
            id: 'theme',
            title: prefs.read('theme') === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme',
            hint: 'Appearance',
            group: 'Action',
            run: () => prefs.write('theme', prefs.read('theme') === 'dark' ? 'light' : 'dark')
        },
        {
            id: 'density',
            title: 'Toggle compact density',
            hint: 'Appearance',
            group: 'Action',
            run: () => prefs.write('density', prefs.read('density') === 'compact' ? 'comfortable' : 'compact')
        },
        {
            id: 'shortcuts',
            title: 'Keyboard shortcuts',
            hint: 'Help',
            group: 'Action',
            run: () => showShortcuts()
        },
        {
            id: 'logout',
            title: 'Sign out',
            hint: 'Session',
            group: 'Action',
            run: () => $('#logout').click()
        }
    ],
    lookup: async (query, signal) => {
        if (rank() < 1) return [];
        const groups = [];
        const [accountList, knightList, itemList] = await Promise.all([
            api.request('accounts', {}, false, signal).catch(() => []),
            api.request('knights-all', {}, false, signal).catch(() => []),
            rank() === 2
                ? api.request('items', { search: query }, false, signal).catch(() => [])
                : Promise.resolve([])
        ]);
        const needle = query.toLowerCase();
        const matching = (value) => String(value ?? '').toLowerCase().includes(needle);
        groups.push({
            group: 'Accounts',
            items: accountList
                .filter((account) => matching(account.username))
                .slice(0, 8)
                .map((account) => ({
                    id: 'account-' + account.username,
                    title: account.username,
                    hint: 'Account',
                    run: () => go('accounts', { username: account.username })
                }))
        });
        groups.push({
            group: 'Knights',
            items: knightList
                .filter((knight) => matching(knight.name) || matching(knight.username))
                .slice(0, 8)
                .map((knight) => ({
                    id: 'knight-' + knight.username + '-' + knight.name,
                    title: knight.name,
                    hint: knight.username,
                    run: () => go('knights', { username: knight.username, knight: knight.name })
                }))
        });
        if (itemList.length)
            groups.push({
                group: 'Items',
                items: itemList.slice(0, 8).map((item) => ({
                    id: 'item-' + item.config,
                    title: item.name,
                    hint: item.category || 'Item',
                    run: () => go('content', { search: item.name })
                }))
            });
        return groups.filter((group) => group.items.length);
    }
});

function showShortcuts() {
    const dialog = $('#shortcut-dialog');
    if (!dialog.open) dialog.showModal();
}
$('#search').prepend(uiSymbol('search'));
$('#search').onclick = (event) => palette.open('', { trigger: event.currentTarget });
$('#shortcuts').onclick = () => showShortcuts();
$('#shortcuts-close').onclick = () => $('#shortcut-dialog').close();
$('#shortcut-dialog').onclick = (event) => {
    if (event.target === $('#shortcut-dialog')) $('#shortcut-dialog').close();
};

const typing = (node) =>
    node instanceof HTMLElement &&
    (node.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(node.tagName));

document.addEventListener('keydown', (event) => {
    if (!api.session) return;
    const meta = event.ctrlKey || event.metaKey;
    if (meta && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        palette.open('', { trigger: typing(event.target) ? null : event.target });
        return;
    }
    if (typing(event.target) || event.altKey || meta) return;
    if (event.key === '?') {
        event.preventDefault();
        showShortcuts();
    } else if (event.key === '/' && !$('#dialog').open) {
        const field = $('#page').querySelector('input[type="search"], .table-tools input');
        if (field) {
            event.preventDefault();
            field.focus();
            field.select?.();
        }
    } else if (event.key === 'r' && !$('#dialog').open) {
        event.preventDefault();
        refresh();
    } else if (event.key === 't' && !$('#dialog').open) {
        event.preventDefault();
        prefs.write('theme', prefs.read('theme') === 'dark' ? 'light' : 'dark');
    }
});

window.addEventListener('hashchange', navigate);
window.addEventListener('beforeunload', (event) => {
    if (controller?.isDirty?.() || dialogDirty()) {
        event.preventDefault();
        event.returnValue = '';
    }
});
document.addEventListener('session-expired', async () => {
    toast('Your session ended. Sign in again to continue.', { type: 'error' });
    await signedOut();
});
document.addEventListener('data-changed', () => refresh());
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && api.session) refresh();
});
window.addEventListener('online', () => {
    connection('Connected');
    if (api.session) refresh();
});
window.addEventListener('offline', () => {
    connection('Offline', false);
    toast('This browser went offline. Changes cannot be saved until it reconnects.', {
        type: 'error'
    });
});

/* Sessions expire after 30 idle minutes; warn while there is still time to save. */
setInterval(async () => {
    if (!api.session || document.hidden) return;
    try {
        const session = await api.request('session');
        if (!api.session) return;
        api.session.expires = session.expires;
        const left = session.expires - Date.now();
        if (left < 5 * 60_000 && left > 0 && !expiryWarned) {
            expiryWarned = true;
            toast('Your session expires in a few minutes.', {
                type: 'info',
                detail: 'Saving a change or refreshing the page extends it.',
                timeout: 15000
            });
        } else if (left > 10 * 60_000) {
            expiryWarned = false;
        }
    } catch {
        /* The next poll reports an expired session. */
    }
}, 240000);

preferences();
clock();
setInterval(clock, 30000);
await loadAssets();
try {
    await api.restore();
    signedIn();
} catch {
    await signedOut();
}
