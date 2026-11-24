/* Apply preferences before styles. No account or server data is stored here. */
try {
    for (const [key, values, fallback] of [
        ['theme', ['dark', 'light'], 'dark'],
        ['density', ['comfortable', 'compact'], 'comfortable'],
        ['motion', ['auto', 'reduce'], 'auto'],
        ['clock', ['24-hour', '12-hour'], '24-hour']
    ]) {
        const value = localStorage.getItem('tortodrone-' + key);
        document.documentElement.dataset[key] = values.includes(value) ? value : fallback;
    }
} catch {
    /* Browser storage can be disabled. */
}
