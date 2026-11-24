/**
 * The address is the only view state a colleague can be handed, so the rules around
 * it are pinned here rather than discovered by a round of manual testing:
 *
 *  - a table that owns `?search=` reads it back on a cold load;
 *  - the address outranks a filter this browser remembered;
 *  - the address is written once a read lands, so a copied link is not lying;
 *  - the one page with two lists gives each list its own parameter.
 *
 * The same file pins the navigation contract the audits broke twice: a word on the
 * toggle, and one breakpoint shared by the CSS and the script.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../../../', import.meta.url).pathname;
const read = (path) => readFileSync(`${root}${path}`, 'utf8');

const table = read('frontend/assets/components/table.js');
const app = read('frontend/assets/app.js');
const layout = read('frontend/assets/css/layout.css');
const base = read('frontend/assets/css/base.css');
const components = read('frontend/assets/css/components.css');
const html = read('frontend/index.html');
const content = read('frontend/assets/pages/content.js');
const logs = read('frontend/assets/pages/logs.js');
const consolePage = read('frontend/assets/pages/console.js');
const stream = read('frontend/assets/components/log-stream.js');

test('a search in the address survives a cold load', () => {
    // The option is read before `this.address` is assigned: reading the field there
    // was always undefined, so four of five lists opened unfiltered under a filtered
    // address.
    assert.match(
        table,
        /const fromAddress = address \? route\(\)\.params\.get\(addressParam\) : null;/,
        'the table reads its own address parameter from the option'
    );
    assert.match(
        table,
        /if \(fromAddress\) this\.query\.value = fromAddress;/,
        'the address fills the search box before the first render'
    );
    const optionAt = table.indexOf('address ? route().params.get(addressParam)');
    const assignAt = table.indexOf('this.address = address;');
    assert.ok(optionAt > 0 && assignAt > optionAt, 'the option, not the field, is read first');
});

test('the address outranks a remembered filter', () => {
    const start = table.indexOf('const savedValue = saved?.filters?.[name];');
    assert.ok(start > 0, 'the filter defaults are still read from storage');
    const block = table.slice(start, start + 900);
    const addressAt = block.indexOf('has(fromAddress)');
    const savedAt = block.indexOf('has(savedValue)');
    assert.ok(addressAt > 0 && savedAt > addressAt, 'a shared link wins over this browser');
    // A filter option may be `[value, label]`, and a value has to be compared, never
    // the pair: matching the pair matched nothing, so WebUI Access listed no rows.
    assert.match(
        table,
        /const values = options\.map\(\(option\) => \(Array\.isArray\(option\) \? option : \[option, option\]\)\)/,
        'the option list is normalised to value/label pairs before anything is compared'
    );
});

test('a read writes the address it is looking at', () => {
    const start = table.indexOf('    update(rows) {');
    assert.ok(start > 0, 'the table still has an update method');
    const block = table.slice(start, table.indexOf('    /**', start));
    assert.match(block, /this\.syncAddress\(\);/, 'a landed read makes the address true');
});

test('two lists on one page own two parameters', () => {
    assert.match(
        content,
        /addressParam:\s*'merchant'/,
        'the merchant table does not steal the picker’s ?search='
    );
    assert.match(content, /address:\s*true/, 'the merchant table keeps its filter in the address');
});

test('the navigation toggle is a word and shares one breakpoint', () => {
    assert.match(html, /id="menu"[\s\S]{0,200}>\s*Menu<\/button/, 'the toggle says Menu');
    assert.doesNotMatch(layout, /#menu::before/, 'no hand-drawn hamburger');
    // The drawer breakpoint is the one 900px block that reveals #menu; the script has
    // to name the same number, or the toggle is dead between the two widths.
    const drawer =
        /@media \(max-width: (\d+)px\) \{((?:(?!@media)[\s\S])*?)#menu \{\s*display: inline-flex/.exec(
            layout
        );
    assert.ok(drawer, 'the drawer reveals the navigation toggle');
    assert.ok(
        app.includes(`matchMedia('(max-width: ${drawer[1]}px)')`),
        'the script and the stylesheet agree on where the drawer starts'
    );
});

test('a link-styled control is still a target', () => {
    const start = base.indexOf('button.link,');
    assert.ok(start > 0);
    const block = base.slice(start, base.indexOf('}', base.indexOf('text-decoration: underline', start)));
    assert.match(block, /min-block-size:\s*2[4-9]px|min-block-size:\s*3[0-9]px/, '24px floor');
    assert.doesNotMatch(block, /min-block-size:\s*0/, 'a zero-height control cannot be pressed');
});

test('row actions share one size', () => {
    const start = components.indexOf('td button,');
    assert.ok(start > 0, 'row actions are sized in one place');
    const block = components.slice(start, components.indexOf('}', start));
    assert.match(block, /td \.button/, 'an anchor that routes is a row action too');
});

test('a field the browser refused looks refused', () => {
    assert.match(base, /:user-invalid/, 'an invalid field is marked after the operator touches it');
});

test('the log pane names where to go next', () => {
    assert.match(logs, /extras:/, 'the log bar carries the pages that explain a line');
    for (const page of ['audit', 'accounts', 'overview'])
        assert.ok(logs.includes(`['${page}'`), `logs links to ${page}`);
    assert.match(stream, /extras = \[\]/, 'the stream accepts extra bar controls');
});

test('an empty console is not a dead end', () => {
    assert.match(consolePage, /commands\\n/, 'the banner counts the commands');
    assert.match(consolePage, /notice: true/, 'the banner is a line in the transcript');
    assert.match(stream, /row\.entry\.links\?\.length && onLink/, 'a notice keeps its links');
});

/**
 * The console is a terminal emulator, not a page with a shell drawn in it:
 *
 *  - the prompt is the line after the last answer, inside the scrollback, so scrolling
 *    up into history takes the prompt with it;
 *  - the transcript prints from the top, with the empty screen below it;
 *  - the filters are a find bar opened with Ctrl+F, because a terminal has no toolbar.
 *
 * The row-rendering pass rebuilds the body's children by index and removes anything it
 * did not draw, so the slot holding the prompt has to be known to it: without the guard
 * the prompt was culled on the next poll and the page lost its only input.
 */
test('the console keeps its prompt at the end of the buffer', () => {
    assert.match(consolePage, /stream\.bodyTail\.append\(prompt\)/, 'the prompt is a line in the buffer');
    assert.doesNotMatch(consolePage, /insertBefore\(prompt/, 'the prompt is not a strip under the screen');
    assert.match(stream, /child !== bodyTail/, 'the render pass keeps the prompt slot');
    assert.match(stream, /output\.append\(bodyTail\)/, 'the slot is the last child of the body');
    assert.match(consolePage, /findBar: true/, 'the console answers Ctrl+F with a find bar');
    assert.match(stream, /tools\.hidden = true/, 'the find bar is closed on arrival');
    assert.match(stream, /findBar && event\.key === 'Escape'/, 'Escape closes the find bar from its own field');
});
test('the console prints from the top of the screen', () => {
    const pages = read('frontend/assets/css/pages.css');
    assert.match(
        pages,
        /\.console-workspace \.terminal-body::before \{[^}]*display: none;/,
        'the leading spacer that floats a short transcript to the bottom is off for the console'
    );
    assert.match(pages, /\.terminal-find\[hidden\] \{\s*display: none;/, 'the bar is gone when closed');
});
test('an in-app link moves the box, not only a reloaded document', () => {
    // The table reads its parameter when it is built, so a page that only handles
    // `navigate` by re-rendering rows leaves the search box where the last visit put
    // it: the address says one thing, the list another.
    for (const [file, call] of [
        ['frontend/assets/pages/accounts.js', "table.setQuery(params.get('search')"],
        ['frontend/assets/pages/knights.js', "roster.setQuery(next.get('search')"],
        ['frontend/assets/pages/mailing.js', "table.setQuery(params.get('search')"],
        ['frontend/assets/pages/content.js', "stationTable.setQuery(params.get('merchant')"],
        ['frontend/assets/pages/depot.js', 'offers.setQuery('],
        ['frontend/assets/pages/players.js', 'players.setQuery(']
    ]) {
        assert.ok(read(file).includes(call), `${file} drives its own address parameter`);
    }
});
