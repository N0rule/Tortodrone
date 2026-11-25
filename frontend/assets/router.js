/** `#dashboard` is the old spelling of Overview; the shell rewrites it to `#overview`. */
export function route() {
    const [name, query = ''] = location.hash.slice(1).split('?');
    return {
        name: name === 'dashboard' ? 'overview' : name || 'overview',
        params: new URLSearchParams(query)
    };
}
/**
 * The hash a page lives at. A drill-down rendered as an `<a href>` keeps
 * middle-click, "open in new tab" and "copy link address" working, which a
 * button that calls go() silently takes away.
 */
export function href(name, values = {}) {
    const query = new URLSearchParams(values);
    return '#' + name + (query.size ? '?' + query : '');
}
export function go(name, values = {}) {
    const query = new URLSearchParams(values);
    location.hash = name + (query.size ? '?' + query : '');
}
