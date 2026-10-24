import { el, number, heat } from '../ui.js';
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
        if (response.ok) manifest = await response.json();
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
export function itemLine(definition, item) {
    const root = el('div', null, 'item-line'),
        names = el('div');
    names.append(
        el('strong', definition?.name || item?.config?.split('/').at(-1) || 'Unknown item')
    );
    if (definition?.category) names.append(el('small', definition.category));
    root.append(icon(definition?.config || item?.config, definition?.rarity), names);
    return root;
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
        if (def?.progression)
            root.append(
                el(
                    'small',
                    `Level ${item.level + 1} · ${(item.heatProgress * 100).toFixed(1)}% Heat`
                ),
                heat(item.heatProgress)
            );
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
