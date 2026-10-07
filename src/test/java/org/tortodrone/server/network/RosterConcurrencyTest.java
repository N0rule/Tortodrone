package org.tortodrone.server.network;

import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.support.MultiSessionScenario;

public final class RosterConcurrencyTest {
    public static void main(String[] args) throws Exception {
        AccountStore accounts = new AccountStore(new CharacterStore());
        accounts.create("Alice", "alice-password".toCharArray()); accounts.create("Bob", "bob-password".toCharArray());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KeyPair keys = generator.generateKeyPair();
        ExecutorService workers = Executors.newCachedThreadPool();
        List<Future<?>> sessions = new CopyOnWriteArrayList<>();
        try (ServerSocket listener = new ServerSocket(0, 3, InetAddress.getLoopbackAddress())) {
            Future<?> acceptor = workers.submit(() -> {
                try {
                    for (int id = 1; id <= 3; id++) {
                        Socket socket = listener.accept(); int connectionId = id;
                        sessions.add(workers.submit(() -> {
                            try (socket) { SecureHandshake.run(socket, keys.getPrivate(), ignored -> {}, true, connectionId,
                                "127.0.0.1", listener.getLocalPort(), accounts); }
                            catch (Exception error) { throw new RuntimeException(error); }
                        }));
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            MultiSessionScenario.run(listener.getLocalPort(), keys.getPublic());
            acceptor.get(10, TimeUnit.SECONDS);
            for (Future<?> session : sessions) session.get(10, TimeUnit.SECONDS);
            System.out.println("PASS: live create/edit/delete, account isolation, resubscription, unsubscribe, concurrent saves and disconnect cleanup");
        } finally { workers.shutdownNow(); }
    }
}
