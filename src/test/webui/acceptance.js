/** Run in the collaborative browser against a disposable WebUI fixture only.
 * Copy this module beside the fixture's assets; never package it for production.
 */
import { api } from './api.js';
const $ = (selector) => document.querySelector(selector);
const check = (condition, message) => {
    if (!condition) throw new Error(message);
};
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(predicate, message) {
    const end = Date.now() + 8000;
    while (!predicate()) {
        if (Date.now() > end) throw new Error('Timed out: ' + message);
        await pause(30);
    }
}
function control(name, root = document) {
    return root.querySelector(`[aria-label="${name}"]`);
}
function button(name, root = $('#page')) {
    const result = [...root.querySelectorAll('button')].find((b) => b.textContent.trim() === name);
    check(result, 'Missing button: ' + name);
    return result;
}
function set(input, value, event = 'input') {
    check(input, 'Missing input');
    if (input.type === 'checkbox') input.checked = value;
    else input.value = value;
    input.dispatchEvent(new Event(event, { bubbles: true }));
}
async function navigate(name, params = {}, ready = () => true) {
    location.hash = name + (Object.keys(params).length ? '?' + new URLSearchParams(params) : '');
    await until(
        () =>
            $('#navigation [aria-current="page"]')?.dataset.page === name &&
            !$('#page .loading') &&
            ready(),
        name
    );
    await pause(50);
}
async function refresh() {
    $('#refresh').click();
    await until(() => !$('#refresh').disabled, 'refresh complete');
}
async function close() {
    $('#cancel-dialog').click();
    await until(() => !$('#dialog').open, 'close dialog');
}
async function save() {
    $('#editor').requestSubmit();
    await until(() => !$('#dialog').open || !!$('#dialog-error').textContent, 'save editor');
    check(!$('#dialog').open, $('#dialog-error').textContent);
    await refresh();
}
export async function run(group, { disposable = false, username, knight } = {}) {
    check(
        disposable && ['127.0.0.1', 'localhost'].includes(location.hostname),
        'Disposable loopback fixture required'
    );
    check(
        username && knight && api.session?.role === 'ADMINISTRATOR',
        'Authenticated fixture administrator required'
    );
    const profile = { username, knight },
        passed = [],
        originalConfirm = window.confirm;
    window.confirm = () => true;
    const pass = (name) => passed.push(name);
    try {
        if (group === 'navigation') {
            await navigate('knights', profile, () => !!$('.knight-name'));
            button('Inventory').click();
            await until(() => !!control('Search inventory'), 'inventory');
            const search = control('Search inventory');
            set(search, 'Gel');
            button('Battle Sprites').click();
            await until(() => !!$('.sprite-card'), 'Sprite card');
            check(
                $('.sprite-card').textContent.includes('Offline'),
                'Offline Sprite is never claimed deployed'
            );
            const advanced = $('.sprite-card details');
            advanced.open = true;
            await refresh();
            check(advanced.isConnected && advanced.open, 'Sprite inspection survives refresh');
            button('Inventory').click();
            await until(() => control('Search inventory') === search, 'cached inventory');
            check(search.value === 'Gel', 'Inventory filter survives tab changes');
            pass('inventory and Sprite tab state');
            button('All Knights').click();
            await until(() => !$('.knight-name')?.checkVisibility(), 'Knight roster');
            button('Open profile').click();
            await until(() => $('.knight-name')?.checkVisibility(), 'return to same Knight');
            pass('Knight roster/profile return');
            await navigate('accounts', { username }, () => !!$('.account-detail'));
            await navigate('accounts', {}, () => !$('.account-detail'));
            button('Inspect').click();
            await until(() => !!$('.account-detail'), 'reopen account');
            pass('account deselection/reselection');
        }
        if (group === 'gear') {
            await navigate('knights', profile, () => !!$('.equipment-slot:not(.empty-slot)'));
            $('.equipment-slot:not(.empty-slot)').click();
            await until(
                () => $('#dialog').open && $('#dialog').textContent.includes('Draft is valid.'),
                'gear preview'
            );
            const level = control('Level', $('#dialog')),
                heat = control('Heat fraction', $('#dialog'));
            set(level, 2);
            set(heat, 0.375);
            level.focus();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'updated preview'
            );
            const choice = $('#dialog input[data-variant]:not(:disabled)');
            if (choice) {
                choice.click();
                await until(
                    () => $('#dialog').textContent.includes('Draft is valid.'),
                    'compatible UV'
                );
            }
            await refresh();
            check(
                control('Level', $('#dialog')) === level &&
                    level.value === '2' &&
                    document.activeElement === level,
                'Polling preserves editor input and focus'
            );
            window.confirm = () => false;
            $('#cancel-dialog').click();
            check($('#dialog').open, 'Dirty editor decline preserves draft');
            window.confirm = () => true;
            await save();
            const view = await api.request('knight-view', profile),
                item = view.profile.items.find((i) => i.equippedSlot === 0);
            check(
                item.level === 1 && Math.abs(item.heatProgress - 0.375) < 0.00001,
                'Saved gear uses native level and precise Heat'
            );
            pass('gear preview, draft protection and authoritative save');
            $('.equipment-slot:not(.empty-slot)').click();
            await until(() => $('#dialog').open, 'gear reopen');
            button('Edit accessories', $('#dialog')).click();
            await until(() => !!control('Search accessories'), 'visual accessories');
            const tile = $('#dialog .item-tile');
            if (tile) {
                check(!!tile.querySelector('img'), 'Accessory has resolved icon');
                tile.click();
                await save();
                const updated = await api.request('knight-view', profile),
                    helm = updated.profile.items.find((i) => i.equippedSlot === 0);
                check(
                    helm.accessory || helm.additionalAccessories.length,
                    'Accessory attachment committed'
                );
                pass('visual accessory attachment');
            } else await close();
        }
        if (group === 'news') {
            await navigate('news', {}, () => !!control('Enable News'));
            const canvas = document.createElement('canvas');
            canvas.width = 280;
            canvas.height = 190;
            canvas.getContext('2d').fillRect(0, 0, 280, 190);
            const upload = await api.change('news-upload', {
                name: 'acceptance.png',
                data: canvas.toDataURL().split(',')[1]
            });
            await refresh();
            button('Add pack').click();
            set(control('Enable News'), true, 'change');
            set(control('Banner image'), upload.image, 'change');
            set(control('Tooltip'), 'Fixture featured');
            check(
                button('Remove spotlight').disabled,
                'Featured banner cannot be removed as a spotlight'
            );
            control('left spotlight').click();
            set(control('Banner image'), upload.image, 'change');
            set(control('Tooltip'), 'Fixture left');
            control('right spotlight').click();
            set(control('Banner image'), upload.image, 'change');
            set(control('Tooltip'), 'Fixture right');
            button('Review and save News').click();
            await until(() => $('#dialog').open, 'News review');
            await save();
            const published = await api.request('news');
            check(
                published.catalog.packs.at(-1).right.tooltip === 'Fixture right',
                'Three-slot visual News saved'
            );
            pass('three-slot News publishing');
            set(control('Tooltip'), 'Preserved local draft');
            const remote = await api.change('news-save', {
                yaml: published.yaml + '\n# concurrent fixture edit\n',
                revision: published.revision
            });
            await refresh();
            check(
                control('Tooltip').value === 'Preserved local draft' &&
                    button('Review and save News').disabled &&
                    button('Review and save YAML').disabled,
                'Remote revision preserves draft and blocks both save paths'
            );
            window.confirm = () => false;
            button('Load latest News').click();
            await pause(60);
            check(
                control('Tooltip').value === 'Preserved local draft',
                'Declined reload preserves News draft'
            );
            window.confirm = () => true;
            button('Load latest News').click();
            await until(() => !button('Review and save News').disabled, 'latest News');
            check(
                control('Advanced News YAML').value === remote.yaml,
                'Explicit reload takes latest revision'
            );
            pass('News revision conflict and recovery');
            set(control('Advanced News YAML'), remote.yaml + '\n# unsaved draft\n');
            window.confirm = () => false;
            location.hash = 'overview';
            await pause(120);
            check(
                location.hash === '#news' && !!control('Enable News'),
                'Unsaved YAML blocks page departure'
            );
            window.confirm = () => true;
            button('Load latest News').click();
            await pause(100);
            pass('News page departure protection');
        }
        if (group === 'console') {
            await navigate('console', {}, () => !!control('Command'));
            set(control('Command'), 'help');
            $('.command-form').requestSubmit();
            await until(
                () => $('[aria-label="Command results"]').textContent.includes('> help'),
                'command result'
            );
            await until(() => control('Command').value === '', 'clear submitted command');
            set(control('Command'), 'unsubmitted draft');
            await navigate('logs', {}, () => !control('Command'));
            await navigate('console', {}, () => !!control('Command'));
            check(
                control('Command').value === 'unsubmitted draft',
                'Command draft survives navigation'
            );
            check(
                $('[aria-label="Command results"]').textContent.includes('> help'),
                'Command history survives navigation'
            );
            pass('console draft and result history');
            await refresh();
            check(
                control('Command').value === 'unsubmitted draft',
                'Console refresh preserves input'
            );
        }
        if (group === 'refresh') {
            await navigate('overview', {}, () => !!$('#page svg.chart'));
            const fetch = window.fetch;
            let releaseStatus,
                releaseLogs,
                holdStatus = true,
                holdLogs = false,
                inFlight = 0,
                maximum = 0;
            window.fetch = async (resource, options) => {
                const url = String(resource),
                    status = holdStatus && url.startsWith('/admin/api/status'),
                    logs = holdLogs && url.startsWith('/admin/api/logs');
                if (status) holdStatus = false;
                if (logs) {
                    inFlight++;
                    maximum = Math.max(maximum, inFlight);
                }
                try {
                    const response = await fetch(resource, options);
                    if (status)
                        await new Promise((resolve) => {
                            releaseStatus = resolve;
                        });
                    if (logs && !releaseLogs)
                        await new Promise((resolve) => {
                            releaseLogs = resolve;
                        });
                    return response;
                } finally {
                    if (logs) inFlight--;
                }
            };
            try {
                $('#refresh').click();
                await until(() => !!releaseStatus, 'old overview request');
                await navigate('console', {}, () => !!control('Command'));
                holdLogs = true;
                $('#refresh').click();
                await until(() => !!releaseLogs, 'new console request');
                releaseStatus();
                await pause(80);
                check(
                    $('#refresh').disabled,
                    'Old page completion cannot unlock a new page refresh'
                );
                document.dispatchEvent(new Event('data-changed'));
                await pause(80);
                check(maximum === 1, 'Overlapping refresh is coalesced');
                releaseLogs();
                await until(() => !$('#refresh').disabled, 'serialized refreshes');
                check(maximum === 1, 'A delayed old page cannot clear the new page refresh job');
                pass('navigation cancellation and refresh serialization');
            } finally {
                holdStatus = holdLogs = false;
                releaseStatus?.();
                releaseLogs?.();
                window.fetch = fetch;
            }
        }
        if (group === 'pages') {
            for (const name of [
                'overview',
                'players',
                'accounts',
                'knights',
                'depot',
                'auctions',
                'news',
                'content',
                'console',
                'logs',
                'webui-users',
                'audit'
            ]) {
                await navigate(name);
                check(!$('#page > .error'), name + ' failed to mount');
                check(
                    document.documentElement.scrollWidth <= innerWidth,
                    name + ' overflows the viewport'
                );
                pass(name + ' layout at ' + innerWidth + 'px');
            }
        }
        return passed;
    } finally {
        window.confirm = originalConfirm;
    }
}
