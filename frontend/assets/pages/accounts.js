import { api } from '../api.js';
import { el, toolbar, button, routeLink, panel, facts, badge, date, copy } from '../ui.js';
import { DataTable } from '../components/table.js';
import { edit, confirm, confirmAction, field } from '../components/dialog.js';

export async function mount(ctx) {
    const root = el('div', null, 'stack'),
        detail = el('div'),
        controls = toolbar();
    let rows = [],
        identities,
        shownFor,
        selected = ctx.params.get('username'),
        selection = 0,
        detailSignature;
    const createAccount = () =>
        edit('Create game account', 'account-create', [
            field('username', 'Username'),
            field('password', 'Password', '', 'password', null, { minLength: 8, maxLength: 128 }),
            field('gm', 'Game Master', false, 'checkbox')
        ]);
    if (ctx.admin) controls.append(button('Create account', createAccount, 'primary'));
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
            address: true,
            caption: 'Game accounts',
            exportName: 'accounts',
            empty: {
                title: 'No game accounts exist yet',
                hint: 'Accounts are created here, or registered by the game client when a player signs up.',
                action: ctx.admin ? { label: 'Create account', run: () => createAccount() } : undefined,
                icon: 'accounts'
            },
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
                // A row's name is its address here: an anchor keeps "open in new tab"
                // and "copy link address" working on the listing itself.
                routeLink('Inspect', 'accounts', { username: get().username }, 'button')
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
            // Escalation, not a tidy-up: the same gold Confirm as every other write
            // made a grant of in-game admin rights look like saving a field.
            variant: gameMaster ? 'danger' : 'primary',
            onSubmit: async (form, progress) => {
                let done = 0;
                const failed = [];
                for (const [index, row] of rows.entries()) {
                    progress(index + 1, rows.length, gameMaster ? 'Granting' : 'Removing');
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
        // A deep-linked account marks its own row, so the table and the panel agree
        // about what is being described.
        table.markInspected?.(account || null);
        if (!account) {
            detailSignature = null;
            detail.replaceChildren(
                selected
                    ? panel(
                          'No such account',
                          el(
                              'p',
                              `No game account is named “${selected}”. It may have been deleted, or the link may be stale.`,
                              'muted'
                          ),
                          toolbar(
                              routeLink('Back to accounts', 'accounts', {}, 'button secondary')
                          )
                      )
                    : el('p', 'Select an account to see its knights and actions.', 'muted')
            );
            return;
        }
        const knights = await ctx.request('knights', { username: account.username });
        if (id !== selection) return;
        const identity = (identities ?? []).find(
            (candidate) => candidate.username === account.username
        );
        const signature = JSON.stringify([account, knights, identity ?? null]);
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
                        [
                            field('password', 'New password', '', 'password', null, {
                                minLength: 8,
                                maxLength: 128
                            })
                        ],
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
        // A switch that posts on change asks for no confirmation and cannot be
        // undone, which contradicts the rule the rest of the application keeps; and
        // natively disabling it for a moderator removed the control *and* the reason
        // from the tab order. One button, through the same confirmation the bulk path
        // uses for the same route.
        const feedback = el(
            'small',
            'Game account permission; WebUI roles are managed separately.'
        );
        actions.prepend(
            button(
                account.gameMaster ? 'Remove Game Master' : 'Grant Game Master',
                () => bulkPermission([account], !account.gameMaster),
                'secondary',
                () =>
                    ctx.admin ||
                    'Granting and removing Game Master rights needs an administrator role.',
                { showReason: true }
            ),
            feedback
        );
        // One person can be a game account, an audit actor and a WebUI identity,
        // so the detail links all three views of them.
        const links = toolbar(routeLink('Back to accounts', 'accounts', {}, 'button secondary'));
        // The audit trail and the identity store are administrator-only pages, so
        // hide those jumps from a moderator instead of bouncing them to the overview.
        if (ctx.admin)
            links.append(
                // A game account is what an action was done TO, never who did it.
                routeLink(
                    'Audit events naming this account',
                    'audit',
                    { target: account.username },
                    'button secondary'
                )
            );
        if (identity)
            links.append(
                routeLink(
                    'Open WebUI identity',
                    'webui-users',
                    { user: identity.username },
                    'button secondary'
                )
            );
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
                empty: {
                    title: 'No knights on this account',
                    hint: 'Knights are created in the game client and appear here after the first login.',
                    icon: 'knights'
                },
                actions: (get) => [
                    routeLink(
                        'Open profile',
                        'knights',
                        { username: account.username, knight: get().name },
                        'button'
                    )
                ]
            }
        );
        roster.update(knights);
        const view = panel(
            account.username,
            links,
            facts([
                ['Permission', account.gameMaster ? 'Game Master' : 'Player'],
                ['Created', date(account.createdAt)],
                ['Knights', account.knights],
                [
                    'WebUI identity',
                    identity
                        ? badge(
                              identity.role.toLowerCase(),
                              identity.role === 'ADMINISTRATOR' ? 'gold' : ''
                          )
                        : ctx.admin
                          ? 'No WebUI identity'
                          : 'Administrators only'
                ]
            ]),
            actions,
            roster.node
        );
        // Inspect panes share one treatment: the panel itself, no accent border.
        view.classList.add('stack');
        detail.replaceChildren(view);
        // The pane sits under the table, so a selection scrolls it into view once.
        if (shownFor !== account.username) {
            shownFor = account.username;
            view.scrollIntoView({ block: 'nearest' });
        }
    }
    async function refresh() {
        rows = await ctx.request('accounts');
        // The WebUI identity store is administrator-only: read it only for an
        // administrator, so a moderator never gets a link that fails.
        if (ctx.admin) identities = await ctx.request('webui-users');
        table.update(rows);
        if (selected) await show();
    }
    await refresh();
    return {
        node: root,
        refresh,
        interval: 30000,
        async navigate(params) {
            // `?search=` belongs to the table: a link to a filtered list has to drive
            // the box the same way a cold load does, or the address lies in-app.
            table.setQuery(params.get('search') ?? '');
            selected = params.get('username');
            await show();
        }
    };
}
