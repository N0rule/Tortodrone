package org.tortodrone.server.network;

import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.support.MultiSessionScenario;

public final class RosterConcurrencyTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-roster-");
        var content = org.tortodrone.resources.ContentManager.load(Path.of("."));
        AccountStore accounts = new AccountStore(directory, false, content.items(), content.town());
        accounts.create("System", "system-password".toCharArray());
        accounts.create("Alice", "alice-password".toCharArray()); accounts.create("Bob", "bob-password".toCharArray());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KeyPair keys = generator.generateKeyPair();
        ExecutorService workers = Executors.newCachedThreadPool();
        List<Future<?>> sessions = new CopyOnWriteArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 3, InetAddress.getLoopbackAddress())) {
            Future<?> acceptor = workers.submit(() -> {
                try {
                    for (int id = 1; id <= 4; id++) {
                        Socket socket = listener.accept(); int connectionId = id;
                        sessions.add(workers.submit(() -> {
                            try (socket) { SecureHandshake.run(socket, keys.getPrivate(), ignored -> {}, true, connectionId,
                                "127.0.0.1", listener.getLocalPort(), accounts); }
                            catch (java.net.SocketException expectedDisconnect) { }
                            catch (Exception error) { throw new RuntimeException(error); }
                        }));
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            MultiSessionScenario.run(listener.getLocalPort(), keys.getPublic());
            acceptor.get(10, TimeUnit.SECONDS);
            for (Future<?> session : sessions) session.get(10, TimeUnit.SECONDS);
            System.out.println("PASS: authenticated replacement, private rosters, edits/deletion, subscriptions and abrupt reconnect");
        } finally {
            workers.shutdownNow();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
