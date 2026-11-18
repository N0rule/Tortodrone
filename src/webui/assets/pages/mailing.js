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
import { itemLine } from '../components/items.js';

const SENDERS = [
    ['6', 'Spiral HQ'],
    ['5', 'Haven Treasury'],
    ['4', 'Spiral Knights Support']
];
const senderName = (sender) => SENDERS.find(([id]) => Number(id) === sender)?.[1] || `System ${sender}`;
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
            bound: !!gift.bound,
            expireDays: gift.expireDays || 0
        }))
    );
}

function statusBadge(campaign, now) {
    const status = mailingStatus(campaign, now);
    return badge(status, status === 'Active' ? 'good' : status === 'Scheduled' ? 'gold' : '');
}

function preview(subject, body, sender, crowns, energy) {
    const node = el('div', null, 'mail-preview');
    node.append(
        el('small', 'Uplink message', 'muted'),
        el('strong', subject.trim() || 'Subject preview', 'mail-subject'),
        el('p', body.trim() || 'Your message will appear here.', 'mail-body'),
        toolbar(
            el('span', senderName(Number(sender)), 'muted'),
            ...(crowns ? [el('span', `${crowns} crowns`, 'muted')] : []),
            ...(energy ? [el('span', `${energy} energy`, 'muted')] : [])
        )
    );
    return node;
}

export async function mount(ctx) {
    let data = await ctx.request('mail');
    const definitions = new Map(),
        node = el('div', null, 'stack'),
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
        const missing = [
            ...new Set(campaigns.flatMap((campaign) => campaign.gifts.map((gift) => gift.config)))
        ].filter((config) => !definitions.has(config));
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
            [
                'Sender',
                (campaign) => senderName(campaign.sender),
                (campaign) => senderName(campaign.sender)
            ],
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
                        cell.append(itemLine(definitions.get(gift.config), { config: gift.config }));
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
                        el('span', `${campaign.crowns} crowns`),
                        el('span', `${campaign.energy} energy`)
                    ),
                (campaign) => campaign.crowns + campaign.energy
            ]
        ],
        {
            actions: (get) => [
                button('Edit', () => compose(get()), 'secondary', () => get().enabled),
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
                    (campaign, value) => value === 'All' || mailingStatus(campaign, data.now) === value
                ]
            ]
        }
    );

    function renderSummary() {
        const counts = { Active: 0, Scheduled: 0, Ended: 0, Cancelled: 0 };
        let delivered = 0;
        for (const campaign of data.campaigns) {
            counts[mailingStatus(campaign, data.now)]++;
            delivered += campaign.delivered;
        }
        summary.replaceChildren(
            panel(
                'Delivery status',
                guidance,
                toolbar(
                    ...Object.entries(counts).map(([name, count]) =>
                        badge(`${count} ${name.toLowerCase()}`, name === 'Active' ? 'good' : '')
                    ),
                    badge(`${delivered.toLocaleString()} letter${delivered === 1 ? '' : 's'} delivered`, 'gold')
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
            )
        );
    }

    function renderWelcome() {
        const welcome = data.welcome;
        welcomeHost.replaceChildren(
            panel(
                'New knight welcome mail',
                el(
                    'small',
                    'Optional. Only knights created while this is enabled receive it; existing knights are not mailed. It arrives once, on the knight’s first visit. Disabling stops new copies but keeps eligibility for knights created while it was enabled.',
                    'muted'
                ),
                toolbar(
                    badge(
                        welcome.enabled ? 'Enabled' : 'Disabled',
                        welcome.enabled ? 'good' : ''
                    ),
                    el(
                        'span',
                        welcome.enabled
                            ? `Applies to knights created after ${date(welcome.enabledAt)}`
                            : welcome.enabledAt
                              ? `Stopped ${date(welcome.disabledAt)}; knights created while it was enabled still receive it`
                              : 'New knights receive no automatic mail',
                        'muted'
                    )
                ),
                ...(welcome.enabled
                    ? [
                          el('strong', welcome.subject),
                          el(
                              'p',
                              welcome.body.replace(/\s+/g, ' ').length > 240
                                  ? welcome.body.replace(/\s+/g, ' ').slice(0, 240) + '…'
                                  : welcome.body.replace(/\s+/g, ' '),
                              'muted'
                          )
                      ]
                    : []),
                toolbar(button('Edit welcome mail', editWelcome, 'secondary'))
            )
        );
    }

    function editWelcome() {
        const welcome = data.welcome;
        const enabled = input('Enable welcome mail', welcome.enabled, 'checkbox');
        const sender = select('Sender', SENDERS, String(welcome.sender));
        const subject = input('Subject', welcome.subject);
        subject.maxLength = 50;
        const body = input('Body', welcome.body, 'textarea');
        body.rows = 6;
        body.maxLength = 8096;
        const form = el('div', null, 'form-grid');
        form.append(
            label('Enabled', enabled),
            label('Sender', sender),
            label('Subject', subject),
            label('Body', body)
        );
        form.lastElementChild.classList.add('wide');
        const content = el('div', null, 'stack');
        content.append(
            el(
                'small',
                'Enabling starts a new eligibility clock. Existing knights are not mailed, and a copy already delivered is never removed.',
                'muted'
            ),
            form
        );
        openDialog({
            title: 'New knight welcome mail',
            description: welcome.enabled
                ? `Currently enabled for knights created after ${date(welcome.enabledAt)}.`
                : welcome.enabledAt
                  ? `Disabled since ${date(welcome.disabledAt)}. Knights created while it was enabled still receive it.`
                  : 'Disabled. New knights receive no automatic mail.',
            content,
            submit: 'Save welcome mail',
            notifySaved: false,
            isDirty: () =>
                enabled.checked !== welcome.enabled ||
                Number(sender.value) !== welcome.sender ||
                subject.value !== welcome.subject ||
                body.value !== welcome.body,
            after: () => refresh(),
            onSubmit: async () => {
                if (enabled.checked && !subject.value.trim()) throw new Error('Subject is required.');
                if (enabled.checked && !body.value.trim()) throw new Error('Body is required.');
                await api.change('mail-welcome-save', {
                    enabled: enabled.checked,
                    sender: Number(sender.value),
                    subject: subject.value,
                    body: body.value
                });
                changed(enabled.checked ? 'Welcome mail enabled.' : 'Welcome mail disabled.');
            }
        });
    }

    async function compose(campaign, direct = false) {
        if (direct) roster = null;
        const draft = campaign
            ? {
                  id: campaign.id,
                  revision: campaign.updated,
                  subject: campaign.subject,
                  body: campaign.body,
                  sender: campaign.sender,
                  starts: campaign.starts,
                  ends: campaign.ends,
                  crowns: campaign.crowns,
                  energy: campaign.energy,
                  gifts: campaign.gifts.map((gift) => ({ ...gift })),
                  recipient: ''
              }
            : {
                  id: 0,
                  revision: 0,
                  subject: '',
                  body: '',
                  sender: 6,
                  starts: Date.now(),
                  ends: Date.now() + 30 * 86_400_000,
                  crowns: 0,
                  energy: 0,
                  gifts: [],
                  recipient: ''
              };
        const rows = direct ? await rosterRows() : [];
        if (direct && !rows.length) throw new Error('Create a knight before sending direct mail.');
        const node = el('div', null, 'stack'),
            form = el('div', null, 'form-grid'),
            gifts = el('div', null, 'stack'),
            unitLabel = el('small', '', 'muted');
        const subject = input('Subject', draft.subject);
        subject.maxLength = 50;
        const body = input('Body', draft.body, 'textarea');
        body.rows = 7;
        body.maxLength = 8096;
        const sender = select('Sender', SENDERS, String(draft.sender));
        const starts = input('Starts', localDate(draft.starts), 'datetime-local');
        const ends = input('Ends', localDate(draft.ends), 'datetime-local');
        const crowns = input('Crowns', draft.crowns, 'number', { min: 0, max: 2147483647, step: 1 });
        const energy = input('Energy', draft.energy, 'number', { min: 0, max: 2147483647, step: 1 });
        let filter, target;
        if (direct) {
            filter = input('Filter knights');
            target = select('Knight', [], '');
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
            const countLabel = el('small', '', 'muted');
            const recipientPanel = panel(
                'Recipient',
                el(
                    'small',
                    'The letter is written straight to this knight’s inbox; offline knights read it when they return.',
                    'muted'
                ),
                el('div', null, 'form-grid'),
                countLabel
            );
            recipientPanel.classList.add('stack');
            const recipientForm = recipientPanel.querySelector('.form-grid');
            recipientForm.append(label('Filter', filter), label('Knight', target));
            node.append(recipientPanel);
            fill();
        }
        form.append(
            label('Subject', subject),
            label('Sender', sender),
            ...(direct ? [] : [label('Starts', starts), label('Ends', ends)]),
            label('Crowns', crowns),
            label('Energy', energy),
            label('Body', body)
        );
        form.lastElementChild.classList.add('wide');

        let shown = preview(draft.subject, draft.body, draft.sender, draft.crowns, draft.energy);
        const updatePreview = () => {
            shown.replaceWith(
                (shown = preview(
                    subject.value,
                    body.value,
                    sender.value,
                    Number(crowns.value || 0),
                    Number(energy.value || 0)
                ))
            );
        };
        for (const control of [subject, body, sender, crowns, energy])
            control.addEventListener('input', updatePreview);

        function renderGifts() {
            gifts.replaceChildren(
                ...draft.gifts.map((gift, index) => {
                    const quantity = input('Quantity', gift.count, 'number', {
                        min: 1,
                        max: 999,
                        step: 1
                    });
                    const expiry = input('Expires after (days)', gift.expireDays, 'number', {
                        min: 0,
                        max: 3650,
                        step: 1
                    });
                    const bound = input('Bound on delivery', gift.bound, 'checkbox');
                    quantity.oninput = () => {
                        gift.count = Math.max(1, Math.min(999, Number(quantity.value) || 1));
                        updateUnits();
                    };
                    expiry.oninput = () => {
                        gift.expireDays = Math.max(0, Math.min(3650, Number(expiry.value) || 0));
                        updateUnits();
                    };
                    bound.onchange = () => (gift.bound = bound.checked);
                    const row = el('div', null, 'mail-gift');
                    row.append(
                        itemLine(definitions.get(gift.config), { config: gift.config }),
                        label('Quantity', quantity),
                        label('Expires (days)', expiry),
                        label('Bound', bound),
                        button(
                            'Remove',
                            () => {
                                draft.gifts.splice(index, 1);
                                renderGifts();
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
                    (gift) => gift.config === definition.config && !gift.bound && !gift.expireDays
                );
                if (existing) existing.count = Math.min(999, existing.count + 1);
                else draft.gifts.push({ config: definition.config, count: 1, bound: false, expireDays: 0 });
                renderGifts();
            },
            { grantable: true }
        );
        renderGifts();
        const fields = panel(
            'Message',
            el('small', 'Subject and body are shown as written. Up to 50 and 8,096 characters.', 'muted'),
            form,
            shown
        );
        const pickerDetails = el('details');
        pickerDetails.append(el('summary', 'Add a gift item'), picker.node);
        const giftPanel = panel(
            'Gifts',
            el(
                'small',
                'Up to 14 item units. Expiry is counted from delivery; 0 keeps the item permanent.',
                'muted'
            ),
            unitLabel,
            gifts,
            pickerDetails
        );
        node.append(fields, giftPanel);
        openDialog({
            title: direct ? 'Send mail to a knight' : campaign ? 'Edit mailing' : 'New mailing',
            description: direct
                ? 'Delivered immediately to the selected knight, whether they are online or offline.'
                : campaign
                  ? 'Knights who already received this mailing keep the old letter.'
                  : 'Save first, then every knight receives it once while the window is open.',
            content: node,
            notifySaved: false,
            submit: direct ? 'Send mail' : campaign ? 'Save mailing' : 'Create mailing',
            isDirty: () =>
                JSON.stringify([
                    direct ? target.value : '',
                    subject.value,
                    body.value,
                    Number(sender.value),
                    direct ? '' : starts.value,
                    direct ? '' : ends.value,
                    Number(crowns.value || 0),
                    Number(energy.value || 0),
                    draft.gifts
                ]) !==
                JSON.stringify([
                    draft.recipient,
                    draft.subject,
                    draft.body,
                    draft.sender,
                    direct ? '' : localDate(draft.starts),
                    direct ? '' : localDate(draft.ends),
                    draft.crowns,
                    draft.energy,
                    campaign ? campaign.gifts : []
                ]),
            dispose: () => picker.dispose(),
            after: () => refresh(),
            onSubmit: async () => {
                if (!subject.value.trim()) throw new Error('Subject is required.');
                if (!body.value.trim()) throw new Error('Body is required.');
                if (units(draft.gifts) > 14) throw new Error('A mailing can carry at most 14 item units.');
                const values = {
                    sender: Number(sender.value),
                    subject: subject.value,
                    body: body.value,
                    crowns: Math.max(0, Number(crowns.value) || 0),
                    energy: Math.max(0, Number(energy.value) || 0),
                    items: mailingItems(draft.gifts)
                };
                if (direct) {
                    if (!target.value) throw new Error('Choose a knight.');
                    const [username, knight] = target.value.split('|');
                    await api.change('mail-send', { ...values, username, knight });
                    changed(`Mail queued for ${knight}. It is waiting even while they are offline.`);
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
                    changed(campaign ? 'Mailing saved.' : 'Mailing created.');
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
    node.append(
        summary,
        welcomeHost,
        panel(
            'Mailings',
            toolbar(
                button('New mailing', () => compose(null)),
                button('Send to knight', () => compose(null, true), 'secondary')
            ),
            table.node
        )
    );
    return {
        node,
        interval: 30000,
        refresh
    };
}
