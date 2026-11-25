import { symbol as uiSymbol } from './components/symbols.js';
import { api } from './api.js';
import { $, el, button, text, notify, report, calendarDate, date, zoneName, toolbar } from './ui.js';
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
        // `#mail` is what people type and what older notes link to.
        alias: ['mail'],
        description: 'Campaigns, direct mail and the new-knight welcome letter.'
    },
    {
        name: 'news',
        title: 'Uplink News',
        section: 'Content',
        rank: 2,
        module: news,
        description: 'Banners and the welcome chat message.'
    },
    {
        name: 'content',
        title: 'Content Catalogue',
        section: 'Content',
        rank: 2,
        module: content,
        description: 'Validated item catalogue, merchant stock and content warnings.'
    },
    {
        name: 'console',
        title: 'Console',
        section: 'Operations',
        rank: 2,
        module: consolePage,
        // The terminal's bar names the pane, its footer lists the keys and `help` lists
        // the commands, so a sentence describing the page above the screen was the third
        // copy of the same fact - and it is the only page whose own surface is the whole
        // screen.
        description: ''
    },
    {
        name: 'audit',
        title: 'Audit Trail',
        section: 'Operations',
        rank: 2,
        module: audit,
        description: 'Privileged requests and their recorded outcomes, newest first.'
    },
    {
        name: 'webui-users',
        title: 'WebUI Access',
        section: 'Operations',
        rank: 2,
        module: access,
        // `#access` is the name links and the docs use; keep both spellings working.
        alias: ['access'],
        description: 'Identities and roles.'
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
// Matches the 900px card-and-sidebar breakpoint in layout.css: between 761px and
    // 900px the sidebar is a drawer, so the toggle has to open it there too.
const mobile = matchMedia('(max-width: 900px)');
const pageFor = (name) =>
    pages.find(
        (page) =>
            (page.name === name || page.alias?.includes(name)) && page.rank <= rank()
    );

function announce(message) {
    text($('#page-status'), message);
}

function menu(open, restoreFocus = false) {
    open = open && mobile.matches;
    $('#shell').classList.toggle('menu-open', open);
    $('#menu').setAttribute('aria-expanded', String(open));
    $('.workspace').inert = open;
    if (open) {
        // Fourteen destinations do not fit every phone, and the drawer scrolled with no
        // sign that it did: "Content Catalogue" was cut mid-glyph and the last three
        // pages might as well not have existed. The fade appears only when there is
        // more below it.
        const nav = $('#navigation');
        $('#sidebar').classList.toggle('nav-overflows', nav.scrollHeight > nav.clientHeight + 2);
        $('#close-navigation').focus();
    } else {
        $('#sidebar').classList.remove('nav-overflows');
        if (restoreFocus) $('#menu').focus();
    }
}

/**
 * When the shell last read anything. The pill used to assert "Connected" forever after
 * any successful mount, including on a page with no refresh hook at all, so the one
 * status the operator trusts most was the one never aged.
 */
let lastRead = 0,
    readState = 'Connected';

function connection(value, online = true) {
    const node = $('#connection');
    readState = value;
    if (online) lastRead = Date.now();
    node.classList.toggle('online', online);
    ageConnection();
}

/**
 * The pill reads the banner, not the fact that a function returned.
 *
 * A page's mount can catch its own failed read, raise the notice and return normally:
 * the shell then painted "Connected" over "The server did not answer" until the first
 * refresh corrected it, which is the one moment the operator is deciding whether to
 * believe the page at all.
 */
function noticeRaised() {
    const notice = $('#notice');
    return !!notice && !notice.hidden && notice.classList.contains('error');
}

function ageConnection() {
    const node = $('#connection'),
        age = lastRead ? Date.now() - lastRead : Infinity;
    // Overview and Players put the server's own state in this pill instead of the
    // transport's, so only the "Connected" reading is aged — but every reading says
    // when it was last refreshed.
    if (readState === 'Connected') {
        text(node, age > 60_000 ? `Connected · ${Math.round(age / 60_000)}m ago` : 'Connected');
    } else text(node, readState);
    node.title = lastRead
        ? `Last read from the server ${
              age < 5_000 ? 'just now' : Math.round(age / 1000) + 's ago'
          }.`
        : 'Nothing has been read from the server since this page opened.';
}

function clock() {
    const now = new Date(),
        node = $('#clock');
    // The zone is part of a time, and it comes from the same formatter the rows use: the
    // header once spelled the zone "GMT+2" over rows that spelled it "EET".
    text(node, prefs.read('clock') === 'date-time' ? date(now) : calendarDate(now) + ' ' + zoneName(now));
    node.dateTime = now.toISOString();
    node.title = `This browser's clock${
        Intl.DateTimeFormat().resolvedOptions().timeZone
            ? ' · ' + Intl.DateTimeFormat().resolvedOptions().timeZone
            : ''
    }. Rows are stamped by the server.`;
    ageConnection();
}

/**
 * `polled` distinguishes the ten-second tick from the operator pressing Refresh. A page
 * that can be paused needs both: the header's Refresh is a deliberate read and must go
 * through, while the tick is what a pause is for. A page that has no opinion gets the
 * same read either way — `poll()` falls back to `refresh()`.
 */
async function refresh(polled = false) {
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
    // A refetch of a table used to look like a dead control: the only busy state the
    // shell had was the first paint's skeleton.
    $('#page').classList.add('refreshing');
    job.promise = (async () => {
        const notice = $('#notice');
        try {
            if (polled && target.poll) await target.poll();
            else await target.refresh();
            // A page that catches its own failed read returns normally, so the notice it
            // raised is the only evidence the server did not answer: the pill said
            // "Connected" beside a banner that said the server was not answering.
            if (id === generation && !noticeRaised()) connection('Connected');
            // A recovered poll ends the error it raised; a sticky notice that
            // outlives the failure trains operators to ignore notices.
            if (notice && !notice.hidden && notice.classList.contains('error')) {
                notice.hidden = true;
                notice.classList.remove('error');
            }
        } catch (error) {
            if (id === generation && error.name !== 'AbortError') {
                connection('Reconnecting', false);
                // A failed poll read nothing and wrote nothing.
                report(error, { read: true });
            }
        } finally {
            if (id === generation) {
                $('#refresh').disabled = false;
                $('#page').classList.remove('refreshing');
            }
            if (refreshJob === job) {
                refreshJob = null;
                if (job.repeat && id === generation) refresh(polled);
            }
        }
    })();
    return job.promise;
}

function poll() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
        const id = generation;
        if (api.session && !document.hidden) await refresh(true);
        if (api.session && id === generation) poll();
    }, (controller?.interval || 15000) * prefs.read('pollScale'));
}

/**
 * The sidebar: grouped by section, each group labelled, each link named.
 *
 * Painting and building are separate so one failing link cannot leave the column blank —
 * a navigation that throws is a shell with no way out of it. That is exactly what an
 * empty art pack produced: `symbol()` answers null for every job when the pack is empty
 * (the normal state of a server started with `--skip-client-assets`), and the unguarded
 * `setAttribute` on that null threw inside `flatMap`, before `replaceChildren` was ever
 * called. The links fall back to words, the way `ui.js` already treats a missing glyph:
 * an invented icon is worse than a label.
 */
function renderNavigation() {
    const nav = $('#navigation');
    try {
        nav.replaceChildren(...navigationGroups());
    } catch (error) {
        // Words beat a blank column: the operator can still reach every page, and the
        // failure is reported instead of hidden in a console nobody has open.
        nav.replaceChildren(
            ...pages
                .filter((page) => page.rank <= rank())
                .map((page) => {
                    const link = el('a', page.title);
                    link.href = '#' + page.name;
                    link.dataset.page = page.name;
                    return link;
                })
        );
        report(error);
    }
}

function navigationGroups() {
    return sections.flatMap((section) => {
        const visible = pages.filter((page) => page.section === section && page.rank <= rank());
        if (!visible.length) return [];
        const group = el('div', null, 'nav-section');
        // A labelled group keeps the page heading as the only heading in the shell, so
        // the outline starts at h1 and screen readers still hear the section name for
        // each run of links.
        const heading = el('p', section, 'nav-heading');
        heading.id = 'nav-' + section.toLowerCase().replace(/\s+/g, '-');
        group.append(heading);
        const list = el('div', null, 'nav-list');
        list.setAttribute('role', 'group');
        list.setAttribute('aria-labelledby', heading.id);
        // The artwork pack exports one image under three names (nav-console,
        // nav-settings, nav-webui-users), and the rail draws it on all three: they are
        // the console's own three administration screens, so one mark for the three reads
        // as a family. Hiding it on two of them left two empty slots in a column of
        // thirteen glyphs, which reads as a failed image instead.
        for (const page of visible) {
            const link = el('a');
            const icon = uiSymbol(page.name);
            if (icon) {
                icon.setAttribute('aria-hidden', 'true');
                link.append(icon);
            }
            link.append(el('span', page.title));
            link.href = '#' + page.name;
            link.dataset.page = page.name;
            list.append(link);
        }
        group.append(list);
        return [group];
    });
}

/**
 * A refused address is a page, not a notice: say which page was asked for, why it is
 * not here, and what the operator can do about it.
 *
 * The card is *state*, not a node written into `#page`. Written directly it lasted one
 * task: the redirect below re-entered `navigate()`, which swaps a skeleton and then
 * Overview into the same container before the browser paints, so the reason an
 * operator's link failed never appeared at all. The paint helpers below prepend it,
 * and any real navigation clears it.
 */
let refusal = null;
/**
 * Drop the card and repaint what is underneath it.
 *
 * A refusal always ends by rewriting the address to `#overview`, so the card's own
 * "Open Overview" navigates to the address it is already on: no `hashchange` fires, and
 * the banner stayed over the page it offered to take you to. Clearing it here is what
 * makes that button do what it says.
 */
function clearRefusal() {
    if (!refusal) return false;
    refusal = null;
    if (controller?.node) paintPage(controller.node);
    return true;
}
function showRefusal({ title, message }) {
    const card = el('div', null, 'stack refusal');
    card.append(
        el('h2', title),
        el('p', message, 'muted'),
        toolbar(
            button(
                'Open Overview',
                () => {
                    clearRefusal();
                    go('overview');
                },
                'primary'
            ),
            button(
                'Search pages',
                () => {
                    clearRefusal();
                    $('#search').click();
                },
                'secondary'
            )
        )
    );
    refusal = card;
    announce('Refused: ' + title);
}
/** Paint the page container, keeping a live refusal above whatever it holds. */
function paintPage(...nodes) {
    const page = $('#page');
    page.replaceChildren(...(refusal ? [refusal, ...nodes] : nodes));
    // One arrival, not a dozen: the page fades in as a whole instead of every card
    // animating on its own. Reduced motion turns this off with every other transition.
    page.classList.remove('page-enter');
    requestAnimationFrame(() => page.classList.add('page-enter'));
}

async function navigate() {
    if (!api.session) return;
    const next = route(),
        spec = pageFor(next.name);
    if (!spec) {
        // A denied link is not an empty click: say which role the page needs, in a
        // notice the redirect cannot clear.
        // `#access` and `#mail` are aliases: matching the raw name told a moderator
        // that a page they were looking at does not exist. Resolve the alias first,
        // and only call it unknown when no page owns that spelling.
        const denied =
            pages.find((page) => page.name === next.name || page.alias?.includes(next.name)) ||
            null;
        const needs = ['a viewer', 'a moderator', 'an administrator'][denied?.rank ?? 2];
        // A refusal is not a moment: it is a page. The notice was hidden again by the
        // redirect it caused, so an operator who mistyped a link saw Overview and no
        // reason for it. The card stays until they ask for something else.
        showRefusal(
            denied
                ? {
                      title: denied.title,
                      message: `That page needs ${needs} role, which this session does not hold. Overview is shown below instead.`
                  }
                : {
                      title: `There is no page called “${next.name}”`,
                      message:
                          'Nothing is stored under that link. The pages are listed in the sidebar, and Ctrl K searches them.'
                  }
        );
        if (location.hash !== '#overview') window.history.replaceState(null, '', '#overview');
        acceptedHash = '#overview';
        current = null;
        return navigate();
    }
    // `#dashboard` is an alias of Overview, and the router has already resolved it: the
    // address has to be rewritten from the raw hash, or it keeps saying dashboard.
    if (!['#' + spec.name, '#' + (spec.alias?.[0] ?? spec.name)].includes(location.hash.split('?')[0])) {
        const query = next.params.toString();
        const hash = '#' + spec.name + (query ? '?' + query : '');
        if (location.hash !== hash) window.history.replaceState(null, '', hash);
        acceptedHash = hash;
    }
    if (
        current &&
        spec.name !== current &&
        controller?.isDirty?.() &&
        !window.confirm('Discard the unsaved changes on this page?')
    ) {
        location.hash = acceptedHash;
        return;
    }
    // Only a page change has to close the open editor. A link inside the same page
    // that names a record (a mailing, an offer) is handled by that page's navigate,
    // and closing the dialog first is what made those links look inert.
    if (location.hash !== acceptedHash && current !== spec.name && !closeDialog()) {
        location.hash = acceptedHash;
        return;
    }
    acceptedHash = location.hash;
    const fromMenu = $('#shell').classList.contains('menu-open');
    menu(false);
    if (fromMenu) $('#main').focus();
    for (const a of $('#navigation').querySelectorAll('a'))
        // The nav names the page, not the spelling in the address: `#access` still
        // highlights WebUI Access.
        a.setAttribute('aria-current', a.dataset.page === spec.name ? 'page' : 'false');
    text($('#title'), spec.title);
    text($('#page-description'), spec.description ?? '');
    document.title = spec.title + ' · Tortodrone';
    $('#page').setAttribute('aria-label', spec.title);
    announce(spec.title + ' page');
    if (current === spec.name && controller) {
        if (controller.navigate) {
            try {
                await controller.navigate(next.params);
            } catch (error) {
                if (error.name !== 'AbortError') report(error, { read: true });
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
    current = spec.name;
    clearTimeout(timer);
    const placeholder = skeleton({ rows: 5, columns: 4, label: 'Loading ' + spec.title });
    // `loading` keeps the documented acceptance contract that waits for the
    // placeholder to disappear before it inspects a page.
    placeholder.classList.add('loading');
    $('#page').setAttribute('aria-busy', 'true');
    paintPage(placeholder);
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
        // An expired session is recoverable from a page, not only by a reload: the
        // console offers "Sign in again" through this.
        reauth: signedOut,
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
        paintPage(mounted.node);
        $('#refresh').disabled = !mounted.refresh;
        if (noticeRaised()) connection('Reconnecting', false);
        else connection('Connected');
        // Keyboard and screen-reader users land on the new page's heading, not
        // on a stale control from the previous page.
        if (!fromMenu && document.activeElement === document.body) $('#title').focus();
        poll();
    } catch (error) {
        if (ctx.alive() && error.name !== 'AbortError') {
            $('#page').setAttribute('aria-busy', 'false');
            paintPage(
                errorState({
                    error,
                    title: spec.title + ' could not be loaded',
                    retry: () => {
                        current = null;
                        return navigate();
                    }
                })
            );
            // A page that never mounted is the one failure this shell paints itself, and
            // the pill has to say so: the banner alone left "Connected" over a page that
            // could not be loaded at all.
            connection('Reconnecting', false);
            report(error, { read: true });
        }
    }
}

function signedIn() {
    generation++;
    current = null;
    refusal = null;
    $('#login').hidden = true;
    $('#shell').hidden = false;
    // The name and the role are two things, so they are two elements: a middot
    // between them made one string that wrapped mid-sentence in a 220px column.
    text($('#identity-name'), api.session.username);
    text($('#identity-role'), String(api.session.role || '').toLowerCase());
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
    // A greyed-out "Sign in" reads as broken; the spinner says it is working.
    submit.disabled = true;
    submit.setAttribute('aria-busy', 'true');
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
        submit.removeAttribute('aria-busy');
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

// `() => refresh()`, never `refresh`: an onclick handler receives the click `Event`,
// which is truthy, so `refresh(event)` read as "this is the poll" and sent the
// operator's own Refresh through `poll()` — a dead button on any page that pauses.
$('#refresh').onclick = () => refresh();
$('#navigation').addEventListener('click', () => clearRefusal());
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

/* Appearance shortcuts stay in the shell footer; preferences own the values. The
   label names the setting and the switch reports whether it is on. */
const APPEARANCE = [
    ['theme', 'dark', 'light'],
    ['density', 'comfortable', 'compact']
];
function preferences() {
    for (const [key, off, on] of APPEARANCE) {
        const node = $('#' + key);
        if (!node) continue;
        // The title names what a click does, so it can never contradict the switch
        // it sits on (a light theme used to offer to "switch the light theme").
        const on2 = prefs.read(key) === on;
        // The switch reports its own state. It never did: the markup's `aria-checked`
        // stayed at the value it shipped with, so a screen reader heard "off" on a
        // light theme and the checked styling never applied.
        node.setAttribute('aria-checked', String(on2));
        node.title =
            key === 'theme'
                ? on2
                    ? 'Switch to the dark theme'
                    : 'Switch to the light theme'
                : on2
                  ? 'Switch to comfortable density'
                  : 'Switch to compact density';
    }
}
for (const [key, off, on] of APPEARANCE) {
    $('#' + key).onclick = () => prefs.write(key, prefs.read(key) === on ? off : on);
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
                icon: page.name,
                group: 'Page',
                run: () => go(page.name)
            })),
        {
            // The key an operator copies to add this server on another machine used
            // to be reachable only by knowing it lives in an Overview panel. Palette
            // matching covers `title` and `hint`, so the words people actually type
            // (key, fingerprint, modulus, public) all land here.
            id: 'identity',
            title: 'Server public key',
            hint: 'SSH key',
            icon: 'overview',
            group: 'Page',
            run: () => go('overview', { panel: 'identity' })
        },
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
                    art: item.config,
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

window.addEventListener('hashchange', () => {
    // A refusal explains the address that produced it. Any other address is a new
    // question, so the answer goes with it.
    refusal = null;
    navigate();
});
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
        // Take the whole session, not just its expiry: a rotated CSRF token lives
        // here, and holding a stale one makes every write fail until a reload.
        api.session = { ...api.session, ...session };
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
