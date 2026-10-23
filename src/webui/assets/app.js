import { Api } from './api.js';
const api = new Api(), $ = s => document.querySelector(s), page = $('#page');
let generation = 0, busy = false, paused = false, autoScroll = true, samples = [], current = 'dashboard', pollTimer;
const pages = [['dashboard', 'Overview', 'Your server, at a glance.', 0], ['players', 'Online players', 'Connections, knights and active scenes.', 0], ['accounts', 'Accounts & knights', 'Manage identities and their inventory.', 1], ['depot', 'Supply Depot', 'Offers, Specials and scheduled promotions.', 2], ['auctions', 'Auction House', 'Listings, escrow and authoritative settlement.', 2], ['news', 'Uplink News', 'Manage the banners your players see.', 2], ['content', 'Content catalogue', 'Validated definitions and supported items.', 2], ['logs', 'Logs', 'A bounded live view of server activity.', 0], ['audit', 'Audit trail', 'Privileged actions and their outcomes.', 2]];
const rank = () => ['VIEWER', 'MODERATOR', 'ADMINISTRATOR'].indexOf(api.session?.role);
function el(tag, text, cls) { const n = document.createElement(tag); if (text !== undefined)
    n.textContent = String(text ?? '—'); if (cls)
    n.className = cls; return n; }
function button(text, fn, cls = 'secondary') { const b = el('button', text, cls); b.type = 'button'; b.onclick = () => Promise.resolve(fn()).catch(showError); return b; }
function showError(e) { if (e.status === 401) {
    signedOut();
    return;
} $('#notice').textContent = e.message || 'Operation unavailable'; }
function date(n) { return n ? new Date(n).toLocaleString() : '—'; }
function duration(n) { return `${Math.floor(n / 3600)}h ${Math.floor(n % 3600 / 60)}m ${Math.floor(n % 60)}s`; }
function heading(t) { return el('h2', t); }
function toolbar(...nodes) { const d = el('div', undefined, 'toolbar'); d.append(...nodes); return d; }
function detail(title, value) { const d = el('details'); d.append(el('summary', title), el('pre', JSON.stringify(value, null, 2))); return d; }
function table(columns, rows, actions) { const wrap = el('div', undefined, 'table-wrap'), t = el('table'), head = el('thead'), tr = el('tr'), body = el('tbody'); for (const c of columns)
    tr.append(el('th', c[0])); if (actions)
    tr.append(el('th', 'Actions')); head.append(tr); t.append(head, body); for (const row of rows) {
    const r = el('tr');
    for (const c of columns) {
        const td = el('td'), v = c[1](row);
        td.append(v instanceof Node ? v : document.createTextNode(String(v ?? '—')));
        r.append(td);
    }
    if (actions) {
        const td = el('td');
        td.append(...actions(row));
        r.append(td);
    }
    body.append(r);
} if (!rows.length) {
    const td = el('td', 'No matching records.', 'empty');
    td.colSpan = columns.length + (actions ? 1 : 0);
    const r = el('tr');
    r.append(td);
    body.append(r);
} wrap.append(t); return wrap; }
function searchable(columns, rows, actions, filters = []) { const root = el('div'), search = el('input'); search.placeholder = 'Search records…'; search.setAttribute('aria-label', 'Search records'); const result = el('div'), choices = []; const controls = toolbar(search); for (const [label, values, match] of filters) {
    const select = el('select');
    select.setAttribute('aria-label', label);
    for (const v of values) {
        const o = el('option', v);
        o.value = v;
        select.append(o);
    }
    choices.push([select, match]);
    select.onchange = render;
    controls.append(select);
} function render() { const q = search.value.toLowerCase(); const selected = rows.filter(r => JSON.stringify(r).toLowerCase().includes(q) && choices.every(([s, m]) => m(r, s.value))); result.replaceChildren(table(columns, selected, actions)); } search.oninput = render; root.append(controls, result); render(); return root; }
const field = (key, label, value = '', type = 'text', options) => ({ key, label, value, type, options });
function edit(title, route, fields, fixed = {}, description = 'Review this change before confirming. It will be recorded in the audit trail.') {
    const dialog = $('#dialog'), form = $('#editor');
    $('#dialog-title').textContent = title;
    $('#dialog-description').textContent = description;
    $('#dialog-error').textContent = '';
    $('#fields').replaceChildren();
    for (const f of fields) {
        const label = el('label', f.label, f.type === 'textarea' ? 'wide' : '');
        let input;
        if (f.options) {
            input = el('select');
            for (const v of f.options) {
                const o = el('option', v);
                o.value = v;
                input.append(o);
            }
        }
        else
            input = el(f.type === 'textarea' ? 'textarea' : 'input');
        input.name = f.key;
        if (f.type !== 'textarea' && !f.options)
            input.type = f.type;
        if (f.type === 'checkbox') {
            input.checked = Boolean(f.value);
            label.classList.add('check');
        }
        else
            input.value = String(f.value ?? '');
        if (f.type === 'password')
            input.autocomplete = 'new-password';
        input.required = !['variants', 'referencePrice', 'sprite', 'accessory'].includes(f.key) && f.type !== 'checkbox';
        label.append(input);
        $('#fields').append(label);
    }
    form.onsubmit = async (e) => { e.preventDefault(); const submit = $('#submit-dialog'); submit.disabled = true; $('#dialog-error').textContent = ''; try {
        const values = { ...fixed };
        for (const f of fields) {
            const input = form.elements.namedItem(f.key);
            values[f.key] = f.type === 'checkbox' ? input.checked : f.type === 'datetime-local' ? new Date(input.value).getTime() : input.value;
        }
        const result = await api.change(route, values);
        dialog.close();
        $('#notice').textContent = route === 'account-check-password' ? (result.matches ? 'Password matches.' : 'Password does not match.') : 'Change saved.';
        await render();
    }
    catch (error) {
        $('#dialog-error').textContent = error.message;
    }
    finally {
        submit.disabled = false;
    } };
    dialog.showModal();
}
$('#close-dialog').onclick = $('#cancel-dialog').onclick = () => $('#dialog').close();
function confirmAction(title, route, values = {}, extra = []) { edit(title, route, extra, values, 'This action takes effect immediately. Confirm only after checking the selected target.'); }
function localDate(n) { const d = new Date(n); return new Date(n - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16); }
function metric(label, value, note) { const d = el('div', undefined, 'metric'); d.append(el('small', label), el('div', value, 'value'), el('small', note)); return d; }
function chart(key, max, label) { const panel = el('div', undefined, 'panel'); panel.append(heading(label)); const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox', '0 0 500 150'); svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', label + ' over the last five minutes'); svg.classList.add('chart'); for (const y of [30, 75, 120]) {
    const line = document.createElementNS(svg.namespaceURI, 'line');
    for (const [k, v] of Object.entries({ x1: 0, x2: 500, y1: y, y2: y }))
        line.setAttribute(k, v);
    svg.append(line);
} const path = document.createElementNS(svg.namespaceURI, 'path'); path.setAttribute('d', samples.map((s, i) => `${i ? 'L' : 'M'}${i * 500 / Math.max(1, samples.length - 1)},${145 - Math.max(0, Math.min(1, s[key] / max)) * 135}`).join(' ')); svg.append(path); panel.append(svg, el('small', '5-second samples · up to 5 minutes · gaps while this page is inactive')); return panel; }
async function dashboard(host) { const s = await api.request('status'); if (!host.isConnected)
    return; $('#connection').textContent = s.state; const m = s.metrics; samples.push({ cpu: Math.max(0, m.processCpu) * 100, heap: m.heapUsed / 1048576 }); samples = samples.slice(-60); const cards = el('div', undefined, 'metrics'); cards.append(metric('GAME LISTENER', s.state, s.endpoint || 'Awaiting start'), metric('ONLINE', s.sessions.length, 'Connected clients'), metric('ACCOUNTS', s.accounts, `${s.scenes.length} scene instances`), metric('UPTIME', duration(s.uptimeSeconds), `Tortodrone ${s.version}`)); host.append(cards); const charts = el('div', undefined, 'grid'); charts.append(chart('cpu', 100, 'Process CPU · %'), chart('heap', Math.max(1, m.heapMax / 1048576), 'JVM heap · MiB')); host.append(charts); const panel = el('div', undefined, 'panel'); panel.append(heading('Listener controls'), el('p', 'Stopping the game listener disconnects players. HTTP administration remains available.')); if (rank() === 2)
    panel.append(toolbar(button('Start', () => confirmAction('Start game listener', 'start')), button('Stop', () => confirmAction('Stop and disconnect all players', 'stop')), button('Restart', () => confirmAction('Restart game listener', 'restart')), button('Shut down process', () => confirmAction('Shut down the whole process', 'shutdown', {}, [field('shutdown', 'Type SHUT DOWN PROCESS')]), 'danger'))); panel.append(detail('Database operations', s.database), detail('JVM and host metrics', m)); if (s.failure)
    panel.append(el('p', s.failure, 'error')); host.append(panel); const recent = await api.request('logs'); host.append(heading('Recent warnings and errors'), table([['Time', r => date(r.timestamp)], ['Level', r => r.level], ['Message', r => r.message]], recent.filter(r => ['WARN', 'ERROR'].includes(r.level)).slice(-8).reverse())); }
async function players(host) { const s = await api.request('status'); host.append(searchable([['Session', r => r.id], ['Account', r => r.username], ['Knight', r => r.knight], ['State', r => r.state], ['Scene / instance', r => `${r.scene || '—'} / ${r.instanceId || '—'}`], ['Duration', r => duration((Date.now() - Date.parse(r.connectedAt)) / 1000)]], s.sessions, r => rank() >= 1 ? [button('Inspect', () => openAccount(r.username)), button('Kick', () => confirmAction(`Disconnect session ${r.id}`, 'kick', { session: r.id }))] : []), heading('Scene population'), searchable([['Scene', r => r.name ?? r.scene], ['Instance', r => r.instanceId ?? r.instance], ['Players', r => r.players], ['Details', r => detail('Instance state', r)]], s.scenes)); }
let selectedAccount = null, selectedKnight = null;
async function openAccount(username) { if (!username)
    return; selectedAccount = username; selectedKnight = null; location.hash = 'accounts'; if (current === 'accounts')
    await render(); }
const baseFields = () => [field('username', 'Username'), field('password', 'Password', '', 'password'), field('gm', 'Game Master', false, 'checkbox')];
async function accountsPage(host) { const rows = await api.request('accounts'); if (rank() === 2)
    host.append(toolbar(button('Create account', () => edit('Create game account', 'account-create', baseFields())))); host.append(searchable([['ID', r => r.id], ['Username', r => r.username], ['GM', r => r.gameMaster ? 'Yes' : 'No'], ['Knights', r => r.knights]], rows, r => [button('Inspect', () => openAccount(r.username))])); if (!selectedAccount)
    return; const account = rows.find(a => a.username === selectedAccount); if (!account)
    return; host.append(heading(account.username)); const fixed = { username: account.username, accountId: account.id }; if (rank() === 2)
    host.append(toolbar(button('Check password', () => edit('Check account password', 'account-check-password', [field('password', 'Password to check', '', 'password')], fixed)), button('Change password', () => edit('Change account password', 'account-password', [field('password', 'New password', '', 'password')], fixed)), button('GM permission', () => edit('Change game permission', 'account-gm', [field('gm', 'Game Master', account.gameMaster, 'checkbox')], fixed)), button('Delete account', () => confirmAction('Delete ' + account.username, 'account-delete', fixed, [field('nameConfirmation', 'Type the account username')]), 'danger'))); const knights = await api.request('knights', { username: account.username }); host.append(table([['Knight', k => k.name], ['Roster ID', k => k.id]], knights, k => [button('Inventory & progress', async () => { selectedKnight = k.name; await render(); })])); if (selectedKnight)
    await knightPage(host, account, selectedKnight); }
function modifiersFields(item) { return [field('level', 'Level (zero-based)', item?.level || 0, 'number'), field('heat', 'Heat fraction (0–1)', item?.heatProgress || 0), field('variants', 'Variant paths, one per line', (item?.variants || []).map(v => v.name).join('\n'), 'textarea')]; }
async function knightPage(host, account, name) { const p = await api.request('knight', { username: account.username, knight: name }); const fixed = { username: account.username, knight: name, accountId: account.id, worldId: p.worldId, revision: p.revision }; host.append(heading(name), el('p', `${p.crowns.toLocaleString()} crowns · ${p.energy.toLocaleString()} energy · rank ${p.progress.rank}-${p.progress.subrank} · ${p.progress.prestige} prestige · revision ${p.revision}`)); if (rank() === 2)
    host.append(toolbar(button('Currency', () => edit('Manage currency', 'knight-currency', [field('currency', 'Currency', 'CROWNS', 'text', ['CROWNS', 'ENERGY']), field('operation', 'Operation', 'set', 'text', ['set', 'delta']), field('amount', 'Amount (negative delta removes)', 0, 'number')], fixed)), button('Progress', () => edit('Rank and prestige', 'knight-progress', [field('rank', 'Rank', p.progress.rank, 'number'), field('subrank', 'Subrank', p.progress.subrank, 'number'), field('prestige', 'Prestige', p.progress.prestige, 'number'), field('sprite', 'Optional first sprite', '', 'text', ['', 'Drakon', 'Seraphynx', 'Maskeraith'])], fixed)), button('Grant item', () => edit('Grant item', 'item-grant', [field('config', 'Exact catalogue item path'), field('quantity', 'Quantity', 1, 'number'), ...modifiersFields(), field('bound', 'Bind granted item', false, 'checkbox'), field('expireDays', 'Expiration in days (0 permanent)', 0, 'number')], fixed, 'Use the Content catalogue to find a supported item path.')), button('Delete knight', () => confirmAction('Delete ' + name, 'knight-delete', fixed, [field('nameConfirmation', 'Type the knight name')]), 'danger'))); host.append(searchable([['Item', i => i.config], ['Quantity', i => i.count], ['Equipment', i => i.equippedSlot ?? 'Unequipped'], ['Level / heat', i => `${i.level + 1} / ${Math.round(i.heatProgress * 100)}%`], ['State', i => detail('Flags, accessories & expiry', i)]], p.items, i => rank() === 2 ? [button('Accessories', () => edit('Add or remove accessories', 'item-accessory', [field('accessory', 'Exact accessory path (empty removes all)')], { ...fixed, itemId: i.id })), button('Edit gear', () => edit('Edit gear modifiers', 'item-modifiers', modifiersFields(i), { ...fixed, itemId: i.id })), button('Quantity / remove', () => edit('Set item quantity', 'item-quantity', [field('quantity', 'Quantity (0 removes)', i.count, 'number')], { ...fixed, itemId: i.id }, 'Starter, permanent and equipped items are protected by the authoritative inventory service.'))] : [])); }
function offerFields(o) { const g = o?.good || {}; return [field('config', 'Item path', g.item || ''), field('quantity', 'Quantity', g.count || 1, 'number'), field('crowns', 'Crowns', Math.max(0, g.price || 0), 'number'), field('energy', 'Energy', g.energy || 0, 'number'), field('limit', 'Lifetime purchase limit (0 unlimited)', g.limit || 0, 'number'), field('featured', 'Featured', o?.featured || false, 'checkbox'), field('enabled', 'Enabled', o?.enabled ?? true, 'checkbox'), field('promotionOnly', 'Promotion only', o?.promotionOnly || false, 'checkbox')]; }
async function depot(host) { const d = await api.request('depot'), fixed = { revision: d.snapshot.revision }; host.append(toolbar(button('Create offer', () => edit('Create Depot offer', 'depot-save', offerFields(), { ...fixed, id: -1 })), el('span', `Revision ${fixed.revision}`, 'badge'))); host.append(searchable([['ID', o => o.id], ['Item', o => o.good.item], ['Qty', o => o.good.count], ['Price', o => `${o.good.price} crowns / ${o.good.energy} energy`], ['Status', o => o.enabled ? 'Enabled' : 'Disabled'], ['Featured', o => o.featured ? 'Yes' : 'No']], d.snapshot.offers, o => [button('Edit', () => edit('Edit offer #' + o.id, 'depot-save', offerFields(o), { ...fixed, id: o.id })), button('Schedule', () => edit('Schedule promotion', 'promotion-create', [field('starts', 'Starts', localDate(Date.now()), 'datetime-local'), field('ends', 'Ends', localDate(Date.now() + 86400000), 'datetime-local'), field('crowns', 'Crowns', Math.max(0, o.good.price), 'number'), field('energy', 'Energy', o.good.energy, 'number'), field('referencePrice', 'Comparison price (optional)', '', 'number'), field('featured', 'Featured', true, 'checkbox')], { ...fixed, offerId: o.id }))], [['Availability', ['All', 'Enabled', 'Disabled', 'Featured'], (o, v) => v === 'All' || v === 'Enabled' && o.enabled || v === 'Disabled' && !o.enabled || v === 'Featured' && o.featured]])); host.append(heading('Promotions'), searchable([['ID', p => p.id], ['Offer', p => p.offerId], ['State', promotionStatus], ['Starts', p => date(p.starts)], ['Ends', p => date(p.ends)]], d.promotions, p => p.cancelled ? [] : [button('Cancel', () => confirmAction('Cancel promotion #' + p.id, 'promotion-cancel', { ...fixed, id: p.id }))], [['State', ['All', 'Active', 'Scheduled', 'Expired', 'Cancelled'], (p, v) => v === 'All' || promotionStatus(p) === v]]), heading('Effective player catalogue'), searchable([['Offer', o => o.id], ['Item', o => o.good.item], ['Price', o => o.good.energy ? `${o.good.energy} energy` : `${o.good.price} crowns`], ['Special', o => o.promotionId < 0 ? 'Automatic Special' : o.sale ? 'Promotion sale' : o.featured ? 'Featured' : 'Regular'], ['Details', o => detail('Effective offer', o)]], d.effective.offers.filter(o => o.enabled))); }
function promotionStatus(p) { return p.cancelled ? 'Cancelled' : p.starts > Date.now() ? 'Scheduled' : p.ends <= Date.now() ? 'Expired' : 'Active'; }
function auctionStatus(a) { return a.cancelled ? 'Cancelled' : a.ended || a.expires <= Date.now() ? 'Ended' : a.availableAt > Date.now() ? 'Scheduled' : 'Active'; }
async function auctions(host) { const rows = await api.request('auctions'); host.append(toolbar(button('Create featured auction', () => edit('Schedule featured auction', 'auction-create', [field('config', 'Item path'), field('quantity', 'Quantity', 1, 'number'), field('bid', 'Opening bid', 100, 'number'), field('buy', 'Buyout (0 disables)', 0, 'number'), field('stock', 'Stock (1 for bidding)', 1, 'number'), field('starts', 'Starts', localDate(Date.now()), 'datetime-local'), field('expires', 'Expires', localDate(Date.now() + 86400000), 'datetime-local')])), button('Settle expired', () => confirmAction('Settle expired auctions', 'auction-settle')))); host.append(searchable([['ID', r => r.listing.id], ['Item', r => r.listing.item.config], ['Seller', r => r.seller], ['Bidder', r => r.bidder], ['Bid / buyout', r => `${r.listing.bid} / ${r.listing.buy}`], ['State', r => auctionStatus(r.listing)]], rows, r => [button('Inspect', async () => { const deliveries = await api.request('deliveries', { id: r.listing.id }); const d = el('div', undefined, 'panel'); d.append(heading('Auction #' + r.listing.id), detail('Listing & escrow', r), detail('Deliveries', deliveries)); host.append(d); }), ...(!r.listing.ended ? [button('Cancel', () => confirmAction('Cancel auction #' + r.listing.id, 'auction-cancel', { id: r.listing.id }))] : [])], [['State', ['All', 'Active', 'Scheduled', 'Ended', 'Cancelled'], (r, v) => v === 'All' || auctionStatus(r.listing) === v], ['Seller', ['All', 'Player', 'System'], (r, v) => v === 'All' || (v === 'System') === r.listing.featured]])); }
async function news(host) { const n = await api.request('news'); host.append(toolbar(button('Edit validated News YAML', () => edit('Update Uplink News', 'news-save', [field('yaml', 'News configuration', n.yaml, 'textarea')], { revision: n.revision }, 'Only news.yml is replaced after validation. Use existing News images. Restart the game listener to publish the new pack selection.')), el('span', n.catalog.enabled ? 'Visible' : 'Hidden', 'badge'))); host.append(el('p', 'Pack selection is randomized by the stock client protocol. Array order is preserved; disabling News hides every pack.')); for (const [i, p] of n.catalog.packs.entries()) {
    const panel = el('div', undefined, 'panel');
    panel.append(heading('Pack ' + (i + 1)));
    for (const b of [p.featured, p.left, p.right].filter(Boolean)) {
        const img = el('img');
        img.src = '/news/' + b.image.split('/').map(encodeURIComponent).join('/');
        img.alt = b.tooltip || b.image;
        img.className = 'banner';
        panel.append(img, el('p', b.tooltip || b.image));
    }
    host.append(panel);
} if (!n.catalog.packs.length)
    host.append(el('p', 'No News packs configured.')); }
async function content(host) { const c = await api.request('content'); host.append(el('p', `Startup validation passed · ${c.maps} maps · ${c.items} items`)); for (const w of c.warnings)
    host.append(el('p', w, 'error')); const search = el('input'); search.placeholder = 'Search item catalogue'; search.setAttribute('aria-label', 'Search item catalogue'); const result = el('div'); let timer; async function load() { const rows = await api.request('items', { search: search.value }); result.replaceChildren(table([['Item', r => r.name], ['Path', r => r.config], ['Category', r => r.category], ['Support', r => r.grantable ? 'Grantable' : r.reason]], rows)); } search.oninput = () => { clearTimeout(timer); timer = setTimeout(() => load().catch(showError), 300); }; host.append(search, el('p', 'Up to 250 matches. Copy the canonical path when granting an item.'), result, detail('Merchant catalogue', c.merchants)); await load(); }
let logQuery = '', logSeverity = 'All';
async function logs(host) { const rows = await api.request('logs'), search = el('input'), severity = el('select'); search.value = logQuery; search.placeholder = 'Search logs'; search.setAttribute('aria-label', 'Search logs'); for (const v of ['All', 'INFO', 'WARN', 'ERROR', 'DEBUG'])
    severity.append(el('option', v)); severity.value = logSeverity; severity.setAttribute('aria-label', 'Severity'); const output = el('div', undefined, 'log panel'); function display() { const filtered = rows.filter(r => (logSeverity === 'All' || r.level === logSeverity) && r.line.toLowerCase().includes(logQuery.toLowerCase())); output.replaceChildren(...filtered.map(r => el('div', r.line, r.level))); if (autoScroll)
    output.scrollTop = output.scrollHeight; } search.oninput = () => { logQuery = search.value; display(); }; severity.onchange = () => { logSeverity = severity.value; display(); }; host.append(toolbar(search, severity, button(paused ? 'Resume' : 'Pause', () => { paused = !paused; render(); }), button(autoScroll ? 'Auto-scroll on' : 'Auto-scroll off', () => { autoScroll = !autoScroll; render(); }), button('Export visible', () => { const text = rows.filter(r => (logSeverity === 'All' || r.level === logSeverity) && r.line.toLowerCase().includes(logQuery.toLowerCase())).map(r => r.line).join('\n'); const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' })), a = el('a'); a.href = url; a.download = 'tortodrone-log.txt'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); })), output); display(); }
async function audit(host) { const rows = await api.request('audit'); host.append(searchable([['Time', r => date(r.time)], ['Actor', r => r.actor], ['Action', r => r.action], ['Target', r => r.target], ['Outcome', r => r.outcome]], rows)); }
const renderers = { dashboard, players, accounts: accountsPage, depot, auctions, news, content, logs, audit };
async function render() { if (!api.session)
    return; const id = ++generation; current = location.hash.slice(1) || 'dashboard'; const spec = pages.find(p => p[0] === current && p[3] <= rank()); if (!spec) {
    location.hash = 'dashboard';
    return;
} $('#title').textContent = spec[1]; $('#subtitle').textContent = spec[2]; for (const a of $('#navigation').children)
    a.setAttribute('aria-current', a.hash === '#' + current ? 'page' : 'false'); const host = el('div'); page.replaceChildren(host); host.append(el('p', 'Loading…')); busy = true; try {
    host.replaceChildren();
    await renderers[current](host);
}
catch (e) {
    if (id === generation)
        showError(e);
}
finally {
    if (id === generation)
        busy = false;
} }
function signedIn() { $('#login').hidden = true; $('#shell').hidden = false; $('#identity').textContent = api.session.username + ' · ' + api.session.role.toLowerCase(); $('#navigation').replaceChildren(...pages.filter(p => p[3] <= rank()).map(p => { const a = el('a', p[1]); a.href = '#' + p[0]; return a; })); render(); clearTimeout(pollTimer); poll(); }
function signedOut() { api.session = null; generation++; $('#shell').hidden = true; $('#login').hidden = false; page.replaceChildren(); clearTimeout(pollTimer); }
async function poll() { pollTimer = setTimeout(async () => { try {
    if (api.session && !document.hidden && !busy && !$('#dialog').open && ['dashboard', 'players', 'logs'].includes(current) && !(current === 'logs' && paused) && !page.contains(document.activeElement))
        await render();
}
finally {
    if (api.session)
        poll();
} }, 5000); }
$('#login-form').onsubmit = async (e) => { e.preventDefault(); const f = e.currentTarget, b = f.querySelector('button'); b.disabled = true; $('#login-error').textContent = ''; try {
    await api.login(f.elements.username.value, f.elements.password.value);
    f.reset();
    signedIn();
}
catch (error) {
    $('#login-error').textContent = error.message;
}
finally {
    b.disabled = false;
} };
$('#logout').onclick = async () => { try {
    await api.logout();
    signedOut();
}
catch (e) {
    showError(e);
} };
$('#theme').onclick = () => document.documentElement.dataset.theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
$('#refresh').onclick = () => render();
window.onhashchange = () => render();
$('#clock').textContent = new Date().toLocaleDateString(undefined, { dateStyle: 'long' });
try {
    await api.restore();
    signedIn();
}
catch {
    signedOut();
}
