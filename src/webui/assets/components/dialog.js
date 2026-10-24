import { api } from '../api.js';
import { $, el, input, select, label, changed } from '../ui.js';

let active = null,
    cleanup;
export function openDialog({
    title,
    content,
    description = '',
    submit = 'Save changes',
    onSubmit,
    revision,
    target,
    after,
    dispose,
    notifySaved = true
}) {
    const dialog = $('#dialog'),
        form = $('#editor');
    if (dialog.open) {
        cleanup?.();
        cleanup = null;
        dialog.close();
    }
    $('#dialog-title').textContent = title;
    $('#dialog-description').textContent = description;
    $('#dialog-error').textContent = '';
    $('#dialog-conflict').hidden = true;
    $('#submit-dialog').textContent = submit;
    $('#submit-dialog').disabled = false;
    $('#fields').replaceChildren(content);
    active = { revision, target };
    cleanup = dispose;
    let pending = false;
    form.onsubmit = async (event) => {
        event.preventDefault();
        if (pending) return;
        pending = true;
        $('#submit-dialog').disabled = true;
        $('#dialog-error').textContent = '';
        try {
            const result = await onSubmit(form);
            dialog.close();
            if (notifySaved) changed();
            if (after) await after(result);
        } catch (error) {
            $('#dialog-error').textContent =
                error.status === 409
                    ? error.message +
                      ' Your draft is preserved. Close and reopen this record to use its latest revision.'
                    : error.message;
        } finally {
            pending = false;
            $('#submit-dialog').disabled = false;
        }
    };
    $('#close-dialog').onclick = $('#cancel-dialog').onclick = () => {
        if (!pending) dialog.close();
    };
    dialog.oncancel = (event) => {
        if (pending) event.preventDefault();
    };
    dialog.onclose = () => {
        if (!dialog.open) {
            active = null;
            cleanup?.();
            cleanup = null;
        }
    };
    dialog.showModal();
}
export function conflict(target, revision) {
    if (
        !active ||
        active.target !== target ||
        active.revision == null ||
        active.revision === revision
    )
        return;
    $('#dialog-conflict').textContent =
        'This record changed while you were editing. Your draft is preserved; reopen the record before saving.';
    $('#dialog-conflict').hidden = false;
    $('#submit-dialog').disabled = true;
}
export const field = (key, title, value = '', type = 'text', options, constraints = {}) => ({
    key,
    title,
    value,
    type,
    options,
    constraints
});
export function edit(title, route, fields = [], fixed = {}, description = '', after) {
    const content = el('div', null, 'form-grid'),
        controls = new Map();
    for (const f of fields) {
        const control = f.options
            ? select(f.title, f.options, f.value)
            : input(f.title, f.value, f.type, f.constraints);
        control.name = f.key;
        control.required =
            f.constraints.required ??
            (f.type !== 'checkbox' &&
                !['variants', 'referencePrice', 'sprite', 'accessory'].includes(f.key));
        if (f.type === 'password') {
            control.autocomplete = 'new-password';
            control.maxLength = 128;
        }
        const row = label(f.title, control);
        if (f.type === 'textarea') row.classList.add('wide');
        content.append(row);
        controls.set(f.key, control);
    }
    openDialog({
        title,
        content,
        description,
        revision: fixed.revision,
        target: fixed.worldId ?? route,
        onSubmit: async () => {
            const values = { ...fixed };
            for (const f of fields) {
                const c = controls.get(f.key);
                values[f.key] =
                    f.type === 'checkbox'
                        ? c.checked
                        : f.type === 'datetime-local'
                          ? new Date(c.value).getTime()
                          : c.value;
            }
            if ('passwordConfirmation' in values && values.password !== values.passwordConfirmation)
                throw new Error('Passwords do not match.');
            return api.change(route, values);
        },
        after
    });
}
export function confirm(title, route, fixed = {}, extra = [], description = '') {
    edit(
        title,
        route,
        extra,
        fixed,
        description || 'Review the target before confirming this action.'
    );
}
