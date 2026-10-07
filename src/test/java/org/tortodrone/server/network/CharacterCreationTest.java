package org.tortodrone.server.network;

import java.net.*;
import java.security.*;
import java.util.concurrent.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.support.CharacterScenario;
import org.tortodrone.support.TestClient;
import org.tortodrone.support.TestWire;

/** Independent client verifies the complete creation RPC and its subsequent object update. */
public final class CharacterCreationTest {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair key = generator.generateKeyPair(); CharacterStore store = new CharacterStore();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var completed = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    SecureHandshake.run(socket, key.getPrivate(), ignored -> {}, true, 1, "127.0.0.1", listener.getLocalPort(), store);
                } return null;
            });
            try (Socket peer = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                TestClient.authenticate(peer, key.getPublic(), true, (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, true));
            }
            completed.get(5, TimeUnit.SECONDS);
            TestWire.check(store.roster().size() == 1, "only one character created");
            System.out.println("PASS: creation RPC, confirmation, roster updates, invalid requests and duplicate names");
        } finally { worker.shutdownNow(); }
    }
}
