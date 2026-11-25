import { setSymbols } from './symbols.js';
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
            // The shell paints before the pack is known, so it is repainted now: the
            // wordmarks and the tab icon are the client's mark, not a drawing.
            document.dispatchEvent(new Event('symbols-ready'));
        }
    } catch {
        /* Every icon has a local fallback. */
    }
}
/**
 * The tile a config with no client art wears: the frame stays, the picture becomes
 * the item's initial. Hidden from assistive technology, which reads the name beside
 * it, and announced to nobody as a picture — there is no picture.
 */
function fallbackLetter(root, config) {
    const name = String(config ?? '')
        .split('/')
        .at(-1)
        .replaceAll('_', ' ')
        .trim();
    root.dataset.missing = 'true';
    root.setAttribute('aria-hidden', 'true');
    root.append(el('span', name.slice(0, 1).toUpperCase() || '?', 'item-icon-letter'));
}
function path(value) {
    return typeof value === 'string' && /^[A-Za-z0-9_./-]+$/.test(value) && !value.includes('..')
        ? '/admin/assets/game/' + value
        : null;
}
export function icon(config) {
    const root = el('span', null, 'item-icon'),
        source = path(manifest.items?.[config]);
    // Only client art may appear here. An item the pack has no picture of keeps the
    // frame and takes the first letter of its own name: an empty bordered box reads
    // as a broken image, and a row without a frame breaks the column every other row
    // aligns to. The letter is typography over the name, not art invented for it.
    if (source) {
        const image = el('img');
        image.src = source;
        image.alt = '';
        image.loading = 'lazy';
        image.decoding = 'async';
        image.onerror = () => {
            image.onerror = null;
            image.remove();
            fallbackLetter(root, config);
        };
        root.append(image);
    } else fallbackLetter(root, config);
    // Rarity is a number the game shows as stars; a glyph is not client art, and the
    // word is what a screen reader can say, so it is written out — on its own line, by
    // the callers that have room for one. Overlaid on the frame it covered the art at
    // the 32px size tiles use and hung below it at 56px.
    return root;
}
/** Rarity as a line of its own, for the callers with room for one. */
function rarityLine(rarity) {
    return rarity > 0 ? el('small', `Rarity ${rarity}`, 'item-rarity') : null;
}
/**
 * One item, with its name as the drill-down into the shared inspector.
 * @param {any} [definition] the catalogue definition, when the caller already has it
 * @param {any} [item] a knight's item instance, when the caller has one
 * @param {{actions?: Node[], label?: string, note?: string, compact?: boolean}} [options]
 *   `note` replaces the category line with the caller's own detail, and an empty
 *   one drops that line entirely for a caller whose table already has the category
 *   in a column of its own. `compact` is the table-cell size: the art stays, at a
 *   third of the height, because a 56px thumbnail per row turns a 328-offer list
 *   into a 22-page one.
 */
export function itemLine(definition, item, { actions, label, note, compact } = {}) {
    const root = el('div', null, compact ? 'item-line compact' : 'item-line'),
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
    // `??`, not `||`: a caller whose neighbouring column already carries the
    // category passes '' to say so, and a fallback would have printed it twice.
    const detail = note ?? definition?.category;
    if (detail) names.append(el('small', detail));
    // A gear card has the room for the rarity the game prints as stars; a compact
    // table row does not, and the row's own detail line carries the level instead.
    if (!compact) {
        const rarity = rarityLine(definition?.rarity);
        if (rarity) names.append(rarity);
    }
    root.append(icon(config), names);
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
        source = path(manifest.ui?.[kind]);
    if (source) {
        const image = el('img');
        image.src = source;
        image.alt = '';
        image.onerror = () => {
            image.onerror = null;
            image.remove();
        };
        root.append(image);
    }
    root.append(el('span', number(value)));
    // The unit word is the fallback for a missing icon, not a second label on every
    // cell: "1,200" with the client's coin beside it needs no word.
    if (!source) root.append(el('span', ' ' + kind, 'muted'));
    return root;
}
export function spriteIcon(config, iconTier) {
    const root = el('span', null, 'item-icon'),
        source = path(manifest.sprites?.[`${config}:${iconTier}`]);
    if (source) {
        const image = el('img');
        image.src = source;
        image.alt = '';
        image.onerror = () => {
            image.onerror = null;
            image.remove();
        };
        root.append(image);
    }
    return root;
}
export function itemTile(def, item, onClick) {
    const root = el('button', null, 'item-tile');
    root.type = 'button';
    root.onclick = onClick;
    const rarity = rarityLine(def?.rarity);
    root.append(
        icon(def?.config || item.config),
        el('span', def?.name || item.config.split('/').at(-1), 'item-name')
    );
    if (item) {
        if (rarity) root.append(rarity);
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
    } else {
        // `Shield · Rarity 1`: what the item is and how rare it is answer one question,
        // so they share a line. On two lines every tile was a row taller than its
        // content, and the tiles that had a rarity pushed their last line two lines
        // below the ones that did not — a grid of ragged columns.
        const meta = el('small', null, 'item-meta');
        if (def.category) meta.append(el('span', def.category));
        if (rarity) meta.append(...(meta.childNodes.length ? [el('span', ' · ')] : []), rarity);
        if (meta.childNodes.length) root.append(meta);
        root.append(el('small', def.grantable ? 'Available to grant' : def.reason));
    }
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
    const source = path(manifest.ui?.variants);
    const label = meta?.label || variantName(config),
        separator = label.lastIndexOf(': ');
    node.append(
        ...(source
            ? [
                  (() => {
                      const image = el('img');
                      image.src = source;
                      image.alt = '';
                      image.setAttribute('aria-hidden', 'true');
                      image.onerror = () => {
                          image.onerror = null;
                          image.remove();
                      };
                      return image;
                  })()
              ]
            : []),
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
