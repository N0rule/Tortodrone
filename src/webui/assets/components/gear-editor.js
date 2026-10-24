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
import { itemLine, icon, variantName, variantEffect, gearProgress } from './items.js';
import { itemPicker } from './item-picker.js';

/** Rows are keyed by authoritative labels; pending requests never clear valid stats. */
export function statPanel() {
    const node = el('div', null, 'item-properties'),
        rows = new Map();
    return {
        node,
        update(properties = []) {
            const wanted = new Set();
            for (const property of properties) {
                const key = property.label;
                wanted.add(key);
                let row = rows.get(key);
                if (!row) {
                    row = el('div', null, 'item-property');
                    row.append(el('span', key), el('strong'));
                    rows.set(key, row);
                }
                text(row.children[1], Number(property.value.toFixed(2)));
                row.title = property.note || '';
                let bar = row.querySelector('progress');
                if (property.meter != null) {
                    if (!bar) {
                        bar = heat(0);
                        row.append(bar);
                    }
                    bar.value = property.meter;
                    bar.setAttribute('aria-label', key + ' rating');
                } else bar?.remove();
                if (node.children[Array.from(wanted).length - 1] !== row)
                    node.insertBefore(row, node.children[Array.from(wanted).length - 1] || null);
            }
            for (const [key, row] of rows)
                if (!wanted.has(key)) {
                    row.remove();
                    rows.delete(key);
                }
        }
    };
}
export function modifierControls(def, item) {
    const node = el('div', null, 'stack gear-editor'),
        card = el('section', null, 'gear-card stack'),
        controls = el('div', null, 'gear-progress-controls'),
        stats = statPanel(),
        effects = el('div'),
        selectedView = el('div', null, 'variant-effects'),
        valid = el('p', 'Loading item properties…', 'draft-status muted');
    effects.hidden = true;
    card.append(itemLine(def), controls, stats.node, effects, selectedView, valid);
    node.append(card);
    const level = input('Level', (item?.level ?? 0) + 1, 'number', {
        min: 1,
        max: def.levels,
        step: 1,
        required: true
    });
    const levelSlider = input('Level slider', level.value, 'range', {
        min: 1,
        max: def.levels,
        step: 1
    });
    const heatInput = input('Heat percentage', (item?.heatProgress ?? 0) * 100, 'number', {
        min: 0,
        max: 100,
        step: 'any',
        required: true
    });
    const slider = input('Heat', heatInput.value, 'range', { min: 0, max: 100, step: 0.1 });
    const heatTitle = el('span', 'Heat'),
        heatValue = el('output');
    for (const [title, precise, range] of [
        ['Level', level, levelSlider],
        ['Heat', heatInput, slider]
    ]) {
        const group = el('div', null, 'gear-control'),
            heading = el('div', null, 'gear-control-heading');
        heading.append(title === 'Heat' ? heatTitle : el('span', title), precise);
        if (title === 'Heat') heading.append(el('span', '%'));
        group.append(heading, range);
        if (title === 'Heat') group.append(heatValue);
        controls.append(group);
    }
    controls.hidden = !def.progression;
    const selected = new Set((item?.variants || []).map((v) => v.name)),
        choices = el('div', null, 'variant-picker'),
        search = input('Search Unique Variants'),
        category = select('Effect category', [
            ['', 'All effects'],
            ...[...new Set(Object.values(def.variantDisplay || {}).map((v) => v.category))].map(
                (c) => [c, c.replace(/([a-z])([A-Z])/g, '$1 $2')]
            )
        ]),
        buttons = new Map();
    search.placeholder = 'Search effects';
    for (const path of [...new Set([...def.variants, ...selected])]) {
        const choice = button(
            '',
            () => {
                if (selected.has(path)) selected.delete(path);
                else selected.add(path);
                update();
            },
            'variant-option'
        );
        choice.append(variantEffect(path, def));
        choice.dataset.variant = path;
        choice.dataset.category = def.variantDisplay?.[path]?.category || 'Other';
        choice.setAttribute('aria-pressed', selected.has(path));
        choices.append(choice);
        buttons.set(path, choice);
    }
    const filter = () => {
        for (const choice of choices.children)
            choice.hidden =
                !choice.textContent.toLowerCase().includes(search.value.toLowerCase()) ||
                (!!category.value && choice.dataset.category !== category.value);
    };
    search.oninput = category.onchange = filter;
    if (buttons.size) {
        const uv = el('details', null, 'uv-picker');
        uv.append(
            el('summary', 'Unique Variants'),
            toolbar(search, category),
            el(
                'small',
                'Select up to three effects. Availability follows the item’s compatible variant families.'
            ),
            choices
        );
        node.append(uv);
    }
    let generation = 0,
        timer,
        disposed = false,
        request,
        baselineLoaded = false;
    const values = () => ({
        level: def.progression ? Number(level.value) - 1 : 0,
        heat: def.progression ? Number(heatInput.value) / 100 : 0,
        variants: [...selected].join('\n')
    });
    const initial = JSON.stringify(values()),
        original = values(),
        baseline = el('details');
    if (item) {
        baseline.append(el('summary', 'Compare with saved item'));
        node.append(baseline);
    }
    function update() {
        const max = +level.value >= def.levels;
        if (max) heatInput.value = slider.value = 0;
        heatInput.disabled = slider.disabled = max;
        text(
            heatValue,
            max
                ? 'Maximum level'
                : `${Number(heatInput.value).toLocaleString(undefined, { maximumFractionDigits: 1 })}% to next level`
        );
        slider.style.setProperty('--fill', heatInput.value + '%');
        levelSlider.value = level.value;
        levelSlider.style.setProperty(
            '--fill',
            `${(100 * (Number(level.value) - 1)) / Math.max(1, def.levels - 1)}%`
        );
        // Reconcile selected rows without touching controls or the stats subtree.
        for (const row of [...selectedView.children])
            if (!selected.has(row.dataset.variant)) row.remove();
        for (const path of selected)
            if (![...selectedView.children].some((r) => r.dataset.variant === path)) {
                const row = variantEffect(path, def),
                    remove = button(
                        '×',
                        () => {
                            selected.delete(path);
                            update();
                        },
                        'variant-remove'
                    );
                row.dataset.variant = path;
                remove.setAttribute(
                    'aria-label',
                    'Remove ' + (def.variantDisplay?.[path]?.label || variantName(path))
                );
                row.append(remove);
                selectedView.append(row);
            }
        buttons.forEach((choice, path) => {
            choice.setAttribute('aria-pressed', selected.has(path));
            choice.disabled = !selected.has(path);
        });
        const id = ++generation;
        clearTimeout(timer);
        request?.abort();
        request = new AbortController();
        const signal = request.signal;
        card.dataset.pending = 'true';
        text(valid, 'Checking draft…');
        valid.className = 'draft-status muted';
        timer = setTimeout(async () => {
            try {
                const result = await api.request(
                    'item-preview',
                    { config: def.config, ...values() },
                    false,
                    signal
                );
                if (disposed || id !== generation) return;
                stats.update(result.properties);
                const bonuses = result.effects?.filter((e) => e.source !== 'Unique Variant') || [];
                effects.hidden = !bonuses.length;
                effects.replaceChildren(effectsView(bonuses));
                const compatible = new Set(result.compatibleVariants);
                buttons.forEach((choice, path) => {
                    choice.disabled = !selected.has(path) && !compatible.has(path);
                    choice.title = choice.disabled
                        ? 'Incompatible variant family or maximum of three effects reached.'
                        : '';
                });
                card.dataset.pending = 'false';
                text(valid, 'Draft is valid.');
                if (item && !baselineLoaded) {
                    const before = await api.request(
                        'item-preview',
                        { config: def.config, ...original },
                        false,
                        signal
                    );
                    if (disposed || id !== generation) return;
                    baseline.append(statFacts(before.properties), effectsView(before.effects));
                    baselineLoaded = true;
                }
            } catch (error) {
                if (disposed || id !== generation || error.name === 'AbortError') return;
                buttons.forEach((choice, path) => {
                    choice.disabled =
                        !selected.has(path) && (!def.variants.includes(path) || selected.size >= 3);
                });
                card.dataset.pending = 'false';
                text(valid, error.message || 'Check level, Heat and UV compatibility.');
                valid.className = 'draft-status error';
            }
        }, 250);
    }
    level.oninput = () => {
        levelSlider.value = level.value;
        update();
    };
    levelSlider.oninput = () => {
        level.value = levelSlider.value;
        update();
    };
    heatInput.oninput = () => {
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
        validate: () => {
            if (def.progression && (!level.checkValidity() || !heatInput.checkValidity()))
                throw new Error('Enter a valid Level and Heat percentage.');
            return api.request('item-preview', { config: def.config, ...values() });
        },
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
        if (e.source === 'Unique Variant')
            row.append(variantEffect(e.config, { variantDisplay: { [e.config]: e } }));
        else row.append(el('strong', e.label), el('small', e.source));
        list.append(row);
    }
    return list;
}
function statFacts(properties = []) {
    const panel = statPanel();
    panel.update(properties);
    return panel.node;
}

export async function inspectItem(def, item, fixed, writable = false) {
    const node = el('div', null, 'stack gear-inspector');
    if (!writable) {
        node.append(itemLine(def, item));
        if (def?.progression) node.append(gearProgress(def, item));
    }
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
function grantOptions(...fields) {
    const node = el('div', null, 'grant-options');
    node.append(...fields);
    return node;
}
export function grantItem(fixed) {
    const node = el('div', null, 'stack'),
        preview = el('div', null, 'stack'),
        catalogue = el('details', null, 'grant-catalogue');
    catalogue.open = true;
    catalogue.append(el('summary', 'Choose an item'));
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
            preview.replaceChildren(modifiers.node);
            catalogue.open = false;
            text(catalogue.firstChild, 'Change selected item');
            preview.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        },
        { grantable: true }
    );
    catalogue.append(picker.node);
    node.append(
        preview,
        catalogue,
        grantOptions(
            label('Quantity', quantity),
            label('Expiration days (0 permanent)', expiry),
            label('Bind item', bound)
        )
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
