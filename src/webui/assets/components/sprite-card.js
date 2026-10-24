import { el, panel, toolbar, badge, details, text, heat } from '../ui.js';
import { spriteIcon } from './items.js';

/** Owned progression and scene deployment update independently, without replacing the card. */
export function spriteCard() {
    const node = panel(''),
        image = el('div'),
        name = el('h2'),
        ownership = badge('Owned'),
        deployment = badge(''),
        values = el('dl'),
        live = el('dl'),
        bar = heat(0),
        advanced = details('Authoritative Sprite data', {}),
        skills = el('div', null, 'stack');
    node.classList.add('sprite-card', 'stack');
    node.append(image, name, toolbar(ownership, deployment), values, bar, live, skills, advanced);
    const facts = new Map(),
        liveFacts = new Map(),
        skillCards = [];
    function set(root, cache, key, value) {
        let target = cache.get(key);
        if (!target) {
            target = el('dd');
            cache.set(key, target);
            root.append(el('dt', key), target);
        }
        text(target, value ?? 'Unavailable');
    }
    let imageKey;
    return {
        node,
        update(s) {
            const p = s.state;
            text(name, p.name || p.config.split('/').at(-1));
            text(ownership, s.selected ? 'Selected' : 'Owned');
            ownership.classList.toggle('gold', s.selected);
            text(
                deployment,
                s.active
                    ? 'Active in scene'
                    : !s.online
                      ? 'Offline'
                      : s.active == null
                        ? 'Scene state unavailable'
                        : 'Not deployed'
            );
            deployment.classList.toggle('good', s.active === true);
            const key = `${p.config}:${s.iconTier}`;
            if (key !== imageKey) {
                image.replaceChildren(spriteIcon(p.config, s.iconTier));
                imageKey = key;
            }
            const entries = [
                ['Type', p.config.split('/').at(-1)],
                ['Level', s.displayedLevel],
                ['Heat', `${(p.heat * 100).toFixed(1)}%`],
                [
                    'Skill points spent / earned',
                    `${p.powers.reduce((a, b) => a + b, 0)} / ${s.skillPoints}`
                ],
                ['Harness', s.harness?.config],
                ['Selected perk', p.perk == null ? 'None' : s.perkCatalogue[p.perk]?.name],
                [
                    'Owned perks',
                    p.perks
                        .map((i) => s.perkCatalogue[i]?.name || `Unknown perk ${i}`)
                        .join(', ') || 'None'
                ]
            ];
            for (const [key, value] of entries) set(values, facts, key, value);
            bar.value = p.heat;
            bar.setAttribute('aria-label', `${(p.heat * 100).toFixed(1)}% Heat`);
            live.hidden = !s.live;
            if (s.live) {
                set(live, liveFacts, 'Following', s.live.following ? 'Yes' : 'No');
                set(live, liveFacts, 'Shield', `${s.live.shieldHealth} / ${s.live.shieldMaximum}`);
                set(
                    live,
                    liveFacts,
                    'Skill recharge',
                    s.live.cooldownSeconds.map((n) => `${n.toFixed(1)}s`).join(' / ')
                );
            }
            const definitions = s.definition?.skills || [];
            for (let i = 0; i < Math.max(3, definitions.length); i++) {
                if (!skillCards[i]) {
                    const root = el('dl'),
                        card = panel(`Skill ${i + 1}`, root);
                    skillCards[i] = { root, facts: new Map(), card };
                    skills.append(card);
                }
                const card = skillCards[i],
                    definition = definitions[i];
                set(card.root, card.facts, 'State', s.unlocked[i] ? 'Unlocked' : 'Locked');
                set(
                    card.root,
                    card.facts,
                    'Upgrade ranks',
                    p.powers.slice(i * 3, i * 3 + 3).join(' / ')
                );
                set(card.root, card.facts, 'Ultimate', s.ultimate[i] ? 'Unlocked' : 'Locked');
                set(card.root, card.facts, 'Selected ultimate', ['None', 'A', 'B'][p.ultimates[i]]);
                set(
                    card.root,
                    card.facts,
                    'Attacks',
                    definition?.attacks.map((a) => a.split('/').at(-1)).join(', ')
                );
            }
            text(advanced.querySelector('pre'), JSON.stringify(s, null, 2));
        }
    };
}
