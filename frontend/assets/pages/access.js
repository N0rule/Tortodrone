import { api } from '../api.js';
import { el, text, toolbar, button, badge, panel, facts, date } from '../ui.js';
import { DataTable } from '../components/table.js';
import { openDialog, edit, confirm, field } from '../components/dialog.js';
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
    const role = (value) =>
        field('role', 'Role', value, 'text', ['VIEWER', 'MODERATOR', 'ADMINISTRATOR']);
    /** Rewrite this page's address, keeping the params this action does not own. */
    function here(changes = {}, drop = []) {
        const params = route().params;
        for (const name of drop) params.delete(name);
        for (const [name, value] of Object.entries(changes)) params.set(name, String(value));
        go('webui-users', Object.fromEntries(params));
    }
    const roleBadge = (value) =>
        badge(
            value.toLowerCase(),
            value === 'ADMINISTRATOR' ? 'gold' : value === 'MODERATOR' ? 'cyan' : ''
        );
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
                'muted'
            ),
            toolbar(
                button('Back to identities', () => here({}, ['user']), 'secondary'),
                button(
                    'Audit for this identity',
                    () => ctx.go('audit', { actor: row.username }),
                    'secondary'
                ),
                button('Reset password', () => resetPassword(row)),
                button('Change role', () => changeRole(row)),
                button('Revoke identity', () => revoke(row), 'danger')
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
    const table = new DataTable(
        [
            ['Username', (r) => r.username],
            [
                // The audit trail is the only history of an identity, so the jump to
                // it sits with the role instead of hiding in the actions column.
                'Role',
                (r) =>
                    toolbar(
                        roleBadge(r.role),
                        button(
                            'Audit',
                            () => ctx.go('audit', { actor: r.username }),
                            'quiet'
                        )
                    ),
                (r) => r.role
            ]
        ],
        {
            key: (r) => r.username,
            id: 'webui-users',
            caption: 'WebUI identities',
            exportName: 'webui-identities',
            empty: {
                title: 'No WebUI identities yet',
                hint: 'A WebUI identity is one administrator login; it is separate from a game account.',
                action: { label: 'Create identity', run: () => createIdentity() },
                icon: 'webui-users'
            },
            filters: [
                [
                    'Role',
                    ['All', 'ADMINISTRATOR', 'MODERATOR', 'VIEWER'],
                    (r, value) => value === 'All' || r.role === value
                ]
            ],
            actions: (get) => [
                button('Inspect', () => here({ user: get().username })),
                button('Reset password', () => resetPassword(get())),
                button('Change role', () => changeRole(get())),
                button('Revoke', () => revoke(get()), 'danger')
            ]
        }
    );
    // What the server enforces for this browser session, so an administrator can
    // confirm the transport and cookie protections without reading the config.
    const expiry = el('dd'),
        sessionFacts = facts([
            ['Signed in as', api.session?.username || 'Unavailable'],
            ['Role', api.session?.role || 'Unavailable'],
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
        const minutes = Math.max(0, Math.round((expires - Date.now()) / 60000));
        return (
            date(expires) +
            (minutes > 1 ? ` (${minutes} minutes left)` : ' (renewing on the next request)')
        );
    };
    node.append(
        sessionPanel,
        toolbar(button('Create WebUI identity', () => createIdentity(), 'primary')),
        el(
            'small',
            'Each identity is one WebUI login. Resetting its password or changing its ' +
                'role ends every session it holds; there is no separate ' +
                'sign-out-everywhere action. At least one administrator must remain.'
        ),
        table.node
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
        inspected = wanted;
        const row = rows.find((candidate) => candidate.username === wanted);
        if (!row) {
            if (wanted) ctx.notify(`No WebUI identity is named ${wanted}.`, true);
            return;
        }
        inspect(row);
    }
    applyParams(ctx.params);
    return { node, refresh, navigate: applyParams };
}
