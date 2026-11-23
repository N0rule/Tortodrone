package org.tortodrone.server.network;

import java.util.LinkedHashMap;

/** Bounded receipt cache for idempotent native item requests on one connection.
 *
 * <p>Stock listener ids wrap, so a retry is identified by the world plus the request id
 * only within this connection. Nothing here is persisted and the oldest receipt is
 * discarded at {@value #LIMIT}; two item flows (box opening and confetti) share it.
 */
final class RequestReceipts<T> {
    static final int LIMIT = 256;

    record Key(int world, short request) {}

    private final LinkedHashMap<Key, T> values = new LinkedHashMap<>();

    synchronized T get(int world, short request) {
        return values.get(new Key(world, request));
    }

    synchronized boolean contains(int world, short request) {
        return values.containsKey(new Key(world, request));
    }

    synchronized void put(int world, short request, T value) {
        values.put(new Key(world, request), value);
        while (values.size() > LIMIT) values.remove(values.keySet().iterator().next());
    }

    synchronized T putIfAbsent(int world, short request, T value) {
        var key = new Key(world, request);
        var prior = values.get(key);
        if (prior != null) return prior;
        put(world, request, value);
        return null;
    }
}
