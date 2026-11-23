package org.tortodrone.account;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Live account attachments: the single-session takeover protocol, the per-account
 *  attach lock and the replacement watcher. Account identity and roster access stay in
 *  {@link AccountStore}; this class only decides which session is current. */
final class AccountAttachments {
    /** Identity lookups the attachment protocol needs from its account owner. */
    interface Host {
        boolean current(AccountStore.Account account) throws IOException;

        boolean gameMaster(AccountStore.Account account) throws IOException;
    }

    private final Map<Integer, Attached> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, Object> attachmentLocks = new ConcurrentHashMap<>();
    private ScheduledExecutorService watcher;

    boolean attached(int account) {
        return sessions.containsKey(account);
    }

    /** Shared with offline administration so a session cannot attach mid-write. */
    Object lock(int account) {
        return attachmentLocks.computeIfAbsent(account, id -> new Object());
    }

    AccountStore.Session attach(AccountStore.Account account, Socket socket, Host host) throws IOException {
        synchronized (attachmentLocks.computeIfAbsent(account.id(), id -> new Object())) {
            Attached next = new Attached(account, socket, host), previous;
            synchronized (this) {
                if (!host.current(account) || socket.isClosed()) {
                    socket.close();
                    throw new IOException("Account changed during login");
                }
                previous = sessions.put(account.id(), next);
            }
            // Serialize takeovers per account; a third login cannot overtake old cleanup.
            if (previous != null) previous.close();
            return next;
        }
    }

    private final class Attached implements AccountStore.Session {
        final AccountStore.Account account;
        final Socket socket;
        final Host host;
        private volatile boolean live = true;
        private Runnable cleanup;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        Attached(AccountStore.Account account, Socket socket, Host host) {
            this.account = account;
            this.socket = socket;
            this.host = host;
        }

        @Override
        public boolean current() {
            return live && !socket.isClosed() && sessions.get(account.id()) == this;
        }

        @Override
        public boolean gameMaster() throws IOException {
            if (!current()) throw new IOException("Session replaced");
            return host.gameMaster(account);
        }

        @Override
        public void onInvalidated(Runnable action) {
            synchronized (this) {
                if (live) {
                    cleanup = action;
                    return;
                }
            }
            action.run();
        }

        @Override
        public void close() {
            Runnable action;
            boolean owner;
            synchronized (this) {
                owner = live;
                live = false;
                action = cleanup;
                cleanup = null;
            }
            if (!owner) {
                finished.join();
                return;
            }
            try {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                if (action != null) action.run();
            } finally {
                sessions.remove(account.id(), this);
                finished.complete(null);
            }
        }
    }

    void watch(Host owner) {
        synchronized (this) {
            if (watcher != null) return;
            watcher = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread t = new Thread(task, "AccountSessions");
                t.setDaemon(true);
                return t;
            });
        }
        watcher.scheduleWithFixedDelay(
                () -> sessions.forEach((id, session) -> {
                    try {
                        if (!owner.current(session.account) || !session.current()) session.close();
                    } catch (IOException failure) {
                        session.close();
                    }
                }),
                250,
                250,
                TimeUnit.MILLISECONDS);
    }

    synchronized void shutdownWatcher() {
        if (watcher != null) watcher.shutdownNow();
    }

    void closeSessions() {
        List.copyOf(sessions.values()).forEach(Attached::close);
    }
}
