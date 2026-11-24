/* Apply preferences before styles. No account or server data is stored here. */
/* A first visit has no stored choice: the operating system's preference is the
   only honest default, so a light-preference operator gets a light UI at once. */
const preferredTheme = () => {
    try {
        return matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    } catch {
        return 'dark';
    }
};
try {
    for (const [key, values, fallback] of [
        ['theme', ['dark', 'light'], preferredTheme()],
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
