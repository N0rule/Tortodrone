/* Apply preferences before styles. No account or server data is stored here.
   The navy-and-gold Tortodrone console is the default; Catppuccin Mocha is the
   alternate. The removed light theme and compact density are migrated here so a
   stored legacy value never selects a theme that no longer exists. */
const LEGACY = { dark: 'tortodrone', light: 'catppuccin' };
try {
    for (const [key, values, fallback] of [
        ['theme', ['tortodrone', 'catppuccin'], 'tortodrone'],
        ['motion', ['auto', 'reduce'], 'auto'],
        ['clock', ['date', 'date-time'], 'date']
    ]) {
        let value = localStorage.getItem('tortodrone-' + key);
        if (key === 'theme') value = LEGACY[value] ?? value;
        document.documentElement.dataset[key] = values.includes(value) ? value : fallback;
    }
} catch {
    /* Browser storage can be disabled. */
}
