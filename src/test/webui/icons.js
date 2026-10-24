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
        document.querySelectorAll('#navigation .native-symbol').length === 12,
        'Sidebar uses original icons'
    );
    check(
        document.querySelector('.brand .native-symbol[data-symbol="brand"]'),
        'Brand uses native Trojan mark'
    );
    const active = document.querySelector('#navigation [aria-current=page] .ui-icon');
    if (active)
        check(
            getComputedStyle(active).color ===
                getComputedStyle(document.documentElement).getPropertyValue('--gold').trim() ||
                getComputedStyle(active).color === getComputedStyle(active.parentElement).color,
            'Active icon matches gold navigation'
        );
    const failed = symbol('slot-helmet');
    failed.classList.add('slot-icon');
    failed.querySelector('image').dispatchEvent(new Event('error'));
    check(
        failed.classList.contains('slot-icon') &&
            failed.querySelector('path') &&
            !failed.querySelector('image'),
        'Image failure preserves category sizing and supplies an outline'
    );
    setSymbols({ 'nav-equipment': '../private.key' });
    check(!symbol('equipment').querySelector('image'), 'Untrusted paths use the fallback');
    setSymbols();
    try {
        check(
            symbol('equipment').querySelector('path') &&
                !symbol('equipment').querySelector('image'),
            'Navigation fallback works without a prepared pack'
        );
        check(symbol('slot-weapon').querySelector('path'), 'Empty-slot fallback');
        check(
            symbol('brand').getAttribute('src') === '/admin/assets/icons/mark.svg',
            'Self-contained horse fallback'
        );
    } finally {
        setSymbols(manifest.symbols, manifest.ui);
    }
    return [
        'native sidebar, tabs, actions and equipment symbols',
        'Trojan horse logo',
        'currentColor theme inheritance',
        'packaged image loading',
        'source fallbacks'
    ];
}
