/* Apply preferences before styles. No account or server data is stored here. */
try {
    for (const [key, values, fallback] of [
        ['theme', ['dark', 'light'], 'dark'],
        ['density', ['comfortable', 'compact'], 'comfortable']
    ]) {
        const value = localStorage.getItem('tortodrone-' + key);
        document.documentElement.dataset[key] = values.includes(value) ? value : fallback;
    }
} catch {
    /* Browser storage can be disabled. */
}
