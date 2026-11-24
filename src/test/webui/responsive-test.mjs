/**
 * The narrow-screen behaviour is CSS-driven, so nothing would fail loudly if a
 * refactor dropped it. This test pins the contract: card rows with visible
 * labels, a clipped header row, and target sizes that survive a touch screen.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../../../', import.meta.url).pathname;
const components = readFileSync(`${root}frontend/assets/css/components.css`, 'utf8');
const base = readFileSync(`${root}frontend/assets/css/base.css`, 'utf8');
const table = readFileSync(`${root}frontend/assets/components/table.js`, 'utf8');
const pages = readFileSync(`${root}frontend/assets/css/pages.css`, 'utf8');
const html = readFileSync(`${root}frontend/index.html`, 'utf8');

/** The declarations inside the first `@media (max-width: 760px)` block. */
function mobileBlock(css) {
    const start = css.indexOf('@media (max-width: 760px)');
    assert.ok(start >= 0, 'components.css must keep a 760px breakpoint');
    let depth = 0;
    for (let index = css.indexOf('{', start); index < css.length; index++) {
        if (css[index] === '{') depth++;
        else if (css[index] === '}' && --depth === 0) return css.slice(start, index);
    }
    throw new Error('unbalanced breakpoint block');
}

test('the document declares a responsive viewport', () => {
    assert.match(html, /name="viewport"[^>]*width=device-width/);
});

test('tables become labelled cards below 760px', () => {
    const mobile = mobileBlock(components);
    assert.match(mobile, /\.data-table table\s*\{[^}]*display:\s*block/, 'table must leave grid layout');
    assert.match(mobile, /\.data-table thead\s*\{[^}]*clip-path/, 'the header row must be visually hidden');
    assert.match(mobile, /\.data-table td\s*\{[^}]*grid-template-columns/, 'cells must stack label and value');
    assert.match(mobile, /\.data-table \.table-wrap\s*\{[^}]*overflow:\s*visible/, 'cards must not scroll');
    assert.match(components, /content:\s*attr\(data-label\)/, 'the card label reads data-label');
    assert.match(table, /dataset\.label|data-label/, 'cells must carry the label');
});

test('touch targets stay large enough on a phone', () => {
    const mobile = mobileBlock(components);
    assert.match(mobile, /min-block-size:\s*36px/, 'card actions need a 36px touch target');
    assert.match(base, /min-block-size:\s*32px/, 'buttons never render shorter than 32px');
    assert.match(components, /\.select-target\s*\{[^}]*24px/, 'selection needs a 24px hit area');
});

/** Every `@media (max-width: …)` block in a stylesheet, as text. */
function narrowBlocks(css) {
    const blocks = [];
    for (const match of css.matchAll(/@media[^{]*max-width:[^{]*\{/g)) {
        let depth = 1;
        for (let index = match.index + match[0].length; index < css.length; index++) {
            if (css[index] === '{') depth++;
            else if (css[index] === '}' && --depth === 0) {
                blocks.push(css.slice(match.index, index));
                break;
            }
        }
    }
    return blocks;
}

test('page grids collapse before they can overflow', () => {
    // Wide deliverables inside a grid track are what push a page sideways, so
    // every multi-column page section needs a narrow-screen override.
    const narrow = narrowBlocks(pages);
    for (const selector of ['.news-workspace', '.equipment-grid', '.metric-strip'])
        assert.ok(
            narrow.some(
                (block) =>
                    block.includes(selector) && /grid-template-columns/.test(block)
            ),
            `${selector} needs a narrow-screen override in pages.css`
        );
});

test('the shell fits a 360px viewport', () => {
    // Wide min-widths are the usual cause of horizontal overflow on phones.
    for (const css of [base, components]) {
        for (const match of css.matchAll(/min-width:\s*(\d+)px/g))
            assert.ok(Number(match[1]) <= 360, `min-width:${match[1]}px overflows a 360px viewport`);
    }
});
