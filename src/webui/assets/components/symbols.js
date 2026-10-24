/** Original client UI icons from the optional local pack. Labels remain usable without art. */
let paths = {};
export function setSymbols(ui) {
    paths = ui || {};
}
export function symbol(name) {
    const file = paths['nav-' + name] || paths[name];
    if (typeof file !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(file) || file.includes('..')) {
        const fallback = document.createElement('span');
        fallback.hidden = true;
        return fallback;
    }
    const image = document.createElement('img');
    image.className = 'ui-icon';
    image.alt = '';
    image.setAttribute('aria-hidden', 'true');
    image.src = '/admin/assets/game/' + file;
    image.onerror = () => {
        image.hidden = true;
    };
    return image;
}
