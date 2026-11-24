import { setSymbols, symbol } from './symbols.js';
import { el, number, heat, $, toolbar, button, details, report } from '../ui.js';
import { openDialog } from './dialog.js';
import { go } from '../router.js';
import { api } from '../api.js';
let manifest = { items: {}, ui: {}, sprites: {} };
const definitions = new Map();
export async function definitionsFor(configs) {
    const missing = [...new Set(configs)].filter((c) => c && !definitions.has(c));
    for (let i = 0; i < missing.length; i += 100) {
        const rows = await api.request('item-definitions', {
            configs: missing.slice(i, i + 100).join('\n')
        });
        for (const row of rows) definitions.set(row.config, row);
    }
    return definitions;
}
export async function loadAssets() {
    try {
        const response = await fetch('/admin/assets/game/manifest.json', { cache: 'no-cache' });
        if (response.ok) {
            manifest = await response.json();
            setSymbols(manifest.symbols, manifest.ui);
            if (manifest.symbols?.brand)
                for (const image of document.querySelectorAll('.brand > img'))
                    image.replaceWith(symbol('brand'));
        }
    } catch {
        /* Every icon has a local fallback. */
    }
}
function path(value) {
    return typeof value === 'string' && /^[A-Za-z0-9_./-]+$/.test(value) && !value.includes('..')
        ? '/admin/assets/game/' + value
        : null;
}
export function icon(config, rarity = 0) {
    const root = el('span', null, 'item-icon'),
        image = el('img');
    image.src = path(manifest.items?.[config]) || '/admin/assets/icons/fallback.svg';
    image.alt = '';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.onerror = () => {
        image.onerror = null;
        image.src = '/admin/assets/icons/fallback.svg';
    };
    root.append(image);
    if (rarity > 0) root.append(el('span', '★'.repeat(Math.min(5, rarity)), 'stars'));
    return root;
}
/**
 * One item, with its name as the drill-down into the shared inspector.
 * @param {any} [definition] the catalogue definition, when the caller already has it
 * @param {any} [item] a knight's item instance, when the caller has one
 * @param {{actions?: Node[], label?: string}} [options]
 */
export function itemLine(definition, item, { actions, label } = {}) {
    const root = el('div', null, 'item-line'),
        names = el('div');
    const config = definition?.config || item?.config;
    const name = definition?.name || config?.split('/').at(-1) || 'Unknown item';
    // One dialog exists at a time, so only a name outside an editor can drill: a
    // link inside a dialog would replace the editor the operator is filling in.
    if (config && !document.querySelector('#dialog')?.open) {
        // The name is the drill-down: one control, one inspector, everywhere an
        // item is listed. `.link` is the existing link-styled button variant.
        const control = el('button', label || name, 'link');
        control.type = 'button';
        control.title = 'Inspect ' + (label || name);
        control.onclick = () =>
            inspectItemName(config, { definition, item, actions }).catch(report);
        names.append(control);
    } else names.append(el('strong', label || name));
    if (definition?.category) names.append(el('small', definition.category));
    root.append(icon(config, definition?.rarity), names);
    return root;
}

/** The cross-page jumps every item offers; callers may pass their own instead. */
export function itemLinks(config, name, { catalogue = true } = {}) {
    const links = [];
    // The depot and catalogue pages are administrator-only, and the router sends
    // any other role to the overview: offer only jumps that can be taken.
    if (api.session?.role !== 'ADMINISTRATOR') return links;
    if (name) links.push(button('Where it is sold', () => go('depot', { search: name }), 'secondary'));
    if (catalogue)
        links.push(button('Open in catalogue', () => go('content', { item: config }), 'secondary'));
    return links;
}

/**
 * An item that only has a config string still needs the fields an owned item
 * carries, so the shared inspector can render it read only.
 */
function plainItem(config, item) {
    return {
        config,
        level: 0,
        heatProgress: 0,
        variants: [],
        count: 1,
        equippedSlot: null,
        bound: false,
        bindOnEquip: false,
        cursed: false,
        starter: false,
        permanent: false,
        expiresAt: null,
        accessory: '',
        additionalAccessories: [],
        ...item,
        config
    };
}

/** A config the catalogue cannot describe is still shown, as the record it is. */
function recordOf(config) {
    const content = el('div', null, 'stack');
    content.append(
        el(
            'p',
            'The catalogue has no definition for this configuration, so only the requested path is known.',
            'muted'
        ),
        details('Requested configuration', { config })
    );
    openDialog({
        title: config,
        content,
        submit: 'Close',
        notifySaved: false,
        onSubmit: async () => {}
    });
}

/**
 * THE item inspector. Every item name in the console opens this dialog, so a
 * depot offer, an auction listing and a catalogue entry never lead to three
 * different dead ends. The definition is read lazily when a caller only has a
 * config, and a config the catalogue cannot describe degrades to that record.
 * @param {string} config
 * @param {{definition?: any, item?: any, actions?: Node[]}} [options]
 */
export async function inspectItemName(config, { definition, item, actions } = {}) {
    if (!config) return;
    let def = definition;
    if (def === undefined) {
        try {
            def = (await definitionsFor([config])).get(config);
        } catch {
            def = null;
        }
    }
    if (!def) return recordOf(config);
    let inspect;
    try {
        // Imported here because the gear editor imports this module back.
        ({ inspectItem: inspect } = await import('./gear-editor.js'));
    } catch {
        return recordOf(config);
    }
    await inspect(def, plainItem(config, item), {}, false);
    // The inspector owns its own dialog, so the cross-links join that same body
    // rather than building a second view of one item.
    const host = $('#fields'),
        inspector = host?.querySelector('.gear-inspector');
    if (!inspector) return;
    if (!item)
        // Without an instance the ownership facts describe a copy nobody holds.
        inspector.prepend(
            el(
                'small',
                'Catalogue definition: the ownership facts below describe a fresh, ' +
                    'unowned copy of this item.',
                'muted'
            )
        );
    const links = actions ?? itemLinks(config, def.name);
    if (links.length) host.append(toolbar(...links));
}
export function currency(kind, value) {
    const root = el('span', null, 'currency'),
        image = el('img');
    image.src = path(manifest.ui?.[kind]) || `/admin/assets/icons/${kind}.svg`;
    image.alt = kind;
    image.onerror = () => {
        image.onerror = null;
        image.src = `/admin/assets/icons/${kind}.svg`;
    };
    root.append(image, el('span', number(value)));
    return root;
}
export function spriteIcon(config, iconTier) {
    const root = el('span', null, 'item-icon'),
        image = el('img');
    image.src =
        path(manifest.sprites?.[`${config}:${iconTier}`]) || '/admin/assets/icons/fallback.svg';
    image.alt = '';
    image.onerror = () => {
        image.onerror = null;
        image.src = '/admin/assets/icons/fallback.svg';
    };
    root.append(image);
    return root;
}
export function itemTile(def, item, onClick) {
    const root = el('button', null, 'item-tile');
    root.type = 'button';
    root.onclick = onClick;
    root.append(
        icon(def?.config || item.config, def?.rarity),
        el('span', def?.name || item.config.split('/').at(-1), 'item-name')
    );
    if (item) {
        root.append(
            el('small', `×${number(item.count)}${item.equippedSlot != null ? ' · Equipped' : ''}`)
        );
        if (def?.progression) root.append(gearProgress(def, item));
        if (item.bound || item.expiresAt)
            root.append(
                el(
                    'small',
                    [item.bound ? 'Bound' : '', item.expiresAt ? 'Expires' : '']
                        .filter(Boolean)
                        .join(' · ')
                )
            );
    } else
        root.append(
            el('small', def.category),
            el('small', def.grantable ? 'Available to grant' : def.reason)
        );
    return root;
}

export function variantName(path) {
    return path.split('/').at(-1).replaceAll('_', ' ');
}

/** Native stored levels are zero based; max level is defined per item. */
export function gearProgress(def, item) {
    const node = el('div', null, 'gear-progress');
    const max = item.level + 1 >= def.levels;
    node.append(
        el(
            'small',
            max
                ? `Level ${item.level + 1} · Maximum`
                : `Level ${item.level + 1} · ${(item.heatProgress * 100).toFixed(1)}% Heat`,
            max ? 'max-level' : ''
        )
    );
    if (!max) {
        const bar = heat(item.heatProgress);
        bar.setAttribute('aria-label', 'Heat to next level');
        node.append(bar);
    }
    return node;
}
export function variantEffect(config, def) {
    const meta = def?.variantDisplay?.[config],
        node = el('div', null, 'variant-effect');
    const image = el('img');
    image.src = path(manifest.ui?.variants) || '/admin/assets/icons/variant.svg';
    image.alt = '';
    image.setAttribute('aria-hidden', 'true');
    image.onerror = () => {
        image.onerror = null;
        image.src = '/admin/assets/icons/variant.svg';
    };
    const label = meta?.label || variantName(config),
        separator = label.lastIndexOf(': ');
    node.append(
        image,
        el('span', separator < 0 ? label : label.slice(0, separator + 2), 'effect-name')
    );
    if (separator >= 0) node.append(el('small', label.slice(separator + 2), 'effect-strength'));
    return node;
}
export function variantEffects(def, variants = []) {
    const node = el('div', null, 'variant-effects');
    for (const variant of variants) node.append(variantEffect(variant.name, def));
    return node;
}
