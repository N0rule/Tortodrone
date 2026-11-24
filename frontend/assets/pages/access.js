import { api } from '../api.js';
import { el, text, toolbar, button, panel, facts, date } from '../ui.js';
import { DataTable } from '../components/table.js';
import { edit, confirm, field } from '../components/dialog.js';
export async function mount(ctx) {
    const node = el('div', null, 'stack');
    const passwords = () => [
        field('password', 'New password', '', 'password', null, { minLength: 8, maxLength: 128 }),
        field('passwordConfirmation', 'Confirm password', '', 'password', null, {
            minLength: 8,
            maxLength: 128
        })
    ];
    const role = (value) =>
        field('role', 'Role', value, 'text', ['VIEWER', 'MODERATOR', 'ADMINISTRATOR']);
    const table = new DataTable(
        [
            ['Username', (r) => r.username],
            ['Role', (r) => r.role]
        ],
        {
            key: (r) => r.username,
            id: 'webui-users',
            caption: 'WebUI identities',
            exportName: 'webui-identities',
            actions: (get) => [
                button('Reset password', () =>
                    edit('Password · ' + get().username, 'webui-user-password', passwords(), {
                        username: get().username,
                        revision: get().revision
                    })
                ),
                button('Change role', () =>
                    edit('Role · ' + get().username, 'webui-user-role', [role(get().role)], {
                        username: get().username,
                        revision: get().revision
                    })
                ),
                button(
                    'Revoke',
                    () =>
                        confirm(
                            'Revoke ' + get().username,
                            'webui-user-delete',
                            { username: get().username, revision: get().revision },
                            [field('nameConfirmation', 'Type ' + get().username)],
                            'Revokes this WebUI identity and its sessions.'
                        ),
                    'danger'
                )
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
        toolbar(
            button('Create WebUI identity', () =>
                edit('Create WebUI identity', 'webui-user-create', [
                    field('username', 'Username', '', 'text', null, {
                        minLength: 3,
                        maxLength: 64,
                        pattern: '(?:[a-zA-Z0-9_]|-){3,64}'
                    }),
                    ...passwords(),
                    role('VIEWER')
                ])
            )
        ),
        el(
            'small',
            'Access changes revoke existing sessions. At least one administrator must remain.'
        ),
        table.node
    );
    async function refresh() {
        text(expiry, describeExpiry());
        table.update(await ctx.request('webui-users'));
    }
    await refresh();
    return { node, refresh };
}
