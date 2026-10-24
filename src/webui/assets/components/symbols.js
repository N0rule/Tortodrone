/** Navigation uses one stroke family. Native artwork is reserved for game content. */
const shapes = {
    weapon: 'm4 3 9 8-2 4-4-2-4-9Zm8 10 8 8m-5-2 4-4',
    armor: 'm8 3 4 3 4-3 6 5-4 4-2-2v11H8V10l-2 2-4-4 6-5Z',
    trinket: 'M12 3 3 12l9 9 9-9-9-9Zm0 5v8m-4-4h8',
    costume: 'M10 5a2 2 0 1 1 4 0c0 2-2 2-2 4v2L3 17v3h18v-3l-9-6',
    overview: 'M3 13h4l3-8 4 14 3-8h4',
    players:
        'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 4a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-4M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    accounts: 'M4 21V5l8-3 8 3v16M8 21v-6h8v6M8 7h1m6 0h1M8 11h1m6 0h1',
    knights: 'M5 14V9a7 7 0 0 1 14 0v9l-7 4-7-4v-4Zm0-4 7 3 7-3M12 13v9',
    equipment: 'M4 3l8 5 8-5v10c0 4-8 8-8 8s-8-4-8-8V3Z',
    inventory: 'M4 7h16v14H4V7Zm3 0V3h10v4M9 12h6',
    sprites: 'M12 3c-2 5-7 5-7 11a7 7 0 0 0 14 0c0-6-5-6-7-11ZM9 14h.01M15 14h.01M10 18h4',
    progression: 'M4 21v-6h5v-5h5V5h6M16 3h6v6',
    depot: 'M3 4h2l3 12h11l3-8H6M9 21h.01M19 21h.01',
    auctions: 'm5 4 4-2 8 8-4 4-8-8ZM3 21h12M12 13l-6 6m11-9 4 4',
    news: 'M4 3h16v18H4V3Zm4 4h8M8 11h8M8 15h3M8 18h8',
    content: 'm12 2 9 5v10l-9 5-9-5V7l9-5Zm-9 5 9 5 9-5M12 12v10',
    console: 'm4 6 6 6-6 6M13 18h7',
    logs: 'M5 3h14v18H5V3Zm4 5h6m-6 4h6m-6 4h6',
    'webui-users': 'M12 2 3 6v6c0 6 9 10 9 10s9-4 9-10V6l-9-4Zm-4 10 3 3 5-6',
    audit: 'M4 3h16v18H4V3Zm4 8 3 3 5-6',
    details: 'M12 11v6m0-10h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    edit: 'm16 3 5 5-12 12-6 1 1-6L16 3Zm-2 2 5 5',
    add: 'M12 4v16M4 12h16',
    save: 'm4 12 5 5L20 6',
    remove: 'M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7'
};
export function symbol(name) {
    if (name.startsWith('slot-'))
        name =
            {
                'slot-weapon': 'weapon',
                'slot-helmet': 'knights',
                'slot-armor': 'armor',
                'slot-shield': 'equipment',
                'slot-trinket': 'trinket',
                'slot-costume': 'costume'
            }[name] || 'equipment';
    const ns = 'http://www.w3.org/2000/svg',
        svg = document.createElementNS(ns, 'svg'),
        path = document.createElementNS(ns, 'path');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'ui-icon');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '1.7');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    path.setAttribute('d', shapes[name] || shapes.details);
    svg.append(path);
    return svg;
}
