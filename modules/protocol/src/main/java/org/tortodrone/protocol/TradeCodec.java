package org.tortodrone.protocol;

import static org.tortodrone.protocol.SessionCodec.*;
import static org.tortodrone.protocol.WireWriter.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;

/** Trade-owned downstream frames; shares the connection symbol tables. */
public final class TradeCodec extends WireWriter {
    private final ItemWire items;

    public TradeCodec(WireContext context, ItemCatalog catalog) {
        super(context, catalog);
        items = new ItemWire(context, catalog);
    }

    public byte[] tradeNotification(int playerOid, String sender, boolean add) throws IOException {
        return frame(out -> {
            if (add) {
                eventHeader(out, "EntryAddedEvent", playerOid);
                string(out, "notifications");
                type(out, "com.threerings.projectx.social.data.Notification$TradeRequest");
                name(out, sender);
            } else {
                eventHeader(out, "EntryRemovedEvent", playerOid);
                string(out, "notifications");
                value(out, "TRADE_REQ:" + sender);
            }
        });
    }

    public byte[] tradeDialog(int playerOid, TradeView view, long created) throws IOException {
        return frame(out -> {
            eventHeader(out, "CompoundEvent", playerOid);
            type(out, WireTypes.ARRAY_LIST);
            out.writeInt(2);
            attribute(out, playerOid, "sceneDialogCreated", o -> value(o, created));
            attribute(out, playerOid, "sceneDialog", o -> {
                if (view == null) {
                    o.writeShort(0);
                    return;
                }
                type(o, "com.threerings.projectx.trade.data.TradeDialogInfo");
                o.writeShort(0);
                o.writeShort(0);
                o.writeFloat(0);
                o.writeShort(0);
                o.writeShort(0);
                type(o, WireTypes.NAME_ARRAY);
                o.writeInt(2);
                name(o, view.first().name());
                name(o, view.second().name());
                o.writeInt(view.oid());
            });
        });
    }

    public byte[] tradeObject(short messageId, TradeView view) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.projectx.trade.data.TradeObject", view.oid());
            type(out, "com.threerings.projectx.trade.data.TradeMarshaller");
            out.writeInt(view.oid());
            out.writeInt(TRADE_INVOCATION);
            tradeItems(out, view.first(), view.firstProfile(), view.firstItems());
            tradeItems(out, view.second(), view.secondProfile(), view.secondItems());
            type(out, WireTypes.DSET);
            var ids = tradeIds(view);
            out.writeInt(ids.size());
            for (int i = 0; i < ids.size(); i++) itemOrder(out, ids.get(i), i);
            primitiveInts(out, new int[] {view.firstCrowns(), view.secondCrowns()});
            primitiveInts(out, new int[] {view.firstEnergy(), view.secondEnergy()});
            out.writeInt(view.revision());
            primitiveInts(out, new int[] {view.firstAccepted(), view.secondAccepted()});
            out.writeBoolean(view.completed());
        });
    }

    public byte[] tradeChanges(TradeView before, TradeView after) throws IOException {
        var events = new ArrayList<Writer>();
        int oid = after.oid();
        var oldIds = tradeIds(before);
        var newIds = tradeIds(after);
        for (int i = 0; i < newIds.size(); i++) {
            long id = newIds.get(i);
            int order = i;
            events.add(o -> {
                event(o, oldIds.contains(id) ? "EntryUpdatedEvent" : "EntryAddedEvent", oid);
                string(o, "orderings");
                itemOrder(o, id, order);
            });
        }
        tradeItemEvents(
                events,
                oid,
                "firstItems",
                before.firstItems(),
                after.firstItems(),
                after.first(),
                after.firstProfile());
        tradeItemEvents(
                events,
                oid,
                "secondItems",
                before.secondItems(),
                after.secondItems(),
                after.second(),
                after.secondProfile());
        for (long id : oldIds) if (!newIds.contains(id)) events.add(o -> entryRemoved(o, oid, "orderings", id));
        if (before.firstCrowns() != after.firstCrowns())
            events.add(o -> element(o, oid, "crowns", 0, after.firstCrowns()));
        if (before.secondCrowns() != after.secondCrowns())
            events.add(o -> element(o, oid, "crowns", 1, after.secondCrowns()));
        if (before.firstEnergy() != after.firstEnergy())
            events.add(o -> element(o, oid, "energy", 0, after.firstEnergy()));
        if (before.secondEnergy() != after.secondEnergy())
            events.add(o -> element(o, oid, "energy", 1, after.secondEnergy()));
        if (before.revision() != after.revision())
            events.add(o -> attribute(o, oid, "revision", v -> value(v, after.revision())));
        events.add(o -> element(o, oid, "accepted", 0, after.firstAccepted()));
        events.add(o -> element(o, oid, "accepted", 1, after.secondAccepted()));
        if (before.completed() != after.completed())
            events.add(o -> attribute(o, oid, "completed", v -> value(v, after.completed())));
        return frame(out -> {
            eventHeader(out, "CompoundEvent", oid);
            type(out, WireTypes.ARRAY_LIST);
            out.writeInt(events.size());
            for (var event : events) event.write(out);
        });
    }

    private void tradeItemEvents(
            List<Writer> events,
            int oid,
            String field,
            List<WorldProfile.Item> before,
            List<WorldProfile.Item> after,
            Knight knight,
            WorldProfile owner) {
        var old = new HashMap<Long, WorldProfile.Item>();
        for (var item : before) old.put(item.id(), item);
        var ids = after.stream().map(WorldProfile.Item::id).collect(java.util.stream.Collectors.toSet());
        for (var item : before) if (!ids.contains(item.id())) events.add(o -> entryRemoved(o, oid, field, item.id()));
        for (var item : after)
            if (!item.equals(old.get(item.id())))
                events.add(o -> {
                    event(o, old.containsKey(item.id()) ? "EntryUpdatedEvent" : "EntryAddedEvent", oid);
                    string(o, field);
                    items.item(o, knight, owner.worldId(), item);
                });
    }

    private void tradeItems(DataOutputStream out, Knight knight, WorldProfile owner, List<WorldProfile.Item> items)
            throws IOException {
        type(out, WireTypes.DSET);
        out.writeInt(items.size());
        for (var item : items) this.items.item(out, knight, owner.worldId(), item);
    }

    private static List<Long> tradeIds(TradeView view) {
        var ids = new ArrayList<Long>();
        for (var item : view.firstItems()) ids.add(item.id());
        for (var item : view.secondItems()) ids.add(item.id());
        return ids;
    }

    private void itemOrder(DataOutputStream out, long id, int order) throws IOException {
        type(out, "com.threerings.projectx.trade.data.TradeObject$ItemOrder");
        out.writeLong(id);
        out.writeInt(order);
    }
}
