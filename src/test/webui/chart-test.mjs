import test from 'node:test';
import assert from 'node:assert/strict';
import { nearestSample } from '../../webui/assets/components/chart.js';

test('chart inspection uses elapsed time across uneven sampling and gaps', () => {
    const samples = [0, 5, 10, 90, 100].map((time) => ({ time }));
    assert.equal(nearestSample(samples, 55), 3);
    assert.equal(nearestSample(samples, 11), 2);
    assert.equal(nearestSample(samples, -20), 0);
    assert.equal(nearestSample(samples, 120), 4);
    assert.equal(nearestSample(samples, 50), 2);
    assert.equal(nearestSample([], 0), -1);
    assert.equal(nearestSample([{ time: 12 }], 100), 0);
});
