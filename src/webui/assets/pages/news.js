import { api } from '../api.js';
import { el, input, select, label, toolbar, button, panel, text, notify, report } from '../ui.js';
import { openDialog, conflict as dialogConflict } from '../components/dialog.js';

function schema(catalog) {
    return {
        enabled: catalog.enabled,
        packs: catalog.packs.map((p) => ({
            featured: { ...p.featured },
            spotlights: {
                left: p.left ? { ...p.left } : null,
                right: p.right ? { ...p.right } : null
            }
        }))
    };
}
const emptyBanner = () => ({ image: '', tooltip: '', url: '', action: '', argument: '' });
export function newsDocument(draft) {
    // The native YAML schema treats an action key as an explicit Depot action.
    // Omit empty optional fields rather than publishing an empty action key.
    const banner = (value) =>
        value
            ? Object.fromEntries(
                  Object.entries(value).filter(([key, value]) => key === 'image' || value !== '')
              )
            : null;
    return {
        enabled: draft.enabled,
        packs: draft.packs.map((p) => ({
            featured: banner(p.featured),
            spotlights: { left: banner(p.spotlights.left), right: banner(p.spotlights.right) }
        }))
    };
}
export async function mount(ctx) {
    let data = await ctx.request('news'),
        draft = schema(data.catalog),
        pack = 0,
        slot = 'featured',
        dirty = false,
        revision = data.revision,
        stale = false;
    const node = el('div', null, 'stack'),
        enabled = input('Enable News', draft.enabled, 'checkbox'),
        packs = select('News pack', [], ''),
        dirtyLabel = el('span', null, 'muted'),
        conflict = el('div', null, 'warning');
    conflict.hidden = true;
    const preview = el('div', null, 'news-preview'),
        featured = button('', () => choose('featured'), 'news-slot featured'),
        spots = el('div', null, 'spotlights'),
        left = button('', () => choose('left'), 'news-slot spotlight'),
        right = button('', () => choose('right'), 'news-slot spotlight');
    spots.append(left, right);
    preview.append(featured, spots);
    const image = select('Banner image', [], ''),
        tooltip = input('Tooltip'),
        url = input('Link URL'),
        action = select(
            'Client action',
            [
                ['', 'Open link / none'],
                ['depot', 'Open Supply Depot']
            ],
            ''
        ),
        dimensions = el('small'),
        error = el('p', null, 'error'),
        title = el('h2');
    const removeSpotlight = button('Remove spotlight', () => {
        if (slot === 'featured' || !draft.packs[pack]) return;
        draft.packs[pack].spotlights[slot] = null;
        mark();
        showSlot();
    });
    const fields = panel(
        '',
        title,
        label('Image', image),
        dimensions,
        label('Tooltip', tooltip),
        label('Link URL', url),
        label('Action', action),
        toolbar(removeSpotlight),
        error
    );
    fields.classList.add('stack');
    const workspace = el('div', null, 'news-workspace');
    workspace.append(preview, fields);
    const raw = input('Advanced News YAML', data.yaml, 'textarea');
    raw.spellcheck = false;
    raw.className = 'mono';
    raw.maxLength = 60000;
    const advanced = el('details');
    const saveYaml = button('Review and save YAML', () => review('news-save', { yaml: raw.value }));
    raw.oninput = () => text(dirtyLabel, 'Unsaved YAML draft');
    advanced.append(el('summary', 'Advanced YAML'), raw, saveYaml);
    const file = input('Upload News image', '', 'file', { accept: 'image/png,image/jpeg' });
    const save = button('Review and save News', () =>
        review('news-visual-save', { catalog: JSON.stringify(newsDocument(draft)) })
    );
    const reload = button('Load latest News', async () => {
        if (
            isDirty() &&
            !window.confirm('Discard the unsaved visual and YAML drafts and load the latest News?')
        )
            return;
        accept(await ctx.request('news'));
    });
    const removePack = button('Remove pack', () => {
        if (!draft.packs.length) return;
        openDialog({
            title: 'Remove pack ' + (pack + 1),
            content: el(
                'p',
                'Removes this pack from your draft. Save News to publish this change.'
            ),
            submit: 'Remove from draft',
            notifySaved: false,
            onSubmit: async () => {
                draft.packs.splice(pack, 1);
                pack = Math.max(0, pack - 1);
                mark();
                updatePacks();
                showSlot();
            }
        });
    });
    node.append(
        toolbar(
            label('Enable News', enabled),
            packs,
            button('Add pack', () => {
                draft.packs.push({
                    featured: emptyBanner(),
                    spotlights: { left: null, right: null }
                });
                pack = draft.packs.length - 1;
                slot = 'featured';
                mark();
                updatePacks();
                showSlot();
            }),
            removePack,
            reload,
            save,
            dirtyLabel
        ),
        conflict,
        workspace,
        panel(
            'Image library',
            label('Upload PNG or JPEG (up to 1 MiB)', file),
            el(
                'small',
                'Featured: 570 × 300. Spotlights: 280 × 190. Smaller images retain their native dimensions.'
            )
        ),
        advanced
    );
    const banner = () =>
        !draft.packs[pack]
            ? null
            : slot === 'featured'
              ? draft.packs[pack].featured
              : draft.packs[pack].spotlights[slot];
    function mark() {
        dirty = true;
        text(dirtyLabel, 'Unsaved draft');
    }
    const isDirty = () => dirty || raw.value !== data.yaml;
    function updatePacks() {
        removePack.disabled = !draft.packs.length;
        packs.disabled = !draft.packs.length;
        packs.replaceChildren(
            ...draft.packs.map((_, i) => {
                const option = el('option', 'Pack ' + (i + 1));
                option.value = i;
                return option;
            })
        );
        packs.value = pack;
    }
    function renderPreview() {
        for (const [name, target, value] of [
            ['featured', featured, draft.packs[pack]?.featured],
            ['left', left, draft.packs[pack]?.spotlights.left],
            ['right', right, draft.packs[pack]?.spotlights.right]
        ]) {
            target.setAttribute('aria-pressed', name === slot);
            target.setAttribute(
                'aria-label',
                name === 'featured' ? 'Featured banner' : name + ' spotlight'
            );
            if (!value?.image) {
                target.replaceChildren(
                    el('span', name === 'featured' ? 'Featured · 570 × 300' : `${name} · 280 × 190`)
                );
                continue;
            }
            const img = el('img');
            img.src = '/news/' + value.image.split('/').map(encodeURIComponent).join('/');
            img.alt = value.tooltip || name + ' banner';
            img.title = value.tooltip;
            img.onerror = () => {
                target.replaceChildren(el('span', 'Image unavailable'));
                text(error, 'The selected image could not be loaded.');
            };
            target.replaceChildren(img);
        }
    }
    function renderImages() {
        const value = banner(),
            max = slot === 'featured' ? [570, 300] : [280, 190];
        image.replaceChildren(
            ...[
                ['', 'No image selected'],
                ...data.images.map((i) => [i.image, `${i.image} · ${i.width} × ${i.height}`])
            ].map(([name, text]) => {
                const option = el('option', text);
                option.value = name;
                const info = data.images.find((i) => i.image === name);
                option.disabled = !!info && (info.width > max[0] || info.height > max[1]);
                return option;
            })
        );
        image.value = value?.image || '';
    }
    function showSlot() {
        const value = banner(),
            max = slot === 'featured' ? [570, 300] : [280, 190];
        text(
            title,
            slot === 'featured'
                ? 'Featured banner'
                : slot[0].toUpperCase() + slot.slice(1) + ' spotlight'
        );
        renderImages();
        removeSpotlight.disabled = slot === 'featured' || !value;
        tooltip.value = value?.tooltip || '';
        url.value = value?.url || '';
        action.value = value?.action || '';
        for (const control of [image, tooltip, url, action]) control.disabled = !draft.packs.length;
        const info = data.images.find((i) => i.image === value?.image);
        text(
            dimensions,
            info
                ? `${info.width} × ${info.height} · ${(info.bytes / 1024).toFixed(1)} KiB`
                : `Maximum ${max[0]} × ${max[1]}`
        );
        text(error, value?.image && !info ? 'Selected image is missing or invalid.' : '');
        renderPreview();
    }
    function choose(name) {
        slot = name;
        showSlot();
    }
    for (const control of [image, tooltip, url, action])
        control.addEventListener(
            control === image || control === action ? 'change' : 'input',
            () => {
                if (!draft.packs[pack]) return;
                let value = banner();
                if (!value) {
                    value = emptyBanner();
                    draft.packs[pack].spotlights[slot] = value;
                }
                value.image = image.value;
                value.tooltip = tooltip.value;
                value.url = url.value;
                value.action = action.value;
                value.argument = '';
                mark();
                removeSpotlight.disabled = slot === 'featured' || !banner();
                renderPreview();
                if (control === image) {
                    const info = data.images.find((i) => i.image === image.value);
                    text(
                        error,
                        image.value && !info
                            ? 'Selected image is missing or invalid.'
                            : !image.value
                              ? 'Choose an image for this banner.'
                              : ''
                    );
                    text(
                        dimensions,
                        info
                            ? `${info.width} × ${info.height} · ${(info.bytes / 1024).toFixed(1)} KiB`
                            : 'No image selected'
                    );
                }
            }
        );
    enabled.onchange = () => {
        draft.enabled = enabled.checked;
        mark();
    };
    packs.onchange = () => {
        pack = +packs.value;
        showSlot();
    };
    file.onchange = async () => {
        const upload = file.files[0];
        if (!upload) return;
        file.disabled = true;
        try {
            if (upload.size > 1024 * 1024 || !['image/png', 'image/jpeg'].includes(upload.type))
                throw new Error('Choose a PNG or JPEG no larger than 1 MiB.');
            const bytes = new Uint8Array(await upload.arrayBuffer());
            let binary = '';
            for (let i = 0; i < bytes.length; i += 8192)
                binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
            const result = await api.change('news-upload', {
                name: upload.name,
                data: btoa(binary)
            });
            if (!ctx.alive()) return;
            if (!data.images.some((i) => i.image === result.image)) data.images.push(result);
            showSlot();
            notify('Image uploaded. Select it for a banner, then save News.');
        } catch (failure) {
            report(failure);
        } finally {
            file.disabled = false;
            file.value = '';
        }
    };
    function review(route, values) {
        if (stale) return;
        if (
            route === 'news-visual-save' &&
            raw.value !== data.yaml &&
            !window.confirm('Saving the visual draft replaces the unsaved YAML draft. Continue?')
        )
            return;
        if (
            route === 'news-save' &&
            dirty &&
            !window.confirm('Saving YAML replaces the unsaved visual draft. Continue?')
        )
            return;
        const content = el('div');
        content.append(
            el(
                'p',
                route === 'news-save'
                    ? 'Review the YAML configuration below before saving.'
                    : `${draft.enabled ? 'Enable' : 'Disable'} Uplink News with ${draft.packs.length} pack(s).`
            ),
            el('p', 'The game listener must be restarted to publish a new pack selection.', 'muted')
        );
        if (route === 'news-visual-save') {
            const snapshot = preview.cloneNode(true);
            for (const b of snapshot.querySelectorAll('button')) b.disabled = true;
            content.append(snapshot);
        } else content.append(el('pre', values.yaml));
        openDialog({
            title: 'Publish Uplink News configuration',
            content,
            revision,
            target: 'news',
            submit: 'Save News',
            onSubmit: async () => {
                const result = await api.change(route, { ...values, revision });
                accept(result);
            }
        });
    }
    function accept(latest) {
        data = latest;
        revision = latest.revision;
        draft = schema(latest.catalog);
        dirty = stale = false;
        raw.value = latest.yaml;
        enabled.checked = draft.enabled;
        pack = Math.min(pack, Math.max(0, draft.packs.length - 1));
        text(dirtyLabel, 'Saved');
        conflict.hidden = true;
        save.disabled = saveYaml.disabled = false;
        updatePacks();
        showSlot();
    }
    updatePacks();
    showSlot();
    async function refresh() {
        const latest = await ctx.request('news');
        if (latest.revision !== revision) {
            dialogConflict('news', latest.revision);
            if (!isDirty()) accept(latest);
            else {
                stale = true;
                conflict.hidden = false;
                text(
                    conflict,
                    'News changed on the server. Your draft is preserved. Use Load latest News to discard this draft and edit the current revision.'
                );
                save.disabled = saveYaml.disabled = true;
            }
        }
        if (JSON.stringify(latest.images) !== JSON.stringify(data.images)) {
            data.images = latest.images;
            renderImages();
        }
    }
    return { node, refresh, isDirty, interval: 15000 };
}
