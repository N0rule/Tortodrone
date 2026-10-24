import { el, toolbar, button, panel, facts, badge, date, text } from '../ui.js';
import { DataTable } from '../components/table.js';
import { edit, confirm, field } from '../components/dialog.js';

export async function mount(ctx) {
    const root = el('div', null, 'stack'),
        detail = el('div'),
        controls = toolbar();
    let rows = [],
        selected = ctx.params.get('username'),
        selection = 0,
        detailSignature;
    if (ctx.admin)
        controls.append(
            button('Create account', () =>
                edit('Create game account', 'account-create', [
                    field('username', 'Username'),
                    field('password', 'Password', '', 'password'),
                    field('gm', 'Game Master', false, 'checkbox')
                ])
            )
        );
    const table = new DataTable(
        [
            ['Account', (r) => r.username],
            [
                'Permission',
                (r) => badge(r.gameMaster ? 'Game Master' : 'Player', r.gameMaster ? 'gold' : '')
            ],
            ['Knights', (r) => r.knights],
            ['Created', (r) => date(r.createdAt), (r) => r.createdAt]
        ],
        {
            actions: (get) => [
                button('Inspect', () => ctx.go('accounts', { username: get().username }))
            ]
        }
    );
    root.append(controls, table.node, detail);
    async function show() {
        const id = ++selection,
            account = rows.find((a) => a.username === selected);
        if (!account) {
            detail.replaceChildren();
            return;
        }
        const knights = await ctx.request('knights', { username: account.username });
        if (id !== selection) return;
        const signature = JSON.stringify([account, knights]);
        if (detailSignature === signature) return;
        detailSignature = signature;
        const fixed = { username: account.username, accountId: account.id },
            actions = toolbar();
        if (ctx.admin)
            actions.append(
                button('Change password', () =>
                    edit(
                        'Password · ' + account.username,
                        'account-password',
                        [field('password', 'New password', '', 'password')],
                        fixed
                    )
                ),
                button('Check password', () =>
                    edit(
                        'Check password · ' + account.username,
                        'account-check-password',
                        [field('password', 'Password to check', '', 'password')],
                        fixed,
                        '',
                        (result) =>
                            ctx.notify(
                                result.matches ? 'Password matches.' : 'Password does not match.'
                            )
                    )
                ),
                button('GM permission', () =>
                    edit(
                        'Game permission · ' + account.username,
                        'account-gm',
                        [field('gm', 'Game Master', account.gameMaster, 'checkbox')],
                        fixed
                    )
                ),
                button(
                    'Delete account',
                    () =>
                        confirm(
                            'Delete ' + account.username,
                            'account-delete',
                            fixed,
                            [field('nameConfirmation', 'Type ' + account.username)],
                            'Deletes this account and its Knights. This cannot be undone.'
                        ),
                    'danger'
                )
            );
        const roster = new DataTable(
            [
                ['Knight', (k) => k.name],
                ['Roster ID', (k) => k.id]
            ],
            {
                actions: (get) => [
                    button('Open profile', () =>
                        ctx.go('knights', { username: account.username, knight: get().name })
                    )
                ]
            }
        );
        roster.update(knights);
        const view = panel(
            account.username,
            facts([
                ['Permission', account.gameMaster ? 'Game Master' : 'Player'],
                ['Created', date(account.createdAt)],
                ['Knights', account.knights]
            ]),
            actions,
            roster.node
        );
        view.classList.add('account-detail', 'stack');
        detail.replaceChildren(view);
    }
    async function refresh() {
        rows = await ctx.request('accounts');
        table.update(rows);
        if (selected) await show();
    }
    await refresh();
    return {
        node: root,
        refresh,
        interval: 30000,
        async navigate(params) {
            selected = params.get('username');
            await show();
        }
    };
}
