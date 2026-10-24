import { symbol as uiSymbol } from './components/symbols.js';
import { api } from './api.js';
import { $, el, button, text, notify, report } from './ui.js';
import { route, go } from './router.js';
import { loadAssets } from './components/items.js';
import { dialogDirty, closeDialog } from './components/dialog.js';
import * as overview from './pages/overview.js';
import * as players from './pages/players.js';
import * as accounts from './pages/accounts.js';
import * as knights from './pages/knights.js';
import * as depot from './pages/depot.js';
import * as auctions from './pages/auctions.js';
import * as news from './pages/news.js';
import * as content from './pages/content.js';
import * as consolePage from './pages/console.js';
import * as logs from './pages/logs.js';
import * as access from './pages/access.js';
import * as audit from './pages/audit.js';

const pages = [
    ['overview', 'Overview', null, 0, overview],
    ['players', 'Online Players', null, 0, players],
    ['accounts', 'Accounts', null, 1, accounts],
    ['knights', 'Knights', null, 1, knights],
    ['depot', 'Supply Depot', null, 2, depot],
    ['auctions', 'Auction House', null, 2, auctions],
    ['news', 'Uplink News', null, 2, news],
    ['content', 'Content Catalogue', null, 2, content],
    ['console', 'Console', null, 2, consolePage],
    ['logs', 'Logs', null, 0, logs],
    ['webui-users', 'WebUI Access', null, 2, access],
    ['audit', 'Audit Trail', null, 2, audit]
];
let generation = 0,
    current,
    controller,
    timer,
    refreshJob,
    pageRequests,
    acceptedHash = location.hash,
    setupRequired = false;
const rank = () => ['VIEWER', 'MODERATOR', 'ADMINISTRATOR'].indexOf(api.session?.role);
function connection(value, online = true) {
    text($('#connection'), value);
    $('#connection').classList.toggle('online', online);
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
    }, controller?.interval || 15000);
}
async function navigate() {
    if (!api.session) return;
    const next = route(),
        spec = pages.find((p) => p[0] === next.name && p[3] <= rank());
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
    $('#shell').classList.remove('menu-open');
    $('#menu').setAttribute('aria-expanded', 'false');
    for (const a of $('#navigation').children)
        a.setAttribute('aria-current', a.dataset.page === next.name ? 'page' : 'false');
    text($('#title'), spec[1]);
    document.title = spec[1] + ' · Tortodrone';
    $('#page').setAttribute('aria-label', spec[1]);
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
    pageRequests?.abort();
    const requests = new AbortController();
    pageRequests = requests;
    refreshJob = null;
    controller?.dispose?.();
    controller = null;
    current = next.name;
    clearTimeout(timer);
    $('#page').replaceChildren(el('div', 'Loading…', 'loading'));
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
        alive: () => generation === id && !!api.session,
        request: async (...args) => {
            const value = await api.request(args[0], args[1], false, requests.signal);
            if (generation !== id || !api.session)
                throw new DOMException('Page changed', 'AbortError');
            return value;
        }
    };
    try {
        const mounted = await spec[4].mount(ctx);
        if (!ctx.alive()) {
            mounted.dispose?.();
            return;
        }
        controller = mounted;
        $('#page').replaceChildren(mounted.node);
        connection('Connected');
        poll();
    } catch (error) {
        if (ctx.alive() && error.name !== 'AbortError') {
            $('#page').replaceChildren(
                el('p', error.message, 'error'),
                button('Retry', () => {
                    current = null;
                    return navigate();
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
    $('#navigation').replaceChildren(
        ...pages
            .filter((p) => p[3] <= rank())
            .map(([name, title]) => {
                const a = el('a'),
                    symbol = uiSymbol(name);
                symbol.setAttribute('aria-hidden', 'true');
                a.append(symbol, el('span', title));
                a.href = '#' + name;
                a.dataset.page = name;
                return a;
            })
    );
    navigate();
}
async function signedOut() {
    generation++;
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
$('#refresh').onclick = refresh;
$('#menu').onclick = () => {
    const open = $('#shell').classList.toggle('menu-open');
    $('#menu').setAttribute('aria-expanded', open);
};
function preferences() {
    text(
        $('#theme'),
        document.documentElement.dataset.theme === 'dark' ? 'Light theme' : 'Dark theme'
    );
    text(
        $('#density'),
        document.documentElement.dataset.density === 'comfortable'
            ? 'Compact view'
            : 'Comfortable view'
    );
}
for (const [key, values] of [
    ['theme', ['dark', 'light']],
    ['density', ['comfortable', 'compact']]
]) {
    $('#' + key).onclick = () => {
        const value = values.find((v) => v !== document.documentElement.dataset[key]);
        document.documentElement.dataset[key] = value;
        try {
            localStorage.setItem('tortodrone-' + key, value);
        } catch {}
        preferences();
    };
}
window.addEventListener('hashchange', navigate);
window.addEventListener('beforeunload', (event) => {
    if (controller?.isDirty?.() || dialogDirty()) {
        event.preventDefault();
        event.returnValue = '';
    }
});
document.addEventListener('session-expired', signedOut);
document.addEventListener('data-changed', () => refresh());
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && api.session) refresh();
});
preferences();
text(
    $('#clock'),
    new Date().toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
);
await loadAssets();
try {
    await api.restore();
    signedIn();
} catch {
    await signedOut();
}
