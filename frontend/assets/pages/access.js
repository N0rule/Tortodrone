import { api } from '../api.js';
import { el, text, toolbar, button, routeLink, badge, panel, facts, date, duration } from '../ui.js';
import { DataTable } from '../components/table.js';
import { openDialog, edit, confirm, field, closeDialog } from '../components/dialog.js';
import { route, go } from '../router.js';
export async function mount(ctx) {
    const node = el('div', null, 'stack');
    let rows = [],
        inspected = null;
    const passwords = () => [
        field('password', 'New password', '', 'password', null, { minLength: 8, maxLength: 128 }),
        field('passwordConfirmation', 'Confirm password', '', 'password', null, {
            minLength: 8,
            maxLength: 128
        })
    ];
    /**
     * One spelling of a role in the whole page. The table printed `administrator`, the
     * card beside it printed the raw enum `ADMINISTRATOR` and the editor's choices
     * printed caps, so the same account wore three names on one screen. The enum is
     * what the server stores; the words below are what the operator reads and picks.
     */
    const ROLES = ['VIEWER', 'MODERATOR', 'ADMINISTRATOR'];
    const roleName = (value) =>
        ({ ADMINISTRATOR: 'Administrator', MODERATOR: 'Moderator', VIEWER: 'Viewer' })[
            String(value || '').toUpperCase()
        ] || String(value || 'Unavailable');
    // The picker shows words and submits the enum the server stores.
    const role = (value) =>
        field(
            'role',
            'Role',
            String(value || '').toUpperCase(),
            'text',
            ROLES.map((name) => [name, roleName(name)])
        );
    /** Rewrite this page's address, keeping the params this action does not own. */
    function here(changes = {}, drop = []) {
        const params = route().params;
        for (const name of drop) params.delete(name);
        for (const [name, value] of Object.entries(changes)) params.set(name, String(value));
        go('webui-users', Object.fromEntries(params));
    }
    // A plain role is a word; only the two elevated roles are worth an edge.
    // A chip is a word, not a shout: the sidebar prints the same role in the same
    // casing, and privilege wears the purple every role chip wears.
    const roleBadge = (value) => {
        const node = badge(
            String(roleName(value)).toLowerCase(),
            value === 'ADMINISTRATOR' ? 'purple' : value === 'MODERATOR' ? 'cyan' : ''
        );
        node.title = roleName(value);
        return node;
    };
    const resetPassword = (row) =>
        edit('Password · ' + row.username, 'webui-user-password', passwords(), {
            username: row.username,
            revision: row.revision
        });
    const changeRole = (row) =>
        edit('Role · ' + row.username, 'webui-user-role', [role(row.role)], {
            username: row.username,
            revision: row.revision
        });
    const revoke = (row) =>
        confirm(
            'Revoke ' + row.username,
            'webui-user-delete',
            { username: row.username, revision: row.revision },
            [field('nameConfirmation', 'Type ' + row.username)],
            'Removes this WebUI identity; any session it still holds is refused on its ' +
                'next request. This cannot be undone.'
        );
    const createIdentity = () =>
        edit('Create WebUI identity', 'webui-user-create', [
            field('username', 'Username', '', 'text', null, {
                minLength: 3,
                maxLength: 64,
                pattern: '(?:[a-zA-Z0-9_]|-){3,64}'
            }),
            ...passwords(),
            role('VIEWER')
        ]);
    /**
     * Closing the deep-linked inspection drops `?user=`, so the address never names
     * an identity that is no longer on screen. A replacement dialog opens in the
     * same tick, so the check is deferred and skipped while one is open.
     */
    function unlinkOnClose() {
        const dialog = document.querySelector('#dialog'),
            previous = dialog.onclose;
        dialog.onclose = (event) => {
            previous?.call(dialog, event);
            setTimeout(() => {
                if (dialog.open || !route().params.has('user')) return;
                here({}, ['user']);
            }, 0);
        };
    }
    /** One identity, its role and every action that changes it, in one place. */
    function inspect(row) {
        const content = el('div', null, 'stack');
        content.append(
            facts([
                ['Username', row.username],
                ['Role', roleBadge(row.role)]
            ]),
            el(
                'p',
                'The identity store keeps no creation or last sign-in timestamp; the ' +
                    'audit trail records every privileged action this identity performs.',
                'muted prose-note'
            ),
            toolbar(
                button('Back to identities', () => here({}, ['user']), 'secondary'),
                routeLink(
                    'Audit for this identity',
                    'audit',
                    { actor: row.username },
                    'button secondary'
                ),
                button('Reset password', () => resetPassword(row)),
                button('Change role', () => changeRole(row), 'secondary', () => !lastAdmin(row), {
                    unavailable: LAST_ADMIN
                }),
                button('Revoke identity', () => revoke(row), 'danger', () => !lastAdmin(row), {
                    unavailable: LAST_ADMIN
                })
            )
        );
        openDialog({
            title: row.username,
            content,
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        unlinkOnClose();
    }
    // "At least one administrator must remain" is a rule, not an error message: the
    // page states it and the two controls it forbids say it too.
    const LAST_ADMIN = 'At least one WebUI administrator must remain.';
    // The store ships the role as the enum name; the badge lowercases it for display.
    const isAdmin = (row) => String(row?.role || '').toUpperCase() === 'ADMINISTRATOR';
    const lastAdmin = (row) => isAdmin(row) && rows.filter(isAdmin).length <= 1;
    const table = new DataTable(
        [
            ['Username', (r) => r.username],
            // The role is the role. A link to the audit trail sat beside the badge, so
            // the column of roles read "Administrator Audit" and named a place where it
            // had promised a permission; the trail is reachable from the actions.
            ['Role', (r) => roleBadge(r.role), (r) => r.role]
        ],
        {
            key: (r) => r.username,
            id: 'webui-users',
            caption: 'WebUI identities',
            searchPlaceholder: 'Search username or role',
            exportName: 'webui-identities',
            // A list of logins opens in username order: the server hands them over in
            // whatever order the config file held, and no header declared an order.
            defaultSort: { index: 0 },
            empty: {
                title: 'No WebUI identities yet',
                hint: 'A WebUI identity is one administrator login; it is separate from a game account.',
                action: { label: 'Create identity', run: () => createIdentity() },
                icon: 'webui-users'
            },
            filters: [
                [
                    // The enum is the value the server speaks; the label is what a person
                    // reads. Three spellings of one role ("ADMINISTRATOR" in the filter,
                    // "Administrator" on the badge) made the page look unfinished.
                    'Role',
                    [
                        ['All', 'All roles'],
                        ['ADMINISTRATOR', 'Administrator'],
                        ['MODERATOR', 'Moderator'],
                        ['VIEWER', 'Viewer']
                    ],
                    (r, value) => value === 'All' || r.role === value
                ]
            ],
            // Two actions in the row: the rest of the identity's own controls live in
            // the inspector the first one opens, which is also where they can be read
            // beside the facts they change.
            actions: (get) => [
                // The page's own address, so an inspection does not rename the route.
                routeLink('Inspect', 'access', { user: get().username }, 'button secondary'),
                // The audit trail is the only history an identity has, so it is one
                // press away — but as an action, worded as the place it opens.
                routeLink('Audit trail', 'audit', { actor: get().username }, 'button secondary'),
                button('Revoke', () => revoke(get()), 'danger', () => !lastAdmin(get()), {
                    showReason: true,
                    unavailable: LAST_ADMIN
                })
            ]
        }
    );
    // What the server enforces for this browser session, so an administrator can
    // confirm the transport and cookie protections without reading the config.
    const expiry = el('dd'),
        sessionFacts = facts([
            ['Signed in as', api.session?.username || 'Unavailable'],
            ['Role', roleName(api.session?.role)],
            ['Session expiry', ''],
            [
                'Cookie',
                location.protocol === 'https:'
                    ? 'HttpOnly, SameSite, Secure over HTTPS'
                    : 'HttpOnly, SameSite over plain HTTP'
            ],
            ['Request checks', 'Host, Origin, CSRF token and role on every request'],
            ['Login limit', 'Ten attempts per minute per remote address'],
            ['Audit', 'Every privileged action is recorded with its outcome']
        ]);
    // Replace the placeholder description value with a live element.
    const expirySlot = [...sessionFacts.querySelectorAll('dt')].find(
        (term) => term.textContent === 'Session expiry'
    )?.nextElementSibling;
    if (expirySlot) {
        expirySlot.replaceWith(expiry);
        expiry.id = 'session-expiry';
    }
    const sessionPanel = panel('Your session and transport', sessionFacts);
    const describeExpiry = () => {
        const expires = api.session?.expires;
        if (!expires) return 'Unavailable';
        // The same span in the same unit everywhere: "479 minutes" beside the listener's
        // "2h 2m 57s" asked the operator to convert one of them.
        const seconds = Math.max(0, Math.round((expires - Date.now()) / 1000));
        return (
            date(expires) +
            (seconds > 60 ? ` (${duration(seconds)} left)` : ' (renewing on the next request)')
        );
    };
    node.append(
        toolbar(button('Create WebUI identity', () => createIdentity(), 'primary')),
        el(
            'p',
            'Each identity is one WebUI login. Resetting its password or changing its ' +
                'role ends every session it holds; there is no separate ' +
                'sign-out-everywhere action. At least one administrator must remain.',
            'muted prose-note'
        ),
        table.node,
        // The page is the identities; the session that is reading them is context, so it
        // sits after the list instead of pushing the first row 700px down.
        sessionPanel
    );
    async function refresh() {
        text(expiry, describeExpiry());
        rows = await ctx.request('webui-users');
        table.update(rows);
    }
    await refresh();
    /** `?user=` opens one identity without a reload; losing it closes the pane. */
    function applyParams(params) {
        const wanted = params.get('user');
        if (wanted === inspected) return;
        const previous = inspected;
        inspected = wanted;
        const row = rows.find((candidate) => candidate.username === wanted);
        if (!row) {
            if (wanted) ctx.notify(`No WebUI identity is named ${wanted}.`, true);
            // Losing `?user=` closes the identity pane; without this, history Back left
            // it describing a user the address no longer named.
            else if (previous) closeDialog();
            return;
        }
        inspect(row);
    }
    applyParams(ctx.params);
    return {
        node,
        refresh,
        navigate: applyParams,
        // The session panel prints how long the session has left, from `api.session.expires`. Without an
        // interval the page computed it once at mount and froze, and `app.js` merges a
        // fresh session without repainting, so an operator watched an hour-old number.
        interval: 60_000
    };
}
