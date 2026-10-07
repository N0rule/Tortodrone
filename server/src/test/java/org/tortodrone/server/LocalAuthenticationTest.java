package org.tortodrone.server;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.tortodrone.protocol.HandshakeCodec;

/** End-to-end synthetic authentication; never reads game files or saved credentials. */
public final class LocalAuthenticationTest {
    private static final String PREFIX = "com.threerings.presents.net.";

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        exchange(key, true);
        exchange(key, false);
        System.out.println("PASS: AES credential decryption, synthetic account acceptance/rejection, response correlation/mappings, post-authentication boundary, secret-free logs");
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
                peer.setSoTimeout(3000);
                byte[] plain = new byte[20];
                new SecureRandom().nextBytes(plain);
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.ENCRYPT_MODE, key.getPublic());
                byte[] encrypted = rsa.doFinal(plain);
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(request);
                out.writeShort(-1); out.writeUTF(PREFIX + "SecureRequest"); out.writeShort(11);
                out.writeShort(-2); out.writeUTF(PREFIX + "PublicKeyCredentials");
                out.writeBoolean(true); out.writeInt(encrypted.length); out.write(encrypted);
                out.writeBoolean(true); out.writeInt(4); out.write(plain, 16, 4);
                out.writeInt(1); string(out, "synthetic-build"); string(out, "UTC");
                out.writeShort(-3); out.writeUTF("[Ljava.lang.String;"); out.writeInt(0); out.writeShort(0);
                peer.getOutputStream().write(frame(request.toByteArray()));
                DataInputStream secure = input(HandshakeCodec.readFrame(peer.getInputStream()));
                secure.readShort(); secure.readUTF();
                check(secure.readShort() == 11, "secure sequence");
                secure.readShort(); secure.readUTF(); secure.readInt(); secure.readBoolean();
                byte[] shared = HexFormat.of().parseHex(secure.readUTF());
                for (int i = 0; i < 16; i++) shared[i] ^= plain[i];
                byte[] credentials = credentials(valid);
                check(HandshakeCodec.matchesSyntheticAccount(credentials) == valid, "credential policy");
                // Unknown fields must not be silently ignored in a credential envelope.
                byte[] trailing = Arrays.copyOf(credentials, credentials.length + 1);
                try {
                    HandshakeCodec.matchesSyntheticAccount(trailing);
                    throw new AssertionError("Trailing credential fields accepted");
                } catch (IOException expected) { }
                Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(shared, "AES"), new IvParameterSpec(
                    new byte[] {1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8}));
                byte[] cipher = aes.doFinal(credentials);
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                out = new DataOutputStream(auth);
                out.writeShort(-4); out.writeUTF(PREFIX + "AESAuthRequest"); out.writeShort(12);
                out.writeShort(0); string(out, "synthetic-build"); string(out, "UTC");
                out.writeShort(3); out.writeInt(4); out.writeShort(1); out.writeByte(15);
                for (String group : List.of("test-a", "test-b", "test-c", "test-d")) out.writeUTF(group);
                out.writeInt(cipher.length); out.write(cipher);
                peer.getOutputStream().write(frame(auth.toByteArray()));
                DataInputStream response = input(HandshakeCodec.readFrame(peer.getInputStream()));
                check(response.readShort() == -3 && response.readUTF().equals(PREFIX + "AuthResponse"), "response mapping");
                check(response.readShort() == 12, "auth sequence");
                check(response.readShort() == -4 && response.readUTF().endsWith("ProjectXAuthResponseData"), "game auth data");
                check(response.readInt() == 0 && response.readBoolean(), "auth data fields");
                check(response.readUTF().equals(valid ? "success" : "m.invalid_password"), "auth result");
                check(response.readBoolean() && response.readUTF().equals("tortodrone-local"), "local ident");
                check(response.readBoolean() == valid, "session presence");
                if (valid) UUID.fromString(response.readUTF());
                check(response.readShort() == -5 && response.readUTF().equals("com.threerings.util.Name"), "name mapping");
                check(response.readBoolean() && response.readUTF().equals("tortodrone-local-probe"), "synthetic name");
                check(response.available() == 0, "response boundary");
                if (valid) {
                    ByteArrayOutputStream ping = new ByteArrayOutputStream();
                    out = new DataOutputStream(ping);
                    out.writeShort(-5); out.writeUTF(PREFIX + "PingRequest");
                    out.writeShort(13); out.writeLong(1);
                    peer.getOutputStream().write(frame(ping.toByteArray()));
                }
                check(peer.getInputStream().read() == -1, "stop before bootstrap");
                Arrays.fill(credentials, (byte) 0); Arrays.fill(shared, (byte) 0); Arrays.fill(plain, (byte) 0);
            }
            List<String> logs = result.get(5, TimeUnit.SECONDS);
            check(logs.stream().anyMatch(s -> s.contains(valid ? "Authentication accepted by client" : "result=rejected")), "completion evidence");
            check(logs.stream().noneMatch(s -> s.contains("synthetic-unused") || s.contains("private-test-ident") || s.contains("43e396")), "secret-free logs");
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] credentials(boolean valid) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(-1); out.writeUTF("com.threerings.projectx.data.ProjectXCredentials");
        out.writeShort(-2); out.writeUTF("com.threerings.util.Name"); string(out, "tortodrone-local-probe");
        string(out, HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(
            (valid ? "synthetic-unused" : "wrong-synthetic-password").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        string(out, "private-test-ident"); string(out, "en");
        out.writeBoolean(false); // session
        out.writeBoolean(false); // ticket
        out.writeBoolean(false); out.writeBoolean(false); out.writeInt(0); // platform flags and site
        out.writeBoolean(false); string(out, ""); // invite and region
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(true); out.writeUTF(value);
    }
    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(Objects.requireNonNull(bytes)));
    }
    private static byte[] frame(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(body.length + 4); bytes.write(body);
        return bytes.toByteArray();
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
