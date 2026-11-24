import { api } from '../api.js';
import {
    el,
    toolbar,
    button,
    panel,
    facts,
    badge,
    date,
    text,
    input,
    label,
    copy,
    changed
} from '../ui.js';
import { DataTable } from '../components/table.js';
import { edit, confirm, confirmAction, field } from '../components/dialog.js';

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
            id: 'accounts',
            caption: 'Game accounts',
            exportName: 'accounts',
            selectable: true,
            bulkActions: (selected) => [
                button(
                    'Copy usernames',
                    () =>
                        copy(
                            selected()
                                .map((row) => row.username)
                                .join('\n'),
                            'Usernames copied.'
                        ),
                    'secondary',
                    () => selected().length > 0
                ),
                ...(ctx.admin
                    ? [
                          button(
                              'Set Game Master',
                              () => bulkPermission(selected(), true),
                              'secondary',
                              () => selected().length > 0
                          ),
                          button(
                              'Remove Game Master',
                              () => bulkPermission(selected(), false),
                              'secondary',
                              () => selected().length > 0
                          )
                      ]
                    : [])
            ],
            actions: (get) => [
                button('Inspect', () => ctx.go('accounts', { username: get().username }))
            ]
        }
    );
    /** Apply one permission change across the selection, reporting both outcomes. */
    function bulkPermission(rows, gameMaster) {
        confirmAction({
            title: `${gameMaster ? 'Grant' : 'Remove'} Game Master for ${rows.length} account${rows.length === 1 ? '' : 's'}`,
            description: gameMaster
                ? 'Game Master rights allow in-game administration. Each account is updated individually and the result is reported per account.'
                : 'Game Master rights are removed from each selected account. WebUI roles are not affected.',
            label: gameMaster ? 'Grant' : 'Remove',
            onSubmit: async () => {
                let done = 0;
                const failed = [];
                for (const row of rows) {
                    try {
                        await api.change('account-gm', {
                            username: row.username,
                            accountId: row.id,
                            gm: gameMaster
                        });
                        done++;
                    } catch (error) {
                        failed.push(row.username + ': ' + error.message);
                    }
                }
                ctx.toast(
                    `${done} account${done === 1 ? '' : 's'} updated` +
                        (failed.length ? `, ${failed.length} refused` : '.'),
                    {
                        type: failed.length ? 'error' : 'success',
                        detail: failed.slice(0, 3).join(' · ') || undefined
                    }
                );
                await refresh();
            }
        });
    }

    root.append(controls, table.node, detail);
    async function show() {
        const id = ++selection,
            account = rows.find((a) => a.username === selected);
        if (!account) {
            detailSignature = null;
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
        const gm = input('Game Master permission', account.gameMaster, 'checkbox');
        gm.setAttribute('role', 'switch');
        gm.disabled = !ctx.admin;
        const feedback = el(
            'small',
            'Game account permission; WebUI roles are managed separately.'
        );
        gm.onchange = async () => {
            gm.disabled = true;
            try {
                await api.change('account-gm', { ...fixed, gm: gm.checked });
                text(feedback, 'Game Master permission saved.');
                changed();
            } catch (error) {
                gm.checked = account.gameMaster;
                text(feedback, error.message);
            } finally {
                gm.disabled = false;
            }
        };
        actions.prepend(label('Game Master', gm), feedback);
        const roster = new DataTable(
            [
                ['Knight', (k) => k.name],
                ['Roster ID', (k) => k.id]
            ],
            {
                id: 'account-roster',
                caption: 'Knights on this account',
                exportName: 'account-roster',
                search: false,
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
