import { api } from '../api.js';
import { el, panel, button, routeLink, copy, duration, status, facts, date, sceneLabel, landOn } from '../ui.js';
import { DataTable } from '../components/table.js';
import { go } from '../router.js';
import { confirm, confirmAction, openDialog } from '../components/dialog.js';
// Auctions and Depot disclose that a derived time is this browser's clock against a
// window the server owns. A session's age is the same kind of number and the stronger
// case for saying so: `status` sends the connection time, never the age, so the duration
// is entirely this page's arithmetic — a laptop an hour fast reports an hour-old
// two-minute session. (The first version of this note claimed the server sent the age;
// no route does, which is the same kind of sentence as the bug it was meant to fix.)
const CLOCK_NOTE =
    'This browser’s clock against the connection time the server recorded: the server sends when a session started, not how long it has run, so a wrong clock here shows a wrong duration.';
const durationCell = (seconds) => Object.assign(el('span', duration(seconds)), { title: CLOCK_NOTE });
export async function mount(ctx) {
    const node = el('div', null, 'stack');
    const players = new DataTable(
        [
            [
                'Session',
                (r) => {
                    // A session id is not a dead number: it names a connection whose
                    // facts (and its owner) are worth one click.
                    const open = el('button', String(r.id), 'link');
                    open.type = 'button';
                    open.onclick = () => inspectSession(r);
                    return open;
                }
            ],
            ['Account', (r) => (r.username ? entityLink(r.username, 'accounts', { username: r.username }) : '—')],
            [
                'Knight',
                (r) =>
                    r.username && r.knight
                        ? entityLink(r.knight, 'knights', { username: r.username, knight: r.knight })
                        : r.knight || '—'
            ],
            [
                'Scene',
                (r) =>
                    r.scene
                        ? entityLink(sceneLabel(r.scene), 'players', { scene: r.scene })
                        : '—'
            ],
            ['Instance', (r) => r.instanceId ?? '—'],
            ['Duration', (r) => durationCell(r.elapsedSeconds), (r) => r.connectedAt],
            ['State', (r) => status(r.state, true)]
        ],
        {
            id: 'sessions',
            caption: 'Live sessions',
            exportName: 'sessions',
            empty: {
                title: 'No player sessions are connected',
                hint: 'Sessions appear as players connect; a disconnect keeps the account and knight.',
                icon: 'players'
            },
            initialQuery: ctx.params.get('scene') || '',
            actions: ctx.moderator
                ? (get) => [
                      button(
                          'Account',
                          () => ctx.go('accounts', { username: get().username }),
                          'secondary',
                          () => !!get().username,
                          // A row action with a bare boolean used to refuse with
                          // "That action is not available on this server yet", which
                          // was false: the action exists, this row has no username.
                          {
                              unavailable:
                                  'This session has no account: it has not authenticated yet, so there is nothing to open.'
                          }
                      ),
                      button(
                          'Knight',
                          () =>
                              get().knight &&
                              ctx.go('knights', { username: get().username, knight: get().knight }),
                          'secondary',
                          () => !!get().username && !!get().knight,
                          {
                              unavailable:
                                  'This session has no knight to open: either its account is not known yet or no knight has been selected.'
                          }
                      ),
                      ...(ctx.moderator
                          ? [
                                button(
                                    'Disconnect',
                                    () =>
                                        confirm(
                                            'Disconnect session ' + get().id,
                                            'kick',
                                            { session: get().id },
                                            [],
                                            `Drop ${get().knight || get().username || 'this connection'} now. The player can reconnect immediately; nothing they own is changed.`,
                                            {
                                                destructive: true,
                                                submit: 'Disconnect',
                                                // A kick is not a save. `changed()` toasted
                                                // "Changes saved." at a session it never
                                                // named; the bulk path already said
                                                // "Disconnected N sessions".
                                                notifySaved: false,
                                                after: () =>
                                                    ctx.toast(
                                                        `Disconnected session ${get().id} (${
                                                            get().knight ||
                                                            get().username ||
                                                            'unknown'
                                                        }).`,
                                                        { type: 'success' }
                                                    )
                                            }
                                        ),
                                    'danger'
                                )
                            ]
                          : [])
                  ]
                : undefined,
            selectable: ctx.moderator,
            bulkActions: ctx.moderator
                ? (selected) => [
                      button(
                          'Disconnect selected',
                          () => {
                              const rows = selected();
                              confirmAction({
                                  title: `Disconnect ${rows.length} session${rows.length === 1 ? '' : 's'}`,
                                  description:
                                      'Each selected connection is dropped immediately. Authenticated players can reconnect at once.',
                                  label: 'Disconnect',
                                  variant: 'danger',
                                  onSubmit: async (form, progress) => {
                                      let done = 0;
                                      const failed = [];
                                      for (const [index, row] of rows.entries()) {
                                          progress(index + 1, rows.length, 'Disconnecting');
                                          try {
                                              await api.change('kick', { session: row.id });
                                              done++;
                                          } catch (error) {
                                              failed.push(
                                                  `Session ${row.id}: ${error.message || 'refused'}`
                                              );
                                          }
                                      }
                                      ctx.toast(
                                          `Disconnected ${done} session${done === 1 ? '' : 's'}` +
                                              (failed.length
                                                  ? `, ${failed.length} could not be closed.`
                                                  : '.'),
                                          {
                                              type: failed.length ? 'error' : 'success',
                                              detail: failed.slice(0, 3).join(' · ') || undefined
                                          }
                                      );
                                  }
                              });
                          },
                          'danger',
                          () => selected().length > 0
                      ),
                      button(
                          'Copy session IDs',
                          () =>
                              copy(
                                  selected()
                                      .map((row) => row.id)
                                      .join('\n'),
                                  'Session IDs copied.'
                              ),
                          'secondary',
                          () => selected().length > 0
                      )
                  ]
                : undefined,
            filters: [
                [
                    'State',
                    [
                        'All',
                        'Connected',
                        'Authenticating',
                        'Authenticated',
                        'Knight selected',
                        'In scene'
                    ],
                    (r, v) => v === 'All' || r.state === v
                ]
            ]
        }
    );


    /** A cell that names an entity links to the page that owns it, as a real link. */
    const entityLink = (label, name, values) => routeLink(label, name, values);

    /** Everything recorded about one connection, with the routes out of it. */
    function inspectSession(row) {
        const links = el('div', null, 'toolbar');
        if (row.username)
            links.append(
                button(
                    'Open account',
                    () => {
                        document.querySelector('#close-dialog')?.click();
                        ctx.go('accounts', { username: row.username });
                    },
                    'secondary'
                )
            );
        if (row.username && row.knight)
            links.append(
                button(
                    'Open knight',
                    () => {
                        document.querySelector('#close-dialog')?.click();
                        ctx.go('knights', { username: row.username, knight: row.knight });
                    },
                    'secondary'
                )
            );
        if (row.scene)
            links.append(
                button(
                    'Players in this scene',
                    () => {
                        document.querySelector('#close-dialog')?.click();
                        ctx.go('players', { scene: row.scene });
                    },
                    'secondary'
                )
            );
        if (ctx.moderator)
            links.append(
                button(
                    'Disconnect',
                    () => {
                        document.querySelector('#close-dialog')?.click();
                        confirm(
                            'Disconnect session ' + row.id,
                            'kick',
                            { session: row.id },
                            [],
                            `Disconnect ${row.knight || row.username || 'this connection'}.`
                        );
                    },
                    'danger'
                )
            );
        links.append(button('Copy session id', () => copy(String(row.id)), 'quiet'));
        openDialog({
            title: 'Session ' + row.id,
            submit: 'Close',
            content: el(
                'div',
                null,
                'stack',
                facts([
                    ['Session', String(row.id)],
                    ['Account', row.username || 'not signed in'],
                    ['Knight', row.knight || 'none selected'],
                    ['Scene', row.scene ? sceneLabel(row.scene) : 'not in a scene'],
                    ['Instance', String(row.instanceId ?? '—')],
                    ['State', status(row.state, true)],
                    ['Connected', row.connectedAt ? date(row.connectedAt) : '—'],
                    ['Duration', durationCell(row.elapsedSeconds)]
                ]),
                links
            ),
            onSubmit: async () => true
        });
    }

    const scenes = new DataTable(
        [
            [
                'Scene',
                (r) => routeLink(sceneLabel(r.name), 'players', { scene: r.name }),
                (r) => sceneLabel(r.name)
            ],
            ['Template', (r) => r.templateId],
            ['Instance', (r) => r.instanceId],
            ['Population', (r) => r.players],
            ['State', (r) => r.state]
        ],
        {
            key: (r) => r.instanceId,
            id: 'scene-population',
            caption: 'Scene population',
            exportName: 'scenes',
            empty: {
                title: 'No scene instances are running',
                hint: 'Scenes appear once the world starts them; the overview shows map state.',
                icon: 'overview'
            },
            actions: (get) => [
                button(
                    'Players here',
                    () => go('players', { scene: get().name }),
                    'secondary'
                ),
                button('Monsters', () => go('overview', { scene: get().name }), 'quiet')
            ]
        }
    );
    node.append(players.node, panel('Scene population', scenes.node));
    async function refresh() {
        const s = await ctx.request('status');
        players.update(
            s.sessions.map((r) => ({
                ...r,
                // Derived here, from the connection time the server recorded — which is
                // why every duration on this page says where it came from. A value the
                // server ever does send wins over the arithmetic.
                elapsedSeconds:
                    r.elapsedSeconds ??
                    Math.max(0, Math.floor((Date.now() - new Date(r.connectedAt)) / 1000))
            }))
        );
        scenes.update(s.scenes);
        ctx.connection(s.state, s.state === 'RUNNING');
    }
    await refresh();
    /**
     * Deep links (#players?scene=…) re-filter in place. The param owns the filter,
     * so it is cleared when the link no longer carries one.
     */
    function applyParams(params) {
        const value = params.get('scene');
        players.setQuery(value ?? '');
        if (value) landOn(players.node);
    }
    return { node, refresh, interval: 4000, navigate: applyParams };
}
