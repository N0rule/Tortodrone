import { symbol } from './symbols.js';
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
import { itemLine, icon, variantName } from './items.js';
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
        choices = el('div', null, 'variant-picker'),
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
    controls.hidden = true;
    const checkboxes = [];
    for (const path of [...new Set([...def.variants, ...selected])]) {
        const check = button(def.variantDisplay?.[path]?.label || variantName(path), () => {
            check.checked = !check.checked;
            check.onchange();
        });
        check.checked = selected.has(path);
        check.className = 'variant-option';
        check.prepend(symbol('variants'));
        check.dataset.variant = path;
        const row = el('div', null, 'variant-choice');
        row.append(check);
        row.title = path;
        row.dataset.category = def.variantDisplay?.[path]?.category || 'Other';
        choices.append(row);
        checkboxes.push(check);
        check.onchange = () => {
            if (check.checked) selected.add(path);
            else selected.delete(path);
            check.setAttribute('aria-pressed', check.checked);
            update();
        };
    }
    search.oninput = () => {
        for (const row of choices.children)
            row.hidden = !row.textContent.toLowerCase().includes(search.value.toLowerCase());
    };
    if (checkboxes.length) {
        const uv = el('details', null, 'uv-picker');
        const category = select('Effect category', [
            ['', 'All effects'],
            ...[...new Set(Object.values(def.variantDisplay || {}).map((v) => v.category))].map(
                (c) => [c, c.replace(/([a-z])([A-Z])/g, '$1 $2')]
            )
        ]);
        const filter = () => {
            for (const row of choices.children)
                row.hidden =
                    !row.textContent.toLowerCase().includes(search.value.toLowerCase()) ||
                    (!!category.value && row.dataset.category !== category.value);
        };
        category.onchange = search.oninput = filter;
        uv.append(
            el('summary', 'Add or change Unique Variants'),
            toolbar(search, category),
            el(
                'small',
                'Up to three distinct variants; compatible families are checked by the server.'
            ),
            choices
        );
        node.append(uv);
    }
    const card = el('section', null, 'gear-card stack');
    card.append(preview, controls);
    node.prepend(card);
    node.append(current, valid);
    let generation = 0,
        timer,
        disposed = false,
        request;
    const values = () => ({
        level: def.progression ? Number(level.value) - 1 : 0,
        heat: def.progression ? Number(heatInput.value) : 0,
        variants: [...selected].join('\n')
    });
    const initial = JSON.stringify(values());
    const baseline = el('details', null, 'draft-preview');
    if (item) {
        baseline.append(el('summary', 'Compare with saved item'));
        node.append(baseline);
    }
    let currentStats;
    const original = item
        ? {
              level: item.level,
              heat: item.heatProgress,
              variants: item.variants.map((v) => v.name).join('\n')
          }
        : values();
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
                      toolbar(
                          button(`Level ${level.value}`, () => {
                              controls.hidden = !controls.hidden;
                              if (!controls.hidden) level.focus();
                          }),
                          button(`${(Number(heatInput.value) * 100).toFixed(1)}% Heat`, () => {
                              controls.hidden = !controls.hidden;
                              if (!controls.hidden) heatInput.focus();
                          })
                      ),
                      bar
                  ]
                : []),
            ...[...selected].map((v) => {
                const remove = button(
                    def.variantDisplay?.[v]?.label || variantName(v),
                    () => {
                        selected.delete(v);
                        const c = checkboxes.find((c) => c.dataset.variant === v);
                        if (c) c.checked = false;
                        update();
                    },
                    'uv effect-chip'
                );
                remove.title = 'Remove this Unique Variant';
                remove.prepend(symbol('variants'));
                return remove;
            })
        );
        checkboxes.forEach((c) => {
            c.disabled = !c.checked;
        });
        const id = ++generation;
        clearTimeout(timer);
        request?.abort();
        request = new AbortController();
        const signal = request.signal;
        text(valid, 'Checking draft…');
        timer = setTimeout(async () => {
            try {
                const [result, before] = await Promise.all([
                    api.request('item-preview', { config: def.config, ...values() }, false, signal),
                    currentStats ||
                        api
                            .request(
                                'item-preview',
                                { config: def.config, ...original },
                                false,
                                signal
                            )
                            .catch(() => null)
                ]);
                if (!disposed && id === generation) {
                    text(valid, 'Draft is valid.');
                    valid.className = 'muted';
                    currentStats = before;
                    if (item)
                        baseline.replaceChildren(
                            el('summary', 'Compare with saved item'),
                            statFacts(before?.properties),
                            effectsView(before?.effects)
                        );
                    const compatible = new Set(result.compatibleVariants);
                    checkboxes.forEach((c) => {
                        c.disabled = !c.checked && !compatible.has(c.dataset.variant);
                        c.setAttribute('aria-pressed', c.checked);
                        c.parentElement.title = c.disabled
                            ? 'Incompatible with the selected family or three-UV limit'
                            : c.dataset.variant;
                    });
                    preview.append(
                        statFacts(result.properties),
                        effectsView(result.effects?.filter((e) => e.source !== 'Unique Variant'))
                    );
                }
            } catch (error) {
                if (!disposed && id === generation && error.name !== 'AbortError') {
                    checkboxes.forEach((c) => {
                        c.disabled =
                            !c.checked &&
                            (!def.variants.includes(c.dataset.variant) || selected.size >= 3);
                    });
                    text(valid, error.message || 'Check level, Heat and UV compatibility.');
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
        isDirty: () => initial !== JSON.stringify(values()),
        validate: () => api.request('item-preview', { config: def.config, ...values() }),
        dispose() {
            disposed = true;
            generation++;
            request?.abort();
            clearTimeout(timer);
        }
    };
}
function effectsView(effects = []) {
    const list = el('ul', null, 'item-effects');
    for (const e of effects) {
        const row = el('li');
        row.append(el('strong', e.label), el('small', e.source));
        list.append(row);
    }
    return list;
}
function statFacts(properties = []) {
    const root = el('div', null, 'item-properties');
    for (const property of properties) {
        const row = el('div', null, 'item-property');
        row.append(el('span', property.label), el('strong', Number(property.value.toFixed(2))));
        if (property.meter != null) {
            const bar = heat(property.meter);
            bar.setAttribute('aria-label', property.label + ' rating');
            bar.title = property.note;
            row.append(bar);
        }
        if (property.note) row.title = property.note;
        root.append(row);
    }
    return root;
}

export async function inspectItem(def, item, fixed, writable = false) {
    const node = el('div', null, 'stack gear-inspector');
    if (!writable) node.append(itemLine(def, item));
    const attached = [item.accessory, ...item.additionalAccessories].filter(Boolean),
        accessoryView = el('div', null, 'item-grid'),
        inspection = new AbortController();
    let disposed = false;
    const metadata = el('details');
    metadata.append(
        el('summary', 'Binding, ownership and accessories'),
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
            ['Cursed', item.cursed ? 'Yes' : 'No'],
            [
                'Protection',
                [
                    item.starter ? 'Starter item' : '',
                    item.permanent ? 'Permanent item' : '',
                    def?.locked ? 'Locked item' : ''
                ]
                    .filter(Boolean)
                    .join(', ') || 'Standard item'
            ],
            [
                'Expiration',
                item.expiresAt ? new Date(item.expiresAt).toLocaleString() : 'Permanent'
            ],
            ['Accessories', attached.length ? `${attached.length} attached` : 'None']
        ])
    );
    node.append(
        toolbar(
            badge(item.bound ? 'Bound' : item.bindOnEquip ? 'Binds on equip' : 'Unbound'),
            badge(item.equippedSlot == null ? 'Unequipped' : 'Equipped')
        ),
        metadata
    );
    if (attached.length) {
        node.append(accessoryView);
        const render = (options) => {
            accessoryView.replaceChildren(
                ...attached.map((config) => {
                    const definition = options.find((a) => a.config === config),
                        tile = el('div', null, 'draft-preview');
                    tile.append(
                        icon(definition?.iconConfig),
                        el('strong', definition?.name || config.split('/').at(-1))
                    );
                    if (definition)
                        tile.append(
                            el('small', definition.location.replaceAll('_', ' ').toLowerCase())
                        );
                    return tile;
                })
            );
        };
        render([]);
        api.request('accessories', { config: item.config }, false, inspection.signal)
            .then((options) => {
                if (!disposed) render(options);
            })
            .catch(() => {});
    }
    let modifiers;
    const itemName = def?.name || item.config.split('/').at(-1);
    if (writable && def && (def.progression || def.variants.length)) {
        modifiers = modifierControls(def, item);
        node.append(modifiers.node);
    } else {
        for (const v of item.variants) node.append(badge(variantName(v.name), 'uv'));
        if (def) {
            const stats = el('div');
            node.append(stats);
            api.request(
                'item-preview',
                {
                    config: item.config,
                    level: item.level,
                    heat: item.heatProgress,
                    variants: item.variants.map((v) => v.name).join('\n')
                },
                false,
                inspection.signal
            )
                .then((result) => {
                    if (!disposed)
                        stats.replaceChildren(
                            statFacts(result.properties),
                            effectsView(result.effects)
                        );
                })
                .catch(() => {});
        }
    }
    if (writable) {
        const accessory = button('Edit accessories', async () => {
            const options = await api.request('accessories', { config: item.config });
            const content = el('div', null, 'stack'),
                grid = el('div', null, 'item-grid'),
                filter = input('Search accessories'),
                selection = el('p', 'Choose an accessory to attach.');
            let selected = null;
            for (const option of options) {
                const tile = button(
                    '',
                    () => {
                        selected = option.config;
                        for (const child of grid.children)
                            child.setAttribute('aria-pressed', child === tile);
                        text(
                            selection,
                            `Attach ${option.name} at ${option.location.replaceAll('_', ' ').toLowerCase()}.`
                        );
                    },
                    'item-tile'
                );
                tile.append(
                    icon(option.iconConfig),
                    el('strong', option.name),
                    el('small', option.location.replaceAll('_', ' ').toLowerCase())
                );
                tile.dataset.search = option.name.toLowerCase();
                tile.setAttribute('aria-pressed', 'false');
                grid.append(tile);
            }
            const remove = button('Remove all attached accessories', () => {
                selected = '';
                for (const child of grid.children) child.setAttribute('aria-pressed', 'false');
                text(selection, 'Remove all attached accessories.');
            });
            remove.disabled = !item.accessory && !item.additionalAccessories.length;
            filter.oninput = () => {
                for (const child of grid.children)
                    child.hidden = !(child.dataset.search || '').includes(
                        filter.value.toLowerCase()
                    );
            };
            content.append(
                el(
                    'p',
                    'Existing accessories are preserved when attaching. Occupied locations and blocked combinations are checked on save.'
                ),
                filter,
                grid,
                remove,
                selection
            );
            if (!options.length)
                grid.append(el('p', 'No compatible accessories in the current catalogue.'));
            openDialog({
                title: 'Accessories · ' + itemName,
                content,
                target: fixed.worldId,
                revision: fixed.revision,
                isDirty: () => selected !== null,
                onSubmit: async () => {
                    if (selected === null)
                        throw new Error('Select an accessory or choose removal.');
                    await api.change('item-accessory', {
                        ...fixed,
                        itemId: item.id,
                        accessory: selected
                    });
                }
            });
        });
        accessory.disabled = !['HELM', 'ARMOR', 'HELM_COSTUME', 'ARMOR_COSTUME'].includes(
            item.slot
        );
        node.append(
            toolbar(
                accessory,
                button('Quantity / remove', () =>
                    edit(
                        'Quantity · ' + itemName,
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
        dispose: () => {
            disposed = true;
            inspection.abort();
            modifiers?.dispose();
        },
        isDirty: () => modifiers?.isDirty() || false,
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
            max: 3650,
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
        isDirty: () =>
            !!selected || quantity.value !== '1' || bound.checked || expiry.value !== '0',
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
    let selected,
        disposed = false;
    const initial = new Map();
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
                if (!disposed && !selected) {
                    selected = def;
                    selection.replaceChildren(itemLine(def));
                }
            })
            .catch((error) => {
                if (!disposed) report(error);
            });
    for (const f of fields) {
        const c = f.options
            ? select(f.title, f.options, f.value)
            : input(f.title, f.value, f.type, f.constraints);
        c.required =
            f.constraints.required ?? (f.type !== 'checkbox' && f.key !== 'referencePrice');
        controls.set(f.key, c);
        initial.set(f.key, f.type === 'checkbox' ? c.checked : c.value);
        form.append(label(f.title, c));
    }
    node.append(selection, picker.node, form);
    openDialog({
        title,
        content: node,
        target: route,
        revision: fixed.revision,
        submit: 'Confirm changes',
        isDirty: () =>
            (selected?.config || selectedConfig) !== selectedConfig ||
            fields.some(
                (f) =>
                    initial.get(f.key) !==
                    (f.type === 'checkbox'
                        ? controls.get(f.key).checked
                        : controls.get(f.key).value)
            ),
        dispose: () => {
            disposed = true;
            picker.dispose();
        },
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
