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
        welcome = await ctx.request('welcome'),
        draft = schema(data.catalog),
        pack = 0,
        slot = 'featured',
        dirty = false,
        revision = data.revision,
        welcomeRevision = welcome.revision,
        welcomeMessage = welcome.message,
        welcomeStale = false,
        stale = false;
    /**
     * One boolean, one control. Every live on/off in this console is a switch with a word
     * for its state; this one was a native 13px checkbox, the only control on the page
     * that did not belong to the control language around it.
     */
    function switchControl() {
        const control = el('button', null, 'quiet');
        control.type = 'button';
        control.setAttribute('role', 'switch');
        control.setAttribute('aria-checked', String(!!draft.enabled));
        control.id = 'news-enabled';
        text(control, draft.enabled ? 'On' : 'Off');
        return control;
    }
    function paintEnabled() {
        enabled.setAttribute('aria-checked', String(draft.enabled));
        text(enabled, draft.enabled ? 'On' : 'Off');
    }

    const node = el('div', null, 'stack'),
        // A switch, not a checkbox: this is the one control on the page that turns a
        // feature on for the whole server, and it sat as a 13px native box in a row of
        // 40px buttons beside the shell's 32px switches. Same control, same shape.
        enabled = switchControl(),
        packs = select('News pack', [], ''),
        dirtyLabel = el('span', null, 'muted'),
        conflict = el('div', null, 'warning');
    conflict.hidden = true;
    /* The label sits beside the switch, not above it. A stacked field put "Uplink News"
       on one line and its switch on the next, which broke the toolbar into two rows and
       left the switch floating under the word it belongs to. */
    const enabledField = el('span', null, 'switch-field'),
        enabledLabel = el('span', 'Uplink News', 'switch-label');
    enabled.id = 'news-enabled';
    enabledLabel.id = 'news-enabled-label';
    enabled.setAttribute('aria-labelledby', 'news-enabled-label');
    enabledField.append(enabledLabel, enabled);
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
                // One outcome per label: "Open link / none" named two things at once and
                // the reader could not tell which one pressing it would choose.
                ['', 'Open in the browser only'],
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
    // Declared before the panel that holds it: the panel list is built above the rest of
    // this function, and a `const` placed after it is a temporal-dead-zone error on mount.
    const library = el('div', null, 'news-library');
    // Not `role="list"`: a tile is a `<button>`, and putting `listitem` on it (below)
    // replaced the button role, so the only control that arms a banner announced
    // itself as "list item" and gave no hint it could be pressed. A named group of
    // buttons is what this is.
    library.setAttribute('role', 'group');
    library.setAttribute('aria-label', 'Images on the server');
    const libraryFilter = input('Filter the image library', '');
    libraryFilter.type = 'search';
    libraryFilter.placeholder = 'Filter by name';
    libraryFilter.oninput = () => {
        renderLibrary();
        renderImages();
    };
    const libraryCount = el('span', null, 'muted');
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
    const welcomeInput = input('Welcome message', welcomeMessage);
    welcomeInput.maxLength = 256;
    const welcomeStatus = el('small', 'Restart the server process to apply changes.', 'muted');
    const welcomeSave = button('Save welcome message', () => reviewWelcome());
    const welcomePanel = panel(
        'Welcome message',
        label(
            'Shown once in chat after a knight enters the world. Leave empty to disable.',
            welcomeInput
        ),
        el('small', 'Up to 256 characters. Stored in the active server configuration file.'),
        toolbar(welcomeSave, welcomeStatus)
    );
    welcomePanel.classList.add('stack');
    // A native file input draws the browser's own grey "Choose File / No file chosen"
    // control inside a page of client art, and it cannot be styled into this one
    // language: the control is hidden and a real button opens it, with the name of
    // the file it holds beside it.
    const file = input('Upload News image', '', 'file', { accept: 'image/png,image/jpeg' });
    file.classList.add('visually-hidden');
    file.tabIndex = -1;
    const fileName = el('span', 'No file chosen', 'file-name');
    const fileButton = button('Choose a file', () => file.click(), 'secondary');
    const fileLimit = el('span', 'PNG or JPEG, up to 1 MiB', 'upload-limit');
    fileLimit.id = 'upload-limit';
    fileButton.setAttribute('aria-describedby', 'upload-limit');
    const uploadRow = el('div', null, 'upload-row');
    uploadRow.append(file, fileButton, fileName, fileLimit);
    /** The upload disables its own picker while the bytes are in flight. */
    const fileBusy = (state) => {
        file.disabled = state;
        fileButton.disabled = state;
    };
    // The one action this page exists for carries the primary weight.
    const save = button(
        'Review and save News',
        () => review('news-visual-save', { catalog: JSON.stringify(newsDocument(draft)) }),
        'primary'
    );
    const reload = button('Load latest News', async () => {
        if (
            isDirty() &&
            !window.confirm(
                'Discard the unsaved News, YAML and welcome drafts and load the latest settings?'
            )
        )
            return;
        const [latest, latestWelcome] = await Promise.all([
            ctx.request('news'),
            ctx.request('welcome')
        ]);
        accept(latest);
        acceptWelcome(latestWelcome);
    });
    const removePack = button(
        'Remove pack',
        () => {
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
        },
        'quiet'
    );
    node.append(
        toolbar(
            enabledField,
            packs,
            button(
                'Add pack',
                () => {
                    draft.packs.push({
                        featured: emptyBanner(),
                        spotlights: { left: null, right: null }
                    });
                    pack = draft.packs.length - 1;
                    slot = 'featured';
                    mark();
                    updatePacks();
                    showSlot();
                },
                'quiet'
            ),
            removePack,
            el('span', null, 'spacer'),
            reload,
            save,
            dirtyLabel
        ),
        conflict,
        workspace,
        panel(
            'Image library',
            uploadRow,
            el(
                'small',
                'Featured: 570 × 300. Spotlights: 280 × 190. Smaller images retain their native dimensions.',
                'muted'
            ),
            // The library used to be an upload field and a sentence: the only way to see
            // what was already on the server was the picker's option text. Up to 128
            // images can be stored, so it has a filter of its own.
            toolbar(libraryFilter, libraryCount),
            library
        ),
        advanced,
        welcomePanel
    );
    /** Every place a banner already names this image, so replacing it is informed. */
    function usageOf(name) {
        const uses = [];
        draft.packs.forEach((row, index) => {
            for (const [slotName, value] of [
                ['featured', row.featured],
                ['left', row.spotlights?.left],
                ['right', row.spotlights?.right]
            ])
                if (value && value.image === name) uses.push(`pack ${index + 1} ${slotName}`);
        });
        return uses;
    }
    /** A banner link is pasted into the client, so it has to be a web link. */
    function checkUrl(value) {
        const trimmed = String(value ?? '').trim();
        if (!trimmed) return '';
        if (!/^https?:\/\//i.test(trimmed))
            return 'A banner link must start with https:// (http:// for an internal host): the client opens it in the player’s browser.';
        return '';
    }
    function renderLibrary() {
        const needle = libraryFilter.value.trim().toLowerCase();
        const shown = needle
            ? data.images.filter((info) => info.image.toLowerCase().includes(needle))
            : data.images;
        text(
            libraryCount,
            data.images.length
                ? `${shown.length.toLocaleString()} of ${data.images.length.toLocaleString()} images`
                : ''
        );
        if (!data.images.length) {
            library.replaceChildren(
                el(
                    'p',
                    'No image has been uploaded yet. Upload a PNG or JPEG above; it appears here and in the image picker.',
                    'muted'
                )
            );
            return;
        }
        if (!shown.length) {
            library.replaceChildren(
                el('p', 'No image name matches “' + libraryFilter.value.trim() + '”.', 'muted')
            );
            return;
        }
        const max = slot === 'featured' ? [570, 300] : [280, 190];
        library.replaceChildren(
            ...shown.map((info) => {
                const tile = el('button', null, 'news-library-tile');
                tile.type = 'button';
                const thumb = el('img');
                thumb.loading = 'lazy';
                thumb.alt = '';
                thumb.src = '/news/' + info.image.split('/').map(encodeURIComponent).join('/');
                const uses = usageOf(info.image),
                    tooBig = info.width > max[0] || info.height > max[1];
                tile.append(
                    thumb,
                    el('span', info.image, 'news-library-name'),
                    el(
                        'small',
                        `${info.width} × ${info.height} · ${(info.bytes / 1024).toFixed(1)} KiB`,
                        'muted'
                    ),
                    el('small', uses.length ? `used by ${uses.join(', ')}` : 'not used yet', 'muted')
                );
                const refusal = tooBig
                    ? `${info.width} × ${info.height} does not fit this slot (${max[0]} × ${max[1]}).`
                    : draft.packs.length
                      ? ''
                      : 'Save a pack before choosing an image.';
                if (refusal) {
                    // aria-disabled, not disabled: the reason has to stay reachable. The
                    // reason is printed in the tile and named by `aria-describedby`,
                    // because an aria-disabled tile that keeps no click handler is
                    // focusable, silent, and looks identical to a working one — `title`
                    // alone is hover-only and a phone never hovers.
                    const note = el('small', refusal, 'muted');
                    note.id = 'news-tile-refusal-' + info.image.replace(/[^a-z0-9]+/gi, '-');
                    tile.setAttribute('aria-disabled', 'true');
                    tile.dataset.usable = 'false';
                    tile.setAttribute('aria-describedby', note.id);
                    tile.title = refusal;
                    tile.onclick = () => notify(refusal, true);
                    tile.append(note);
                    return tile;
                }
                tile.title = `Put ${info.image} in this slot`;
                tile.onclick = () => {
                    image.value = info.image;
                    // Reuse the picker's own change handler: one writer for the draft.
                    image.dispatchEvent(new Event('change'));
                };
                return tile;
            })
        );
    }
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
    const newsDirty = () => dirty || raw.value !== data.yaml;
    const welcomeDirty = () => welcomeInput.value !== welcomeMessage;
    const isDirty = () => newsDirty() || welcomeDirty();
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
                // An empty slot names what belongs in it and how big the picture has to
                // be; "left · 280 × 190" was the scene key, not a word an operator uses.
                const placeholder = el(
                    'span',
                    name === 'featured'
                        ? 'Featured banner · 570 × 300'
                        : `${name === 'left' ? 'Left' : 'Right'} spotlight · 280 × 190`,
                    'slot-empty'
                );
                target.replaceChildren(placeholder);
                continue;
            }
            const img = el('img');
            img.src = '/news/' + value.image.split('/').map(encodeURIComponent).join('/');
            img.alt = value.tooltip || name + ' banner';
            img.title = value.tooltip;
            img.onerror = () => {
                target.replaceChildren(el('span', 'Image unavailable', 'slot-empty'));
                text(error, 'The selected image could not be loaded.');
            };
            target.replaceChildren(img);
        }
    }
    function renderImages() {
        const value = banner(),
            max = slot === 'featured' ? [570, 300] : [280, 190],
            // The filter above the picture grid is the filter for the field too: it was
            // the one control still listing all 128 images, so the fix reached the tiles
            // and not the thing the operator has to use.
            needle = libraryFilter.value.trim().toLowerCase(),
            offered = needle
                ? data.images.filter((i) => i.image.toLowerCase().includes(needle))
                : data.images;
        image.replaceChildren(
            ...[
                ['', 'No image selected'],
                ...offered.map((i) => [i.image, `${i.image} · ${i.width} × ${i.height}`])
            ].map(([name, text]) => {
                const option = el('option', text);
                option.value = name;
                const info = data.images.find((i) => i.image === name);
                option.disabled = !!info && (info.width > max[0] || info.height > max[1]);
                return option;
            })
        );
        // The banner's own image stays in the list even when the filter excludes it: a
        // select whose value is not among its options resets to the first one, which
        // would quietly unset a banner the operator did not touch.
        if (value?.image && !offered.some((i) => i.image === value.image)) {
            const option = el('option', `${value.image} · not matching the filter`);
            option.value = value.image;
            image.prepend(option);
        }
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
        renderLibrary();
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
                if (control === url) {
                    const problem = checkUrl(url.value);
                    text(
                        error,
                        problem ||
                            (image.value && !data.images.some((i) => i.image === image.value)
                                ? 'Selected image is missing or invalid.'
                                : '')
                    );
                }
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
    enabled.onclick = () => {
        draft.enabled = !draft.enabled;
        paintEnabled();
        mark();
    };
    packs.onchange = () => {
        pack = +packs.value;
        showSlot();
    };
    file.onchange = async () => {
        const upload = file.files[0];
        fileName.textContent = upload?.name || 'No file chosen';
        if (!upload) return;
        fileBusy(true);
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
            fileBusy(false);
            file.value = '';
            // The picker is empty again, so the readout must not keep the name of a
            // file the control no longer holds; the library tile and the toast are
            // what say the upload worked.
            fileName.textContent = 'No file chosen';
        }
    };
    function review(route, values) {
        if (stale) return;
        if (route === 'news-visual-save') {
            const broken = draft.packs
                .flatMap((row, index) =>
                    [
                        ['featured', row.featured],
                        ['left', row.spotlights?.left],
                        ['right', row.spotlights?.right]
                    ]
                        .filter(([, value]) => value && checkUrl(value.url))
                        .map(([slotName]) => `pack ${index + 1} ${slotName}`)
                )
                .slice(0, 3);
            if (broken.length) {
                notify(
                    `A banner link must start with https:// before this can be published: ${broken.join(', ')}.`,
                    true
                );
                return;
            }
        }
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
    function reviewWelcome() {
        if (welcomeStale) return;
        const content = el('div');
        content.append(
            el('p', 'This message appears once in chat after a knight enters the world.'),
            el('p', welcomeInput.value || '(disabled)', 'mono'),
            el('p', 'The server process must restart before players see the change.', 'muted')
        );
        openDialog({
            title: 'Review welcome message',
            content,
            revision: welcomeRevision,
            target: 'welcome',
            submit: 'Save message',
            onSubmit: async () => {
                const result = await api.change('welcome-save', {
                    message: welcomeInput.value,
                    revision: welcomeRevision
                });
                acceptWelcome(result, true);
            }
        });
    }
    function acceptWelcome(latest, saved = false) {
        welcomeRevision = latest.revision;
        welcomeMessage = latest.message;
        welcomeInput.value = latest.message;
        welcomeStale = false;
        welcomeSave.disabled = false;
        text(
            welcomeStatus,
            saved
                ? 'Saved. Restart the server process to apply.'
                : 'Restart the server process to apply changes.'
        );
    }
    function accept(latest) {
        data = latest;
        revision = latest.revision;
        draft = schema(latest.catalog);
        dirty = stale = false;
        raw.value = latest.yaml;
        paintEnabled();
        pack = Math.min(pack, Math.max(0, draft.packs.length - 1));
        text(dirtyLabel, 'Saved');
        conflict.hidden = true;
        save.disabled = saveYaml.disabled = false;
        updatePacks();
        showSlot();
    }
    updatePacks();
    showSlot();
    /**
     * `#news?pack=` selects a pack by the number the selector shows, so a review
     * link lands on the banner under discussion without discarding a draft.
     */
    function applyParams(params) {
        const wanted = Number(params.get('pack'));
        if (!Number.isInteger(wanted) || wanted < 1) return;
        // A link to a pack that does not exist must not silently open the last one,
        // and must not claim to have opened pack 1 when it did not.
        if (wanted > draft.packs.length) {
            notify(
                `This banner has ${draft.packs.length === 1 ? 'one pack' : `${draft.packs.length} packs`}, so there is no pack ${wanted}. Showing pack ${pack + 1}.`,
                true
            );
            return;
        }
        const next = wanted - 1;
        if (next === pack) return;
        pack = next;
        packs.value = String(pack);
        showSlot();
    }
    async function refresh() {
        const latest = await ctx.request('news');
        if (latest.revision !== revision) {
            dialogConflict('news', latest.revision);
            if (!newsDirty()) accept(latest);
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
        const latestWelcome = await ctx.request('welcome');
        if (latestWelcome.revision !== welcomeRevision) {
            dialogConflict('welcome', latestWelcome.revision);
            if (!welcomeDirty()) acceptWelcome(latestWelcome);
            else {
                welcomeStale = true;
                welcomeSave.disabled = true;
                text(
                    welcomeStatus,
                    'Settings changed on disk. Load the latest settings before editing.'
                );
            }
        }
    }
    applyParams(ctx.params);
    return { node, refresh, isDirty, interval: 15000, navigate: applyParams };
}
