import { el, panel, button, duration, status } from '../ui.js';
import { DataTable } from '../components/table.js';
import { confirm } from '../components/dialog.js';
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
            actions: ctx.moderator
                ? (get) => [
                      button(
                          'Account',
                          () => get().username && ctx.go('accounts', { username: get().username })
                      ),
                      button(
                          'Knight',
                          () =>
                              get().knight &&
                              ctx.go('knights', { username: get().username, knight: get().knight })
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
        { key: (r) => r.instanceId }
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
