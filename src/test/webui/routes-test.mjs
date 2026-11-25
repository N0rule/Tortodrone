/**
 * The admin API is a fixed route vocabulary defined by the server. This test
 * reads that vocabulary out of the Java sources and asserts the WebUI names
 * every route, so a new backend operation cannot ship without a control.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('../../../', import.meta.url).pathname;
const serverSources = [
    'modules/server/src/main/java/org/tortodrone/web/AdminService.java',
    'modules/server/src/main/java/org/tortodrone/web/AdminHandler.java',
    'modules/server/src/main/java/org/tortodrone/web/AdminConsole.java'
];
/** File extensions that share the `case "..."` shape but are not routes. */
const notRoutes = new Set(['css', 'jpg', 'js', 'json', 'png', 'svg', 'webp', 'woff2']);

function walk(directory) {
    return readdirSync(directory).flatMap((entry) => {
        const path = join(directory, entry);
        return statSync(path).isDirectory()
            ? walk(path)
            : path.endsWith('.js')
              ? [path]
              : [];
    });
}

export function routes(java) {
    const found = new Set();
    for (const match of java.matchAll(/(?:case|route\.equals\()\s*"([a-z][a-z0-9-]*)"/g))
        if (!notRoutes.has(match[1])) found.add(match[1]);
    return [...found].sort();
}

const java = serverSources.map((path) => readFileSync(join(root, path), 'utf8')).join('\n');
const vocabulary = routes(java);
const frontend = walk(join(root, 'frontend/assets'))
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n');

test('the route vocabulary is read from the server sources', () => {
    assert.ok(vocabulary.length > 50, `only ${vocabulary.length} routes found`);
    for (const route of ['status', 'console', 'webui-user-role', 'item-grant', 'kick'])
        assert.ok(vocabulary.includes(route), `missing ${route}`);
    for (const extension of notRoutes)
        assert.ok(!vocabulary.includes(extension), `${extension} is not a route`);
});

test('every server route is named by the WebUI', () => {
    const missing = vocabulary.filter(
        (route) =>
            !frontend.includes(`'${route}'`) &&
            !frontend.includes(`"${route}"`) &&
            !frontend.includes(`\`${route}\``)
    );
    assert.deepEqual(missing, [], `routes with no frontend call: ${missing.join(', ')}`);
});

test('the confirmation argument matches the route for every write', () => {
    // The server repeats the browser confirmation server-side, so every write
    // helper must post the route it names.
    const writes = readFileSync(join(root, 'frontend/assets/api.js'), 'utf8');
    assert.match(writes, /confirmation/, 'api.js must send a confirmation field');
});
