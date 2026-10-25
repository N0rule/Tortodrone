/** Copy beside api.js in a disposable deployment; run in its authenticated browser. */
import { api } from './api.js';
const $ = (selector) => document.querySelector(selector);
const check = (value, message) => { if (!value) throw new Error(message); };
async function until(predicate) {
    const end = Date.now() + 8000;
    while (!predicate()) {
        if (Date.now() > end) throw new Error('Timed out waiting for shell state');
        await new Promise((resolve) => setTimeout(resolve, 25));
    }
}
async function page(name) {
    location.hash = name;
    await until(() => $('#navigation [aria-current="page"]')?.dataset.page === name
        && !$('#page .loading') && !$('#refresh').disabled);
}
export async function run({ disposable = false } = {}) {
    check(disposable && ['localhost', '127.0.0.1'].includes(location.hostname) && api.session,
        'Authenticated disposable loopback fixture required');
    await page('overview');
    const original = api.request;
    let release, delayed = false;
    const gate = new Promise((resolve) => { release = resolve; });
    api.request = async function (...args) {
        if (args[0] === 'status' && !delayed) {
            delayed = true;
            await gate;
        }
        return original.apply(this, args);
    };
    try {
        $('#refresh').click();
        await until(() => delayed && $('#refresh').disabled);
        await page('logs');
        check(!$('#refresh').disabled, 'New page can refresh while an obsolete refresh is pending');
    } finally {
        release();
        api.request = original;
    }
    const hash = location.hash;
    $('.skip').click();
    check(location.hash === hash && document.activeElement === $('#main'),
        'Skip to content focuses main without navigating away');
    if (matchMedia('(max-width: 760px)').matches) {
        $('#menu').click();
        check($('.workspace').inert && document.activeElement === $('#close-navigation'),
            'Mobile navigation moves focus and excludes background controls');
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        check(!$('.workspace').inert && document.activeElement === $('#menu'),
            'Escape closes navigation and returns focus');
        $('#menu').click();
        $('#navigation-backdrop').click();
        check(!$('#shell').classList.contains('menu-open'), 'Outside click dismisses navigation');
        $('#menu').click();
        $('#navigation a[data-page="overview"]').click();
        await until(() => $('#navigation [aria-current="page"]')?.dataset.page === 'overview'
            && !$('#page .loading') && !$('#refresh').disabled);
        check(!$('.workspace').inert && document.activeElement === $('#main'),
            'Navigation releases the background and focuses content');
    }
    check(document.documentElement.scrollWidth <= innerWidth, 'Shell fits the viewport');
    return 'PASS: refresh generation, skip link, mobile navigation and viewport width';
}
