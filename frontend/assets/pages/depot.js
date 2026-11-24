import { el, button, toolbar, panel, badge, date, localDate, text, tabs } from '../ui.js';
import { DataTable } from '../components/table.js';
import { field, edit, confirm, conflict } from '../components/dialog.js';
import { itemForm } from '../components/gear-editor.js';
import { itemLine, currency, definitionsFor } from '../components/items.js';
const promotionState = (p) =>
    p.cancelled
        ? 'Cancelled'
        : p.starts > Date.now()
          ? 'Scheduled'
          : p.ends <= Date.now()
            ? 'Expired'
            : 'Active';
function fields(o) {
    const g = o?.good || {};
    return [
        field('quantity', 'Quantity', g.count || 1, 'number', null, { min: 1, step: 1 }),
        field('crowns', 'Crowns', Math.max(0, g.price || 0), 'number', null, { min: 0, step: 1 }),
        field('energy', 'Energy', g.energy || 0, 'number', null, { min: 0, step: 1 }),
        field('limit', 'Lifetime purchase limit (0 unlimited)', g.limit || 0, 'number', null, {
            min: 0,
            step: 1
        }),
        field('featured', 'Featured', o?.featured || false, 'checkbox'),
        field('enabled', 'Enabled', o?.enabled ?? true, 'checkbox'),
        field('promotionOnly', 'Promotion only', o?.promotionOnly || false, 'checkbox')
    ];
}
export async function mount(ctx) {
    const node = el('div', null, 'stack'),
        revision = el('span', null, 'muted');
    let data,
        definitions = new Map(),
        tab = 'offers';
    const fixed = () => ({ revision: data.snapshot.revision });
    const item = (o) => itemLine(definitions.get(o.good.item), { config: o.good.item });
    const price = (o) =>
        toolbar(currency('crowns', o.good.price), currency('energy', o.good.energy));
    const offers = new DataTable(
        [
            ['Item', item, (o) => definitions.get(o.good.item)?.name || o.good.item],
            ['Quantity', (o) => o.good.count],
            ['Price', price, (o) => o.good.price],
            ['Limit', (o) => o.good.limit || 'Unlimited'],
            [
                'Status',
                (o) =>
                    toolbar(
                        badge(o.enabled ? 'Enabled' : 'Disabled', o.enabled ? 'good' : ''),
                        ...(o.featured ? [badge('Featured', 'gold')] : []),
                        ...(o.promotionOnly ? [badge('Promotion only')] : [])
                    )
            ]
        ],
        {
            id: 'depot-offers',
            caption: 'Depot offers',
            exportName: 'depot-offers',
            actions: (get) => [
                button('Edit', () => {
                    const o = get();
                    itemForm(
                        'Edit offer #' + o.id,
                        'depot-save',
                        fields(o),
                        { ...fixed(), id: o.id },
                        o.good.item
                    );
                }),
                button('Schedule promotion', () => {
                    const o = get();
                    edit(
                        'Promotion · ' + (definitions.get(o.good.item)?.name || 'Offer #' + o.id),
                        'promotion-create',
                        [
                            field('starts', 'Starts', localDate(Date.now()), 'datetime-local'),
                            field(
                                'ends',
                                'Ends',
                                localDate(Date.now() + 86400000),
                                'datetime-local'
                            ),
                            field(
                                'crowns',
                                'Promotional Crowns',
                                Math.max(0, o.good.price),
                                'number',
                                null,
                                { min: 0, step: 1 }
                            ),
                            field('energy', 'Promotional Energy', o.good.energy, 'number', null, {
                                min: 0,
                                step: 1
                            }),
                            field(
                                'referencePrice',
                                'Reference price (optional)',
                                '',
                                'number',
                                null,
                                { min: 0, step: 1 }
                            ),
                            field('featured', 'Featured', true, 'checkbox')
                        ],
                        { ...fixed(), offerId: o.id }
                    );
                })
            ],
            filters: [
                [
                    'Availability',
                    ['All', 'Enabled', 'Disabled', 'Featured', 'Promotion only'],
                    (o, v) =>
                        v === 'All' ||
                        (v === 'Enabled' && o.enabled) ||
                        (v === 'Disabled' && !o.enabled) ||
                        (v === 'Featured' && o.featured) ||
                        (v === 'Promotion only' && o.promotionOnly)
                ]
            ]
        }
    );
    const promotions = new DataTable(
        [
            ['Promotion', (p) => p.id],
            [
                'Offer',
                (p) => {
                    // A promotion that only shows an offer id is a dead end: name the
                    // item it discounts and keep the id beside it.
                    const offer = data.snapshot.offers.find((o) => o.id === p.offerId);
                    const cell = el('div', null, 'offer-reference');
                    cell.append(
                        el(
                            'strong',
                            offer
                                ? definitions.get(offer.good.item)?.name || offer.good.item
                                : `Offer #${p.offerId}`
                        )
                    );
                    cell.append(el('small', `offer #${p.offerId}`, 'muted'));
                    return cell;
                },
                (p) => p.offerId
            ],
            [
                'State',
                (p) => badge(promotionState(p), promotionState(p) === 'Active' ? 'good' : '')
            ],
            ['Starts', (p) => date(p.starts), (p) => p.starts],
            ['Ends', (p) => date(p.ends), (p) => p.ends],
            ['Price', (p) => toolbar(currency('crowns', p.crowns), currency('energy', p.energy))]
        ],
        {
            presentationKey: promotionState,
            id: 'promotions',
            caption: 'Scheduled promotions',
            exportName: 'promotions',
            actions: (get) => [
                button(
                    'Show offer',
                    () => {
                        const offer = data.snapshot.offers.find((o) => o.id === get().offerId);
                        tab = 'offers';
                        show();
                        if (offer) {
                            offers.query.value =
                                definitions.get(offer.good.item)?.name || offer.good.item;
                            offers.query.dispatchEvent(new Event('input'));
                        }
                        offers.node.scrollIntoView({ block: 'center' });
                    },
                    'secondary'
                ),
                button(
                    'Cancel',
                    () =>
                        confirm('Cancel promotion #' + get().id, 'promotion-cancel', {
                            ...fixed(),
                            id: get().id
                        }),
                    'secondary',
                    () => !get().cancelled
                )
            ],
            filters: [
                [
                    'Promotion state',
                    ['All', 'Active', 'Scheduled', 'Expired', 'Cancelled'],
                    (p, v) => v === 'All' || promotionState(p) === v
                ]
            ]
        }
    );
    const effective = new DataTable(
        [
            ['Item', item],
            ['Quantity', (o) => o.good.count],
            ['Price', price],
            [
                'Availability',
                (o) => (o.sale ? 'Promotion sale' : o.featured ? 'Featured' : 'Regular')
            ],
            [
                'Promotion',
                (o) => {
                    if (!o.promotionId) return el('span', '—', 'muted');
                    const cell = el('div', null, 'offer-reference');
                    cell.append(el('strong', `#${o.promotionId}`));
                    if (o.promotionEnds)
                        cell.append(el('small', `ends ${date(o.promotionEnds)}`, 'muted'));
                    else cell.append(el('small', o.sale ? 'sale price' : 'linked', 'muted'));
                    return cell;
                },
                (o) => o.promotionId || 0
            ],
            [
                'Was',
                (o) =>
                    o.originalPrice > 0
                        ? toolbar(currency('crowns', o.originalPrice))
                        : el('span', '—', 'muted'),
                (o) => o.originalPrice || 0
            ]
        ],
        {
            key: (o) => o.id,
            id: 'depot-effective',
            caption: 'Effective catalogue: what a player sees today',
            exportName: 'depot-catalogue',
            actions: (get) => [
                button(
                    'Open offer',
                    () => {
                        const offer = data.snapshot.offers.find(
                            (candidate) => candidate.good.item === get().good.item
                        );
                        if (!offer) return;
                        tab = 'offers';
                        show();
                        offers.query.value =
                            definitions.get(offer.good.item)?.name || offer.good.item;
                        offers.query.dispatchEvent(new Event('input'));
                        offers.node.scrollIntoView({ block: 'center' });
                    },
                    'secondary'
                )
            ]
        }
    );
    const content = el('div');
    function show() {
        content.replaceChildren(
            tab === 'offers' ? offers.node : tab === 'promotions' ? promotions.node : effective.node
        );
    }
    node.append(
        toolbar(
            button(
                'Create offer',
                () => itemForm('Create Depot offer', 'depot-save', fields(), { ...fixed(), id: -1 }),
                'primary'
            ),
            revision
        ),
        tabs(
            [
                ['offers', 'Offers'],
                ['promotions', 'Promotions'],
                ['effective', 'Player catalogue & Specials']
            ],
            tab,
            (id) => {
                tab = id;
                show();
            }
        ),
        content
    );
    show();
    async function refresh() {
        data = await ctx.request('depot');
        definitions = await definitionsFor(data.snapshot.offers.map((o) => o.good.item));
        if (!ctx.alive()) return;
        conflict('depot-save', data.snapshot.revision);
        conflict('promotion-create', data.snapshot.revision);
        conflict('promotion-cancel', data.snapshot.revision);
        offers.update(data.snapshot.offers);
        promotions.update(data.promotions);
        effective.update(data.effective.offers.filter((o) => o.enabled));
        text(revision, `Revision ${data.snapshot.revision}`);
    }
    await refresh();
    return { node, refresh, interval: 10000 };
}
