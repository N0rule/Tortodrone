import { el, text } from '../ui.js';

/**
 * Fuzzy subsequence score for the command palette. Returns a positive number
 * when every character of `query` appears in order, with bonuses for matches at
 * the start of the string and at word boundaries, or `0` when there is no match.
 * Pure, so node:test can pin the ordering rules.
 * @param {string} candidate
 * @param {string} query
 * @returns {number}
 */
export function score(candidate, query) {
    const haystack = candidate.toLowerCase();
    const needle = query.toLowerCase().trim();
    if (!needle) return 1;
    let index = 0,
        points = 0,
        streak = 0;
    for (const character of needle) {
        if (character === ' ') continue;
        const found = haystack.indexOf(character, index);
        if (found < 0) return 0;
        streak = found === index ? streak + 1 : 0;
        points += 1 + streak * 2;
        if (found === 0 || /[\s\-_/.:]/.test(haystack[found - 1])) points += 6;
        index = found + 1;
    }
    // Prefer shorter candidates and exact prefixes, as a command palette should.
    points += Math.max(0, 24 - haystack.length) / 4;
    if (haystack.startsWith(needle)) points += 12;
    return points;
}

/**
 * Rank a list of entries by their `title` and optional `hint`, dropping misses.
 * @template T
 * @param {T[]} entries
 * @param {string} query
 * @param {(entry: T) => string} [describe]
 * @returns {T[]}
 */
export function rank(entries, query, describe = (entry) => `${entry.title} ${entry.hint ?? ''}`) {
    if (!query.trim()) return entries;
    return entries
        .map((entry) => ({ entry, points: score(describe(entry), query) }))
        .filter((row) => row.points > 0)
        .sort((a, b) => b.points - a.points)
        .map((row) => row.entry);
}

/**
 * Build a command palette bound to the given sources.
 *
 * - `entries()` returns the always-available commands: pages, actions.
 * - `lookup(query, signal)` optionally returns extra groups from the server.
 *
 * The dialog owns a combobox/listbox pair: arrow keys and the letter keys stay
 * in the input, so the operator never has to learn a second focus model.
 * @param {{entries: () => Array<{id: string, title: string, hint?: string, group?: string, run: () => void}>, lookup?: (query: string, signal: AbortSignal) => Promise<Array<{group: string, items: Array<{id: string, title: string, hint?: string, run: () => void}>}>>, label?: string}} options
 */
export function createPalette({ entries, lookup, label = 'Search or run a command' }) {
    const dialog = el('dialog', null, 'palette');
    dialog.id = 'palette';
    dialog.setAttribute('aria-label', label);
    const field = el('input');
    field.type = 'search';
    field.autocomplete = 'off';
    field.spellcheck = false;
    field.placeholder = label;
    field.id = 'palette-input';
    field.setAttribute('role', 'combobox');
    field.setAttribute('aria-expanded', 'true');
    field.setAttribute('aria-controls', 'palette-list');
    field.setAttribute('aria-autocomplete', 'list');
    field.setAttribute('aria-describedby', 'palette-status');
    const searchBox = el('div', null, 'palette-field');
    searchBox.append(field, el('kbd', 'Esc', 'kbd'));
    const list = el('ul');
    list.id = 'palette-list';
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-label', 'Commands and records');
    const status = el('p', '', 'palette-status');
    status.id = 'palette-status';
    status.setAttribute('aria-live', 'polite');
    const hints = el('div', null, 'palette-hints');
    hints.append(
        hint('↑↓', 'Navigate'),
        hint('↵', 'Open'),
        hint('Esc', 'Close'),
        el('span', 'Powered by the administration API', 'muted')
    );
    dialog.append(searchBox, list, status, hints);
    document.body.append(dialog);

    let options = [],
        active = -1,
        version = 0,
        controller = null,
        debounce = null,
        opener = null;

    function hint(keys, words) {
        const node = el('span', null, 'palette-hint');
        node.append(el('kbd', keys, 'kbd'), el('span', words));
        return node;
    }

    function describe(item, index) {
        return `palette-option-${version}-${index}-${item.id.replace(/[^\w-]/g, '')}`;
    }

    function paint(groups) {
        version++;
        options = [];
        active = groups.some((group) => group.items.length) ? 0 : -1;
        list.replaceChildren();
        for (const group of groups) {
            if (!group.items.length) continue;
            const heading = el('li', group.name, 'palette-group');
            heading.setAttribute('role', 'presentation');
            heading.id = `palette-group-${version}-${list.children.length}`;
            list.append(heading);
            const itemList = el('li');
            itemList.setAttribute('role', 'presentation');
            const inner = el('ul');
            inner.setAttribute('role', 'group');
            inner.setAttribute('aria-labelledby', heading.id);
            itemList.append(inner);
            list.append(itemList);
            for (const item of group.items) {
                const index = options.length;
                options.push(item);
                const option = el('li', null, 'palette-option');
                option.id = describe(item, index);
                option.setAttribute('role', 'option');
                option.setAttribute('aria-selected', 'false');
                option.dataset.index = String(index);
                if (item.hint) option.append(el('span', item.hint, 'palette-kind'));
                option.append(el('span', item.title, 'palette-title'));
                option.addEventListener('mousemove', () => select(index));
                option.addEventListener('click', () => choose(index));
                inner.append(option);
            }
        }
        if (!options.length) {
            const empty = el('li', null, 'palette-empty');
            empty.setAttribute('role', 'presentation');
            empty.append(
                el('p', 'Nothing matched', 'empty-title'),
                el('p', 'Try a different word, or a page name such as Knights.', 'empty-message')
            );
            list.append(empty);
        }
        select(active, true);
        text(
            status,
            options.length ? `${options.length} result${options.length === 1 ? '' : 's'}` : 'No results'
        );
    }

    function select(index, quiet = false) {
        if (!options.length) {
            field.removeAttribute('aria-activedescendant');
            return;
        }
        active = (index + options.length) % options.length;
        for (const node of list.querySelectorAll('[role="option"]')) {
            const selected = Number(node.dataset.index) === active;
            node.setAttribute('aria-selected', String(selected));
            node.classList.toggle('active', selected);
            if (selected && !quiet) {
                field.setAttribute('aria-activedescendant', node.id);
                node.scrollIntoView({ block: 'nearest' });
                text(status, `${options[active].title} · ${active + 1} of ${options.length}`);
            } else if (selected) field.setAttribute('aria-activedescendant', node.id);
        }
    }

    function choose(index = active) {
        const item = options[index];
        if (!item) return;
        // A chosen entry moves focus itself, so skip the focus restore.
        opener = null;
        close();
        item.run();
    }

    /** Page entries repeat their group name, so only other kinds show a label. */
    function decorate(query) {
        return rank(entries(), query).map((entry) => ({
            ...entry,
            hint: entry.group && entry.group !== 'Page' ? entry.group : ''
        }));
    }

    function paintLocal(query) {
        paint([{ name: 'Commands', items: decorate(query) }]);
    }

    async function search(query) {
        paintLocal(query);
        if (!lookup || query.trim().length < 2) return;
        const current = ++version;
        controller?.abort();
        controller = new AbortController();
        text(status, 'Searching records…');
        try {
            const extra = await lookup(query.trim(), controller.signal);
            if (current !== version || !dialog.open) return;
            paint([
                {
                    name: 'Commands',
                    items: decorate(query)
                },
                ...extra.map((group) => ({
                    name: group.group,
                    items: group.items.slice(0, 8)
                }))
            ]);
        } catch (error) {
            if (error.name !== 'AbortError') text(status, 'Record search unavailable');
        }
    }

    field.oninput = () => {
        clearTimeout(debounce);
        const query = field.value;
        paintLocal(query);
        debounce = setTimeout(() => search(query), 160);
    };

    field.onkeydown = (event) => {
        const keys = {
            ArrowDown: () => select(active + 1),
            ArrowUp: () => select(active - 1),
            PageDown: () => select(active + 8),
            PageUp: () => select(active - 8),
            Home: () => select(0),
            End: () => select(options.length - 1)
        };
        if (keys[event.key]) {
            event.preventDefault();
            keys[event.key]();
            return;
        }
        if (event.key === 'Enter') {
            event.preventDefault();
            choose();
        }
    };

    dialog.addEventListener('cancel', (event) => {
        event.preventDefault();
        close();
    });
    dialog.addEventListener('click', (event) => {
        if (event.target === dialog) close();
    });

    // The browser restores focus to whatever was focused before showModal(),
    // which is not always the control that opened the palette. Closing is
    // always routed through the dialog's own close event so every path — the
    // Escape key, a click on the backdrop, choosing an entry, or a programmatic
    // close() — ends with focus on the trigger.
    dialog.addEventListener('close', () => {
        const target = opener?.isConnected ? opener : null;
        opener = null;
        target?.focus();
    });

    function close() {
        if (!dialog.open) return;
        controller?.abort();
        clearTimeout(debounce);
        dialog.close();
        // The close event is queued, so restore right away for callers that
        // expect focus to be back before the next task.
        if (opener?.isConnected) opener.focus();
    }

    /**
     * @param {string} [initial]
     * @param {{ trigger?: HTMLElement | null }} [options]
     */
    function open(initial = '', options = {}) {
        if (dialog.open) {
            field.select();
            return;
        }
        field.value = initial;
        paintLocal(initial);
        // A programmatic click does not move focus, so callers may name the
        // control that should get focus back.
        const active = document.activeElement;
        opener =
            options.trigger ??
            (active instanceof HTMLElement && active !== document.body ? active : null);
        dialog.showModal();
        field.focus();
        if (initial) search(initial);
    }

    return { node: dialog, open, close, get isOpen() { return dialog.open; }, field };
}
