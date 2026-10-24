import {
    el,
    button,
    toolbar,
    panel,
    badge,
    status,
    facts,
    details,
    input,
    select,
    tabs,
    text,
    heat,
    number
} from '../ui.js';
import { DataTable } from '../components/table.js';
import { edit, confirm, field, conflict } from '../components/dialog.js';
import { itemLine, currency, itemTile, icon, variantName } from '../components/items.js';
import { inspectItem, grantItem } from '../components/gear-editor.js';
import { spriteCard } from '../components/sprite-card.js';

const slotNames = new Map([
    [0, 'Helmet'],
    [1, 'Armor'],
    [2, 'Shield'],
    [3, 'Costume helmet'],
    [4, 'Costume armor'],
    [6, 'Weapon 1'],
    [7, 'Weapon 2'],
    [8, 'Weapon 3'],
    [9, 'Weapon 4'],
    [10, 'Trinket 1'],
    [11, 'Trinket 2'],
    [15, 'Costume shield']
]);
const sectionNames = [
    ['equipment', 'Equipment'],
    ['inventory', 'Inventory'],
    ['sprites', 'Battle Sprites'],
    ['progression', 'Progression'],
    ['details', 'Details']
];
export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        list = el('div'),
        profileHost = el('div', null, 'stack');
    let params = ctx.params,
        view,
        selectedKey = '',
        activeTab = sectionNames.some(([id]) => id === params.get('tab'))
            ? params.get('tab')
            : 'equipment',
        requestId = 0,
        bodyController,
        lastRevision,
        lastLive;
    const sections = new Map();
    const roster = new DataTable(
        [
            ['Knight', (k) => k.name],
            ['Account', (k) => k.username],
            [
                'Permission',
                (k) => badge(k.gameMaster ? 'GM' : 'Player', k.gameMaster ? 'gold' : '')
            ],
            ['Status', (k) => status(k.online ? 'Online' : 'Offline', k.online)]
        ],
        {
            key: (k) => `${k.accountId}:${k.id}`,
            actions: (get) => [
                button('Open profile', () =>
                    ctx.go('knights', { username: get().username, knight: get().name })
                )
            ],
            filters: [
                [
                    'Status',
                    ['All', 'Online', 'Offline'],
                    (k, v) => v === 'All' || k.online === (v === 'Online')
                ]
            ]
        }
    );
    list.append(roster.node);
    node.append(list, profileHost);
    const fixed = () => ({
        username: view.username,
        knight: view.name,
        accountId: view.profile.accountId,
        worldId: view.profile.worldId,
        revision: view.profile.revision
    });
    const definition = (item) => view.definitions.find((d) => d.config === item.config);
    const inspect = (item) => inspectItem(definition(item), item, fixed(), ctx.admin);
    const header = el('div', null, 'knight-header'),
        name = el('h2', null, 'knight-name'),
        badges = el('div', null, 'badges'),
        accountLink = el('a'),
        left = el('div'),
        wallet = el('div', null, 'knight-wallet');
    left.append(name, badges, accountLink);
    header.append(left, wallet);
    const crowns = button('', () => editCurrency('CROWNS')),
        energy = button('', () => editCurrency('ENERGY')),
        prestige = button('', editProgress);
    crowns.disabled = energy.disabled = prestige.disabled = !ctx.admin;
    wallet.append(crowns, energy, prestige);
    const actions = toolbar(button('All Knights', () => ctx.go('knights')));
    if (ctx.admin)
        actions.append(
            button('Grant item', () => grantItem(fixed())),
            button('Edit progression', editProgress),
            button(
                'Delete Knight',
                () =>
                    confirm(
                        'Delete ' + view.name,
                        'knight-delete',
                        fixed(),
                        [field('nameConfirmation', 'Type ' + view.name)],
                        'Deletes this Knight and its owned state. This cannot be undone.'
                    ),
                'danger'
            )
        );
    const content = el('div', null, 'stack');
    let tabBar;
    function editCurrency(kind) {
        edit(
            kind === 'CROWNS' ? 'Manage Crowns' : 'Manage Energy',
            'knight-currency',
            [
                field('operation', 'Operation', 'set', 'text', [
                    ['set', 'Set total'],
                    ['delta', 'Add / subtract']
                ]),
                field(
                    'amount',
                    'Amount',
                    kind === 'CROWNS' ? view.profile.crowns : view.profile.energy,
                    'number',
                    null,
                    { step: 1 }
                )
            ],
            { ...fixed(), currency: kind }
        );
    }
    function editProgress() {
        const p = view.profile.progress;
        edit(
            'Progression · ' + view.name,
            'knight-progress',
            [
                field('rank', 'Rank', p.rank, 'number', null, { min: 1, max: 10, step: 1 }),
                field('subrank', 'Subrank', p.subrank, 'number', null, { min: 1, max: 4, step: 1 }),
                field('prestige', 'Prestige', p.prestige, 'number', null, {
                    min: 0,
                    max: 2147483647,
                    step: 1
                }),
                ...(!view.sprites.length
                    ? [
                          field('sprite', 'First Battle Sprite (optional)', '', 'text', [
                              ['', 'Leave unchanged'],
                              ['Drakon', 'Drakon'],
                              ['Seraphynx', 'Seraphynx'],
                              ['Maskeraith', 'Maskeraith']
                          ])
                      ]
                    : [])
            ],
            fixed()
        );
    }
    function updateHeader() {
        text(name, view.name);
        badges.replaceChildren(
            badge(`Rank ${view.profile.progress.rank}-${view.profile.progress.subrank}`),
            status(view.online ? 'Online' : 'Offline', view.online),
            badge(view.gameMaster ? 'Game Master' : 'Player', view.gameMaster ? 'gold' : ''),
            badge(view.guild)
        );
        text(accountLink, view.username);
        accountLink.href = '#accounts?' + new URLSearchParams({ username: view.username });
        for (const [button, kind, value] of [
            [crowns, 'crowns', view.profile.crowns],
            [energy, 'energy', view.profile.energy],
            [prestige, 'prestige', view.profile.progress.prestige]
        ]) {
            if (button.dataset.value !== String(value)) {
                button.replaceChildren(
                    currency(kind, value),
                    el('small', kind[0].toUpperCase() + kind.slice(1))
                );
                button.dataset.value = value;
            }
        }
    }
    function equipment() {
        const grid = el('div', null, 'equipment-grid'),
            cache = new Map();
        function update() {
            const slots = new Map(slotNames);
            for (const item of view.profile.items)
                if (item.equippedSlot != null && !slots.has(item.equippedSlot))
                    slots.set(item.equippedSlot, `Equipment ${item.equippedSlot}`);
            const desired = [...slots].map(([index, title]) => {
                const item = view.profile.items.find((i) => i.equippedSlot === index),
                    def = item && definition(item);
                let slot = cache.get(index);
                if (!slot) {
                    slot = button(
                        '',
                        () => {
                            const current = view.profile.items.find(
                                (i) => i.equippedSlot === index
                            );
                            if (current) return inspect(current);
                        },
                        'equipment-slot'
                    );
                    cache.set(index, slot);
                }
                const signature = JSON.stringify(item || null);
                if (slot.dataset.signature === signature) return slot;
                slot.dataset.signature = signature;
                slot.classList.toggle('empty-slot', !item);
                const info = el('div', null, 'slot-info');
                info.append(
                    el(
                        'span',
                        title + (item ? ` · ${item.slot.toLowerCase().replaceAll('_', ' ')}` : ''),
                        'slot-label'
                    )
                );
                info.append(
                    el('span', item ? def?.name || item.config.split('/').at(-1) : 'Empty')
                );
                if (item && def?.progression)
                    info.append(
                        el(
                            'small',
                            `Level ${item.level + 1} · ${(item.heatProgress * 100).toFixed(1)}% Heat`
                        ),
                        heat(item.heatProgress)
                    );
                if (item?.variants.length)
                    info.append(
                        el('small', item.variants.map((v) => variantName(v.name)).join(' · '), 'uv')
                    );
                if (item?.bound) info.append(el('small', 'Bound'));
                slot.replaceChildren(icon(item?.config, def?.rarity), info);
                slot.disabled = !item;
                return slot;
            });
            const wanted = new Set(desired);
            for (const child of [...grid.children]) if (!wanted.has(child)) child.remove();
            desired.forEach((child, i) => {
                if (grid.children[i] !== child) grid.insertBefore(child, grid.children[i] || null);
            });
        }
        update();
        return { node: panel('Equipped gear', grid), update };
    }
    function inventory() {
        const node = el('div', null, 'stack'),
            search = input('Search inventory'),
            category = select('Inventory category', [['', 'All categories']], ''),
            mode = select(
                'Inventory view',
                [
                    ['grid', 'Icon grid'],
                    ['list', 'Compact list']
                ],
                'grid'
            );
        const sort = select(
            'Sort inventory',
            [
                ['name', 'Name'],
                ['level', 'Level'],
                ['count', 'Quantity'],
                ['equipped', 'Equipped first']
            ],
            'name'
        );
        search.placeholder = 'Search inventory';
        const grid = el('div', null, 'item-grid'),
            result = el('div'),
            count = el('span', null, 'muted');
        const table = new DataTable(
            [
                ['Item', (i) => itemLine(definition(i), i), (i) => definition(i)?.name || i.config],
                ['Quantity', (i) => i.count],
                [
                    'Equipment',
                    (i) =>
                        i.equippedSlot == null
                            ? 'Unequipped'
                            : slotNames.get(i.equippedSlot) || i.equippedSlot
                ],
                [
                    'Level / Heat',
                    (i) =>
                        definition(i)?.progression
                            ? `${i.level + 1} / ${(i.heatProgress * 100).toFixed(1)}%`
                            : '—'
                ],
                ['Binding', (i) => (i.bound ? 'Bound' : 'Unbound')]
            ],
            { search: false, actions: (get) => [button('Inspect', () => inspect(get()))] }
        );
        let page = 1;
        const previous = button('Previous', () => {
                page--;
                update();
            }),
            next = button('Next', () => {
                page++;
                update();
            }),
            cache = new Map();
        result.append(grid, table.node);
        node.append(toolbar(search, category, sort, mode), result, toolbar(count, previous, next));
        function update() {
            const old = category.value;
            const categories = [...new Set(view.definitions.map((d) => d.category))].sort();
            if (category.dataset.signature !== categories.join('|')) {
                category.replaceChildren(
                    ...[['', 'All categories'], ...categories.map((c) => [c, c])].map(
                        ([id, title]) => {
                            const o = el('option', title);
                            o.value = id;
                            return o;
                        }
                    )
                );
                category.value = old;
                category.dataset.signature = categories.join('|');
            }
            const q = search.value.toLowerCase();
            const rows = view.profile.items.filter(
                (i) =>
                    (!q || `${definition(i)?.name} ${i.config}`.toLowerCase().includes(q)) &&
                    (!category.value || definition(i)?.category === category.value)
            );
            rows.sort((a, b) =>
                sort.value === 'level'
                    ? b.level - a.level
                    : sort.value === 'count'
                      ? b.count - a.count
                      : sort.value === 'equipped'
                        ? (b.equippedSlot != null) - (a.equippedSlot != null)
                        : (definition(a)?.name || a.config).localeCompare(
                              definition(b)?.name || b.config
                          )
            );
            const pages = Math.max(1, Math.ceil(rows.length / 48));
            page = Math.min(page, pages);
            text(count, `${rows.length} items · ${page} / ${pages}`);
            previous.disabled = page <= 1;
            next.disabled = page >= pages;
            grid.hidden = mode.value !== 'grid';
            table.node.hidden = mode.value !== 'list';
            previous.hidden = next.hidden = mode.value !== 'grid';
            if (mode.value === 'list') {
                table.update(rows);
                return;
            }
            const desired = rows.slice((page - 1) * 48, page * 48).map((item) => {
                let entry = cache.get(item.id);
                const signature = JSON.stringify(item);
                if (!entry || entry.signature !== signature) {
                    const tile = itemTile(definition(item), item, () =>
                        inspect(view.profile.items.find((i) => i.id === item.id))
                    );
                    if (entry) {
                        entry.tile.replaceChildren(...tile.childNodes);
                        entry.signature = signature;
                    } else {
                        entry = { tile, signature };
                        cache.set(item.id, entry);
                    }
                }
                return entry.tile;
            });
            if (!desired.length) desired.push(el('p', 'No items match these filters.', 'empty'));
            const wanted = new Set(desired);
            for (const child of [...grid.children]) if (!wanted.has(child)) child.remove();
            desired.forEach((child, i) => {
                if (grid.children[i] !== child) grid.insertBefore(child, grid.children[i] || null);
            });
            const ids = new Set(view.profile.items.map((i) => i.id));
            for (const key of cache.keys()) if (!ids.has(key)) cache.delete(key);
        }
        search.oninput =
            category.onchange =
            sort.onchange =
            mode.onchange =
                () => {
                    page = 1;
                    update();
                };
        update();
        return { node, update };
    }
    function sprites() {
        const node = el('div', null, 'sprite-grid'),
            cards = new Map(),
            empty = el('p', 'This Knight owns no Battle Sprites.', 'empty');
        function update() {
            const desired = view.sprites.map((s) => {
                let card = cards.get(s.state.id);
                if (!card) {
                    card = spriteCard();
                    cards.set(s.state.id, card);
                }
                card.update(s);
                return card.node;
            });
            if (!desired.length) desired.push(empty);
            const wanted = new Set(desired);
            for (const child of [...node.children]) if (!wanted.has(child)) child.remove();
            desired.forEach((child, i) => {
                if (node.children[i] !== child) node.insertBefore(child, node.children[i] || null);
            });
            const ids = new Set(view.sprites.map((s) => s.state.id));
            for (const id of cards.keys()) if (!ids.has(id)) cards.delete(id);
        }
        update();
        return { node, update };
    }
    function simpleSection() {
        const node = el('div');
        function update() {
            const p = view.profile;
            node.replaceChildren(
                activeTab === 'progression'
                    ? panel(
                          'Progression',
                          facts([
                              ['Rank', `${p.progress.rank}-${p.progress.subrank}`],
                              ['Prestige', number(p.progress.prestige)],
                              ['Saved loadouts', p.town.loadouts.length],
                              ['Learned recipes', p.town.recipes.length]
                          ]),
                          details('Saved loadouts (read only)', p.town.loadouts)
                      )
                    : panel(
                          'Knight details',
                          facts([
                              ['Account', view.username],
                              ['Guild', view.guild],
                              ['Status', view.online ? 'Online' : 'Offline'],
                              ['Permission', view.gameMaster ? 'Game Master' : 'Player']
                          ]),
                          details('Identifiers and revision', {
                              worldId: p.worldId,
                              accountId: p.accountId,
                              rosterId: p.knightId,
                              revision: p.revision
                          })
                      )
            );
        }
        update();
        return { node, update };
    }
    function showTab() {
        bodyController = sections.get(activeTab);
        if (!bodyController) {
            bodyController =
                activeTab === 'equipment'
                    ? equipment()
                    : activeTab === 'inventory'
                      ? inventory()
                      : activeTab === 'sprites'
                        ? sprites()
                        : simpleSection();
            sections.set(activeTab, bodyController);
        } else bodyController.update();
        content.replaceChildren(bodyController.node);
    }
    async function refresh() {
        const id = ++requestId;
        const username = params.get('username'),
            knight = params.get('knight'),
            key = `${username}:${knight}`;
        if (!username || !knight) {
            list.hidden = false;
            profileHost.hidden = true;
            const rows = await ctx.request('knights-all');
            if (id === requestId) roster.update(rows);
            return;
        }
        const result = await ctx.request('knight-view', { username, knight });
        if (id !== requestId) return;
        const switched = selectedKey !== key;
        view = result;
        selectedKey = key;
        list.hidden = true;
        profileHost.hidden = false;
        updateHeader();
        conflict(view.profile.worldId, view.profile.revision);
        if (switched) {
            sections.clear();
            tabBar = tabs(sectionNames, activeTab, (tab) =>
                ctx.go('knights', { username: view.username, knight: view.name, tab })
            );
            profileHost.replaceChildren(header, actions, tabBar, content);
            showTab();
        } else if (
            lastRevision !== view.profile.revision ||
            (activeTab === 'sprites' && lastLive !== JSON.stringify(view.sprites))
        )
            bodyController?.update();
        lastRevision = view.profile.revision;
        lastLive = JSON.stringify(view.sprites);
    }
    await refresh();
    return {
        node,
        interval: 4000,
        refresh,
        async navigate(next) {
            requestId++;
            const nextTab = sectionNames.some(([id]) => id === next.get('tab'))
                ? next.get('tab')
                : 'equipment';
            params = next;
            if (next.get('username') + ':' + next.get('knight') === selectedKey && view) {
                const returning = profileHost.hidden;
                list.hidden = true;
                profileHost.hidden = false;
                if (activeTab !== nextTab) {
                    activeTab = nextTab;
                    showTab();
                    for (const b of tabBar.children) {
                        b.setAttribute('aria-selected', b.dataset.tab === activeTab);
                        b.tabIndex = b.dataset.tab === activeTab ? 0 : -1;
                    }
                }
                if (returning) await refresh();
            } else {
                activeTab = nextTab;
                await refresh();
            }
        }
    };
}
