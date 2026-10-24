export function route() {
    const [name, query = ''] = location.hash.slice(1).split('?');
    return {
        name: name === 'dashboard' ? 'overview' : name || 'overview',
        params: new URLSearchParams(query)
    };
}
export function go(name, values = {}) {
    const query = new URLSearchParams(values);
    location.hash = name + (query.size ? '?' + query : '');
}
