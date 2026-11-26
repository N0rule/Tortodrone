/* Apply preferences before styles. No account or server data is stored here.
   Catppuccin Mocha is the default; the navy-and-gold Tortodrone console is the
   alternate. The removed light theme and compact density are migrated here so a
   stored legacy value never selects a theme that no longer exists. */
const LEGACY = { dark: 'tortodrone', light: 'catppuccin' };
try {
    for (const [key, values, fallback] of [
        ['theme', ['catppuccin', 'tortodrone'], 'catppuccin'],
        ['motion', ['auto', 'reduce'], 'auto'],
        ['clock', ['24-hour', '12-hour'], '24-hour']
    ]) {
        let value = localStorage.getItem('tortodrone-' + key);
        if (key === 'theme') value = LEGACY[value] ?? value;
        document.documentElement.dataset[key] = values.includes(value) ? value : fallback;
    }
} catch {
    /* Browser storage can be disabled. */
}
