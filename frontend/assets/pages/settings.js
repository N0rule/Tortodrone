import { api } from '../api.js';
import { el, panel, toolbar, button, select, text, label, date, copy } from '../ui.js';
import { toast } from '../components/toast.js';
import * as prefs from '../prefs.js';

const choices = {
    theme: [
        ['dark', 'Dark'],
        ['light', 'Light']
    ],
    density: [
        ['comfortable', 'Comfortable'],
        ['compact', 'Compact']
    ],
    motion: [
        ['auto', 'Follow the system'],
        ['reduce', 'Reduce motion']
    ],
    clock: [
        ['date', 'Date only'],
        ['date-time', 'Date and time']
    ],
    pageSize: [
        [15, '15 rows'],
        [30, '30 rows'],
        [60, '60 rows'],
        [120, '120 rows']
    ],
    pollScale: [
        [0.5, 'Twice as often'],
        [1, 'Standard'],
        [2, 'Half as often'],
        [4, 'Four times less often']
    ],
};

const describe = {
    pageSize: (value) => value + ' rows',
    pollScale: (value) => `×${value}`
};

export async function mount(ctx) {
    const node = el('div', null, 'stack');
    const controls = new Map();
    const feedback = el('p', '', 'visually-hidden');
    feedback.setAttribute('role', 'status');
    feedback.setAttribute('aria-live', 'polite');

    /** Every preference row shares one label, help line and announcement. */
    function setting({ key, title, help }) {
        const options = choices[key];
        const control = select(title, options, prefs.read(key));
        control.id = 'pref-' + key;
        controls.set(key, control);
        control.onchange = () => {
            const raw = control.value;
            const value =
                typeof options[0][0] === 'number'
                    ? Number(raw)
                    : raw === 'true'
                      ? true
                      : raw === 'false'
                        ? false
                        : raw;
            const applied = prefs.write(key, value);
            text(
                feedback,
                `${title} set to ${describe[key] ? describe[key](applied) : applied}.`
            );
        };
        const row = el('div', null, 'setting');
        row.append(label(title, control), el('small', help, 'setting-help'));
        return row;
    }

    function paint() {
        for (const [key, control] of controls) control.value = String(prefs.read(key));
    }

    const appearance = panel(
        'Appearance',
        setting({
            key: 'theme',
            title: 'Theme',
            help: 'Applies immediately and is remembered in this browser only.'
        }),
        setting({
            key: 'density',
            title: 'Density',
            help: 'Compact removes vertical padding from rows, panels and toolbars.'
        }),
        setting({
            key: 'motion',
            title: 'Motion',
            help: 'Reduce motion disables transitions, animations and smooth scrolling.'
        }),
        setting({
            key: 'clock',
            title: 'Header clock',
            help: 'Show the date alone, or the date with the local time.'
        })
    );

    const display = panel(
        'Data display',
        setting({
            key: 'pageSize',
            title: 'Rows per page',
            help: 'New tables start at this size. Each table also remembers its own choice.'
        })
    );

    const live = panel(
        'Live updates',
        setting({
            key: 'pollScale',
            title: 'Refresh frequency',
            help: 'Slower polling reduces load on the server and on metered connections.'
        })
    );

    const support = panel(
        'Session and support',
        el(
            'p',
            'Preferences are stored in this browser only. They are not part of your WebUI identity and are never sent to the server.'
        ),
        toolbar(
            button('Keyboard shortcuts', () => document.querySelector('#shortcut-dialog').showModal()),
            button(
                'Copy diagnostics',
                async () => {
                    const status = await api.request('status').catch(() => null);
                    const report = {
                        version: status?.version ?? 'unknown',
                        role: api.session?.role,
                        expires: api.session ? date(api.session.expires) : null,
                        preferences: prefs.snapshot(),
                        userAgent: navigator.userAgent,
                        viewport: `${window.innerWidth}×${window.innerHeight}`
                    };
                    // The shared helper falls back to the legacy command, which is
                    // the only thing that works over plain HTTP.
                    await copy(JSON.stringify(report, null, 2), 'Diagnostics copied.');
                },
                'secondary'
            ),
            button(
                'Reset preferences',
                () => {
                    prefs.reset();
                    paint();
                    text(feedback, 'Preferences reset to their defaults.');
                    toast('Preferences reset.');
                },
                'danger'
            )
        ),
        feedback
    );

    const session = panel(
        'This session',
        (() => {
            const list = el('dl', null, 'facts');
            list.append(
                el('dt', 'Identity'),
                el('dd', api.session.username + ' · ' + api.session.role.toLowerCase()),
                el('dt', 'Expires'),
                el('dd', api.session ? date(api.session.expires) : '—'),
                el('dt', 'Idle timeout'),
                el('dd', '30 minutes without a request, at most eight hours per session')
            );
            return list;
        })()
    );

    node.append(appearance, display, live, support, session);
    paint();

    function refresh() {
        paint();
    }
    return { node, refresh };
}
