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

test('an empty console prints a banner, and the banner names the keys', () => {
    assert.match(consolePage, /commands\\n/, 'the banner counts the commands');
    assert.match(consolePage, /notice: true/, 'the banner is a line in the transcript');
    assert.match(consolePage, /Tab completes · ↑↓ history/, 'the banner writes the chords down');
    assert.match(
        consolePage,
        /matchMedia\('\(pointer: fine\)'\)/,
        'a touch screen is not told to press Tab and Ctrl'
    );
});

/**
 * A terminal prints; it does not tabulate. The log viewer's gutter - time, level and
 * thread in three columns - was the last piece of it left in the console: every command
 * went out under `22:17:53 CMD`, and the answers under it were laid out as rows of a
 * log. A shell prints the prompt and the output, and nothing beside it.
 */
test('a console row is printed, not tabulated', () => {
    const pages = read('frontend/assets/css/pages.css');
    assert.match(stream, /line\.classList\.add\('printed'\)/, 'an answer is a printed row');
    assert.match(
        pages,
        /\.terminal-line\.notice,\s*\.terminal-line\.printed \{\s*grid-template-columns: minmax\(min-content, 1fr\);/,
        'a printed row takes the whole width and may widen for an unwrapped table'
    );
    // The output is the answer: folding it behind a "show all" control put a widget in
    // the middle of a terminal, and the pane already scrolls.
    assert.doesNotMatch(stream, /ANSWER_LINES_SHOWN|more \$\{rest\}/, 'an answer is not folded');
    // Those destinations are in the sidebar. Printing them again under every answer is
    // the one thing a Minecraft panel's console never does.
    assert.doesNotMatch(stream, /linkStrip|command-links/, 'the transcript links nowhere');
});

/**
 * A list of records is a table with one header row, which is how a terminal prints one:
 * `ps`, a SQL client, anything with more than one row to show. Nested plain, four
 * accounts printed `username: alice` five times over and the seam between the four rows
 * of data disappeared.
 */
test('records print as a table, one header row', () => {
    assert.match(stream, /function table\(records, indent\)/, 'records are tabulated');
    assert.match(stream, /row\(widths\.map\(\(width\) => '-'.repeat\(width\)\)\)/, 'the header is ruled off');
    assert.match(stream, /value\.map\(\(record\) => align\(record, indent \+ '  '\)\)/, 'a record with a nested object keeps its block');
});

/** The prompt is the terminal, so the caret goes where the operator clicks. */
test('clicking the screen puts the caret on the prompt', () => {
    assert.match(consolePage, /output\.addEventListener\('click'/, 'the screen is a way into the prompt');
    assert.match(consolePage, /isCollapsed/, 'dragging a selection is not a click on the screen');
    assert.match(consolePage, /closest\('a, button/, 'a control in the transcript keeps its own click');
});

/**
 * A console has no second status bar: its chords are in the banner and its state is in
 * the bar above the screen, so the strip under the last line is only drawn when a page
 * writes something into it.
 */
test('the console keeps its state in the bar, not in a strip', () => {
    assert.match(consolePage, /stream\.barMeta\.append\(feedback/, 'the state is in the bar');
    assert.match(consolePage, /keys: \[\]/, 'the console binds no permanent key legend');
    assert.match(stream, /footer\.hidden =/, 'an empty strip is not drawn');
    assert.match(read('frontend/assets/css/pages.css'), /\.terminal-status\[hidden\] \{\s*display: none;/, 'a hidden strip is hidden');
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
/**
 * A filter that outlives its own bar is a screen that lies about what it holds: Escape
 * closes the find bar and keeps the term, so the view stayed filtered with nothing on it
 * saying why, and the next answer arrived filtered out while the prompt looked like it
 * had eaten the command. The bar names the filter now, one click drops it, and a command
 * always lands on a whole screen.
 */
test('a closed find bar still says what it is filtering', () => {
    assert.match(consolePage, /filterChip\.hidden = !term;/, 'the bar carries the filter');
    assert.match(consolePage, /'\\u201c' \+ search \+ '\\u201d'/, 'and names the term');
    assert.match(consolePage, /severity !== 'All' \? severity : ''/, 'and the level as the select spells it');
    assert.match(consolePage, /stream\.barMeta\.append\(feedback, filterChip\)/, 'in the bar, not a strip');
    assert.match(
        consolePage,
        /showFilter\(view\.severity, view\.search\)/,
        'a deep-linked filter says so on arrival'
    );
    assert.match(
        stream,
        /findBar\s*\?\s*\/\/[^]*?Clear it in the bar, or press Ctrl F\./,
        'the empty screen points at the control that is on screen'
    );
});
test('a command is never born behind a filter', () => {
    // The filter is dropped before the echo goes out, so its answer cannot arrive into a
    // hidden row; the chip and the address follow in clearFind.
    assert.match(
        consolePage,
        /if \(!value\) return;\n[^]*?clearFind\(\);/,
        'submitting clears the find'
    );
    assert.match(
        consolePage,
        /view = \{ severity: 'All', search: '' \};\s*stream\.filter\(view\);/,
        'and repaints the whole screen, both axes dropped'
    );
    assert.match(consolePage, /writeAddress\(view\)/, 'the address stops claiming the filter');
});
test('the prompt lets go of the keyboard', () => {
    // Tab completes and lists, which kept it from ever being an exit: Shift+Tab returns
    // focus to the bar, where the menu and the filter chip live.
    assert.match(
        consolePage,
        /if \(event\.key === 'Tab'\) \{\n\s*\/\/[^]*?\n\s*if \(event\.shiftKey\) return;/,
        'Shift+Tab leaves the prompt'
    );
});
test('an empty value prints on the line that names it', () => {
    // `byType` printed as a dangling colon with a lone dash under it, in a block where
    // every other key was `key : value`, and the dash landed in a column of its own.
    assert.match(stream, /const empty =\s*\n?\s*nested && \(Array\.isArray\(item\)/, 'emptiness is tested first');
    assert.match(stream, /if \(nested && !empty\) return `\$\{label\}:\\n\$\{align/, 'only a bag with rows nests');
    assert.match(stream, /\$\{empty \? '—' : human\(key, item\)\}/, 'an empty bag stays on its key line');
    // A top-level empty answer was a lone dash under the prompt, which is punctuation
    // where an answer should be: it names the absence instead.
    assert.match(stream, /return command \? `\(no \$\{command\}\)` : '—';/, 'an empty answer names itself');
    assert.match(stream, /formatOutput\(entry\.output, entry\.command\)/, 'the answer knows its command');
});
/**
 * The address is the view. The sidebar's Console entry points at `#console` while the
 * screen it is already on may be filtered, and that visit changes only the parameters -
 * so the pane has to be told, or it keeps a filter the address no longer claims.
 */
test('a visit that changes only the parameters reaches the pane', () => {
    assert.match(consolePage, /navigate\(params\) \{/, 'the console answers a parameter-only visit');
    assert.match(
        consolePage,
        /if \(next\.severity === view\.severity && next\.search === view\.search\) return;/,
        'and does nothing when the parameters say what the pane already shows'
    );
    assert.match(consolePage, /showFilter\(view\.severity, view\.search\)/, 'the chip follows the address');
});
