import { el, toolbar, button } from '../ui.js';
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
    node.append(
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
        table.update(await ctx.request('webui-users'));
    }
    await refresh();
    return { node, refresh };
}
