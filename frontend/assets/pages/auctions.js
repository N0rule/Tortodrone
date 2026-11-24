import { el, toolbar, button, badge, facts, details, date, localDate } from '../ui.js';
import { api } from '../api.js';
import { DataTable } from '../components/table.js';
import { openDialog, confirmAction, field } from '../components/dialog.js';
import { itemForm } from '../components/gear-editor.js';
import { itemLine, currency, definitionsFor } from '../components/items.js';
import { route, go } from '../router.js';
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
/** The states the listing-state filter offers; `?status=` matches them by name. */
const listingStates = ['Active', 'Scheduled', 'Awaiting settlement', 'Settled', 'Cancelled'];
const stateTone = (value) =>
    value === 'Active' ? 'good' : value === 'Cancelled' ? 'bad' : value === 'Settled' ? 'cyan' : '';
export async function mount(ctx) {
    const node = el('div', null, 'stack');
    let definitions = new Map(),
        rows = [],
        inspected = null,
        appliedStatus = null;
    /**
     * Rewrite this page's address, keeping the params this action does not own, so
     * inspecting a listing never resets the filter the operator arrived with.
     */
    function here(changes = {}, drop = []) {
        const params = route().params;
        for (const name of drop) params.delete(name);
        for (const [name, value] of Object.entries(changes)) params.set(name, String(value));
        go('auctions', Object.fromEntries(params));
    }
    const filterFor = (value) =>
        ['All', ...listingStates].find((name) => name.toLowerCase() === String(value).toLowerCase()) ||
        'All';
    /** The auction service settles expired listings in one pass, so say so. */
    const settle = (id) => {
        const description =
            'The auction service settles every expired listing in one pass: escrow is ' +
            'released, refunds are arranged and each delivery is recorded.' +
            (id == null
                ? ''
                : ' Settlement is not offered for one listing, so other expired ' +
                  'listings settle at the same time.');
        return confirmAction({
            title: id == null ? 'Settle expired auctions' : 'Settle auction #' + id,
            description,
            label: id == null ? 'Settle expired' : 'Settle expired listings',
            onSubmit: async () => {
                await api.change('auction-settle', {});
            }
        });
    };
    function inspectDialog(row, deliveries) {
        const a = row.listing,
            deliveryTable = new DataTable(
                [
                    [
                        'Recipient',
                        (d) =>
                            d.recipient
                                ? button(
                                      d.recipient,
                                      () => ctx.go('accounts', { username: d.recipient }),
                                      'link'
                                  )
                                : '—'
                    ],
                    ['Kind', (d) => (d.item ? 'Item delivery' : 'Crowns')],
                    ['Crowns', (d) => currency('crowns', d.crowns)],
                    ['State', (d) => (d.claimed ? 'Claimed' : 'Pending')],
                    ['Details', (d) => details('Delivery record', d)]
                ],
                {
                    key: (d) => d.id,
                    id: 'auction-deliveries',
                    caption: 'Auction deliveries',
                    exportName: 'auction-deliveries',
                    empty: {
                        title: 'No deliveries recorded',
                        hint: 'One delivery per recipient: the winning bidder and the seller.',
                        icon: 'mailing'
                    }
                }
            );
        const content = el('div', null, 'stack'),
            party = (name) =>
                name ? button(name, () => ctx.go('accounts', { username: name }), 'link') : 'None';
        content.append(
            itemLine(definitions.get(a.item.config), a.item),
            facts([
                ['Seller', party(row.seller)],
                ['Bidder', party(row.bidder)],
                ['State', badge(state(a), stateTone(state(a)))],
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
                    definitions.get(a.item.config)?.progression ? a.item.level + 1 : 'Not applicable'
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
            el('h3', 'Deliveries'),
            deliveryTable.node,
            toolbar(
                button('Back to listings', () => here({}, ['listing']), 'secondary'),
                button(
                    'Settle this listing',
                    () => settle(a.id),
                    'secondary',
                    () => state(a) === 'Awaiting settlement'
                ),
                button(
                    'Cancel listing',
                    () =>
                        confirmAction({
                            title: 'Cancel auction #' + a.id,
                            description:
                                'The auction service releases escrow and arranges any required refunds.',
                            label: 'Cancel listing',
                            onSubmit: async () => {
                                await api.change('auction-cancel', { id: a.id });
                            }
                        }),
                    'danger',
                    () => !a.ended && !a.cancelled
                )
            )
        );
        deliveryTable.update(deliveries);
        openDialog({
            title: 'Auction #' + a.id,
            content,
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        unlinkListingOnClose();
    }
    /**
     * Closing the inspector drops `?listing=`, so the address never names a listing
     * that is no longer on screen. A replacement dialog opens in the same tick, so
     * the check is deferred and skipped while one is open.
     */
    function unlinkListingOnClose() {
        const dialog = document.querySelector('#dialog'),
            previous = dialog.onclose;
        dialog.onclose = (event) => {
            previous?.call(dialog, event);
            setTimeout(() => {
                if (dialog.open || !route().params.has('listing')) return;
                here({}, ['listing']);
            }, 0);
        };
    }
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
                (r) => toolbar(currency('crowns', r.listing.bid), currency('crowns', r.listing.buy)),
                (r) => r.listing.bid
            ],
            ['Status', (r) => badge(state(r.listing), stateTone(state(r.listing)))],
            ['Expires', (r) => date(r.listing.expires), (r) => r.listing.expires]
        ],
        {
            key: (r) => r.listing.id,
            presentationKey: (r) => state(r.listing),
            id: 'auctions',
            caption: 'Auction listings',
            exportName: 'auctions',
            empty: {
                title: 'No auction listings exist',
                hint:
                    'Listings are created in the auction house in game; featured ' +
                    'auctions scheduled here appear too.',
                action: { label: 'Schedule featured auction', run: () => scheduleFeatured() },
                icon: 'auctions'
            },
            actions: (get) => [
                button('Inspect', () => here({ listing: get().listing.id })),
                button(
                    'Settle',
                    () => settle(get().listing.id),
                    'secondary',
                    () => state(get().listing) === 'Awaiting settlement'
                ),
                button(
                    'Cancel',
                    () =>
                        confirmAction({
                            title: 'Cancel auction #' + get().listing.id,
                            description:
                                'The auction service releases escrow and arranges any required refunds.',
                            label: 'Cancel listing',
                            onSubmit: async () => {
                                await api.change('auction-cancel', { id: get().listing.id });
                            }
                        }),
                    'danger',
                    () => !get().listing.ended && !get().listing.cancelled
                )
            ],
            filters: [
                [
                    'Listing state',
                    ['All', ...listingStates],
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
    function scheduleFeatured() {
        itemForm('Schedule featured auction', 'auction-create', [
            field('quantity', 'Item quantity', 1, 'number', null, { min: 1, max: 20, step: 1 }),
            field('bid', 'Opening bid', 100, 'number', null, { min: 0, step: 1 }),
            field('buy', 'Buyout (0 disables)', 0, 'number', null, { min: 0, step: 1 }),
            field('stock', 'Stock (1 for bidding)', 1, 'number', null, { min: 1, step: 1 }),
            field('starts', 'Starts', localDate(Date.now()), 'datetime-local'),
            field('expires', 'Expires', localDate(Date.now() + 86400000), 'datetime-local')
        ]);
    }
    node.append(
        toolbar(
            button('Schedule featured auction', () => scheduleFeatured()),
            button(
                'Settle expired',
                () => settle(null),
                'secondary',
                // A no-op that opens a confirmation looks like a broken button, so it
                // says why it is off instead.
                () =>
                    rows.some((r) => r.listing.expires <= Date.now()) ||
                    'No expired listings to settle.',
                { unavailable: 'No expired listings to settle.' }
            )
        ),
        table.node
    );
    async function inspect(id) {
        const row = rows.find((r) => r.listing.id === id);
        if (!row) {
            ctx.notify(`Listing #${id} is not in the current auction overview.`);
            return;
        }
        const deliveries = await ctx.request('deliveries', { id });
        if (!ctx.alive() || inspected !== id) return;
        inspectDialog(row, deliveries);
    }
    async function refresh() {
        rows = await ctx.request('auctions');
        definitions = await definitionsFor(rows.map((r) => r.listing.item.config));
        if (!ctx.alive()) return;
        table.update(rows);
    }
    await refresh();
    /**
     * `?listing=` opens the inspector for one listing and `?status=` picks a
     * listing-state filter, both without a reload. A param that is gone clears
     * the state it owned, so a filter never survives the visit that set it.
     */
    function applyParams(params) {
        if (params.get('status') !== null || appliedStatus !== null) {
            appliedStatus = params.get('status');
            table.setFilter('Listing state', filterFor(appliedStatus ?? 'All'));
        }
        const listing = Number(params.get('listing'));
        const wanted = Number.isInteger(listing) && listing > 0 ? listing : null;
        if (wanted === inspected) return;
        inspected = wanted;
        if (wanted != null) inspect(wanted);
    }
    applyParams(ctx.params);
    return { node, refresh, interval: 10000, navigate: applyParams };
}
