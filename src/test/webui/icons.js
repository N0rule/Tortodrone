/** Copy beside api.js in a disposable fixture. Tests native symbols and source fallbacks. */
import { setSymbols, symbol } from './components/symbols.js';
const check = (value, message) => {
    if (!value) throw new Error(message);
};
export async function run({ disposable = false } = {}) {
    check(
        disposable && ['127.0.0.1', 'localhost'].includes(location.hostname),
        'Disposable fixture required'
    );
    const manifest = await (await fetch('/admin/assets/game/manifest.json')).json();
    check(manifest.symbols?.brand, 'Packaged Trojan horse icon');
    for (const name of [
        'equipment',
        'inventory',
        'sprites',
        'add',
        'edit',
        'remove',
        'slot-weapon',
        'slot-helmet',
        'slot-armor',
        'slot-shield',
        'slot-trinket',
        'slot-costume',
        'brand'
    ]) {
        const node = symbol(name),
            image = node.querySelector('image');
        check(
            image && image.getAttribute('href').startsWith('/admin/assets/game/icons/'),
            'Native icon restored: ' + name
        );
        const response = await fetch(image.getAttribute('href'));
        check(
            response.ok && response.headers.get('content-type').startsWith('image/'),
            'Packaged image loads: ' + name
        );
        check(
            node.querySelector('feFlood').getAttribute('flood-color') === 'currentColor',
            'Icon follows its control color: ' + name
        );
    }
    check(
        document.querySelectorAll('#navigation .ui-icon').length === 13,
        'Sidebar renders one icon per page'
    );
    check(
        document.querySelectorAll('#navigation .native-symbol').length >= 12,
        'Sidebar uses original icons where the pack provides them'
    );
    check(
        document.querySelector('.brand .native-symbol[data-symbol="brand"]'),
        'Brand uses native Trojan mark'
    );
    const active = document.querySelector('#navigation [aria-current=page] .ui-icon');
    if (active)
        check(
            getComputedStyle(active).color ===
                getComputedStyle(document.documentElement).getPropertyValue('--brand').trim() ||
                getComputedStyle(active).color === getComputedStyle(active.parentElement).color,
            'Active icon matches brand navigation'
        );
    const failed = symbol('slot-helmet');
    document.body.append(failed);
    failed.querySelector('image').dispatchEvent(new Event('error'));
    check(!failed.isConnected, 'A broken icon file leaves no icon, never a drawn stand-in');
    setSymbols({ 'nav-equipment': '../private.key' });
    check(symbol('equipment') === null, 'Untrusted paths resolve to no icon at all');
    setSymbols();
    try {
        check(
            symbol('equipment') === null,
            'A job with no client art renders no icon instead of an invented one'
        );
        check(symbol('search') === null, 'No invented magnifier');
        check(
            symbol('brand').getAttribute('src') === '/admin/assets/icons/mark.svg',
            'The product mark is the app logo, not game art'
        );
    } finally {
        setSymbols(manifest.symbols, manifest.ui);
    }
    return [
        'native sidebar, tabs, actions and equipment symbols',
        'Trojan horse logo',
        'currentColor theme inheritance',
        'packaged image loading',
        'no invented icons'
    ];
}
