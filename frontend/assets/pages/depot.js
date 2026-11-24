import { el, button, toolbar, badge, date, localDate, text, tabs, facts, panel, routeLink, copy, $ } from '../ui.js';
import { DataTable } from '../components/table.js';
import { field, edit, confirm, conflict, closeDialog, openDialog } from '../components/dialog.js';
import { itemForm } from '../components/gear-editor.js';
import { itemLine, currency, definitionsFor } from '../components/items.js';
import { route, go } from '../router.js';
const promotionState = (p) =>
    p.cancelled
        ? 'Cancelled'
        : p.starts > Date.now()
          ? 'Scheduled'
          : p.ends <= Date.now()
            ? 'Expired'
            : 'Active';
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
        revision = el('span', null, 'muted');
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
    const price = (o) =>
        toolbar(currency('crowns', o.good.price), currency('energy', o.good.energy));
    const offerName = (o) => definitions.get(o.good.item)?.name || o.good.item;
    const offerLabel = (o) => `${offerName(o)} · offer #${o.id} · ${o.good.price} Crowns`;
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
                row.append(
                    el('strong', `${promotionState(p)} · ${p.crowns} Crowns`),
                    el(
                        'small',
                        `${date(p.starts)} → ${date(p.ends)} · promotion #${p.id}`,
                        'muted'
                    )
                );
                list.append(row);
            }
        openDialog({
            title: `${offerName(offer)} · offer #${offer.id}`,
            content: panel(
                '',
                el('p', offer.enabled ? 'Enabled in the Depot.' : 'Disabled.', 'muted'),
                facts([
                    ['Item', offerName(offer)],
                    ['Quantity', String(offer.good.count)],
                    ['Price', `${offer.good.price} Crowns${offer.good.energy ? ` + ${offer.good.energy} Energy` : ''}`],
                    ['Lifetime limit', offer.good.limit ? String(offer.good.limit) : 'Unlimited'],
                    ['Featured', offer.featured ? 'yes' : 'no'],
                    ['Promotion only', offer.promotionOnly ? 'yes' : 'no'],
                    ['Promotions', promotionsFor.length ? String(promotionsFor.length) : 'none'],
                    ['Offer id', String(offer.id)]
                ]),
                el('h3', 'Promotions'),
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
                // Offers 0 and 1 stock the same item with different durations, so the row
                // names the config it actually sells and its own id: two priced offers
                // must never be visually identical.
                (o) => {
                    const cell = el('div', null, 'offer-reference');
                    cell.append(item(o));
                    const detail = String(o.good.item || '')
                        .split('/')
                        .filter(Boolean)
                        .slice(-2)
                        .join(' / ');
                    cell.append(el('small', `${detail} · offer #${o.id}`, 'muted'));
                    return cell;
                },
                (o) => definitions.get(o.good.item)?.name || o.good.item
            ],
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
            address: true,
            caption: 'Depot offers',
            exportName: 'depot-offers',
            empty: {
                title: 'No depot offers are defined',
                hint: 'An offer stocks one item in the Supply Depot; players only see enabled offers.',
                action: { label: 'Create offer', run: () => createOffer() },
                icon: 'depot'
            },
            actions: (get) => [
                routeLink('Inspect', 'depot', { offer: get().id }, 'button secondary'),
                button('Edit', () => editOffer(get()))
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
                            offer ? offerName(offer) : `Offer #${p.offerId}`
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
                    () => data.snapshot.offers.some((o) => o.id === get().offerId)
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
        for (const control of tabBar.children) {
            const selected = control.dataset.tab === tab;
            control.setAttribute('aria-selected', selected);
            control.tabIndex = selected ? 0 : -1;
        }
    }
    // The filled action follows the tab: on Promotions, the page's one primary must
    // not still be the offer editor.
    const createOfferButton = button('Create offer', () => createOffer()),
        createPromotionButton = button('Schedule promotion', () => schedulePromotion(null));
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
