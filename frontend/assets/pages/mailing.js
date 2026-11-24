import { api } from '../api.js';
import {
    $,
    el,
    button,
    routeLink,
    input,
    select,
    label,
    toolbar,
    panel,
    badge,
    facts,
    date,
    localDate,
    text,
    changed,
    landOn
} from '../ui.js';
import { DataTable } from '../components/table.js';
import { toast } from '../components/toast.js';
import { openDialog, confirm as confirmAction } from '../components/dialog.js';
import { emptyState, errorState, waiting } from '../components/states.js';
import { itemPicker } from '../components/item-picker.js';
import { currency, itemLine } from '../components/items.js';

const TEMPLATES = [
    {
        id: 'maintenance',
        label: 'Maintenance',
        subject: 'Scheduled maintenance',
        body: 'Haven goes offline for maintenance in one hour.\n\nExpected downtime is 30 minutes. Please finish your runs before then.'
    },
    {
        id: 'compensation',
        label: 'Compensation',
        subject: 'Sorry for the interruption',
        body: 'Haven dropped a few of you mid-run earlier today.\n\nTake this as an apology, and thank you for the reports.'
    },
    {
        id: 'event',
        label: 'Event',
        subject: 'Weekend event is live',
        body: 'From now until Sunday, bonus rewards are active in the Clockworks.\n\nGroup up, go deep, and tell us how it goes.'
    },
    {
        id: 'season',
        label: 'Season start',
        subject: 'New season begins',
        body: 'A new season opens today.\n\nPrestige, gear and rewards reset as usual; your account and inventory stay exactly as they are.'
    }
];
/**
 * The server ships the supported sender ordinals. Only the ordinal the game's own
 * SystemSender names is labelled here; a second invented name would make the
 * preview assert a sender the knight never sees, so the rest show their ordinal.
 */
const SENDER_NAMES = { 6: 'Spiral HQ' };
/** Mirrors of the shipped limits, used only when an older server omits them. */
const FALLBACK_LIMITS = { subject: 50, body: 8096, units: 14, senderLabel: 40 };
/** StaffMail stores both fields behind the game's tainted-literal prefix. */
const UPLINK = '%uplink:~';
/** Local receipts for direct letters; the server keeps its own delivery record. */
const RECEIPTS_KEY = 'tortodrone-mail-receipts',
    RECEIPT_LIMIT = 200;
/** The roster draws at most this many rows, so "Select all shown" means these. */
const ROSTER_ROWS = 200;

const strip = (value) => String(value ?? '').trim();
const senderName = (sender) => SENDER_NAMES[Number(sender)] || `Sender ${sender}`;
const displaySender = (value) =>
    strip(value.senderLabel) ? strip(value.senderLabel) : senderName(value.sender);
const units = (gifts) => gifts.reduce((total, gift) => total + Number(gift.count || 0), 0);
const unitCount = (value) => `${value} unit${value === 1 ? '' : 's'}`;
// One formatter for the page: the app's own `date()` (24-hour, four-digit year),
// so a window never reads 11/20/86 next to a stored row that reads 24/11/2026. A
// value nobody picked yet says so instead of rendering "Invalid Date".
const moment = (value) => (Number.isFinite(value) ? date(value) : 'an unchosen time');
const sentTime = (value) => moment(value);
const countOf = (value, noun) => `${Number(value).toLocaleString()} ${noun}${value === 1 ? '' : 's'}`;
/** A cell that names an entity links to the page that owns it, as a real link. */
const entityLink = (label, name, values) => routeLink(label, name, values);

function loadReceipts() {
    try {
        const rows = JSON.parse(localStorage.getItem(RECEIPTS_KEY) || '[]');
        return Array.isArray(rows) ? rows.filter((row) => row && row.knight) : [];
    } catch {
        // A corrupt or blocked store leaves an empty list, never a broken page.
        return [];
    }
}

function saveReceipts(rows) {
    try {
        localStorage.setItem(RECEIPTS_KEY, JSON.stringify(rows.slice(0, RECEIPT_LIMIT)));
    } catch {
        /* Private browsing keeps the session copy only. */
    }
}

/**
 * dialog.js dresses every 409 as a revision race. A mail refusal is a sentence,
 * so only the sentences that really are about a moved record keep that status.
 */
function normalized(error) {
    const message = strip(error?.message);
    // A dead server must not reach the operator as "Failed to fetch".
    if (!error?.status)
        return new Error(
            'The server did not answer. The change may or may not have been stored, so reopen this record and read its current state before saving again — saving twice sends the letter twice.'
        );
    if (error.status !== 409) return error;
    // Only the sentences that say the record moved earn reload advice, and it is
    // spelled out here instead of letting every 409 read as a revision race.
    if (!/changed|reopen|cancel/i.test(message))
        return new Error(message || 'The server refused this change.');
    return new Error(
        `${message.replace(/[.;]$/, '')}. Close and reopen this record to use its latest revision.`
    );
}

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

/** The letter as the client renders it. `notes` is the reach and timing footer. */
function preview(message, definitions, notes = []) {
    const node = el('div', null, 'mail-preview');
    node.append(
        el('small', 'Uplink message', 'muted'),
        el('strong', strip(message.subject) || 'Subject preview', 'mail-subject'),
        el('p', strip(message.body) || 'Your message will appear here.', 'mail-body'),
        toolbar(
            el('span', displaySender(message), 'muted'),
            ...(message.crowns > 0 ? [currency('crowns', message.crowns)] : []),
            ...(message.energy > 0 ? [currency('energy', message.energy)] : [])
        )
    );
    const gifts = giftsLine(message.gifts || [], definitions);
    if (gifts) node.append(gifts);
    for (const note of notes) node.append(el('small', note, 'muted'));
    return node;
}

/**
 * Exactly what StaffMail writes into `town_mail`, so the operator sees the stored
 * value (literal prefix, negated sender) instead of a projection of it.
 */
function letterText(message) {
    const senderLabel = strip(message.senderLabel);
    const prefix = senderLabel ? '' : UPLINK;
    const lines = [
        senderLabel
            ? `sender: -${message.sender} (the custom label replaces the stock ${senderName(message.sender)})`
            : `sender: -${message.sender} (${senderName(message.sender)})`,
        `sender_label: ${senderLabel || '(none: the stock SystemSender name is shown)'}`,
        'mail_type: 3 (SYSTEM)',
        `subject: ${prefix}${strip(message.subject)}`,
        `body: ${prefix}${strip(message.body)}`,
        `crowns: ${Number(message.crowns) || 0}`,
        `energy: ${Number(message.energy) || 0}`
    ];
    const gifts = message.gifts || [];
    if (!gifts.length) lines.push('attachments: none');
    else {
        lines.push('attachments:');
        for (const gift of gifts)
            lines.push(`  ${gift.count} × ${gift.config}${gift.bound ? ' (bound)' : ''}`);
    }
    return lines.join('\n');
}

function draftOf(mode, campaign, senders) {
    const homeSender = senders.includes(6) || !senders.length ? 6 : senders[senders.length - 1];
    const soon = Math.ceil((Date.now() + 3_600_000) / 900_000) * 900_000;
    if (mode === 'welcome') {
        const welcome = campaign;
        return {
            enabled: welcome.enabled,
            enabledAt: welcome.enabledAt,
            sender: welcome.sender,
            senderLabel: welcome.senderLabel || '',
            subject: welcome.subject || '',
            body: welcome.body || '',
            crowns: welcome.crowns,
            energy: welcome.energy,
            gifts: (welcome.gifts || []).map((gift) => ({ ...gift }))
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
            gifts: (campaign.gifts || []).map((gift) => ({ ...gift }))
        };
    }
    return {
        id: 0,
        revision: 0,
        enabled: false,
        sender: homeSender,
        senderLabel: '',
        subject: '',
        body: '',
        // A mailing is live for its window, so a new one opens on a future window:
        // an accidental save cannot deliver before the operator has read it back.
        starts: soon,
        ends: soon + 30 * 86_400_000,
        crowns: 0,
        energy: 0,
        gifts: []
    };
}

export async function mount(ctx) {
    let data = await ctx.request('mail');
    const limits = { ...FALLBACK_LIMITS, ...(data.limits || {}) },
        senders = Array.isArray(data.senders) && data.senders.length ? data.senders : [4, 5, 6],
        zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'local time',
        definitions = new Map(),
        node = el('div', null, 'stack mail-page'),
        summary = el('div', null, 'stack'),
        welcomeHost = el('div', null, 'stack'),
        receiptsHost = el('div', null, 'stack'),
        // The explanation is worth having and not worth 60px above every list: it opens
        // on request, like any other aside.
        guidance = el('details', null, 'mail-guidance'),
        guidanceText = el(
            'p',
            'A mailing reaches every knight once while its window is open, including knights created later. Delivery happens on their next visit; delivered letters are never duplicated.',
            'muted'
        );
    guidance.append(el('summary', 'How delivery works'), guidanceText);
    let roster = null,
        receipts = loadReceipts();

    const rosterRows = async () => {
        if (!roster) roster = await ctx.request('knights-all');
        return roster;
    };
    const population = () => (roster ? roster.length : null);

    const loadDefinitions = async (campaigns) => {
        const configs = [
            ...new Set(campaigns.flatMap((campaign) => (campaign.gifts || []).map((g) => g.config)))
        ];
        if (data.welcome) configs.push(...(data.welcome.gifts || []).map((gift) => gift.config));
        const missing = [...new Set(configs)].filter((config) => !definitions.has(config));
        await Promise.all(
            missing.map(async (config) => {
                try {
                    definitions.set(config, await ctx.request('item', { config }));
                } catch (error) {
                    // Keep the config in the map so the row can say the definition is
                    // unavailable instead of silently rendering nothing.
                    if (error.name !== 'AbortError')
                        definitions.set(config, { config, unavailable: true });
                }
            })
        );
    };

    const write = async (route, values) => {
        try {
            return await api.change(route, values);
        } catch (error) {
            throw normalized(error);
        }
    };

    function recordReceipt(row) {
        receipts.unshift({ at: Date.now(), ...row });
        receipts = receipts.slice(0, RECEIPT_LIMIT);
        saveReceipts(receipts);
    }

    function deliveredLabel(campaign) {
        const total = population();
        // A server with no knights delivered "0 of 0", which reads as a failure
        // rather than as "there is nobody to deliver to".
        return total === null || total === 0
            ? campaign.delivered.toLocaleString()
            : `${campaign.delivered.toLocaleString()} of ${total.toLocaleString()}`;
    }

    /** One row per knight that received the mailing, straight from the delivery table. */
    async function openDeliveries(campaign) {
        const host = el('div', null, 'stack');
        host.append(waiting('Reading deliveries…'));
        openDialog({
            title: `Recipients · ${strip(campaign.subject) || 'mailing'}`,
            description:
                'One row per knight that received this mailing, oldest first. A knight deleted after delivery keeps its world id.',
            content: host,
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        let rows;
        try {
            rows = await ctx.request('mail-deliveries', { id: campaign.id });
        } catch (error) {
            host.replaceChildren(
                errorState({ error, retry: () => openDeliveries(campaign) })
            );
            return;
        }
        const recipients = rows.recipients || [];
        if (!recipients.length) {
            host.replaceChildren(
                emptyState({
                    title: 'No delivery yet',
                    message:
                        mailingStatus(campaign, data.now) === 'Cancelled'
                            ? 'This mailing was cancelled before anyone received it, and a cancelled mailing never delivers again. Duplicate it to send the same letter.'
                            : mailingStatus(campaign, data.now) === 'Scheduled'
                              ? `Nothing is delivered before ${moment(campaign.starts)}; after that each knight receives it once, on their next login.`
                              : 'Knights receive a mailing on their next login while its window is open. Check the window and status above.',
                    icon: 'mailing'
                })
            );
            return;
        }
        const table = new DataTable(
            [
                [
                    'Knight',
                    (row) =>
                        row.knight
                            ? entityLink(row.knight, 'knights', { username: row.username || '', knight: row.knight })
                            : el('span', 'deleted knight', 'muted'),
                    (row) => row.knight || ''
                ],
                [
                    'Account',
                    (row) =>
                        row.username
                            ? entityLink(row.username, 'accounts', { username: row.username })
                            : el('span', 'unknown account', 'muted'),
                    (row) => row.username || ''
                ],
                ['Sent', (row) => sentTime(row.sent), (row) => row.sent],
                ['World', (row) => String(row.worldId), (row) => row.worldId]
            ],
            {
                // Delivery rows carry no id of their own; the world id is the one
                // identity a knight keeps across a rename or a deletion.
                key: (row) => String(row.worldId),
                caption: 'Knights that received this mailing',
                exportName: `mail-deliveries-${campaign.id}`,
                pageSize: 30,
                persist: false
            }
        );
        table.update(recipients);
        const total = population();
        host.replaceChildren(
            toolbar(
                badge(
                    total === null
                        ? countOf(recipients.length, 'recipient')
                        : `${countOf(recipients.length, 'recipient')} of ${countOf(total, 'knight')}`,
                    'gold'
                ),
                el('span', `${moment(campaign.starts)} → ${moment(campaign.ends)}`, 'muted')
            ),
            table.node
        );
    }

    /** The stored letter, read-only: what a knight actually receives. */
    function openLetter(campaign) {
        const total = population();
        const content = panel(
            '',
            preview(campaign, definitions, [
                total === null || total === 0
                    ? `${campaign.delivered.toLocaleString()} delivered so far`
                    : `${campaign.delivered.toLocaleString()} of ${total.toLocaleString()} knights on this server received it`,
                `Window · ${zone}`
            ]),
            facts([
                ['Status', mailingStatus(campaign, data.now)],
                ['Sender', displaySender(campaign)],
                ['Custom sender', strip(campaign.senderLabel) || 'none; the stock sender name is used'],
                ['Starts', moment(campaign.starts)],
                ['Ends', moment(campaign.ends)],
                ['Crowns', Number(campaign.crowns).toLocaleString()],
                ['Energy', Number(campaign.energy).toLocaleString()],
                [
                    'Gifts',
                    campaign.gifts.length
                        ? `${countOf(campaign.gifts.length, 'stack')}, ${unitCount(units(campaign.gifts))}`
                        : 'none'
                ],
                ['Delivered', deliveredLabel(campaign)],
                ['Created', date(campaign.created)],
                ['Last saved', date(campaign.updated)]
            ]),
            toolbar(
                button(
                    'Edit this mailing',
                    () => compose('campaign', campaign),
                    'secondary',
                    () => campaign.enabled,
                    {
                        showReason: true,
                        unavailable:
                            'Cancelled mailings cannot be edited. Duplicate this one to send it again.'
                    }
                ),
                button('Recipients', () => openDeliveries(campaign), 'secondary'),
                routeLink(
                    'Link to this mailing',
                    'mailing',
                    { campaign: campaign.id },
                    'button quiet'
                ),
                // A cancelled mailing is only useful as a copy, and that was the one
                // action the dialog did not offer.
                button(
                    'Duplicate',
                    () => {
                        document.querySelector('#close-dialog')?.click();
                        duplicate(campaign);
                    },
                    'secondary',
                    () => !campaign.enabled
                )
            )
        );
        openDialog({
            title: strip(campaign.subject) || 'Mail campaign',
            description: campaign.enabled
                ? 'This is the letter as the client shows it from this server. Editing changes what knights who have not received it yet will get.'
                : 'This mailing is cancelled and will not be delivered. It is kept so it can be read or duplicated.',
            content,
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
    }

    const table = new DataTable(
        [
            [
                'Subject',
                (campaign) => {
                    const cell = el('div', null, 'mail-subject-cell');
                    const name = routeLink(campaign.subject, 'mailing', {
                        campaign: campaign.id
                    });
                    name.title = 'Read this letter as the client shows it';
                    cell.append(
                        name,
                        el('small', campaign.body.replace(/\s+/g, ' ').slice(0, 90), 'muted')
                    );
                    return cell;
                },
                (campaign) => campaign.subject
            ],
            ['Sender', displaySender, displaySender],
            [
                // One zone for the whole column, instead of a suffix per cell that
                // the letter dialog repeats differently.
                `Delivery window · ${zone}`,
                (campaign) => {
                    const cell = el('div', null, 'mail-window');
                    cell.append(
                        el('span', moment(campaign.starts)),
                        el('span', '→', 'muted'),
                        el('span', moment(campaign.ends))
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
                (campaign) => {
                    const label = deliveredLabel(campaign);
                    // Zero reads neutral and the Inspect action still opens the
                    // roster; a delivered count is the drill-down.
                    if (!campaign.delivered) return el('span', label, 'muted');
                    return button(label, () => openDeliveries(campaign), 'link');
                },
                (campaign) => campaign.delivered
            ],
            [
                'Crowns',
                // One column, one number: energy is beside it in the letter dialog, and
                // two stacked currencies made every row taller than the rest of it.
                (campaign) => currency('crowns', campaign.crowns),
                (campaign) => campaign.crowns
            ]
        ],
        {
            id: 'mail-campaigns',
            address: true,
            caption: 'Mail campaigns',
            exportName: 'mail-campaigns',
            // An empty list says what a mailing is and where knights come from,
            // instead of guessing that it fills on its own.
            empty: {
                title: 'No mailings yet',
                hint: 'A mailing is a letter every knight receives once while its window is open. Knights are created in the game client, and a knight created later still receives an open mailing.',
                icon: 'mailing'
            },
            // The delivered cell reads "42 of N", so the population is part of what
            // a row shows and must re-render when the roster is counted.
            presentationKey: () => String(population()),
            actions: (get) => [
                button(
                    'Edit',
                    () => compose('campaign', get()),
                    'secondary',
                    () => get().enabled,
                    {
                        // The note appears when the row is hovered or focused: a
                        // permanent caption turns one empty row 305px tall and clips
                        // the buttons beside it.
                        showReason: true,
                        unavailable:
                            'Cancelled mailings cannot be edited. Duplicate it to send it again.'
                    }
                ),
                button('Recipients', () => openDeliveries(get()), 'secondary'),
                button('Duplicate', () => duplicate(get()), 'secondary'),
                button(
                    'Cancel',
                    async () => {
                        try {
                            await confirmAction(
                                'Cancel mailing · ' + get().subject,
                                'mail-cancel',
                                { id: get().id, revision: get().updated },
                                [],
                                'Knights who already received this mailing keep it. No further deliveries will be made.'
                            );
                        } catch (error) {
                            throw normalized(error);
                        }
                    },
                    'secondary',
                    () => get().enabled,
                    { showReason: true, unavailable: 'This mailing is already cancelled.' }
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

    /** A copy is a new mailing that delivers again, so say so and open a future window. */
    function duplicate(source) {
        const soon = Math.ceil((Date.now() + 3_600_000) / 900_000) * 900_000;
        compose(
            'campaign',
            {
                ...source,
                id: 0,
                created: soon,
                updated: 0,
                delivered: 0,
                enabled: true,
                subject: `${source.subject} (copy)`.slice(0, limits.subject),
                starts: soon,
                ends: soon + 7 * 86_400_000
            },
            {
                banner: `This is a new mailing, not a resumption. ${
                    source.delivered
                        ? `Saving it delivers again, including to the ${countOf(source.delivered, 'knight')} that already received the original. `
                        : ''
                }The window below starts in the future, so nothing is delivered until it opens.`
            }
        );
    }

    function renderSummary() {
        const counts = { Active: 0, Scheduled: 0, Ended: 0, Cancelled: 0 };
        let delivered = 0;
        for (const campaign of data.campaigns) {
            counts[mailingStatus(campaign, data.now)]++;
            delivered += campaign.delivered;
        }
        const total = population();
        const countKnights = button(
            'Retry the knight count',
            async () => {
                countKnights.setBusy(true);
                try {
                    roster = null;
                    await rosterRows();
                } finally {
                    countKnights.setBusy(false);
                }
                if (ctx.alive()) {
                    renderSummary();
                    table.update(data.campaigns);
                }
            },
            'quiet'
        );
        countKnights.title = 'The roster read failed; this retries it once.';
        const content = [
            guidance,
            toolbar(
                ...Object.entries(counts).map(([name, count]) =>
                    badge(
                        `${count} ${name.toLowerCase()}`,
                        name === 'Active' && count > 0 ? 'good' : ''
                    )
                ),
                badge(
                    total === null
                        ? `${countOf(delivered, 'letter')} delivered`
                        : delivered === 0
                          ? 'Nothing delivered yet'
                          : `${delivered.toLocaleString()} of ${countOf(total, 'knight')} reached`,
                    delivered ? 'gold' : ''
                ),
                ...(roster ? [] : [countKnights])
            )
        ];
        if (roster && !roster.length)
            content.push(
                el(
                    'p',
                    'No knights exist on this server yet, so a mailing saved now delivers to nobody until a knight is created in the game client.',
                    'muted'
                )
            );
        const summaryPanel = panel('Delivery status', ...content);
        summaryPanel.classList.add('stack');
        summary.replaceChildren(summaryPanel);
    }

    function renderReceipts() {
        const header = toolbar();
        if (receipts.length)
            header.append(
                button(
                    'Clear',
                    () =>
                        confirmAction({
                            title: `Clear ${countOf(receipts.length, 'local receipt')}?`,
                            description:
                                'These receipts are stored in this browser. The delivery counts on this page come from the server and are not affected.',
                            label: 'Clear receipts',
                            variant: 'danger',
                            onSubmit: async () => {
                                receipts = [];
                                saveReceipts(receipts);
                                renderReceipts();
                            }
                        }),
                    'secondary'
                ),
                routeLink('Link to this section', 'mailing', { tab: 'receipts' }, 'link')
            );
        else
            header.append(
                routeLink('Link to this section', 'mailing', { tab: 'receipts' }, 'link')
            );
        const content = [
            el(
                'small',
                'Letters sent directly from this browser, newest first, kept in this browser only. A mailing’s own deliveries are counted from the server and listed under Recipients.',
                'muted'
            )
        ];
        if (!receipts.length)
            content.push(
                el(
                    'p',
                    'Nothing sent from this browser yet. A direct letter, a batch send or a test copy adds a receipt here.',
                    'empty'
                )
            );
        else {
            const table = new DataTable(
                [
                    [
                        'Knight',
                        (row) =>
                            row.knight
                                ? entityLink(row.knight, 'knights', { username: row.username || '', knight: row.knight })
                                : el('span', 'deleted knight', 'muted')
                    ],
                    [
                        'Account',
                        (row) =>
                            row.username
                                ? entityLink(row.username, 'accounts', { username: row.username })
                                : el('span', 'unknown account', 'muted')
                    ],
                    ['Subject', (row) => row.subject],
                    ['Sent', (row) => date(row.at), (row) => row.at],
                    [
                        'Result',
                        (row) => {
                            // The receipt keeps the reason, so a refusal is still
                            // explainable after the toast is gone.
                            const cell = el('div', null, 'mail-send-result');
                            cell.append(
                                badge(row.ok ? 'Delivered' : 'Refused', row.ok ? 'good' : 'bad'),
                                el('small', row.ok ? 'in the inbox' : row.reason || 'refused', 'muted')
                            );
                            return cell;
                        },
                        (row) => (row.ok ? 'Delivered' : 'Refused ' + (row.reason || ''))
                    ]
                ],
                {
                    key: (row) => String(row.at) + row.knight,
                    id: 'mail-receipts',
                    caption: 'Direct mail receipts',
                    search: false,
                    persist: false,
                    pageSize: 10
                }
            );
            table.update(receipts);
            content.push(table.node);
        }
        receiptsHost.replaceChildren(panel('Delivery receipts', header, ...content));
    }


    /** One row per knight that received the welcome letter. */
    async function openWelcomeDeliveries() {
        const host = el('div', null, 'stack');
        host.append(waiting('Reading deliveries…'));
        openDialog({
            title: 'Recipients · welcome mail',
            description:
                'One row per knight that received the welcome letter automatically, oldest first.',
            content: host,
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        });
        let rows;
        try {
            rows = await ctx.request('mail-welcome-deliveries');
        } catch (error) {
            host.replaceChildren(
                el('p', error.message || 'The delivery roster is unavailable.', 'error')
            );
            return;
        }
        const recipients = rows.recipients || [];
        if (!recipients.length) {
            host.replaceChildren(
                emptyState({
                    title: 'No delivery yet',
                    message:
                        'A knight created after the letter was enabled receives it on their first visit.',
                    icon: 'mailing'
                })
            );
            return;
        }
        const table = new DataTable(
            [
                [
                    'Knight',
                    (row) =>
                        row.knight
                            ? entityLink(row.knight, 'knights', { username: row.username || '', knight: row.knight })
                            : el('span', 'deleted knight', 'muted'),
                    (row) => row.knight || ''
                ],
                [
                    'Account',
                    (row) =>
                        row.username
                            ? entityLink(row.username, 'accounts', { username: row.username })
                            : el('span', 'unknown account', 'muted'),
                    (row) => row.username || ''
                ],
                ['Sent', (row) => sentTime(row.sent), (row) => row.sent]
            ],
            {
                key: (row) => String(row.worldId),
                caption: 'Knights that received the welcome letter',
                exportName: 'mail-welcome-deliveries',
                pageSize: 25
            }
        );
        table.update(recipients);
        host.replaceChildren(table.node);
    }

    const welcomeDetails = el('details');
    const welcomeSummary = el('summary');
    welcomeSummary.append(routeLink('Link to this section', 'mailing', { tab: 'welcome' }, 'link'));
    function renderWelcome() {
        const welcome = data.welcome;
        text(
            welcomeSummary,
            'Welcome mail · ' +
                (welcome.enabled
                    ? `on since ${date(welcome.enabledAt)}`
                    : welcome.enabledAt
                      ? `off${welcome.disabledAt ? ` since ${date(welcome.disabledAt)}` : ''}`
                      : 'never sent')
        );
        const status = welcome.enabled
            ? `Knights created after ${date(welcome.enabledAt)} receive it once, on their first visit.`
            : welcome.enabledAt
              ? `Knights created while it was enabled still receive it; knights created after ${
                    welcome.disabledAt ? date(welcome.disabledAt) : 'it was switched off'
                } do not.`
              : 'Knights created now receive no automatic mail.';
        const content = [el('small', status, 'muted')];
        const gifts = welcome.gifts || [];
        if (strip(welcome.subject) || strip(welcome.body) || gifts.length)
            content.push(preview({ ...welcome, gifts }, definitions));
        content.push(
            toolbar(
                button('Edit welcome mail', () => compose('welcome', welcome), 'secondary'),
                // The welcome letter records its deliveries too; a count nobody can
                // open is the same hole the campaigns had.
                ...(welcome.enabledAt
                    ? [
                          button(
                              'Who received it',
                              () => openWelcomeDeliveries(),
                              'secondary'
                          )
                      ]
                    : [])
            )
        );
        const card = panel('', ...content);
        card.classList.add('stack');
        welcomeHost.replaceChildren(card);
    }

    async function compose(mode, record = null, options = {}) {
        if (mode === 'direct') roster = null;
        // The composer states how many knights a campaign reaches, so the roster is
        // loaded before it opens instead of pointing at a count on another panel.
        if (!roster) await rosterRows().catch(() => null);
        const rows = mode === 'direct' ? roster || [] : [];
        if (mode === 'direct' && !rows.length) {
            // A server with no knights is the normal dev state: say so instead of
            // opening a composer that cannot send to anybody.
            openDialog({
                title: 'Send mail to a knight',
                content: emptyState({
                    title: 'No knights exist yet',
                    message:
                        'Knights are created in the game client. Once one exists, its name appears here and you can write to it.',
                    icon: 'knights'
                }),
                submit: 'Close',
                notifySaved: false,
                onSubmit: async () => {}
            });
            return;
        }
        const draft = draftOf(mode, record, senders);
        const node = el('div', null, 'stack'),
            header = el('div', null, 'form-grid'),
            gifts = el('div', null, 'stack'),
            unitLabel = el('small', '', 'muted'),
            results = el('div', null, 'stack'),
            chosen = new Set(),
            batch = new Map();
        let attempted = [];
        const enabled =
            mode === 'welcome' ? input('Enable welcome mail', draft.enabled, 'checkbox') : null;
        const subject = input('Subject', draft.subject);
        const subjectCount = el('small', '', 'muted');
        subjectCount.id = 'mail-subject-count';
        subject.setAttribute('aria-describedby', subjectCount.id);
        const body = input('Body', draft.body, 'textarea');
        body.rows = 12;
        const bodyCount = el('small', '', 'muted');
        bodyCount.id = 'mail-body-count';
        body.setAttribute('aria-describedby', bodyCount.id);
        // A counter is a description, not part of the field's name, so it sits beside
        // the label instead of inside it (inside, it joined the accessible name).
        const bodyField = el('div', null, 'mail-field');
        const sender = select(
            'Sender',
            senders.map((id) => [String(id), senderName(id)]),
            String(draft.sender)
        );
        const customSender = input('Custom sender (optional)', draft.senderLabel);
        customSender.placeholder = 'Overrides the stock sender name';
        const senderCount = el('small', '', 'muted');
        senderCount.id = 'mail-sender-count';
        customSender.setAttribute('aria-describedby', senderCount.id);
        const starts =
            mode === 'campaign' ? input('Starts', localDate(draft.starts), 'datetime-local') : null;
        const ends =
            mode === 'campaign' ? input('Ends', localDate(draft.ends), 'datetime-local') : null;
        const crowns = input('Crowns', draft.crowns, 'number', { min: 0, max: 2147483647, step: 1 });
        const energy = input('Energy', draft.energy, 'number', { min: 0, max: 2147483647, step: 1 });

        // The server refuses text over the shipped limits, so the counter says the
        // same number and turns red instead of the field silently truncating.
        const syncCounter = (control, counter, limit) => {
            const length = control.value.length;
            text(counter, `${length.toLocaleString()} / ${limit.toLocaleString()} characters`);
            counter.classList.toggle('error', length > limit);
        };
        const counters = () => {
            syncCounter(subject, subjectCount, limits.subject);
            syncCounter(body, bodyCount, limits.body);
            syncCounter(customSender, senderCount, limits.senderLabel);
        };
        for (const control of [subject, body, customSender])
            control.addEventListener('input', counters);
        counters();

        const keyOf = (row) => `${row.username}|${row.name}`;
        function currentMessage() {
            return {
                sender: Number(sender.value),
                senderLabel: customSender.value,
                subject: subject.value,
                body: body.value,
                crowns: Number(crowns.value || 0),
                energy: Number(energy.value || 0),
                gifts: draft.gifts
            };
        }
        function letterValues() {
            const message = currentMessage();
            return {
                sender: message.sender,
                senderLabel: message.senderLabel,
                subject: message.subject,
                body: message.body,
                crowns: message.crowns,
                energy: message.energy,
                items: mailingItems(draft.gifts)
            };
        }
        /** Client mirrors of the server's own sentences, so a refusal is not a round trip. */
        // A control character (a U+0092 pasted from a Windows-1252 source, say) is
        // invisible in the field and the server refuses the whole letter for it, so
        // the composer counts and strips it before saving.
        const CONTROL = /[\u0000-\u001f\u007f-\u009f]/g;
        function cleanField(control, fieldName) {
            const raw = control.value;
            const found = raw.match(CONTROL);
            if (!found) return;
            control.value = raw.replace(CONTROL, '');
            refreshPreview();
            throw new Error(
                `${fieldName} contained ${found.length === 1 ? 'a character' : `${found.length} characters`} the game cannot display. ${found.length === 1 ? 'It has' : 'They have'} been removed; check the text and save again.`
            );
        }
        function checkLetter() {
            const requiresText = mode !== 'welcome' || enabled.checked || draft.enabledAt;
            cleanField(subject, 'Subject');
            cleanField(body, 'Body');
            cleanField(customSender, 'Sender name');
            for (const [control, name] of [
                [subject, 'Subject'],
                [body, 'Body'],
                [customSender, 'Sender name']
            ])
                if (control.value.startsWith(' '))
                    throw new Error(`${name} must not start with a space`);
            if (mode === 'campaign') {
                const start = new Date(starts.value).getTime(),
                    end = new Date(ends.value).getTime();
                // The server caps a window at ten years; the pickers do not.
                if (Number.isFinite(start) && Number.isFinite(end) && end - start > 3652 * 86_400_000)
                    throw new Error('The delivery window is too long');
            }
            if (requiresText && !strip(subject.value)) throw new Error('Subject is required');
            if (requiresText && !strip(body.value)) throw new Error('Body is required');
            if (strip(subject.value).length > limits.subject)
                throw new Error(`Subject must be at most ${limits.subject} characters`);
            if (strip(body.value).length > limits.body)
                throw new Error(
                    mode === 'welcome'
                        ? 'Welcome mail is too long'
                        : `Body must be at most ${limits.body} characters`
                );
            if (strip(customSender.value).length > limits.senderLabel)
                throw new Error(`Sender name must be at most ${limits.senderLabel} characters`);
            if (Number(crowns.value) < 0 || Number(energy.value) < 0)
                throw new Error('Crowns and energy cannot be negative');
            if (draft.gifts.length > limits.units)
                throw new Error(`Add at most ${limits.units} gift stacks`);
            if (units(draft.gifts) > limits.units)
                throw new Error(`A mailing can carry at most ${limits.units} item units`);
        }

        /** Delivers to `keys`, never rewriting a knight that already succeeded here. */
        async function send(keys, values, label) {
            const outcome = { sent: 0, refused: [], stopped: null };
            for (const [index, key] of keys.entries()) {
                // Resume and retry share this loop: a knight that already has the
                // letter in this batch is skipped, never written twice.
                if (batch.get(key)?.ok === true) continue;
                const line = $('#dialog-progress');
                if (line) {
                    line.hidden = false;
                    text(line, `${label} ${index + 1} of ${keys.length}…`);
                }
                const [username, knight] = key.split('|');
                try {
                    await write('mail-send', { ...values, username, knight });
                    batch.set(key, { ok: true });
                    outcome.sent++;
                    recordReceipt({ knight, username, subject: strip(values.subject), ok: true });
                } catch (error) {
                    const reason = error.message || 'refused';
                    batch.set(key, { ok: false, reason });
                    recordReceipt({
                        knight,
                        username,
                        subject: strip(values.subject),
                        ok: false,
                        reason
                    });
                    // "No such knight" is about this one recipient; anything else is
                    // the letter, which would be refused for everybody.
                    if (error.status === 404 || /no such knight|unknown knight/i.test(reason))
                        outcome.refused.push({ key, knight, username, reason });
                    else {
                        outcome.stopped = error;
                        break;
                    }
                }
            }
            return outcome;
        }

        function renderResults() {
            results.replaceChildren();
            if (!attempted.length) return;
            const entry = (key) => batch.get(key);
            const sent = attempted.filter((key) => entry(key)?.ok === true),
                refused = attempted.filter((key) => entry(key)?.ok === false),
                pending = attempted.filter((key) => !entry(key));
            const list = el('div', null, 'stack');
            for (const key of attempted) {
                const state = entry(key),
                    [username, knight] = key.split('|');
                const row = el('div', null, 'mail-send-result');
                row.append(
                    el('strong', knight),
                    el('small', username, 'muted'),
                    state
                        ? badge(state.ok ? 'Delivered' : 'Refused', state.ok ? 'good' : 'bad')
                        : badge('Not sent', ''),
                    ...(state?.reason ? [el('small', state.reason, 'muted')] : [])
                );
                list.append(row);
            }
            const actions = toolbar();
            if (refused.length)
                actions.append(
                    button(`Retry refused (${refused.length})`, () => retry(refused), 'secondary')
                );
            if (pending.length)
                actions.append(
                    button(`Resume remaining (${pending.length})`, () => retry(pending), 'secondary')
                );
            results.append(
                panel(
                    'This batch',
                    el(
                        'small',
                        `${sent.length} delivered · ${refused.length} refused · ${pending.length} not sent. A knight that already succeeded is never written twice.`,
                        'muted'
                    ),
                    list,
                    actions
                )
            );
        }

        async function retry(keys) {
            const outcome = await send(keys, letterValues(), 'Sending');
            attempted = [...new Set([...attempted, ...keys])];
            renderResults();
            renderReceipts();
            if (outcome.stopped)
                toast(outcome.stopped.message || 'That send was refused.', { type: 'error' });
            else if (outcome.sent < keys.length)
                toast(`${outcome.sent} of ${keys.length} delivered; the batch list keeps each refusal.`, {
                    type: 'error'
                });
            else toast(`All ${keys.length} delivered.`, { type: 'success' });
        }

        if (mode === 'direct') {
            const filter = input('Filter knights');
            const target = el('div', null, 'mail-recipients');
            target.setAttribute('role', 'group');
            target.setAttribute('aria-label', 'Recipients');
            const countLabel = el('small', '', 'muted');
            countLabel.setAttribute('role', 'status');
            countLabel.setAttribute('aria-live', 'polite');
            const tools = toolbar();
            const view = { online: false, master: false };
            let shownRows = [];
            const visibleRows = () => {
                const query = filter.value.trim().toLowerCase();
                return rows.filter(
                    (row) =>
                        (!view.online || row.online) &&
                        (!view.master || row.gameMaster) &&
                        `${row.name} ${row.username}`.toLowerCase().includes(query)
                );
            };
            const syncCount = () => {
                text(
                    countLabel,
                    chosen.size
                        ? `${chosen.size.toLocaleString()} of ${countOf(rows.length, 'knight')} selected`
                        : `Choose at least one knight · ${countOf(rows.length, 'knight')} on this server`
                );
                // Red before the first click taught people to ignore red: the count
                // is neutral until a send actually needs a recipient and has none.
                countLabel.classList.toggle('error', chosen.size === 0 && attemptedSend);
            };
            const fill = () => {
                const matches = visibleRows();
                shownRows = matches.slice(0, ROSTER_ROWS);
                target.replaceChildren(
                    ...shownRows.map((row) => {
                        const key = keyOf(row);
                        const box = input(`Send to ${row.name}`, chosen.has(key), 'checkbox');
                        const name = el('span', null, 'mail-recipient-name');
                        name.append(
                            el('strong', row.name),
                            el(
                                'small',
                                `${row.username} · ${row.online ? 'online' : 'offline'}${
                                    row.gameMaster ? ' · game master' : ''
                                }`,
                                'muted'
                            )
                        );
                        const rowLabel = el('label', null, 'mail-recipient');
                        box.onchange = () => {
                            if (box.checked) chosen.add(key);
                            else chosen.delete(key);
                            syncCount();
                        };
                        rowLabel.append(box, name);
                        return rowLabel;
                    })
                );
                if (!matches.length)
                    target.append(
                        el(
                            'p',
                            rows.length
                                ? 'No knight matches this view; clear a filter to see more.'
                                : 'No knights on this server yet.',
                            'muted'
                        )
                    );
                else if (matches.length > shownRows.length)
                    target.append(
                        el(
                            'p',
                            `${countOf(
                                matches.length - shownRows.length,
                                'more knight'
                            )} hidden; “Select all shown” takes only the ${shownRows.length.toLocaleString()} above.`,
                            'muted'
                        )
                    );
                tools.replaceChildren(
                    button(
                        'Select all shown',
                        () => {
                            for (const row of shownRows) chosen.add(keyOf(row));
                            fill();
                        },
                        'quiet',
                        () => shownRows.length > 0
                    ),
                    ...(matches.length > shownRows.length
                        ? [
                              button(
                                  `Select all ${matches.length.toLocaleString()} matching`,
                                  () => {
                                      for (const row of matches) chosen.add(keyOf(row));
                                      fill();
                                  },
                                  'quiet'
                              )
                          ]
                        : []),
                    button(
                        'Clear',
                        () => {
                            chosen.clear();
                            fill();
                        },
                        'quiet',
                        () => chosen.size > 0
                    )
                );
                syncCount();
            };
            const toggle = (name, key) => {
                const node = button(
                    name,
                    () => {
                        view[key] = !view[key];
                        node.setAttribute('aria-pressed', String(view[key]));
                        fill();
                    },
                    'secondary'
                );
                node.setAttribute('aria-pressed', String(view[key]));
                node.classList.add('sm');
                return node;
            };
            filter.oninput = fill;

            // A proof read: the operator's own knight first, so the letter can be
            // checked in the client before it reaches anybody else.
            const mine = rows.filter((row) => row.username === api.session?.username);
            const pick =
                mine.length > 1
                    ? select(
                          'Copy to',
                          mine.map((row, index) => [String(index), row.name]),
                          '0'
                      )
                    : null;
            const myKnight = () => (pick ? mine[Number(pick.value)] || mine[0] : mine[0]);
            const copy = button(
                'Send a copy to me',
                async () => {
                    const knight = myKnight();
                    copy.setBusy(true);
                    try {
                        checkLetter();
                        await write('mail-send', {
                            ...letterValues(),
                            username: knight.username,
                            knight: knight.name
                        });
                        recordReceipt({
                            knight: knight.name,
                            username: knight.username,
                            subject: strip(subject.value),
                            ok: true
                        });
                        renderReceipts();
                        toast(`A copy is in ${knight.name}’s inbox; open the client to read it.`, {
                            type: 'success'
                        });
                    } catch (error) {
                        toast(error.message || 'The copy was refused.', { type: 'error' });
                    } finally {
                        copy.setBusy(false);
                    }
                },
                'secondary',
                () => mine.length > 0
            );
            copy.title = mine.length
                ? 'Sends this exact letter to your own knight first, as a proof read in the client.'
                : 'This account has no knight, so there is no inbox to test with. Create a knight in the game client.';
            const recipientPanel = panel(
                'To',
                el(
                    'small',
                    'Written straight to each knight’s inbox; offline knights read it when they return. This is not a mailing, so it is sent once.',
                    'muted'
                ),
                el('div', null, 'form-grid'),
                toolbar(
                    toggle('Online only', 'online'),
                    toggle('Game masters', 'master'),
                    el('span', null, 'spacer')
                ),
                target,
                tools,
                countLabel,
                toolbar(
                    copy,
                    ...(pick ? [pick] : []),
                    el(
                        'small',
                        mine.length
                            ? `Copies go to ${mine.map((row) => row.name).join(', ')}.`
                            : 'No knight on this account, so the copy is unavailable.',
                        'muted'
                    )
                )
            );
            recipientPanel.classList.add('stack');
            recipientPanel.querySelector('.form-grid').append(label('Filter', filter));
            fill();
            node.append(recipientPanel);
        }

        // Set when a dangerous submit asked for a second press, cleared by any edit.
    let before = '';
    const armReset = () => {
        if (before) before = '';
    };
    // Any edit withdraws an armed confirmation, so the second press sends what the
    // operator is looking at rather than what they confirmed. `attemptedSend` keeps the
    // recipient count neutral until a send has actually been tried.
    let attemptedSend = false;
    const messagePanel = panel(
            'Message',
            el('div', null, 'mail-compose-split'),
            el(
                'small',
                'Leading and trailing spaces are removed on save, so both views below show the stripped text the client receives.',
                'muted'
            )
        );
        const split = messagePanel.querySelector('.mail-compose-split');
        const plain = el('pre', null, 'mail-plain');
        const gameHost = el('div', null, 'mail-preview-host');
        const notesHost = el('div', null, 'stack');
        const viewTabs = toolbar(
            button('In-game', () => showPreview('game'), 'quiet'),
            button('Stored text', () => showPreview('plain'), 'quiet')
        );
        let previewMode = 'game';
        const previewNotes = () => {
            const notes = [];
            if (subject.value !== strip(subject.value) || body.value !== strip(body.value))
                notes.push('Leading and trailing spaces are removed on save.');
            if (mode === 'campaign') {
                const start = new Date(starts.value).getTime(),
                    end = new Date(ends.value).getTime();
                const knights = population();
                notes.push(
                    (knights === null
                        ? 'Reaches every knight on this server once, including knights created later; the knight count is unavailable.'
                        : knights === 0
                          ? 'Reaches nobody yet: no knight exists on this server. A knight created while the window is open still receives it.'
                          : `Reaches every knight on this server once, including knights created later (${countOf(
                                knights,
                                'knight'
                            )} exist right now).`)
                );
            } else if (mode === 'welcome')
                notes.push('Sent once, on the first visit of a knight created while it is enabled.');
            else notes.push('Sent once to each chosen knight, online or offline.');
            return notes;
        };
        function showPreview(view) {
            previewMode = view;
            for (const [index, tab] of [...viewTabs.querySelectorAll('button')].entries())
                tab.setAttribute('aria-pressed', String(index === (view === 'game' ? 0 : 1)));
            gameHost.hidden = view !== 'game';
            plain.hidden = view !== 'plain';
            if (view === 'plain') plain.textContent = letterText(currentMessage());
        }
        const refreshPreview = () => {
            gameHost.replaceChildren(preview(currentMessage(), definitions));
            notesHost.replaceChildren(
                ...previewNotes().map((note) => el('small', note, 'muted'))
            );
            if (previewMode === 'plain') showPreview('plain');
        };
        for (const control of [subject, body, sender, customSender, crowns, energy, starts, ends])
            if (control) control.addEventListener('input', refreshPreview);
        refreshPreview();
        const previewPanel = el('div', null, 'stack');
        // The same letter as the client renders it and as stored text, because the
        // in-game view normalises whitespace and the stored row carries the prefix.
        previewPanel.append(viewTabs, gameHost, plain, notesHost);
        plain.hidden = true;
        previewPanel.querySelectorAll('button').forEach((tab) => tab.classList.add('sm'));
        showPreview('game');
        bodyField.append(label('Body', body), bodyCount);
        split.append(bodyField, label('Preview', previewPanel));

        const pickerDetails = el('details');
        const attachments = panel(
            'Attachments',
            el('div', null, 'form-grid'),
            el(
                'small',
                `Up to ${limits.units} item units. Gifts stay in the inbox until claimed; stock item expiry applies after claiming.`,
                'muted'
            ),
            unitLabel,
            gifts,
            pickerDetails
        );
        const extras = attachments.querySelector('.form-grid');
        extras.append(label('Crowns', crowns), label('Energy', energy));

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
                `${countOf(draft.gifts.length, 'gift stack')} · ${total} of ${limits.units} units`
            );
            unitLabel.classList.toggle('error', total > limits.units);
        }
        const picker = itemPicker(
            (definition) => {
                definitions.set(definition.config, definition);
                const existing = draft.gifts.find(
                    (gift) => gift.config === definition.config && !gift.bound
                );
                if (existing) existing.count = Math.min(999, existing.count + 1);
                else draft.gifts.push({ config: definition.config, count: 1, bound: false });
                renderGifts();
                refreshPreview();
            },
            { grantable: true }
        );
        renderGifts();
        pickerDetails.append(el('summary', 'Add a gift item'), picker.node);

        // Templates fill the subject and body in one click; the preview follows.
        const templateRow = toolbar(
            el('small', 'Start from', 'muted'),
            ...TEMPLATES.map((template) =>
                button(
                    template.label,
                    () => {
                        if (
                            strip(body.value) &&
                            strip(body.value) !== strip(template.body) &&
                            !window.confirm(
                                'Replace the current subject and body with this template?'
                            )
                        )
                            return;
                        subject.value = template.subject;
                        body.value = template.body;
                        counters();
                        refreshPreview();
                    },
                    'secondary'
                )
            )
        );
        templateRow.classList.add('mail-templates');
        templateRow.querySelectorAll('button').forEach((item) => item.classList.add('sm'));
        messagePanel.prepend(templateRow);
        messagePanel.classList.add('stack');
        attachments.classList.add('stack');

        const enabledRow = mode === 'welcome' ? toolbar(label('Enabled', enabled)) : null;
        const subjectField = el('div', null, 'wide mail-field');
        subjectField.append(label('Subject', subject), subjectCount);
        if (mode === 'campaign') header.append(label('Starts', starts), label('Ends', ends));
        header.append(
            label('From', sender),
            (() => {
                const wrap = el('div', null, 'mail-field');
                wrap.append(label('Custom sender', customSender), senderCount);
                return wrap;
            })(),
            subjectField
        );
        if (mode === 'campaign') {
            // The store has no draft flag: a saved mailing is live for its window, so
            // a window that contains now delivers on save and needs saying out loud.
            const armNote = el('p', '', 'warning');
            armNote.classList.add('wide');
            const syncArm = () => {
                const start = new Date(starts.value).getTime();
                text(
                    armNote,
                    Number.isFinite(start)
                        ? `Every knight receives this once while the window is open.${
                              start <= Date.now()
                                  ? ' The window has already started, so saving delivers on their next visit.'
                                  : ` Nothing is delivered before ${date(start)}.`
                          }`
                        : 'Choose a start and end; saving arms the mailing for that window.'
                );
            };
            starts.addEventListener('input', syncArm);
            syncArm();
            // The window has three shapes an operator asks for by name, and picking one
            // only fills the fields: the old button saved a thirty-day mailing behind a
            // click that said "+1 hour".
            const preset = (label, startAt, endAt, hint) => {
                const node = button(
                    label,
                    () => {
                        starts.value = localDate(startAt);
                        ends.value = localDate(endAt);
                        syncArm();
                        refreshPreview();
                    },
                    'secondary'
                );
                node.title = hint;
                return node;
            };
            const hour = 3_600_000,
                day = 86_400_000,
                soon = Math.ceil((Date.now() + hour) / 900_000) * 900_000;
            header.append(
                armNote,
                toolbar(
                    preset('Start now', Date.now(), Date.now() + 7 * day, 'A window that is open now: saving delivers it to every knight on their next visit, for a week.'),
                    preset('Start in 1 hour', soon, soon + day, 'A window that opens in an hour and closes a day later; nothing is delivered before it opens.'),
                    preset('Start tomorrow', Date.now() + day, Date.now() + 8 * day, 'A window that opens tomorrow and closes a week later.'),
                    el('small', 'These fill the window; nothing is delivered until you save.', 'muted')
                )
            );
        }
        if (options.banner) node.append(el('p', options.banner, 'warning'));
        if (enabledRow) node.append(enabledRow);
        node.append(header, messagePanel, attachments, results);
        // Any edit withdraws an armed confirmation and lets the recipient count be
        // judged again from a clean state.
        node.addEventListener('input', armReset);
        node.addEventListener('change', armReset);

        function snapshot() {
            return [
                mode === 'welcome' ? enabled.checked : '',
                mode === 'direct' ? [...chosen].sort().join(',') : '',
                subject.value,
                body.value,
                Number(sender.value),
                strip(customSender.value),
                starts?.value || '',
                ends?.value || '',
                Number(crowns.value || 0),
                Number(energy.value || 0),
                draft.gifts
            ];
        }
        const initial = JSON.stringify(snapshot());
        openDialog({
            title:
                mode === 'welcome'
                    ? 'Welcome mail'
                    : mode === 'direct'
                      ? 'Send mail to a knight'
                      : record?.id
                        ? 'Edit mailing'
                        : record
                          ? 'Duplicate mailing'
                          : 'New mailing',
            description:
                mode === 'welcome'
                    ? 'Sent once to knights created while it is enabled.'
                    : mode === 'direct'
                      ? 'Delivered immediately to each selected knight, whether they are online or offline.'
                      : record?.id
                        ? 'Knights who already received this mailing keep the old letter.'
                        : 'Saving arms the mailing for the window below; every knight receives it once while that window is open.',
            content: node,
            notifySaved: false,
            submit:
                mode === 'welcome'
                    ? 'Save welcome mail'
                    : mode === 'direct'
                      ? 'Send mail'
                      : record?.id
                        ? 'Save mailing'
                        : 'Create mailing',
            isDirty: () => JSON.stringify(snapshot()) !== initial,
            dispose: () => picker.dispose(),
            after: () => refresh(),
            onSubmit: async () => {
                checkLetter();
                const values = letterValues();
                // A confirmation the operator can read, inside the editor: pressing Save
                // a second time is the confirmation, so nothing is lost to a native
                // dialog and nothing is written on the first press.
                const confirmAgain = (message) => {
                    throw new Error(message + ' Press the same button again to confirm.');
                };
                if (mode === 'direct') {
                    const recipients = [...chosen].filter(
                        (key) => batch.get(key)?.ok !== true
                    );
                    attemptedSend = true;
                    if (!recipients.length)
                        throw new Error(
                            batch.size
                                ? 'Every selected knight already received this letter in this batch.'
                                : 'Choose at least one knight.'
                        );
                    // A mass send is never one click: name the count before writing.
                    if (recipients.length > 1 && before !== `${recipients.length}`) {
                        before = `${recipients.length}`;
                        confirmAgain(
                            `This sends the letter to ${recipients.length} knights.`
                        );
                    }
                    attempted = [...new Set([...attempted, ...recipients])];
                    const outcome = await send(recipients, values, 'Sending');
                    renderResults();
                    renderReceipts();
                    if (outcome.stopped) throw outcome.stopped;
                    if (outcome.refused.length) {
                        const pending =
                            recipients.length - outcome.sent - outcome.refused.length;
                        throw new Error(
                            `${outcome.sent} of ${recipients.length} delivered; ${
                                outcome.refused.length
                            } refused${pending ? `, ${pending} not sent` : ''}. ${
                                outcome.refused[0].reason
                            }`
                        );
                    }
                    changed(
                        recipients.length === 1
                            ? `Mail queued for ${
                                  recipients[0].split('|')[1]
                              }. It waits while they are offline.`
                            : `${recipients.length} knights will receive this mail.`
                    );
                } else if (mode === 'welcome') {
                    await write('mail-welcome-save', { ...values, enabled: enabled.checked });
                    changed(
                        enabled.checked
                            ? 'Welcome mail saved. New knights receive it on their first visit.'
                            : 'Welcome mail disabled; knights created while it was on still receive it.'
                    );
                } else {
                    const start = new Date(starts.value).getTime(),
                        end = new Date(ends.value).getTime();
                    if (!Number.isFinite(start) || !Number.isFinite(end))
                        throw new Error('Choose a valid start and end time.');
                    if (end <= start) throw new Error('The mailing must end after it starts');
                    if (end <= Date.now())
                        throw new Error('The window has already ended; choose a later end.');
                    if (start <= Date.now() && before !== 'past') {
                        before = 'past';
                        confirmAgain(
                            'This window starts in the past, so saving delivers to every knight with mail open now.'
                        );
                    }
                    await write('mail-save', {
                        ...values,
                        id: draft.id,
                        revision: draft.revision,
                        starts: start,
                        ends: end
                    });
                    // Nothing is delivered before the window opens, which is
                    // the only draft the store can express.
                    if (start > Date.now())
                        changed(`Saved as a draft: nothing is delivered before ${date(start)}.`);
                    else changed('Mailing saved; knights receive it on their next visit.');
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
    renderReceipts();
    table.update(data.campaigns);
    welcomeDetails.append(welcomeSummary, welcomeHost);
    const newMailing = button(
        'New mailing',
        async () => {
            // The composer opens at once; the reach count fills in when the roster
            // lands, so the button never looks dead for the length of that read.
            newMailing.setBusy(true);
            try {
                await compose('campaign');
            } finally {
                newMailing.setBusy(false);
            }
        },
        'primary'
    );
    newMailing.title = 'Write a letter every knight receives once while the window is open.';
    // Reading the roster is a request, so the trigger says it is working instead
    // of looking dead for the length of a knights-all read.
    const directMail = button(
        'Send to knight',
        async () => {
            directMail.setBusy(true);
            try {
                await compose('direct');
            } finally {
                directMail.setBusy(false);
            }
        },
        'secondary',
        // A server with no knights cannot be sent to, and a modal that only says so
        // wastes the read the page already had: the control says it instead.
        () => roster === null || roster.length > 0,
        {
            showReason: true,
            unavailable: 'No knight exists on this server yet; knights are created in the game client.'
        }
    );
    directMail.title =
        'Write a one-off letter to one knight; it waits in the inbox while they are offline.';
    const mailings = panel(
        'Mailings',
        toolbar(
            newMailing,
            directMail,
            routeLink('Link to this section', 'mailing', { tab: 'mailings' }, 'link')
        ),
        table.node,
        welcomeDetails
    );
    mailings.classList.add('stack');
    node.append(summary, mailings, receiptsHost);
    // The page's three parts are linkable, so a colleague can be sent straight to
    // the section that answers their question.
    // The delivered counts are a one-request read the page already needs; a button
    // that has to be pressed before the numbers mean anything made the page's own
    // claims optional.
    rosterRows()
        .then(() => {
            if (!ctx.alive()) return;
            directMail.refreshAvailability?.();
            renderSummary();
            table.update(data.campaigns);
        })
        .catch(() => {
            if (ctx.alive()) renderSummary();
        });
    /**
     * The address owns the view: the section it names scrolls into place and the
     * letter it names opens. A cold load and an in-app link then behave alike, which
     * is the whole point of putting the letter in the URL.
     */
    function applyParams(params) {
        // The campaign table owns `?search=`; the composer reads `?campaign=`.
        table.setQuery(params.get('search') ?? '');
        const tab = params.get('tab');
        // The node may not be in the document yet, so scrolling now does nothing:
        // the arrival is scheduled for the frame after insertion.
        const arrive = () => {
            if (tab === 'welcome') {
                welcomeDetails.open = true;
                landOn(welcomeDetails);
            } else if (tab === 'receipts') landOn(receiptsHost);
            else if (tab === 'mailings') landOn(mailings);
        };
        if (tab) arrive();
        const campaign = Number(params.get('campaign'));
        if (!Number.isInteger(campaign) || campaign <= 0) return;
        const match = data.campaigns.find((row) => row.id === campaign);
        if (match) openLetter(match);
        else
            ctx.notify(
                `There is no mailing #${campaign} on this server; the list below is the whole set.`
            );
    }
    applyParams(ctx.params);
    return {
        node,
        interval: 30000,
        refresh,
        navigate: applyParams
    };
}
