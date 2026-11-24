import { api } from '../api.js';
import {
    el,
    button,
    input,
    select,
    label,
    toolbar,
    panel,
    badge,
    date,
    localDate,
    text,
    changed
} from '../ui.js';
import { DataTable } from '../components/table.js';
import { openDialog, confirm as confirmAction } from '../components/dialog.js';
import { itemPicker } from '../components/item-picker.js';
import { currency, itemLine } from '../components/items.js';

const SENDERS = [
    ['6', 'Spiral HQ'],
    ['5', 'Haven Treasury'],
    ['4', 'Spiral Knights Support']
];
const senderName = (sender) =>
    SENDERS.find(([id]) => Number(id) === sender)?.[1] || `System ${sender}`;
const displaySender = (value) =>
    value.senderLabel?.trim() ? value.senderLabel.trim() : senderName(value.sender);
const units = (gifts) => gifts.reduce((total, gift) => total + Number(gift.count || 0), 0);
const unitCount = (value) => `${value} unit${value === 1 ? '' : 's'}`;

/** Exported for the Node serialization test and shared by the editor. */
export function mailingStatus(campaign, now = Date.now()) {
    if (!campaign.enabled) return 'Cancelled';
    if (campaign.ends <= now) return 'Ended';
    if (campaign.starts > now) return 'Scheduled';
    return 'Active';
}

export function mailingItems(gifts) {
    return JSON.stringify(
        gifts.map((gift) => ({
            config: gift.config,
            count: gift.count,
            bound: !!gift.bound
        }))
    );
}

function statusBadge(campaign, now) {
    const status = mailingStatus(campaign, now);
    return badge(status, status === 'Active' ? 'good' : status === 'Scheduled' ? 'gold' : '');
}

function giftsLine(gifts, definitions) {
    if (!gifts.length) return null;
    const node = el('div', null, 'mail-attachments');
    for (const gift of gifts) {
        const line = itemLine(definitions.get(gift.config), { config: gift.config });
        line.append(el('small', `×${gift.count}`));
        node.append(line);
    }
    return node;
}

function preview(message, definitions) {
    const node = el('div', null, 'mail-preview');
    node.append(
        el('small', 'Uplink message', 'muted'),
        el('strong', message.subject.trim() || 'Subject preview', 'mail-subject'),
        el('p', message.body.trim() || 'Your message will appear here.', 'mail-body'),
        toolbar(
            el('span', displaySender(message), 'muted'),
            ...(message.crowns > 0 ? [currency('crowns', message.crowns)] : []),
            ...(message.energy > 0 ? [currency('energy', message.energy)] : [])
        )
    );
    const gifts = giftsLine(message.gifts || [], definitions);
    if (gifts) node.append(gifts);
    return node;
}

function draftOf(mode, campaign) {
    if (mode === 'welcome') {
        const welcome = campaign;
        return {
            enabled: welcome.enabled,
            enabledAt: welcome.enabledAt,
            sender: welcome.sender,
            senderLabel: welcome.senderLabel || '',
            subject: welcome.subject,
            body: welcome.body,
            crowns: welcome.crowns,
            energy: welcome.energy,
            gifts: welcome.gifts.map((gift) => ({ ...gift })),
            recipient: ''
        };
    }
    if (mode === 'campaign' && campaign) {
        return {
            id: campaign.id,
            revision: campaign.updated,
            sender: campaign.sender,
            senderLabel: campaign.senderLabel || '',
            subject: campaign.subject,
            body: campaign.body,
            starts: campaign.starts,
            ends: campaign.ends,
            crowns: campaign.crowns,
            energy: campaign.energy,
            gifts: campaign.gifts.map((gift) => ({ ...gift })),
            recipient: ''
        };
    }
    return {
        id: 0,
        revision: 0,
        enabled: false,
        sender: 6,
        senderLabel: '',
        subject: '',
        body: '',
        starts: Date.now(),
        ends: Date.now() + 30 * 86_400_000,
        crowns: 0,
        energy: 0,
        gifts: [],
        recipient: ''
    };
}

export async function mount(ctx) {
    let data = await ctx.request('mail');
    const definitions = new Map(),
        node = el('div', null, 'stack mail-page'),
        summary = el('div', null, 'stack'),
        welcomeHost = el('div'),
        guidance = el(
            'p',
            'A mailing reaches every knight once while the window is open, including knights created later. Delivery happens on their next visit; delivered letters are never duplicated.'
        );
    let roster = null;

    const rosterRows = async () => {
        if (!roster) roster = await ctx.request('knights-all');
        return roster;
    };

    const loadDefinitions = async (campaigns) => {
        const configs = [
            ...new Set(campaigns.flatMap((campaign) => campaign.gifts.map((gift) => gift.config)))
        ];
        if (data.welcome) configs.push(...data.welcome.gifts.map((gift) => gift.config));
        const missing = [...new Set(configs)].filter((config) => !definitions.has(config));
        await Promise.all(
            missing.map(async (config) => {
                try {
                    definitions.set(config, await ctx.request('item', { config }));
                } catch (error) {
                    if (error.name !== 'AbortError') definitions.set(config, null);
                }
            })
        );
    };

    const table = new DataTable(
        [
            [
                'Subject',
                (campaign) => {
                    const cell = el('div', null, 'mail-subject-cell');
                    cell.append(
                        el('strong', campaign.subject),
                        el('small', campaign.body.replace(/\s+/g, ' ').slice(0, 90), 'muted')
                    );
                    return cell;
                },
                (campaign) => campaign.subject
            ],
            ['Sender', displaySender, displaySender],
            [
                'Delivery window',
                (campaign) => {
                    const cell = el('div', null, 'mail-window');
                    cell.append(
                        el('span', date(campaign.starts)),
                        el('span', '→', 'muted'),
                        el('span', date(campaign.ends))
                    );
                    return cell;
                },
                (campaign) => campaign.starts
            ],
            [
                'Status',
                (campaign) => statusBadge(campaign, data.now),
                (campaign) => mailingStatus(campaign, data.now)
            ],
            [
                'Gifts',
                (campaign) => {
                    if (!campaign.gifts.length) return el('span', 'Text only', 'muted');
                    const cell = el('div', null, 'mail-gifts-cell');
                    cell.append(el('span', unitCount(units(campaign.gifts)), 'muted'));
                    for (const gift of campaign.gifts)
                        cell.append(
                            itemLine(definitions.get(gift.config), { config: gift.config })
                        );
                    return cell;
                },
                (campaign) => units(campaign.gifts)
            ],
            [
                'Delivered',
                (campaign) => campaign.delivered.toLocaleString(),
                (campaign) => campaign.delivered
            ],
            [
                'Crowns / energy',
                (campaign) =>
                    toolbar(
                        currency('crowns', campaign.crowns),
                        currency('energy', campaign.energy)
                    ),
                (campaign) => campaign.crowns + campaign.energy
            ]
        ],
        {
            id: 'mail-campaigns',
            caption: 'Mail campaigns',
            exportName: 'mail-campaigns',
            actions: (get) => [
                button(
                    'Edit',
                    () => compose('campaign', get()),
                    'secondary',
                    () => get().enabled
                ),
                button(
                    'Cancel',
                    () =>
                        confirmAction(
                            'Cancel mailing · ' + get().subject,
                            'mail-cancel',
                            { id: get().id, revision: get().updated },
                            [],
                            'Knights who already received this mailing keep it. No further deliveries will be made.'
                        ),
                    'secondary',
                    () => get().enabled
                )
            ],
            filters: [
                [
                    'Status',
                    ['All', 'Active', 'Scheduled', 'Ended', 'Cancelled'],
                    (campaign, value) =>
                        value === 'All' || mailingStatus(campaign, data.now) === value
                ]
            ]
        }
    );
    table.node.classList.add('mail-table');

    function renderSummary() {
        const counts = { Active: 0, Scheduled: 0, Ended: 0, Cancelled: 0 };
        let delivered = 0;
        for (const campaign of data.campaigns) {
            counts[mailingStatus(campaign, data.now)]++;
            delivered += campaign.delivered;
        }
        const summaryPanel = panel(
            'Delivery status',
            guidance,
            toolbar(
                ...Object.entries(counts).map(([name, count]) =>
                    badge(`${count} ${name.toLowerCase()}`, name === 'Active' ? 'good' : '')
                ),
                badge(
                    `${delivered.toLocaleString()} letter${delivered === 1 ? '' : 's'} delivered`,
                    'gold'
                )
            ),
            el(
                'small',
                'Editing an active mailing changes what knights who have not received it yet will get. Delivered letters stay untouched.',
                'muted'
            ),
            ...(data.campaigns.length
                ? []
                : [
                      el(
                          'p',
                          'No mailings yet. Create a windowed mailing for every knight, or send a one-off letter to a single knight.',
                          'empty'
                      )
                  ])
        );
        summaryPanel.classList.add('stack');
        summary.replaceChildren(summaryPanel);
    }

    function renderWelcome() {
        const welcome = data.welcome;
        const status = welcome.enabled
            ? `Applies to knights created after ${date(welcome.enabledAt)}`
            : welcome.enabledAt
              ? `Stopped ${date(welcome.disabledAt)}; knights created while it was enabled still receive it`
              : 'New knights receive no automatic mail';
        const content = [
            el(
                'small',
                'Optional. Only knights created while this is enabled receive it; existing knights are not mailed. It arrives once, on the knight’s first visit. Disabling stops new copies but keeps eligibility for knights created while it was enabled.',
                'muted'
            ),
            toolbar(
                badge(welcome.enabled ? 'Enabled' : 'Disabled', welcome.enabled ? 'good' : ''),
                el('span', status, 'muted')
            )
        ];
        if (welcome.subject || welcome.body || welcome.gifts.length)
            content.push(
                preview(
                    {
                        ...welcome,
                        senderLabel: welcome.senderLabel
                    },
                    definitions
                )
            );
        content.push(
            toolbar(button('Edit welcome mail', () => compose('welcome', welcome), 'secondary'))
        );
        const card = panel('New knight welcome mail', ...content);
        card.classList.add('stack');
        welcomeHost.replaceChildren(card);
    }

    async function compose(mode, record = null) {
        if (mode === 'direct') roster = null;
        const rows = mode === 'direct' ? await rosterRows() : [];
        if (mode === 'direct' && !rows.length)
            throw new Error('Create a knight before sending direct mail.');
        const draft = draftOf(mode, record);
        const node = el('div', null, 'stack'),
            header = el('div', null, 'form-grid'),
            gifts = el('div', null, 'stack'),
            unitLabel = el('small', '', 'muted');
        const enabled =
            mode === 'welcome' ? input('Enable welcome mail', draft.enabled, 'checkbox') : null;
        const subject = input('Subject', draft.subject);
        subject.maxLength = 50;
        const body = input('Body', draft.body, 'textarea');
        body.rows = 12;
        body.maxLength = 8096;
        const sender = select('Sender', SENDERS, String(draft.sender));
        const customSender = input('Custom sender (optional)', draft.senderLabel);
        customSender.maxLength = 40;
        customSender.placeholder = 'Overrides the system sender name';
        const starts =
            mode === 'campaign' ? input('Starts', localDate(draft.starts), 'datetime-local') : null;
        const ends =
            mode === 'campaign' ? input('Ends', localDate(draft.ends), 'datetime-local') : null;
        const crowns = input('Crowns', draft.crowns, 'number', {
            min: 0,
            max: 2147483647,
            step: 1
        });
        const energy = input('Energy', draft.energy, 'number', {
            min: 0,
            max: 2147483647,
            step: 1
        });
        let filter, target;
        if (mode === 'direct') {
            filter = input('Filter knights');
            target = select('Knight', [], '');
            const countLabel = el('small', '', 'muted');
            const recipientPanel = panel(
                'To',
                el(
                    'small',
                    'Written straight to this knight’s inbox; offline knights read it when they return.',
                    'muted'
                ),
                el('div', null, 'form-grid'),
                countLabel
            );
            recipientPanel.classList.add('stack');
            const recipientForm = recipientPanel.querySelector('.form-grid');
            recipientForm.append(label('Filter', filter), label('Knight', target));
            const fill = () => {
                const query = filter.value.trim().toLowerCase();
                const matches = rows.filter((row) =>
                    `${row.name} ${row.username}`.toLowerCase().includes(query)
                );
                target.replaceChildren(
                    ...matches.map((row) => {
                        const option = el(
                            'option',
                            `${row.name} · ${row.username} · ${row.online ? 'online' : 'offline'}`
                        );
                        option.value = `${row.username}|${row.name}`;
                        return option;
                    })
                );
                if (!matches.length) {
                    const option = el('option', 'No matching knights');
                    option.value = '';
                    target.append(option);
                }
                if (!target.value && target.options.length) target.value = target.options[0].value;
                if (!draft.recipient) draft.recipient = target.value;
                text(countLabel, `${matches.length} knight${matches.length === 1 ? '' : 's'}`);
            };
            filter.oninput = fill;
            fill();
            node.append(recipientPanel);
        }
        const enabledRow = mode === 'welcome' ? toolbar(label('Enabled', enabled)) : null;
        if (mode === 'campaign') header.append(label('Starts', starts), label('Ends', ends));
        header.append(
            label('From', sender),
            label('Custom sender', customSender),
            label('Subject', subject)
        );
        subject.parentElement.classList.add('wide');

        let previewHost = preview(draft, definitions);
        const refreshPreview = () => {
            const next = preview(
                {
                    subject: subject.value,
                    body: body.value,
                    sender: Number(sender.value),
                    senderLabel: customSender.value,
                    crowns: Number(crowns.value || 0),
                    energy: Number(energy.value || 0),
                    gifts: draft.gifts
                },
                definitions
            );
            previewHost.replaceWith(next);
            previewHost = next;
        };
        for (const control of [subject, body, sender, customSender, crowns, energy])
            control.addEventListener('input', refreshPreview);

        function renderGifts() {
            gifts.replaceChildren(
                ...draft.gifts.map((gift, index) => {
                    const quantity = input('Quantity', gift.count, 'number', {
                        min: 1,
                        max: 999,
                        step: 1
                    });
                    const bound = input('Bound on delivery', gift.bound, 'checkbox');
                    const boundLabel = label('Bound', bound);
                    boundLabel.classList.remove('check');
                    quantity.oninput = () => {
                        gift.count = Math.max(1, Math.min(999, Number(quantity.value) || 1));
                        updateUnits();
                        refreshPreview();
                    };
                    bound.onchange = () => (gift.bound = bound.checked);
                    const row = el('div', null, 'mail-gift');
                    row.append(
                        itemLine(definitions.get(gift.config), { config: gift.config }),
                        label('Quantity', quantity),
                        boundLabel,
                        button(
                            'Remove',
                            () => {
                                draft.gifts.splice(index, 1);
                                renderGifts();
                                refreshPreview();
                            },
                            'quiet'
                        )
                    );
                    return row;
                })
            );
            updateUnits();
        }
        function updateUnits() {
            const total = units(draft.gifts);
            text(
                unitLabel,
                `${draft.gifts.length} gift stack${draft.gifts.length === 1 ? '' : 's'} · ${total} of 14 units`
            );
            unitLabel.classList.toggle('error', total > 14);
        }
        const picker = itemPicker(
            (definition) => {
                definitions.set(definition.config, definition);
                const existing = draft.gifts.find(
                    (gift) => gift.config === definition.config && !gift.bound
                );
                if (existing) existing.count = Math.min(999, existing.count + 1);
                else
                    draft.gifts.push({
                        config: definition.config,
                        count: 1,
                        bound: false
                    });
                renderGifts();
                refreshPreview();
            },
            { grantable: true }
        );
        renderGifts();
        const pickerDetails = el('details');
        pickerDetails.append(el('summary', 'Add a gift item'), picker.node);
        const messagePanel = panel(
            'Message',
            el('div', null, 'mail-compose-split'),
            el(
                'small',
                'Subject and body are shown as written. Up to 50 and 8,096 characters.',
                'muted'
            )
        );
        const split = messagePanel.querySelector('.mail-compose-split');
        split.append(label('Body', body), label('Preview', previewHost));
        const attachments = panel(
            'Attachments',
            el('div', null, 'form-grid'),
            el(
                'small',
                'Up to 14 item units. Gifts stay in the inbox until claimed; stock item expiry applies after claiming.',
                'muted'
            ),
            unitLabel,
            gifts,
            pickerDetails
        );
        const extras = attachments.querySelector('.form-grid');
        extras.append(label('Crowns', crowns), label('Energy', energy));
        messagePanel.classList.add('stack');
        attachments.classList.add('stack');
        if (enabledRow) node.append(enabledRow);
        node.append(header, messagePanel, attachments);

        const initial = JSON.stringify(snapshot());
        function snapshot() {
            return [
                mode === 'welcome' ? enabled.checked : '',
                mode === 'direct' ? target.value : '',
                subject.value,
                body.value,
                Number(sender.value),
                customSender.value.trim(),
                starts?.value || '',
                ends?.value || '',
                Number(crowns.value || 0),
                Number(energy.value || 0),
                draft.gifts
            ];
        }
        openDialog({
            title:
                mode === 'welcome'
                    ? 'Welcome mail'
                    : mode === 'direct'
                      ? 'Send mail to a knight'
                      : record
                        ? 'Edit mailing'
                        : 'New mailing',
            description:
                mode === 'welcome'
                    ? 'Sent once to knights created while it is enabled.'
                    : mode === 'direct'
                      ? 'Delivered immediately to the selected knight, whether they are online or offline.'
                      : record
                        ? 'Knights who already received this mailing keep the old letter.'
                        : 'Save first, then every knight receives it once while the window is open.',
            content: node,
            notifySaved: false,
            submit:
                mode === 'welcome'
                    ? 'Save welcome mail'
                    : mode === 'direct'
                      ? 'Send mail'
                      : record
                        ? 'Save mailing'
                        : 'Create mailing',
            isDirty: () => JSON.stringify(snapshot()) !== initial,
            dispose: () => picker.dispose(),
            after: () => refresh(),
            onSubmit: async () => {
                const requiresText = mode !== 'welcome' || enabled.checked || draft.enabledAt;
                if (requiresText && !subject.value.trim()) throw new Error('Subject is required.');
                if (requiresText && !body.value.trim()) throw new Error('Body is required.');
                if (units(draft.gifts) > 14)
                    throw new Error('A mailing can carry at most 14 item units.');
                const values = {
                    sender: Number(sender.value),
                    senderLabel: customSender.value,
                    subject: subject.value,
                    body: body.value,
                    crowns: Math.max(0, Number(crowns.value) || 0),
                    energy: Math.max(0, Number(energy.value) || 0),
                    items: mailingItems(draft.gifts)
                };
                if (mode === 'direct') {
                    if (!target.value) throw new Error('Choose a knight.');
                    const [username, knight] = target.value.split('|');
                    await api.change('mail-send', { ...values, username, knight });
                    changed(
                        `Mail queued for ${knight}. It is waiting even while they are offline.`
                    );
                } else if (mode === 'welcome') {
                    await api.change('mail-welcome-save', { ...values, enabled: enabled.checked });
                    changed(enabled.checked ? 'Welcome mail saved.' : 'Welcome mail disabled.');
                } else {
                    const start = new Date(starts.value).getTime(),
                        end = new Date(ends.value).getTime();
                    if (!Number.isFinite(start) || !Number.isFinite(end))
                        throw new Error('Choose a valid start and end time.');
                    if (end <= start) throw new Error('The mailing must end after it starts.');
                    await api.change('mail-save', {
                        ...values,
                        id: draft.id,
                        revision: draft.revision,
                        starts: start,
                        ends: end
                    });
                    changed(record ? 'Mailing saved.' : 'Mailing created.');
                }
                picker.dispose();
            }
        });
    }

    async function refresh() {
        data = await ctx.request('mail');
        await loadDefinitions(data.campaigns);
        renderSummary();
        renderWelcome();
        table.update(data.campaigns);
    }

    await loadDefinitions(data.campaigns);
    renderSummary();
    renderWelcome();
    table.update(data.campaigns);
    const mailings = panel(
        'Mailings',
        toolbar(
            button('New mailing', () => compose('campaign')),
            button('Send to knight', () => compose('direct'), 'secondary')
        ),
        table.node
    );
    mailings.classList.add('stack');
    node.append(summary, welcomeHost, mailings);
    return {
        node,
        interval: 30000,
        refresh
    };
}
