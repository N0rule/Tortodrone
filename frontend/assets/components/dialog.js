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
    notifySaved = true,
    // A run that can take minutes says so, and can be given up on. `stoppable` puts a
    // Stop control beside the progress line; `onSubmit` reads `run.stopped` between
    // iterations. Before this, a 200-knight send held the operator in a modal that
    // refused Close, Cancel and Escape while it worked, and there was nothing else to
    // press.
    stoppable = false
}) {
    const dialog = $('#dialog'),
        form = $('#editor');
    bindEditorKeys(form);
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
    // A read-only inspector closes from the footer: the heading's X is the third
    // copy of the same action, and it comes back for every other dialog.
    $('#close-dialog').hidden = readOnly;
    $('#fields').replaceChildren(content);
    const state = { revision, target, isDirty, pending: false, conflicted: false, stop: false };
    const stopButton = $('#dialog-stop');
    if (stopButton) {
        stopButton.hidden = true;
        stopButton.textContent = 'Stop';
        stopButton.removeAttribute('aria-disabled');
        stopButton.onclick = () => {
            state.stop = true;
            stopButton.textContent = 'Stopping…';
            stopButton.setAttribute('aria-disabled', 'true');
        };
    }
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
        state.stop = false;
        if (stoppable && stopButton) stopButton.hidden = false;
        try {
            const result = await onSubmit(form, progress, {
                get stopped() {
                    return state.stop;
                }
            });
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
                    : // A check this page ran itself never reached the server, so its own
                      // sentence is the whole answer: "the window has already ended" was
                      // being replaced by a lost-write warning about a request never sent.
                      error.local || error.status
                      ? error.message
                      : // No status means no answer, not no effect: say what is true of a
                        // write whose reply was lost.
                        'The server did not answer. The change may or may not have been stored: reopen this record and read its state before saving again.';
        } finally {
            state.pending = false;
            $('#submit-dialog').removeAttribute('aria-busy');
            const progressNode = $('#dialog-progress');
            if (progressNode) progressNode.hidden = true;
            if (stopButton) stopButton.hidden = true;
            if (active === state) $('#submit-dialog').disabled = state.conflicted;
        }
    };

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
/* The editor form is a singleton, so its short cuts are bound once rather than once
   per `openDialog()`. Bound in every open they stacked: fifty edits in one session left
   fifty identical listeners on the same node, which is the kind of cost that shows up
   as "the editor feels slow" and never as a bug. The binding is lazy because this
   module is imported by the Node test suite, where there is no document. */
let editorKeysBound = false;
function bindEditorKeys(form) {
    if (editorKeysBound) return;
    form.addEventListener('keydown', (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
            event.preventDefault();
            form.requestSubmit();
        }
    });
    // Set after the binding, not before: a throw between the two would leave the flag
    // set with no listener and no retry path, which is Ctrl+Enter dead for the session.
    editorKeysBound = true;
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
    const { variant, submit, guardDirty = true, notifySaved = true } =
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
        // A confirmation gate is not an editor: nothing typed in it survives the
        // dialog, so asking "discard the unsaved changes?" on the way out is a second
        // question with no answer worth having.
        isDirty: guardDirty
            ? () =>
                  fields.some(
                      (f) =>
                          initial.get(f.key) !==
                          (f.type === 'checkbox'
                              ? controls.get(f.key).checked
                              : controls.get(f.key).value)
                  )
            : undefined,
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
        // Forwarded, not swallowed: an action that is not a save (a kick, a
        // settlement) says its own sentence instead of "Changes saved.".
        notifySaved,
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
    onSubmit,
    notifySaved = true,
    after
}) {
    const body = el('div', null, 'stack');
    if (content) body.append(content);
    body.append(el('p', description, 'muted'));
    // `notifySaved: false` is for an action that never reaches the server: clearing a
    // browser-local list toasted "Changes saved." and refreshed the whole page.
    return openDialog({ title, content: body, submit: label, variant, onSubmit, notifySaved, after });
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
 * Confirm one server action.
 *
 * A retype gate is one way to know an action destroys something, but it is not the
 * only one: dropping every live session is destructive whether or not it asks for a
 * typed word. Inferring from the field alone left Stop, Restart, Kick, Disconnect and
 * Cancel promoting a gold "Confirm" — the same weight as Save — so the caller can now
 * say so outright with `{destructive, submit}`. `submit` names what the press does
 * ("Stop the listener"), because "Confirm" makes the operator re-read the title to
 * find out.
 */
export function confirm(title, route, fixed = {}, extra = [], description = '', options = {}) {
    const destructive = options.destructive ?? isDestructive(extra);
    edit(
        title,
        route,
        extra,
        fixed,
        description || 'Review the target before confirming this action.',
        options.after,
        // `notifySaved` and the rest travel through the options object `edit` hands to
        // `openDialog`: a kick that ends in "Changes saved." is the same recycled
        // sentence as a save, and only this pass-through lets a caller say otherwise.
        { ...options, variant: destructive ? 'danger' : 'primary', submit: options.submit || 'Confirm' }
    );
}
