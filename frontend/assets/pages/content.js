import { el, toolbar, badge, details, panel } from '../ui.js';
import { itemPicker } from '../components/item-picker.js';
import { itemLine } from '../components/items.js';
import { openDialog } from '../components/dialog.js';
export async function mount(ctx) {
    const data = await ctx.request('content'),
        node = el('div', null, 'stack');
    const picker = itemPicker((def) =>
        openDialog({
            title: def.name,
            content: panel(
                '',
                itemLine(def),
                details('Definition and authoritative properties', def)
            ),
            submit: 'Close',
            notifySaved: false,
            onSubmit: async () => {}
        })
    );
    node.append(
        toolbar(
            badge(`${data.maps} maps`),
            badge(`${data.items.toLocaleString()} items`),
            badge(
                data.warnings.length ? `${data.warnings.length} warnings` : 'Content validated',
                data.warnings.length ? 'gold' : 'good'
            )
        )
    );
    for (const warning of data.warnings) node.append(el('p', warning, 'warning'));
    node.append(picker.node, details('Merchant catalogue', data.merchants));
    return { node, dispose: () => picker.dispose() };
}
