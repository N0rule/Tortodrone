import { api } from '../api.js';
import { el, panel, button, copy, duration, status } from '../ui.js';
import { DataTable } from '../components/table.js';
import { confirm, confirmAction } from '../components/dialog.js';
export async function mount(ctx) {
    const node = el('div', null, 'stack');
    const players = new DataTable(
        [
            ['Session', (r) => r.id],
            ['Account', (r) => r.username || '—'],
            ['Knight', (r) => r.knight || '—'],
            ['Scene', (r) => r.scene || '—'],
            ['Instance', (r) => r.instanceId ?? '—'],
            ['Duration', (r) => duration(r.elapsedSeconds), (r) => r.connectedAt],
            ['State', (r) => status(r.state, true)]
        ],
        {
            id: 'sessions',
            caption: 'Live sessions',
            exportName: 'sessions',
            actions: ctx.moderator
                ? (get) => [
                      button(
                          'Account',
                          () => ctx.go('accounts', { username: get().username }),
                          'secondary',
                          () => !!get().username
                      ),
                      button(
                          'Knight',
                          () =>
                              get().knight &&
                              ctx.go('knights', { username: get().username, knight: get().knight }),
                          'secondary',
                          () => !!get().username && !!get().knight
                      ),
                      ...(ctx.moderator
                          ? [
                                button(
                                    'Kick',
                                    () =>
                                        confirm(
                                            'Disconnect session ' + get().id,
                                            'kick',
                                            { session: get().id },
                                            [],
                                            `Disconnect ${get().knight || get().username || 'this connection'}.`
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
                                  onSubmit: async () => {
                                      let done = 0,
                                          failed = 0;
                                      for (const row of rows) {
                                          try {
                                              await api.change('kick', { session: row.id });
                                              done++;
                                          } catch {
                                              failed++;
                                          }
                                      }
                                      ctx.toast(
                                          `Disconnected ${done} session${done === 1 ? '' : 's'}` +
                                              (failed ? `, ${failed} could not be closed` : '.'),
                                          { type: failed ? 'error' : 'success' }
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
    const scenes = new DataTable(
        [
            ['Scene', (r) => r.name],
            ['Template', (r) => r.templateId],
            ['Instance', (r) => r.instanceId],
            ['Population', (r) => r.players],
            ['State', (r) => r.state]
        ],
        {
            key: (r) => r.instanceId,
            id: 'scene-population',
            caption: 'Scene population',
            exportName: 'scenes'
        }
    );
    node.append(players.node, panel('Scene population', scenes.node));
    async function refresh() {
        const s = await ctx.request('status');
        players.update(
            s.sessions.map((r) => ({
                ...r,
                elapsedSeconds: Math.floor((Date.now() - new Date(r.connectedAt)) / 1000)
            }))
        );
        scenes.update(s.scenes);
        ctx.connection(s.state, s.state === 'RUNNING');
    }
    await refresh();
    return { node, refresh, interval: 4000 };
}
