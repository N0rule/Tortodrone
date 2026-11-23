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
        advanced = details('Advanced Sprite data', {}),
        perks = el('details'),
        perkList = el('ul', null, 'perk-list'),
        skills = el('div', null, 'sprite-skills');
    node.classList.add('sprite-card', 'stack');
    const overview = el('div', null, 'sprite-overview'),
        identity = el('div', null, 'stack');
    identity.append(name, toolbar(ownership, deployment), bar, values);
    overview.append(image, identity);
    perks.append(el('summary', 'All owned perks'), perkList);
    node.append(overview, skills, perks, live, advanced);
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
                ['Type', s.display?.name || p.config.split('/').at(-1)],
                ['Level / tier', `${s.displayedLevel} / ${s.tier}`],
                ['Heat', `${(p.heat * 100).toFixed(1)}%`],
                ['Skill points spent / available', `${s.spentPoints} / ${s.availablePoints}`],
                ['Harness', s.harness?.config],
                [
                    'Selected perk',
                    p.perk == null ? 'None' : s.perkNames?.[s.perkCatalogue[p.perk]?.name]
                ],
                [
                    'Owned perks',
                    p.perks
                        .slice(0, 3)
                        .map((i) => s.perkNames?.[s.perkCatalogue[i]?.name] || `Unknown perk ${i}`)
                        .join(', ') +
                        (p.perks.length > 3 ? ` and ${p.perks.length - 3} more` : '') || 'None'
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
            const perkSignature = JSON.stringify(p.perks);
            if (perkList.dataset.signature !== perkSignature) {
                perkList.replaceChildren(
                    ...p.perks.map((i) =>
                        el('li', s.perkNames?.[s.perkCatalogue[i]?.name] || 'Unknown perk')
                    )
                );
                perkList.dataset.signature = perkSignature;
            }
            perks.hidden = !p.perks.length;
            const definitions = s.definition?.skills || [];
            for (let i = 0; i < Math.max(3, definitions.length); i++) {
                if (!skillCards[i]) {
                    const root = el('dl'),
                        card = panel(`Skill ${i + 1}`, root);
                    skillCards[i] = { root, facts: new Map(), card };
                    skills.append(card);
                }
                const card = skillCards[i],
                    definition = definitions[i],
                    display = s.display?.skills?.[i];
                const title = card.card.querySelector('h2');
                if (title.dataset.config !== p.config) {
                    title.replaceChildren(
                        spriteIcon(p.config, `skill:${i}`),
                        el('span', display?.name || `Skill ${i + 1}`)
                    );
                    title.dataset.config = p.config;
                }
                set(card.root, card.facts, 'State', s.unlocked[i] ? 'Unlocked' : 'Locked');
                set(
                    card.root,
                    card.facts,
                    'Upgrade ranks',
                    p.powers
                        .slice(i * 3, i * 3 + 3)
                        .map(
                            (rank, j) =>
                                `${display?.upgrades?.[j] || 'Upgrade ' + (j + 1)}: ${rank}`
                        )
                        .join(' • ')
                );
                set(card.root, card.facts, 'Ultimate', s.ultimate[i] ? 'Unlocked' : 'Locked');
                set(
                    card.root,
                    card.facts,
                    'Selected ultimate',
                    p.ultimates[i]
                        ? display?.ultimates?.[p.ultimates[i] - 1] || 'Unavailable'
                        : 'None'
                );
                set(
                    card.root,
                    card.facts,
                    'Ultimate choices',
                    display?.ultimates?.join(' / ') || 'Unavailable'
                );
            }
            text(advanced.querySelector('pre'), JSON.stringify(s, null, 2));
        }
    };
}
