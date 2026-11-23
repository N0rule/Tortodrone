package org.tortodrone.server.network;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tortodrone.core.character.WorldProfile;

/** Session-scoped pickups and the native four-slot quickbar. These items are not written to
 *  SQLite; they disappear when the scene changes and are published through the ordered stream
 *  under the session monitor. */
final class SessionCarriedItems {
    private final WorldSession session;
    private final Map<Long,WorldProfile.Item> items=new LinkedHashMap<>();
    private final long[] quickbar=new long[4];
    private long nextItem=-1;

    SessionCarriedItems(WorldSession session) {
        this.session = session;
    }

    Map<Long,WorldProfile.Item> items() {
        return items;
    }

    long nextId() {
        return nextItem--;
    }

    int quickbarLength() {
        return quickbar.length;
    }

    boolean carriesConsumable(long id) {
        return items.containsKey(id) && items.get(id).equippedSlot() == null;
    }

    void clearQuickbarItem(long item) {
        for (int index = 0; index < quickbar.length; index++) if (quickbar[index] == item) quickbar[index] = 0;
    }

    void quickbarSlot(int slot,long item) {
        quickbar[slot] = item;
    }

    boolean quickbarContains(long id) {
        for (long value : quickbar) if (value == id) return true;
        return false;
    }

    void addToQuickbar(long id) {
        for (int slot = 0; slot < quickbar.length; slot++) if (quickbar[slot] == 0) { quickbar[slot] = id; break; }
    }

    long[] snapshot() {
        return quickbar.clone();
    }

    void publish() throws IOException {
        synchronized (session) {
            if (session.membership() != null) session.membership().inventory(session.visibleProfile());
            for (int slot = 0; slot < quickbar.length; slot++) if (!items.containsKey(quickbar[slot])) quickbar[slot] = 0;
            var slots = quickbar.clone();
            session.responder().send(() -> session.responder().codec().quickbar(session.connectionOid(), slots));
        }
    }

    /** Remove one consumable/session pickup from the visible inventory without a store write. */
    boolean trash(long id) throws IOException {
        synchronized (session) {
            if (!items.containsKey(id)) return false;
            var item = items.get(id);
            if (item.permanent() || item.equippedSlot() != null) throw new IOException("m.internal_error");
            var before = session.visibleProfile();
            items.remove(id);
            var after = session.visibleProfile();
            session.responder().send(() -> session.responder().codec().itemChanges(session.connectionOid(),
                session.selectedKnight(), before, after));
            publish();
            return true;
        }
    }
}
