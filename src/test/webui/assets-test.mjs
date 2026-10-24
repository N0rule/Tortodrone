/** Verify a copied deployment, with no access to or dependency on an installed game. */
import assert from 'node:assert/strict';
import { readFile, realpath } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, sep } from 'node:path';
const root = await realpath(process.argv[2] || 'build/webui');
const game = resolve(root, 'assets/game');
const manifest = JSON.parse(await readFile(resolve(game, 'manifest.json'), 'utf8'));
const symbols = [
    'brand',
    ...[
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
        'audit',
        'equipment',
        'inventory',
        'sprites',
        'progression',
        'details',
        'add',
        'edit',
        'remove',
        'save'
    ].map((k) => 'nav-' + k),
    ...['helmet', 'armor', 'shield', 'weapon', 'trinket', 'costume'].map((k) => 'slot-' + k)
];
for (const key of symbols) assert.ok(manifest.symbols?.[key], 'Missing native symbol: ' + key);
assert.ok(Object.keys(manifest.items).length > 9000, 'Original item artwork is packaged');
assert.ok(Object.keys(manifest.sprites).length > 0, 'Sprite artwork is packaged');
const files = new Set();
for (const group of ['items', 'ui', 'sprites', 'symbols'])
    for (const file of Object.values(manifest[group] || {})) {
        assert.match(file, /^icons\/[a-f0-9]{64}\.(png|webp)$/);
        files.add(file);
    }
for (const file of files) {
    const absolute = await realpath(resolve(game, file));
    assert.ok(absolute.startsWith(game + sep), 'Asset must live inside the deployment');
    const hash = createHash('sha256')
        .update(await readFile(absolute))
        .digest('hex');
    assert.ok(file.includes(hash), 'Content fingerprint: ' + file);
}
console.log(
    `PASS: standalone WebUI contains ${files.size} verified local images and ${symbols.length} native UI symbols; no client paths or external image references`
);
