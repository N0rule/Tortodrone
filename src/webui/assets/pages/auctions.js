import { el, toolbar, button, panel, badge, facts, details, date, localDate } from '../ui.js';
import { DataTable } from '../components/table.js';
import { confirm, field } from '../components/dialog.js';
import { itemForm } from '../components/gear-editor.js';
import { itemLine, currency, definitionsFor } from '../components/items.js';
const state = (a) =>
    a.cancelled
        ? 'Cancelled'
        : a.ended
          ? 'Settled'
          : a.expires <= Date.now()
            ? 'Awaiting settlement'
            : a.availableAt > Date.now()
              ? 'Scheduled'
              : 'Active';
export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        detail = el('div');
    let definitions = new Map(),
        selected = null,
        rows = [],
        selection = 0,
        detailSignature;
    const table = new DataTable(
        [
            [
                'Item',
                (r) => itemLine(definitions.get(r.listing.item.config), r.listing.item),
                (r) => definitions.get(r.listing.item.config)?.name || r.listing.item.config
            ],
            ['Listing', (r) => r.listing.id],
            ['Seller', (r) => r.seller || 'System'],
            ['Bidder', (r) => r.bidder || 'None'],
            [
                'Bid / buyout',
                (r) =>
                    toolbar(currency('crowns', r.listing.bid), currency('crowns', r.listing.buy)),
                (r) => r.listing.bid
            ],
            ['Status', (r) => badge(state(r.listing))],
            ['Expires', (r) => date(r.listing.expires), (r) => r.listing.expires]
        ],
        {
            key: (r) => r.listing.id,
            presentationKey: (r) => state(r.listing),
            actions: (get) => [
                button('Inspect', () => {
                    selected = get().listing.id;
                    return inspect();
                }),
                button(
                    'Cancel',
                    () =>
                        confirm(
                            'Cancel auction #' + get().listing.id,
                            'auction-cancel',
                            { id: get().listing.id },
                            [],
                            'The auction service will release escrow and arrange any required refunds.'
                        ),
                    'secondary',
                    () => !get().listing.ended && !get().listing.cancelled
                )
            ],
            filters: [
                [
                    'Listing state',
                    ['All', 'Active', 'Scheduled', 'Awaiting settlement', 'Settled', 'Cancelled'],
                    (r, v) => v === 'All' || state(r.listing) === v
                ],
                [
                    'Seller type',
                    ['All', 'Player', 'System'],
                    (r, v) => v === 'All' || r.listing.featuredTotal > 0 === (v === 'System')
                ]
            ]
        }
    );
    async function inspect() {
        const id = ++selection,
            row = rows.find((r) => r.listing.id === selected);
        if (!row) {
            detailSignature = null;
            detail.replaceChildren();
            return;
        }
        const deliveries = await ctx.request('deliveries', { id: selected });
        if (id !== selection) return;
        const signature = JSON.stringify([row, deliveries, state(row.listing)]);
        if (detailSignature === signature) return;
        detailSignature = signature;
        const a = row.listing;
        const deliveryTable = new DataTable(
            [
                ['Recipient', (d) => d.recipient],
                ['Kind', (d) => (d.item ? 'Item delivery' : 'Crowns')],
                ['Crowns', (d) => currency('crowns', d.crowns)],
                ['State', (d) => (d.claimed ? 'Claimed' : 'Pending')],
                ['Details', (d) => details('Delivery record', d)]
            ],
            { key: (d) => d.id }
        );
        deliveryTable.update(deliveries);
        detail.replaceChildren(
            panel(
                'Auction #' + a.id,
                itemLine(definitions.get(a.item.config), a.item),
                facts([
                    ['Seller', row.seller || 'System'],
                    ['Bidder', row.bidder || 'None'],
                    ['State', state(a)],
                    [
                        'Stock',
                        a.featuredTotal > 0
                            ? `${a.featuredRemaining} / ${a.featuredTotal}`
                            : 'Player listing'
                    ],
                    ['Starts', date(a.availableAt)],
                    ['Expires', date(a.expires)],
                    ['Opening / current bid', a.bid.toLocaleString() + ' Crowns'],
                    ['Buyout', a.buy ? a.buy.toLocaleString() + ' Crowns' : 'Disabled'],
                    [
                        'Level',
                        definitions.get(a.item.config)?.progression
                            ? a.item.level + 1
                            : 'Not applicable'
                    ],
                    [
                        'Heat',
                        definitions.get(a.item.config)?.progression
                            ? `${(a.item.heatProgress * 100).toFixed(1)}%`
                            : 'Not applicable'
                    ],
                    [
                        'Binding',
                        a.item.bound ? 'Bound' : a.item.bindOnEquip ? 'Binds on equip' : 'Unbound'
                    ],
                    [
                        'Escrow',
                        a.cancelled || a.ended
                            ? 'Settlement recorded; see deliveries below'
                            : 'Held by the auction service'
                    ]
                ]),
                details('Listing and escrow state', row),
                panel('Deliveries', deliveryTable.node)
            )
        );
    }
    node.append(
        toolbar(
            button('Schedule featured auction', () =>
                itemForm('Schedule featured auction', 'auction-create', [
                    field('quantity', 'Item quantity', 1, 'number', null, {
                        min: 1,
                        max: 20,
                        step: 1
                    }),
                    field('bid', 'Opening bid', 100, 'number', null, { min: 0, step: 1 }),
                    field('buy', 'Buyout (0 disables)', 0, 'number', null, { min: 0, step: 1 }),
                    field('stock', 'Stock (1 for bidding)', 1, 'number', null, { min: 1, step: 1 }),
                    field('starts', 'Starts', localDate(Date.now()), 'datetime-local'),
                    field('expires', 'Expires', localDate(Date.now() + 86400000), 'datetime-local')
                ])
            ),
            button('Settle expired', () =>
                confirm(
                    'Settle expired auctions',
                    'auction-settle',
                    {},
                    [],
                    'The auction service handles settlement, refunds and deliveries.'
                )
            )
        ),
        table.node,
        detail
    );
    async function refresh() {
        rows = await ctx.request('auctions');
        definitions = await definitionsFor(rows.map((r) => r.listing.item.config));
        if (!ctx.alive()) return;
        table.update(rows);
        if (selected != null) await inspect();
    }
    await refresh();
    return { node, refresh, interval: 10000 };
}
