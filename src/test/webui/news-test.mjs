import test from 'node:test';
import assert from 'node:assert/strict';
import { newsDocument } from '../../webui/assets/pages/news.js';

test('visual News saves omit the empty action key rejected by the native schema', () => {
    const banner = { image: 'featured.png', tooltip: '', url: '', action: '', argument: '' };
    const draft = {
        enabled: true,
        packs: [
            {
                featured: banner,
                spotlights: { left: null, right: { ...banner, image: 'small.png' } }
            }
        ]
    };
    const before = structuredClone(draft);
    assert.deepEqual(newsDocument(draft), {
        enabled: true,
        packs: [
            {
                featured: { image: 'featured.png' },
                spotlights: { left: null, right: { image: 'small.png' } }
            }
        ]
    });
    assert.deepEqual(draft, before, 'saving must not rewrite the open draft');
});

test('explicit Depot actions, tooltips and URLs survive visual serialization', () => {
    const featured = {
        image: 'featured.png',
        tooltip: 'Open the Depot',
        url: 'https://example.test/',
        action: 'depot',
        argument: ''
    };
    const result = newsDocument({
        enabled: false,
        packs: [{ featured, spotlights: { left: null, right: null } }]
    });
    assert.deepEqual(result.packs[0].featured, {
        image: 'featured.png',
        tooltip: 'Open the Depot',
        url: 'https://example.test/',
        action: 'depot'
    });
    assert.equal(result.enabled, false);
});
