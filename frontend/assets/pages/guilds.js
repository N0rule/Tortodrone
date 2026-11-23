import { el, toolbar, button, panel, facts, badge, date, number } from '../ui.js';
import { DataTable } from '../components/table.js';

/**
 * Formed guilds, their well and their roster.
 *
 * Read-only by design: guild mutations are authorised inside the town service against the
 * caller's stored auth revision, and the WebUI has no knight to authenticate as. Listing,
 * the record and the log pages are what an operator can inspect without inventing a write
 * path the stock client does not have.
 */
export async function mount(ctx) {
    const root = el('div', null, 'stack'),
        detail = el('div'),
        controls = toolbar();
    let rows = [],
        selected = ctx.params.get('name'),
        selection = 0,
        detailSignature;

    controls.append(
        el(
            'small',
            'Guild membership, ranks, the treasury well and the record are written by the game client. This page inspects them.'
        )
    );

    const table = new DataTable(
        [
            ['Guild', (r) => r.name],
            ['Members', (r) => r.members + '/' + r.memberLimit],
            ['Well', (r) => number(r.energy)],
            ['Upkeep', (r) => number(r.upkeep)],
            ['Hall', (r) => badge(r.hallOpen ? 'Open' : 'Closed', r.hallOpen ? 'gold' : '')],
            ['Theme', (r) => r.theme || '—'],
            ['Formed', (r) => date(r.formed), (r) => r.formed]
        ],
        {
            key: (r) => r.id,
            actions: (get) => [
                button('Inspect', () => ctx.go('guilds', { name: get().name }))
            ],
            filters: [
                [
                    'Hall access',
                    ['All', 'Open', 'Closed'],
                    (r, v) => v === 'All' || (v === 'Open') === r.hallOpen
                ]
            ]
        }
    );

    root.append(controls, table.node, detail);

    async function show() {
        const guild = rows.find((g) => g.name.toLowerCase() === (selected || '').toLowerCase());
        if (!guild) {
            detailSignature = null;
            detail.replaceChildren();
            return;
        }
        const id = ++selection,
            record = await ctx.request('guild', { name: guild.name, count: 25 });
        if (id !== selection) return;
        const signature = JSON.stringify(record);
        if (detailSignature === signature) return;
        detailSignature = signature;

        const roster = new DataTable(
            [
                ['Rank', (m) => m.rank],
                ['Knight', (m) => m.name],
                ['Joined', (m) => date(m.joined), (m) => m.joined],
                ['Last session', (m) => (m.lastSession ? date(m.lastSession) : 'Never'), (m) => m.lastSession],
                [
                    'Last withdrawal',
                    (m) => (m.lastWithdrawal ? date(m.lastWithdrawal) : 'None'),
                    (m) => m.lastWithdrawal
                ]
            ],
            { key: (m) => m.name }
        );
        roster.update(record.roster);

        const log = new DataTable(
            [
                ['When', (e) => date(e.when), (e) => e.when],
                ['Type', (e) => e.type],
                ['Knight', (e) => e.knightName || '—'],
                ['Arguments', (e) => (e.args || []).join(' · ') || '—']
            ],
            { key: (e) => e.id }
        );
        log.update(record.log?.entries || []);

        const view = panel(
            record.guild.name,
            facts([
                ['Id', record.guild.id],
                ['Formed', date(record.guild.formed)],
                ['Members', record.guild.members + '/' + record.guild.memberLimit],
                ['Well', number(record.guild.energy) + ' energy'],
                ['Upkeep', number(record.guild.upkeep)],
                ['Hall access', record.guild.hallOpen ? 'Open' : 'Invite only'],
                ['Theme', record.guild.theme || 'None'],
                ['Environment', record.guild.environment || 'None'],
                ['Message', record.guild.motd || 'None']
            ]),
            logNote(record.log),
            roster.node,
            log.node
        );
        view.classList.add('guild-detail', 'stack');
        detail.replaceChildren(view);
    }

    function logNote(log) {
        const total = log?.entryCount ?? 0;
        return el(
            'small',
            total > (log?.entries?.length || 0)
                ? 'Showing the newest ' + (log.entries?.length || 0) + ' of ' + total + ' recorded entries.'
                : 'Recorded entries: ' + total + '. The game client localizes each entry type; the page shows the stored type byte and arguments.'
        );
    }

    async function refresh() {
        rows = await ctx.request('guilds');
        table.update(rows);
        if (selected) await show();
    }

    await refresh();
    return {
        node: root,
        refresh,
        interval: 30000,
        async navigate(params) {
            selected = params.get('name');
            await show();
        }
    };
}
