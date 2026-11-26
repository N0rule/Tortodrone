/**
 * Interface preferences shared by the shell and every page. Values live in
 * localStorage under `tortodrone-<key>`; nothing here is account or server data.
 * The boot script `preferences.js` applies the theme before first paint, so this
 * module is the single reader/writer for every preference after that.
 */
const storage = (() => {
    if (typeof localStorage === 'undefined') return null;
    try {
        const probe = 'tortodrone-probe';
        localStorage.setItem(probe, probe);
        localStorage.removeItem(probe);
        return localStorage;
    } catch {
        return null;
    }
})();

/** Coercion table: the first entry of each list is the default. */
export const schema = {
    theme: ['catppuccin', 'tortodrone'],
    pageSize: [30, 15, 60, 120],
    pollScale: [1, 0.5, 2, 4],
    clock: ['date', 'date-time'],
    motion: ['auto', 'reduce']
};

const listeners = new Set();

/* Stored values from the removed appearance options. `dark` was the navy console and
   keeps working as `tortodrone`; `light` has no successor, so it lands on the default.
   Migration happens on read and is written back by the next write, so no account or
   server state is touched. */
const LEGACY_THEME = { dark: 'tortodrone', light: 'catppuccin' };

/** @param {string} key @returns {string|number|boolean} */
function coerce(key, value) {
    if (key === 'theme' && value in LEGACY_THEME) value = LEGACY_THEME[value];
    const [fallback, ...options] = schema[key] ?? [value];
    if (typeof fallback === 'number') {
        const number = Number(value);
        return options.includes(number) ? number : fallback;
    }
    if (typeof fallback === 'boolean') {
        if (value === 'true' || value === true) return true;
        if (value === 'false' || value === false) return false;
        return fallback;
    }
    return options.includes(value) ? value : fallback;
}

/** @param {string} key @returns {any} */
export function read(key) {
    let raw = null;
    try {
        raw = storage?.getItem('tortodrone-' + key) ?? null;
    } catch {
        raw = null;
    }
    return coerce(key, raw);
}

/** @param {string} key @param {any} value */
export function write(key, value) {
    const next = coerce(key, value);
    try {
        storage?.setItem('tortodrone-' + key, String(next));
    } catch {
        /* Private browsing or a full quota keeps the session value only. */
    }
    apply();
    for (const listener of listeners) listener(key, next);
    return next;
}

/** Restore every preference to its documented default. */
export function reset() {
    for (const key of Object.keys(schema)) {
        try {
            storage?.removeItem('tortodrone-' + key);
        } catch {
            /* Storage is optional. */
        }
        for (const listener of listeners) listener(key, read(key));
    }
    apply();
}

/** @param {(key: string, value: any) => void} listener @returns {() => void} */
export function subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

/** Mirror preferences that CSS can act on onto the document element. */
export function apply() {
    // node:test imports the pure helpers from this module without a DOM.
    if (typeof document === 'undefined') return;
    const root = document.documentElement;
    root.dataset.theme = read('theme');
    root.dataset.motion = read('motion');
    root.dataset.clock = read('clock');
}

/** All current values, for the settings page and diagnostics. */
export function snapshot() {
    return Object.fromEntries(Object.keys(schema).map((key) => [key, read(key)]));
}

apply();
