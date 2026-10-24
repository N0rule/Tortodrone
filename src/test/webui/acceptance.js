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
            await until(
                () =>
                    !$('.knight-name')?.checkVisibility() &&
                    [...$('#page').querySelectorAll('button')].some(
                        (b) => b.textContent.trim() === 'Open profile'
                    ),
                'Knight roster'
            );
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
            $('.equipment-slot[data-slot="0"]').click();
            await until(() => $('#dialog').open, 'equipment actions');
            button('Inspect / edit equipped item', $('#dialog')).click();
            await until(
                () => $('#dialog').open && $('#dialog').textContent.includes('Draft is valid.'),
                'gear preview'
            );
            const level = control('Level', $('#dialog')),
                heat = control('Heat percentage', $('#dialog'));
            set(level, 2);
            set(heat, 37.5);
            level.focus();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'updated preview'
            );
            const choice = $('#dialog button[data-variant]:not(:disabled)');
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
            $('.equipment-slot[data-slot="0"]').click();
            await until(() => $('#dialog').open, 'equipment actions');
            button('Inspect / edit equipped item', $('#dialog')).click();
            await until(() => $('#dialog').open, 'gear reopen');
            const beforeAccessory = await api.request('knight-view', profile);
            const attached = beforeAccessory.profile.items.find((i) => i.equippedSlot === 0);
            if (attached.accessory || attached.additionalAccessories.length) {
                await api.change('item-accessory', {
                    username,
                    knight,
                    accountId: beforeAccessory.profile.accountId,
                    worldId: beforeAccessory.profile.worldId,
                    revision: beforeAccessory.profile.revision,
                    itemId: attached.id,
                    accessory: ''
                });
                await close();
                await refresh();
                $('.equipment-slot[data-slot="0"]').click();
                button('Inspect / edit equipped item', $('#dialog')).click();
            }
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
        if (group === 'polish') {
            await navigate('knights', profile, () => !!$('.equipment-slot[data-slot="7"]'));
            $('.equipment-slot[data-slot="7"]').click();
            await until(() => $('#dialog').open, 'weapon actions');
            button('Inspect / edit equipped item', $('#dialog')).click();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'weapon preview'
            );
            check(
                $('#dialog .gear-card').textContent.includes('Elemental damage'),
                'Diskgun elemental damage'
            );
            check(
                !$('#dialog .gear-card').textContent.includes('Health bonus'),
                'No irrelevant weapon health'
            );
            await close();
            $('.equipment-slot[data-slot="6"]').click();
            button('Inspect / edit equipped item', $('#dialog')).click();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'Ban Stick preview'
            );
            for (const label of [
                'Attack Speed Increased: Very High',
                'Damage Bonus vs Beast: Very High',
                'Charge Time Reduction: Very High',
                'Charge Time Reduction: Low'
            ])
                check($('#dialog .gear-card').textContent.includes(label), label);
            const uv = $('#dialog .uv-picker');
            uv.open = true;
            const search = control('Search Unique Variants', $('#dialog'));
            set(search, 'Charge Time');
            check(
                [...uv.querySelectorAll('.variant-option')]
                    .filter((n) => !n.hidden)
                    .every((n) => n.textContent.includes('Charge Time')),
                'Searchable effects'
            );
            await close();
            $('.equipment-slot[data-slot="7"]').click();
            button('Unequip', $('#dialog')).click();
            await save();
            await until(
                () => $('.equipment-slot[data-slot="7"]').classList.contains('empty-slot'),
                'empty slot update'
            );
            $('.equipment-slot[data-slot="7"]').click();
            await until(
                () => !!control('Search compatible equipment', $('#dialog')),
                'empty slot picker'
            );
            set(control('Search compatible equipment', $('#dialog')), 'Fiery Diskgun');
            const tile = [...$('#dialog').querySelectorAll('.item-tile')].find(
                (t) => !t.hidden && t.textContent.includes('Fiery Diskgun')
            );
            check(tile, 'Owned compatible item');
            tile.click();
            await save();
            await until(
                () => !$('.equipment-slot[data-slot="7"]').classList.contains('empty-slot'),
                'equip update'
            );
            $('.equipment-slot[data-slot="7"]').click();
            const grantSection = [...$('#dialog').querySelectorAll('details')].find(
                (d) => d.querySelector('summary')?.textContent === 'Grant and equip'
            );
            grantSection.open = true;
            set(control('Search items', grantSection), 'Proto Gun');
            await until(
                () =>
                    [...grantSection.querySelectorAll('.item-tile')].some((t) =>
                        t.textContent.includes('Proto Gun')
                    ),
                'Compatible grant catalogue'
            );
            [...grantSection.querySelectorAll('.item-tile')]
                .find((t) => t.textContent.includes('Proto Gun'))
                .click();
            await save();
            await until(
                () => $('.equipment-slot[data-slot="7"]').textContent.includes('Proto Gun'),
                'Atomic grant and equip'
            );
            $('.equipment-slot[data-slot="7"]').click();
            [...$('#dialog').querySelectorAll('.item-tile')]
                .find((t) => t.textContent.includes('Fiery Diskgun'))
                .click();
            await save();
            await until(
                () => $('.equipment-slot[data-slot="7"]').textContent.includes('Fiery Diskgun'),
                'Restore weapon through owned picker'
            );
            pass('Native item cards, searchable UVs, empty slot equip and occupied slot unequip');
            let heavy = 0,
                probes = 0;
            const fetchBefore = window.fetch;
            window.fetch = (url, ...args) => {
                if (String(url).includes('/knight-view?')) heavy++;
                if (String(url).includes('/knight-state?')) probes++;
                return fetchBefore(url, ...args);
            };
            try {
                await refresh();
                await refresh();
                check(probes >= 2 && heavy === 0, 'Unchanged profiles use lightweight probes');
            } finally {
                window.fetch = fetchBefore;
            }
            await navigate('knights', { ...profile, tab: 'sprites' }, () => !!$('.sprite-card'));
            check(
                $('.sprite-skills').textContent.includes('Firebolt'),
                'Official Sprite skill names'
            );
            check(
                $('.sprite-card').getBoundingClientRect().width >
                    $('#page').getBoundingClientRect().width * 0.8,
                'Sprite uses available width'
            );
            check(
                $('.sprite-skills').querySelectorAll('.panel').length === 3,
                'Three readable skill cards'
            );
            check(
                document.querySelectorAll('#navigation svg.ui-icon').length === 12,
                'Consistent navigation icon family'
            );
            await navigate('accounts', { username }, () => !!control('Game Master permission'));
            const gm = control('Game Master permission');
            const original = gm.checked;
            gm.click();
            await until(() => !gm.disabled, 'GM save');
            check(
                (await api.request('accounts')).find((a) => a.username === username).gameMaster !==
                    original,
                'Inline GM persists'
            );
            await navigate('knights', profile, () => !!$('.knight-header'));
            check(
                $('.knight-header').textContent.includes(original ? 'Player' : 'Game Master'),
                'Knight permission badge updates'
            );
            await navigate('accounts', { username }, () => !!control('Game Master permission'));
            control('Game Master permission').click();
            await until(
                () => !control('Game Master permission').disabled,
                'Restore fixture permission'
            );
            pass('Lightweight polling, wide Sprite skills, icons and inline GM permissions');
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
        if (group === 'grants') {
            await navigate('knights', profile, () => !!$('.knight-name'));
            const before = await api.request('knight-view', profile);
            $('.knight-wallet button').click();
            set(control('Amount', $('#dialog')), before.profile.crowns + 125);
            await save();
            check(
                (await api.request('knight-view', profile)).profile.crowns ===
                    before.profile.crowns + 125,
                'Currency editor remains functional'
            );
            button('Grant item').click();
            set(control('Search items', $('#dialog')), 'Owlite Shield');
            await until(
                () =>
                    [...document.querySelectorAll('#dialog .item-tile .item-name')].some(
                        (n) => n.textContent === 'Owlite Shield'
                    ),
                'Grant catalogue'
            );
            [...document.querySelectorAll('#dialog .item-tile')]
                .find((n) => n.querySelector('.item-name')?.textContent === 'Owlite Shield')
                .click();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'Grant preview'
            );
            check(
                $('#dialog').querySelectorAll('.item-line').length === 1,
                'Selected item identity appears once'
            );
            const level = control('Level slider', $('#dialog'));
            set(level, level.max);
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'Maximum-level preview'
            );
            check(control('Heat', $('#dialog')).disabled, 'Max-level grant disables Heat');
            $('#dialog .uv-picker').open = true;
            $('#dialog .variant-option:not(:disabled)').click();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'Grant UV preview'
            );
            await save();
            const after = await api.request('knight-view', profile),
                ids = new Set(before.profile.items.map((i) => i.id)),
                item = after.profile.items.find((i) => !ids.has(i.id));
            check(
                item &&
                    item.level + 1 === +level.max &&
                    item.heatProgress === 0 &&
                    item.variants.length === 1,
                'Grant persists authoritative level, Heat and UVs'
            );
            $('.equipment-slot[data-slot="2"]').click();
            [...document.querySelectorAll('#dialog .item-tile')]
                .find((n) => n.querySelector('.item-name')?.textContent === 'Owlite Shield')
                .click();
            await save();
            const card = $('.equipment-slot[data-slot="2"]');
            check(
                card.textContent.includes('Maximum') &&
                    !card.querySelector('progress') &&
                    card.querySelectorAll('.variant-effect').length === 1,
                'Equipped maximum gear uses one UV row and no empty Heat meter'
            );
            card.click();
            button('Unequip', $('#dialog')).click();
            await save();
            button('Inventory').click();
            await until(() => !!control('Search inventory'), 'Inventory');
            set(control('Search inventory'), 'Owlite Shield');
            await until(
                () =>
                    [...document.querySelectorAll('#page .item-tile')].some(
                        (n) => n.querySelector('.item-name')?.textContent === 'Owlite Shield'
                    ),
                'Granted inventory item'
            );
            [...document.querySelectorAll('#page .item-tile')]
                .find((n) => n.querySelector('.item-name')?.textContent === 'Owlite Shield')
                .click();
            await until(
                () => $('#dialog').textContent.includes('Draft is valid.'),
                'Owned gear inspector'
            );
            button('Quantity / remove', $('#dialog')).click();
            set(control('Quantity (0 removes)', $('#dialog')), 0);
            await save();
            check(
                !(await api.request('knight-view', profile)).profile.items.some(
                    (i) => i.id === item.id
                ),
                'Quantity removal remains functional'
            );
            pass(
                'Currency, grant with modifiers, max-level equipment, inventory inspection and removal'
            );
        }
        if (group === 'console') {
            await navigate('console', {}, () => !!control('Command'));
            set(control('Command'), 'help');
            $('.command-form').requestSubmit();
            await until(
                () => $('[aria-label="Console output"]').textContent.includes('> help'),
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
                $('[aria-label="Console output"]').textContent.includes('> help'),
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
