package org.tortodrone.server.network;

import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.support.TestClient;
import static org.tortodrone.support.TestWire.check;

/** End-to-end synthetic authentication; never reads game files or saved credentials. */
public final class LocalAuthenticationTest {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        exchange(key, true);
        exchange(key, false);
        rejection(key,"MissingUser","m.no_such_user");
        System.out.println("PASS: authentication, snapshots, receivers, batched requests, invocation failure callbacks, logoff, secret-free logs");
    }

    private static void rejection(KeyPair key,String username,String reason)throws Exception {
        try(var workers=Executors.newSingleThreadExecutor();var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var server=workers.submit(()->{try(var socket=listener.accept()){SecureHandshake.run(socket,key.getPrivate(),message->{},true);}return null;});
            try(var peer=new Socket(listener.getInetAddress(),listener.getLocalPort())) {
                TestClient.authenticate(peer,key.getPublic(),username,"wrong-password",reason,(a,b,c)->{});
            }
            server.get(5,TimeUnit.SECONDS);
        }
    }

    private static void exchange(KeyPair key, boolean valid) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<List<String>> result = executor.submit(() -> {
                List<String> logs = new ArrayList<>();
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    SecureHandshake.run(peer, key.getPrivate(), logs::add, true);
                }
                return logs;
            });
            try (Socket peer = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                TestClient.authenticate(peer, key.getPublic(), valid);
            }
            List<String> logs = result.get(5, TimeUnit.SECONDS);
            check(logs.stream().anyMatch(s -> s.contains(valid ? "Authentication accepted by client" : "result=rejected")), "completion evidence");
            check(logs.stream().noneMatch(s -> s.contains("password=") || s.contains("private-test-ident")
                || s.contains(TestClient.passwordHash("tortodrone"))), "secret-free logs");
        } finally {
            executor.shutdownNow();
        }
    }
}
