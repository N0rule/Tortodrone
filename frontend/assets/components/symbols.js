/** Every icon is client art, tinted with the surrounding text colour. */
let masks = {},
    originals = {},
    nextId = 0;
export function setSymbols(symbols = {}, ui = {}) {
    masks = symbols || {};
    originals = ui || {};
}
function asset(value) {
    return typeof value === 'string' && /^[A-Za-z0-9_./-]+$/.test(value) && !value.includes('..')
        ? '/admin/assets/game/' + value
        : null;
}
/* Artwork rule: every icon in the WebUI is client art, copied out of the game
   files at server start into assets/game. Nothing here draws a shape. A job the
   pack has no art for gets no icon at all, and the control carries its label —
   an invented glyph is worse than a word. `alias` maps a job to the client
   symbol that already means it. */
const alias = {
    check: 'save',
    inspect: 'details',
    view: 'details',
    review: 'details',
    help: 'details',
    info: 'details',
    weapon: 'slot-weapon',
    helmet: 'slot-helmet',
    armor: 'slot-armor',
    shield: 'slot-shield',
    trinket: 'slot-trinket',
    costume: 'slot-costume',
    equipment: 'slot-shield'
};
export function symbol(name) {
    // Packs exported before nav-mailing existed carried the stock mail glyph as
    // nav-logs. Treat that pack as legacy: mailing borrows it, while Logs falls
    // back to its document glyph instead of showing a second mail icon.
    const legacyMailPack = !masks['nav-mailing'] && !originals['nav-mailing'];
    if (name === 'logs' && legacyMailPack) return fallback(name);
    const actual = name === 'mailing' && legacyMailPack ? 'logs' : name;
    const key = actual.startsWith('slot-') || actual === 'brand' ? actual : 'nav-' + actual;
    const prepared = asset(masks[key]),
        original = asset(originals[key]);
    if (prepared || original) return nativeSymbol(prepared || original, name, !!prepared);
    return fallback(name);
}
function fallback(name) {
    // The product mark is the app's own logo, not game art, so it survives.
    if (name === 'brand') {
        const image = document.createElement('img');
        image.src = '/admin/assets/icons/mark.svg';
        image.alt = '';
        return image;
    }
    const wanted = alias[name] || name;
    const key = wanted.startsWith('slot-') ? wanted : 'nav-' + wanted;
    const prepared = asset(masks[key]),
        original = asset(originals[key]);
    if (!prepared && !original) return null;
    return nativeSymbol(prepared || original, wanted, !!prepared);
}

function nativeSymbol(file, name, prepared) {
    const ns = 'http://www.w3.org/2000/svg';
    const make = (tag, attrs) => {
        const node = document.createElementNS(ns, tag);
        for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
        return node;
    };
    const root = make('svg', {
            viewBox: '0 0 48 48',
            class: 'ui-icon native-symbol',
            'aria-hidden': 'true'
        }),
        defs = make('defs', {}),
        id = 'icon-tint-' + ++nextId,
        filter = make('filter', {
            id,
            x: '0',
            y: '0',
            width: '100%',
            height: '100%',
            'color-interpolation-filters': 'sRGB'
        });
    // Older prepared packs contain colored UI art. Preserve their luminance until re-exported.
    if (!prepared)
        filter.append(
            make('feColorMatrix', {
                type: 'matrix',
                values: '0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 .54 1.79 .18 0 -.25',
                result: 'luminance'
            })
        );
    if (!prepared)
        filter.append(
            make('feComposite', {
                in: 'luminance',
                in2: 'SourceAlpha',
                operator: 'in',
                result: 'ink'
            })
        );
    filter.append(
        make('feFlood', { 'flood-color': 'currentColor' }),
        make('feComposite', { in2: prepared ? 'SourceAlpha' : 'ink', operator: 'in' })
    );
    defs.append(filter);
    const image = make('image', {
        href: file,
        width: 48,
        height: 48,
        preserveAspectRatio: 'xMidYMid meet',
        filter: `url(#${id})`
    });
    image.addEventListener(
        'error',
        () => {
            if (name === 'brand') {
                image.removeAttribute('filter');
                image.setAttribute('href', '/admin/assets/icons/mark.svg');
            } else {
                // The art is the only thing that may appear here, so a broken file
                // leaves no icon at all: the control keeps its label.
                root.remove();
            }
        },
        { once: true }
    );
    root.dataset.symbol = name;
    root.append(defs, image);
    return root;
}
