import { api } from '../api.js';
import {
    el,
    input,
    select,
    label,
    toolbar,
    button,
    heat,
    text,
    details,
    facts,
    badge,
    report
} from '../ui.js';
import { openDialog, edit, field } from './dialog.js';
import { itemLine, currency } from './items.js';
import { itemPicker } from './item-picker.js';

export function modifierControls(def, item) {
    const node = el('div', null, 'stack'),
        controls = el('div', null, 'form-grid');
    const level = input('Level', (item?.level ?? 0) + 1, 'number', {
        min: 1,
        max: def.levels,
        step: 1,
        required: true
    });
    const heatInput = input('Heat fraction', item?.heatProgress ?? 0, 'number', {
        min: 0,
        max: 1,
        step: 'any',
        required: true
    });
    const slider = input('Heat', heatInput.value, 'range', { min: 0, max: 1, step: 0.001 });
    const bar = heat(+heatInput.value),
        choices = el('div', null, 'choice-list'),
        search = input('Search Unique Variants');
    search.placeholder = 'Filter Unique Variants';
    const selected = new Set((item?.variants || []).map((v) => v.name)),
        current = el('p', null, 'muted'),
        preview = el('div', null, 'draft-preview'),
        valid = el('p', null, 'muted');
    if (def.progression)
        controls.append(
            label('Level', level),
            label('Heat fraction (0–1)', heatInput),
            label('Heat', slider)
        );
    const checkboxes = [];
    for (const path of [...new Set([...def.variants, ...selected])]) {
        const check = input(path, selected.has(path), 'checkbox');
        check.dataset.variant = path;
        if (!def.variants.includes(path)) check.disabled = true;
        const row = label(path.replace(/^Variant\//, '').replaceAll('/', ' › '), check);
        row.title = path;
        choices.append(row);
        checkboxes.push(check);
        check.onchange = () => {
            if (check.checked) selected.add(path);
            else selected.delete(path);
            update();
        };
    }
    search.oninput = () => {
        for (const row of choices.children)
            row.hidden = !row.textContent.toLowerCase().includes(search.value.toLowerCase());
    };
    if (def.variants.length) {
        const uv = el('section');
        uv.append(
            el('h3', 'Unique Variants'),
            search,
            el(
                'small',
                'Up to three distinct variants; compatible families are checked by the server.'
            ),
            choices
        );
        node.append(uv);
    }
    node.prepend(controls);
    node.append(current, preview, valid);
    let generation = 0,
        timer,
        disposed = false;
    const values = () => ({
        level: def.progression ? Number(level.value) - 1 : 0,
        heat: def.progression ? Number(heatInput.value) : 0,
        variants: [...selected].join('\n')
    });
    function update() {
        const max = +level.value >= def.levels;
        if (max) {
            heatInput.value = 0;
            slider.value = 0;
        }
        heatInput.disabled = slider.disabled = max;
        bar.value = +heatInput.value;
        text(
            current,
            item
                ? `Current: Level ${item.level + 1} · ${(item.heatProgress * 100).toFixed(1)}% Heat · ${item.variants.length} UVs`
                : 'Grant preview'
        );
        preview.replaceChildren(
            itemLine(def),
            ...(def.progression
                ? [
                      el(
                          'p',
                          `Level ${level.value} · ${(Number(heatInput.value) * 100).toFixed(1)}% Heat`
                      ),
                      bar
                  ]
                : []),
            ...[...selected].map((v) => badge(v.replace(/^Variant\//, ''), 'uv'))
        );
        checkboxes.forEach((c) => {
            c.disabled =
                !def.variants.includes(c.dataset.variant) || (selected.size >= 3 && !c.checked);
        });
        const id = ++generation;
        clearTimeout(timer);
        text(valid, 'Checking draft…');
        timer = setTimeout(async () => {
            try {
                const result = await api.request('item-preview', {
                    config: def.config,
                    ...values()
                });
                if (!disposed && id === generation) {
                    text(valid, 'Draft is valid.');
                    valid.className = 'muted';
                    const stats = Object.entries(result.stats || {});
                    if (stats.length)
                        preview.append(
                            facts(
                                stats.map(([name, value]) => [
                                    name.replaceAll('-', ' ').replaceAll('.', ' · '),
                                    value
                                ])
                            )
                        );
                }
            } catch (error) {
                if (!disposed && id === generation) {
                    text(valid, 'Draft is not valid. Check level, Heat and UV compatibility.');
                    valid.className = 'error';
                }
            }
        }, 250);
    }
    level.oninput = heatInput.oninput = () => {
        slider.value = heatInput.value;
        update();
    };
    slider.oninput = () => {
        heatInput.value = slider.value;
        update();
    };
    update();
    return {
        node,
        values,
        validate: () => api.request('item-preview', { config: def.config, ...values() }),
        dispose() {
            disposed = true;
            generation++;
            clearTimeout(timer);
        }
    };
}
export async function inspectItem(def, item, fixed, writable = false) {
    const node = el('div', null, 'stack');
    node.append(itemLine(def, item));
    node.append(
        facts([
            ['Category', def?.category],
            ['Level', def?.progression ? item.level + 1 : 'Not applicable'],
            [
                'Heat',
                def?.progression ? `${(item.heatProgress * 100).toFixed(1)}%` : 'Not applicable'
            ],
            ['Quantity', item.count],
            [
                'Equipment',
                item.equippedSlot == null
                    ? 'Owned, unequipped'
                    : `Equipped slot ${item.equippedSlot}`
            ],
            ['Binding', item.bound ? 'Bound' : item.bindOnEquip ? 'Binds on equip' : 'Unbound'],
            [
                'Expiration',
                item.expiresAt ? new Date(item.expiresAt).toLocaleString() : 'Permanent'
            ],
            [
                'Accessories',
                [item.accessory, ...item.additionalAccessories].filter(Boolean).join(', ') || 'None'
            ]
        ])
    );
    let modifiers;
    if (writable && def && (def.progression || def.variants.length)) {
        modifiers = modifierControls(def, item);
        node.append(modifiers.node);
    } else for (const v of item.variants) node.append(badge(v.name, 'uv'));
    if (writable) {
        const accessory = button('Edit accessories', async () => {
            const options = await api.request('accessories', { config: item.config });
            edit(
                'Accessories · ' + def.name,
                'item-accessory',
                [
                    field('accessory', 'Accessory', '', 'text', [
                        ['', 'Remove all attached accessories'],
                        ...options.map((a) => [a.config, `${a.name} · ${a.location}`])
                    ])
                ],
                { ...fixed, itemId: item.id },
                'Adding an accessory preserves existing accessories. The server checks occupied locations and blocked combinations.'
            );
        });
        accessory.disabled = !['HELM', 'ARMOR', 'HELM_COSTUME', 'ARMOR_COSTUME'].includes(
            item.slot
        );
        node.append(
            toolbar(
                accessory,
                button('Quantity / remove', () =>
                    edit(
                        'Quantity · ' + def.name,
                        'item-quantity',
                        [
                            field('quantity', 'Quantity (0 removes)', item.count, 'number', null, {
                                min: 0,
                                max: 9999,
                                step: 1
                            })
                        ],
                        { ...fixed, itemId: item.id },
                        'Equipped, starter and permanent items retain their existing protection rules.'
                    )
                )
            )
        );
    }
    node.append(
        details('Advanced item data', item),
        details('Definition and supported properties', def)
    );
    openDialog({
        title: def?.name || 'Item details',
        content: node,
        target: fixed.worldId,
        revision: fixed.revision,
        submit: modifiers ? 'Save gear' : 'Close',
        notifySaved: !!modifiers,
        dispose: () => modifiers?.dispose(),
        onSubmit: async () => {
            if (modifiers) {
                await modifiers.validate();
                await api.change('item-modifiers', {
                    ...fixed,
                    itemId: item.id,
                    ...modifiers.values()
                });
                modifiers.dispose();
            }
        }
    });
}
export function grantItem(fixed) {
    const node = el('div', null, 'stack'),
        preview = el('div', null, 'stack');
    let selected, modifiers;
    const quantity = input('Quantity', 1, 'number', { min: 1, max: 20, step: 1, required: true });
    const bound = input('Bind item', false, 'checkbox'),
        expiry = input('Expiration in days', 0, 'number', {
            min: 0,
            step: 1,
            max: 36500,
            required: true
        });
    const picker = itemPicker(
        (def) => {
            selected = def;
            modifiers?.dispose();
            modifiers = modifierControls(def);
            preview.replaceChildren(itemLine(def), modifiers.node);
            preview.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        },
        { grantable: true }
    );
    node.append(
        picker.node,
        toolbar(
            label('Quantity', quantity),
            label('Expiration days (0 permanent)', expiry),
            label('Bind item', bound)
        ),
        preview
    );
    openDialog({
        title: 'Grant item',
        content: node,
        submit: 'Confirm grant',
        target: fixed.worldId,
        revision: fixed.revision,
        dispose: () => {
            picker.dispose();
            modifiers?.dispose();
        },
        onSubmit: async () => {
            if (!selected) throw new Error('Select an item first.');
            await modifiers.validate();
            await api.change('item-grant', {
                ...fixed,
                config: selected.config,
                quantity: quantity.value,
                bound: bound.checked,
                expireDays: expiry.value,
                ...modifiers.values()
            });
            picker.dispose();
            modifiers.dispose();
        }
    });
}
/** Shared visual selection for offers and auctions. */
export function itemForm(title, route, fields, fixed = {}, selectedConfig = '') {
    const node = el('div', null, 'stack'),
        selection = el('div'),
        form = el('div', null, 'form-grid'),
        controls = new Map();
    let selected;
    const picker = itemPicker(
        (def) => {
            selected = def;
            selection.replaceChildren(itemLine(def));
        },
        { grantable: true }
    );
    if (selectedConfig)
        api.request('item', { config: selectedConfig })
            .then((def) => {
                if (!selected) {
                    selected = def;
                    selection.replaceChildren(itemLine(def));
                }
            })
            .catch(report);
    for (const f of fields) {
        const c = f.options
            ? select(f.title, f.options, f.value)
            : input(f.title, f.value, f.type, f.constraints);
        c.required =
            f.constraints.required ?? (f.type !== 'checkbox' && f.key !== 'referencePrice');
        controls.set(f.key, c);
        form.append(label(f.title, c));
    }
    node.append(selection, picker.node, form);
    openDialog({
        title,
        content: node,
        target: route,
        revision: fixed.revision,
        submit: 'Confirm changes',
        dispose: () => picker.dispose(),
        onSubmit: async () => {
            if (!selected) throw new Error('Select an item first.');
            const values = { ...fixed, config: selected.config };
            for (const f of fields) {
                const c = controls.get(f.key);
                values[f.key] =
                    f.type === 'checkbox'
                        ? c.checked
                        : f.type === 'datetime-local'
                          ? new Date(c.value).getTime()
                          : c.value;
            }
            await api.change(route, values);
            picker.dispose();
        }
    });
}
