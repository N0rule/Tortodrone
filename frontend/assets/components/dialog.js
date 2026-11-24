import { api } from '../api.js';
import { $, el, input, select, label, changed } from '../ui.js';

let active = null,
    cleanup;
/**
 * Open the shared editor dialog. `variant` picks the submit button's weight:
 * 'primary' for an ordinary save, 'danger' for a decision that destroys
 * something. A destructive dialog also parks focus on Cancel, so Enter on an
 * untouched form does not perform the destructive action.
 */
export function openDialog({
    title,
    content,
    description = '',
    submit = 'Confirm',
    variant = 'primary',
    onSubmit,
    revision,
    target,
    after,
    dispose,
    isDirty,
    notifySaved = true
}) {
    const dialog = $('#dialog'),
        form = $('#editor');
    if (dialog.open) {
        if (
            active?.pending ||
            (active?.isDirty?.() && !window.confirm('Discard the unsaved changes in this editor?'))
        ) {
            dispose?.();
            return false;
        }
        cleanup?.();
        cleanup = null;
        dialog.close();
    }
    $('#dialog-title').textContent = title;
    $('#dialog-description').textContent = description;
    $('#dialog-error').textContent = '';
    $('#dialog-progress').textContent = '';
    $('#dialog-progress').hidden = true;
    $('#dialog-conflict').hidden = true;
    const submitButton = $('#submit-dialog'),
        // A read-only inspector has one exit, not two: "Cancel" beside a filled
        // "Close" is the same button twice with different weights.
        readOnly = submit === 'Close';
    submitButton.textContent = submit;
    // The variant lives here, not in index.html: one dialog markup serves a save
    // and a delete, so the class has to follow the caller.
    submitButton.className = variant === 'danger' ? 'danger' : readOnly ? 'secondary' : 'primary';
    submitButton.disabled = false;
    $('#cancel-dialog').hidden = readOnly;
    $('#fields').replaceChildren(content);
    const state = { revision, target, isDirty, pending: false, conflicted: false };
    active = state;
    cleanup = dispose;
    form.onsubmit = async (event) => {
        event.preventDefault();
        if (state.pending || state.conflicted) return;
        state.pending = true;
        $('#submit-dialog').disabled = true;
        $('#submit-dialog').setAttribute('aria-busy', 'true');
        $('#dialog-error').textContent = '';
        // Sequential bulk work reports its own progress through this callback,
        // so a long run never looks frozen.
        const progress = (done, total, label = 'Updating') => {
            const node = $('#dialog-progress');
            if (!node) return;
            node.hidden = false;
            node.textContent = `${label} ${done} of ${total}…`;
        };
        try {
            const result = await onSubmit(form, progress);
            if (active !== state) return;
            dialog.close();
            if (notifySaved) changed();
            if (after) await after(result);
        } catch (error) {
            if (active !== state) return;
            $('#dialog-error').textContent =
                error.status === 409
                    ? error.message +
                      ' Your draft is preserved. Close and reopen this record to use its latest revision.'
                    : error.message;
        } finally {
            state.pending = false;
            $('#submit-dialog').removeAttribute('aria-busy');
            const progressNode = $('#dialog-progress');
            if (progressNode) progressNode.hidden = true;
            if (active === state) $('#submit-dialog').disabled = state.conflicted;
        }
    };
    form.addEventListener('keydown', (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
            event.preventDefault();
            form.requestSubmit();
        }
    });
    $('#close-dialog').onclick = $('#cancel-dialog').onclick = () => {
        if (
            !state.pending &&
            (!isDirty?.() || window.confirm('Discard the unsaved changes in this editor?'))
        )
            dialog.close();
    };
    dialog.oncancel = (event) => {
        if (
            state.pending ||
            (isDirty?.() && !window.confirm('Discard the unsaved changes in this editor?'))
        )
            event.preventDefault();
    };
    dialog.onclose = () => {
        if (!dialog.open) {
            active = null;
            cleanup?.();
            cleanup = null;
            $('#fields').replaceChildren();
            form.onsubmit = null;
        }
    };
    dialog.showModal();
    if (variant === 'danger') $('#cancel-dialog').focus();
    else {
        // Landing on the dismiss control would throw away a draft on the first
        // Enter, so an editor opens on its first field.
        const first = $('#fields').querySelector(
            'input:not([type=hidden]):not([disabled]), textarea:not([disabled]), select:not([disabled])'
        );
        if (first) first.focus();
    }
    return true;
}
export function dialogDirty() {
    return !!active?.isDirty?.();
}
export function closeDialog(force = false) {
    const dialog = $('#dialog');
    if (!dialog.open) return true;
    if (
        !force &&
        (active?.pending ||
            (dialogDirty() && !window.confirm('Discard the unsaved changes in this editor?')))
    )
        return false;
    dialog.close();
    return true;
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
    active.conflicted = true;
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
/**
 * The one-field-shape editor. `options` may be a variant string or an object
 * `{ variant, submit }`, so a caller can decide how the action reads without a
 * longer argument list.
 */
export function edit(title, route, fields = [], fixed = {}, description = '', after, options = {}) {
    const { variant, submit } =
        typeof options === 'string' ? { variant: options } : { variant: 'primary', ...options };
    const content = el('div', null, 'form-grid'),
        controls = new Map();
    const initial = new Map();
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
        initial.set(f.key, f.type === 'checkbox' ? control.checked : control.value);
    }
    openDialog({
        title,
        content,
        description,
        submit: submit || 'Save changes',
        variant,
        revision: fixed.revision,
        target: fixed.worldId ?? route,
        isDirty: () =>
            fields.some(
                (f) =>
                    initial.get(f.key) !==
                    (f.type === 'checkbox'
                        ? controls.get(f.key).checked
                        : controls.get(f.key).value)
            ),
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
/**
 * A confirmation dialog that does not map onto one server route, used by bulk
 * actions. `onSubmit` performs the work; the dialog closes only when it resolves.
 * @param {{title: string, description: string, label?: string, content?: Node, onSubmit: Function}} options
 *   `variant` may be 'primary' or 'danger'.
 */
export function confirmAction({
    title,
    description,
    label = 'Confirm',
    content,
    variant = 'primary',
    onSubmit
}) {
    const body = el('div', null, 'stack');
    if (content) body.append(content);
    body.append(el('p', description, 'muted'));
    return openDialog({ title, content: body, submit: label, variant, onSubmit });
}

/* A retype gate ("Type the name", "Repeat the command") is the shape this UI uses
   to mark an action as destructive; a confirm password is not one. */
function isDestructive(fields) {
    return fields.some(
        (field) =>
            field?.key !== 'passwordConfirmation' &&
            (String(field?.key || '').endsWith('Confirmation') ||
                /^Type /.test(String(field?.title || '')))
    );
}
/**
 * Confirm one server action. A gated action destroys something, so it submits as
 * a danger action; anything else stays a primary "Confirm" instead of pretending
 * to be a save.
 */
export function confirm(title, route, fixed = {}, extra = [], description = '') {
    const destructive = isDestructive(extra);
    edit(
        title,
        route,
        extra,
        fixed,
        description || 'Review the target before confirming this action.',
        undefined,
        { variant: destructive ? 'danger' : 'primary', submit: 'Confirm' }
    );
}
