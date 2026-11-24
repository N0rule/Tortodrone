import { el, button, toolbar, badge, date, localDate, number, text, tabs, facts, panel, routeLink, copy, $ } from '../ui.js';
import { DataTable } from '../components/table.js';
import { field, edit, confirm, conflict, closeDialog, openDialog } from '../components/dialog.js';
import { itemForm } from '../components/gear-editor.js';
import { itemLine, currency, definitionsFor } from '../components/items.js';
import { route, go } from '../router.js';
// The window is compared with this browser's clock; the server keeps the
// authoritative times, so every state badge says where the judgement came from
// instead of asserting server truth a skewed laptop can contradict.
const CLOCK_NOTE =
    'Worked out by comparing the promotion window with this browser’s clock; the server keeps the authoritative times.';
const promotionState = (p) =>
    p.cancelled
        ? 'Cancelled'
        : p.starts > Date.now()
          ? 'Scheduled'
          : p.ends <= Date.now()
            ? 'Expired'
            : 'Active';
/** A state badge that says where the judgement came from. */
const promotionBadge = (p) => {
    const state = promotionState(p);
    return Object.assign(badge(state, state === 'Active' ? '' : 'flag'), { title: CLOCK_NOTE });
};
/** The URL names the catalogue tab `catalogue`; the page keeps `effective`. */
const tabNames = {
    offers: 'offers',
    promotions: 'promotions',
    catalogue: 'effective',
    effective: 'effective'
};
const urlTab = (id) => (id === 'effective' ? 'catalogue' : id);
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
        // A caption, pushed to the far end of the action row: at body size beside two
        // buttons, "Content revision 2" read as a third control that did nothing.
        revision = el('span', null, 'muted toolbar-note');
    let data,
        definitions = new Map(),
        tab = 'offers',
        appliedSearch = null,
        focusedOffer = null;
    /**
     * Rewrite this page's address, keeping the params this action does not own, so
     * a tab switch never drops a search the operator arrived with.
     */
    function here(changes = {}, drop = []) {
        const params = route().params;
        for (const name of drop) params.delete(name);
        for (const [name, value] of Object.entries(changes)) params.set(name, String(value));
        go('depot', Object.fromEntries(params));
    }
    const fixed = () => ({ revision: data.snapshot.revision });
    const item = (o) => itemLine(definitions.get(o.good.item), { config: o.good.item });
    /**
     * One price, not two: half of a price pair rendered as a coin beside a zero, so
     * every row opened with "0 crowns" and the operator had to work out which coin the
     * real number belonged to. Only the currencies that are actually charged appear,
     * and the record shows the same wording in words.
     */
    const price = (o) => {
        const cell = el('div', null, 'price-cell'),
            crowns = o.good.price,
            energy = o.good.energy;
        if (crowns) cell.append(currency('crowns', crowns));
        if (energy) cell.append(currency('energy', energy));
        if (!crowns && !energy) cell.append(el('span', 'Free', 'muted'));
        cell.title = [
            crowns ? `${crowns.toLocaleString()} Crowns` : '',
            energy ? `${energy.toLocaleString()} Energy` : ''
        ]
            .filter(Boolean)
            .join(' + ');
        return cell;
    };
    const offerName = (o) => definitions.get(o.good.item)?.name || o.good.item;
    /**
     * Offers 0 and 1 stock the same item with different durations, so every row names
     * the config it actually sells and its own id: two priced offers must never be
     * visually identical. The last two path segments carry the difference — except the
     * leaf, when the leaf is the name the line already printed above it, which turned
     * 36 rows of "Acheron / Sword / Acheron" into a stutter.
     */
    const configNote = (o) => {
        const segments = String(o.good.item || '')
            .split('/')
            .filter(Boolean);
        const name = offerName(o);
        // Whatever is left after the leaf is dropped, and nothing at all when the path
        // says no more than the name already did.
        return segments
            .slice(-2)
            .filter((segment) => segment !== name)
            .join(' / ');
    };
    const offerDetail = (o) =>
        [configNote(o), `offer #${o.id}`].filter(Boolean).join(' · ');
    /** A `<select>` option is a string, so the price it names is spelled out and grouped. */
    const offerLabel = (o) => `${offerName(o)} · offer #${o.id} · ${number(o.good.price)} Crowns`;
    /** One editor for the row action and for a deep link into a single offer. */
    function editOffer(o) {
        itemForm('Edit offer #' + o.id, 'depot-save', fields(o), { ...fixed(), id: o.id }, o.good.item);
        // The editor is shared with the offer list, so the way back leads the same
        // dialog body instead of becoming a second surface to learn.
        $('#fields')?.prepend(
            toolbar(
                button(
                    'Back to offers',
                    () => {
                        if (closeDialog()) here({}, ['offer']);
                    },
                    'secondary'
                )
            )
        );
    }
    function createOffer() {
        itemForm('Create Depot offer', 'depot-save', fields(), { ...fixed(), id: -1 });
    }
    /**
     * An offer opens as a record before it opens as a form: a price, a limit and a
     * list of discounts are things to read, and every deep link used to land the
     * operator inside an editor with the server snapshot under their cursor.
     */
    function showOffer(offer) {
        const promotionsFor = data.promotions.filter((p) => p.offerId === offer.id);
        const list = el('div', null, 'stack-tight');
        if (!promotionsFor.length)
            list.append(el('p', 'No promotion discounts this offer.', 'muted'));
        else
            for (const p of promotionsFor) {
                const row = el('div', null, 'offer-reference');
                // The discount is money, so it wears the same coin and the same grouped
                // digits as the price it comes off.
                const summary = el('strong', `${promotionState(p)} · `);
                summary.title = CLOCK_NOTE;
                summary.append(currency('crowns', p.crowns));
                row.append(
                    summary,
                    el(
                        'small',
                        `${date(p.starts)} → ${date(p.ends)} · promotion #${p.id}`,
                        'muted'
                    )
                );
                list.append(row);
            }
        openDialog({
            title: `Offer #${offer.id}`,
            content: panel(
                '',
                // The title is the record's id, not its item: the item arrives as art and
                // a name, the way every other surface names an item. Before this, the
                // one dialog about an item showed no picture of it.
                // The record head already prints the item's name, so it takes no note: the
                // line under it is the category alone. The table row keeps the last two
                // path segments, where two offers of the same item have to differ.
                itemLine(definitions.get(offer.good.item), { config: offer.good.item }, {
                    label: offerName(offer)
                }),
                el('p', offer.enabled ? 'Enabled in the Depot.' : 'Disabled.', 'muted record-state'),
                // The title already names the item and offers the id, and the list under
                // Promotions already says how many there are: both were printed twice
                // in one dialog, once as a heading and once as a row of its own.
                facts([
                    ['Quantity', String(offer.good.count)],
                    // The same renderer the row uses, so money looks the same in the
                    // record as in the list that opened it.
                    ['Price', price(offer)],
                    ['Lifetime limit', offer.good.limit ? String(offer.good.limit) : 'Unlimited'],
                    ['Featured', offer.featured ? 'yes' : 'no'],
                    ['Promotion only', offer.promotionOnly ? 'yes' : 'no']
                ]),
                el(
                    'h3',
                    promotionsFor.length
                        ? `Promotions (${promotionsFor.length})`
                        : 'Promotions'
                ),
                list,
                toolbar(
                    button('Edit this offer', () => editOffer(offer), 'primary'),
                    button('Schedule promotion', () => schedulePromotion(offer), 'secondary'),
                    button('Copy offer id', () => copy(String(offer.id), 'Offer id copied.'), 'quiet')
                )
            ),
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        unlinkOfferOnClose();
    }
    /** Promotions are scheduled from an offer, whichever tab starts the action. */
    function schedulePromotion(offer) {
        const options = data.snapshot.offers.map((o) => [String(o.id), offerLabel(o)]);
        if (!options.length) {
            // Nothing can be discounted yet: send the operator to the step that
            // creates the offer instead of failing silently.
            ctx.notify('Create a depot offer before scheduling a promotion.', true);
            here({ tab: 'offers' }, ['offer']);
            return;
        }
        const chosen = offer || data.snapshot.offers[0];
        edit(
            offer ? 'Promotion · ' + offerName(offer) : 'Schedule promotion',
            'promotion-create',
            [
                field('offerId', 'Offer', String(chosen.id), 'text', options),
                field('starts', 'Starts', localDate(Date.now()), 'datetime-local'),
                field('ends', 'Ends', localDate(Date.now() + 86400000), 'datetime-local'),
                field('crowns', 'Promotional Crowns', Math.max(0, chosen.good.price), 'number', null, {
                    min: 0,
                    step: 1
                }),
                field('energy', 'Promotional Energy', chosen.good.energy, 'number', null, {
                    min: 0,
                    step: 1
                }),
                field('referencePrice', 'Reference price (optional)', '', 'number', null, {
                    min: 0,
                    step: 1
                }),
                field('featured', 'Featured', true, 'checkbox')
            ],
            { ...fixed() }
        );
    }
    const offers = new DataTable(
        [
            [
                'Item',
                (o) =>
                    itemLine(definitions.get(o.good.item), { config: o.good.item }, {
                        note: offerDetail(o),
                        compact: true
                    }),
                (o) => definitions.get(o.good.item)?.name || o.good.item
            ],
            ['Quantity', (o) => o.good.count, (o) => o.good.count, 'end'],
            ['Price', price, (o) => o.good.price, 'end'],
            // A limit is the exception; "Unlimited" in 30 of 30 rows is a column of
            // noise where the item's own detail line should have the width.
            [
                'Limit',
                (o) => o.good.limit || el('span', '—', 'muted'),
                (o) => o.good.limit || 0,
                'end'
            ],
            [
                'Status',
                (o) =>
                    toolbar(
                        o.enabled ? badge('Enabled') : badge('Disabled', 'flag'),
                        ...(o.featured ? [badge('Featured', 'gold')] : []),
                        ...(o.promotionOnly ? [badge('Promotion only', 'flag')] : [])
                    )
            ]
        ],
        {
            id: 'depot-offers',
            address: true,
            caption: 'Depot offers',
            searchPlaceholder: 'Search item, offer id or price',
            // Thirty rows of the same limit and the same status is a column of noise.
            dropConstantColumns: true,
            exportName: 'depot-offers',
            // Offers arrive in the order they were written; an operator reads them by
            // item, which is what the first column says.
            defaultSort: { index: 0 },
            empty: {
                title: 'No depot offers are defined',
                hint: 'An offer stocks one item in the Supply Depot; players only see enabled offers.',
                action: { label: 'Create offer', run: () => createOffer() },
                icon: 'depot'
            },
            // One control per row: the record this opens carries "Edit this offer", so
            // the second button on 328 rows was the same destination twice. It is
            // named for what it opens — "Inspect" said nothing about an offer, and it
            // used the same word as every other row on the server.
            actions: (get) => [
                routeLink('Open offer', 'depot', { offer: get().id }, 'button secondary')
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
                    if (!offer) {
                        const cell = el('div', null, 'offer-reference');
                        cell.append(el('strong', `Offer #${p.offerId}`));
                        cell.append(el('small', 'not in this snapshot', 'muted'));
                        return cell;
                    }
                    return itemLine(
                        definitions.get(offer.good.item),
                        { config: offer.good.item },
                        { note: `offer #${p.offerId}`, compact: true }
                    );
                },
                (p) => p.offerId
            ],
            ['State', (p) => promotionBadge(p)],
            ['Starts', (p) => date(p.starts), (p) => p.starts],
            ['Ends', (p) => date(p.ends), (p) => p.ends],
            [
                'Price',
                // A promotion discounts in one currency, so the other amount is zero and
                // a zero beside the coin reads as a second, empty price. The offer list
                // already declines to print it.
                (p) => {
                    const cell = toolbar();
                    if (p.crowns) cell.append(currency('crowns', p.crowns));
                    if (p.energy) cell.append(currency('energy', p.energy));
                    return cell;
                },
                (p) => p.crowns || p.energy,
                'end'
            ]
        ],
        {
            presentationKey: promotionState,
            id: 'promotions',
            caption: 'Scheduled promotions',
            searchPlaceholder: 'Search promotion or offer id',
            exportName: 'promotions',
            empty: {
                title: 'No promotions are scheduled',
                hint: 'A promotion discounts one offer between two dates. Create an offer on the Offers tab, then schedule its promotion from that offer.',
                action: {
                    label: 'Schedule promotion',
                    run: () => schedulePromotion(null)
                },
                icon: 'add'
            },
            actions: (get) => [
                button(
                    'Open offer',
                    () => here({ tab: 'offers', offer: get().offerId }),
                    'secondary',
                    () => data.snapshot.offers.some((o) => o.id === get().offerId),
                    {
                        unavailable:
                            'The offer this promotion discounts is not in the current snapshot, so there is nothing to open.'
                    }
                ),
                button(
                    'Cancel',
                    () =>
                        confirm(
                            'Cancel promotion #' + get().id,
                            'promotion-cancel',
                            { ...fixed(), id: get().id },
                            [],
                            'The discount comes off offer #' + get().offerId + ' for the rest of its window, and a cancelled promotion cannot be reinstated — a new one has to be scheduled.',
                            { destructive: true, submit: 'Cancel promotion' }
                        ),
                    'secondary',
                    () => !get().cancelled,
                    {
                        unavailable:
                            'This promotion is already cancelled, and a cancelled promotion cannot be reinstated — scheduling a new one is the only way back.'
                    }
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
            ['Quantity', (o) => o.good.count, (o) => o.good.count, 'end'],
            ['Price', price, undefined, 'end'],
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
            searchPlaceholder: 'Search item or station',
            exportName: 'depot-catalogue',
            empty: {
                title: 'The player catalogue is empty',
                hint: 'Enable a depot offer to publish it to the catalogue players browse in game.',
                action: { label: 'Show offers', run: () => here({ tab: 'offers' }, ['offer']) },
                icon: 'depot'
            },
            actions: (get) => [
                button(
                    'Open offer',
                    () => {
                        const offer = data.snapshot.offers.find(
                            (candidate) => candidate.good.item === get().good.item
                        );
                        if (offer) here({ tab: 'offers', offer: offer.id });
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
        const lead = tab === 'promotions' ? createPromotionButton : createOfferButton;
        for (const control of [createOfferButton, createPromotionButton]) {
            control.classList.toggle('primary', control === lead);
            control.classList.toggle('secondary', control !== lead);
        }
        // The tab that has no create action shows neither as the page's lead.
        if (tab === 'effective') {
            createOfferButton.classList.replace('primary', 'secondary');
            createPromotionButton.classList.replace('primary', 'secondary');
        }
    }
    const tabBar = tabs(
        [
            ['offers', 'Offers'],
            ['promotions', 'Promotions'],
            ['effective', 'Player catalogue & Specials']
        ],
        tab,
        // The tab is page state, so it belongs in the address: a reload or a shared
        // link lands on the same view.
        (id) => here({ tab: urlTab(id) }, ['offer'])
    );
    /** Sync the strip in place: rebuilding it would drop keyboard focus. */
    function paintTabs() {
        tabBar.setSelected(tab);
    }
    // The filled action follows the tab: on Promotions, the page's one primary must
    // not still be the offer editor.
    const createOfferButton = button('Create offer', () => createOffer()),
        createPromotionButton = button('Schedule promotion', () => schedulePromotion(null));
    tabBar.bindPanel(content);
    node.append(toolbar(createOfferButton, createPromotionButton, revision), tabBar, content);
    paintTabs();
    show();
    /**
     * Closing the deep-linked offer editor drops `?offer=`, so the address never
     * names an offer that is no longer on screen. A replacement editor opens in
     * the same tick, so the check is deferred and skipped while one is open.
     */
    function unlinkOfferOnClose() {
        const dialog = $('#dialog'),
            previous = dialog.onclose;
        dialog.onclose = (event) => {
            previous?.call(dialog, event);
            setTimeout(() => {
                if (dialog.open || !route().params.has('offer')) return;
                here({}, ['offer']);
            }, 0);
        };
    }
    function focusOffer(id, fromAddress = true) {
        if (id === focusedOffer) return;
        focusedOffer = id;
        if (id == null) return;
        const offer = data.snapshot.offers.find((o) => o.id === id);
        if (!offer) {
            ctx.notify(`Offer #${id} is not in the current depot snapshot.`);
            return;
        }
        tab = 'offers';
        paintTabs();
        show();
        // The address names a record, so the record is what opens: an editor behind a
        // load is a write form the operator never asked for.
        if (fromAddress) showOffer(offer);
        else editOffer(offer);
        unlinkOfferOnClose();
    }
    /**
     * A search that came from the address is re-applied on every visit and cleared
     * when it is gone; a search the operator typed is left alone.
     */
    function applySearch(params) {
        const next = params.get('search');
        if (next === null && appliedSearch === null) return;
        appliedSearch = next;
        offers.setQuery(next ?? '');
        effective.setQuery(next ?? '');
    }
    async function refresh() {
        data = await ctx.request('depot');
        definitions = await definitionsFor(data.snapshot.offers.map((o) => o.good.item));
        if (!ctx.alive()) return;
        conflict('depot-save', data.snapshot.revision);
        conflict('promotion-create', data.snapshot.revision);
        conflict('promotion-cancel', data.snapshot.revision);
        // The name is rendered from the item config, so a search for the item the
        // operator can see must find it: the row carries its own name.
        offers.update(
            data.snapshot.offers.map((o) => ({ ...o, name: offerName(o) }))
        );
        promotions.update(data.promotions);
        effective.update(data.effective.offers.filter((o) => o.enabled));
        text(revision, `Content revision ${data.snapshot.revision}`);
    }
    await refresh();
    /** Deep links re-apply the tab, the search and the focused offer in place. */
    function applyParams(params) {
        const requested = tabNames[params.get('tab') || ''] || 'offers';
        if (requested !== tab) {
            tab = requested;
            paintTabs();
            show();
        }
        applySearch(params);
        // `Number(null)` is 0, so an absent parameter used to pass the >= 0 guard and
        // open offer #0's editor on every load of the depot.
        const raw = params.get('offer'),
            offer = raw === null || raw === '' ? null : Number(raw);
        focusOffer(Number.isInteger(offer) && offer >= 0 ? offer : null);
    }
    applyParams(ctx.params);
    return { node, refresh, interval: 10000, navigate: applyParams };
}
