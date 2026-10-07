package org.tortodrone.server;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import org.tortodrone.protocol.HandshakeCodec;

/** Synthetic protocol fixtures only; requires neither game files nor captured traffic. */
public final class HandshakeTest {
    private static final String PREFIX = "com.threerings.presents.net.";

    public static void main(String[] args) throws Exception {
        for (int length : new int[] {0, 4, 5, 65537, -1}) {
            expectFailure(() -> HandshakeCodec.readFrame(new ByteArrayInputStream(intBytes(length))));
        }
        for (byte[] partial : new byte[][] {{0}, {0, 0, 0}, {0, 0, 0, 8, 1}}) {
            expectFailure(() -> HandshakeCodec.readFrame(new ByteArrayInputStream(partial)));
        }
        check(HandshakeCodec.readFrame(new ByteArrayInputStream(new byte[0])) == null, "clean EOF");
        byte[] response = HandshakeCodec.response((short) 7, "0123456789abcdef0123456789abcdef");
        ByteArrayOutputStream paired = new ByteArrayOutputStream();
        paired.write(response); paired.write(response);
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(paired.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        check(Arrays.equals(HandshakeCodec.readFrame(fragmented), Arrays.copyOfRange(response, 4, response.length)), "fragmented frame");
        check(HandshakeCodec.readFrame(fragmented).length == 135, "coalesced frame");
        check(HandshakeCodec.readFrame(fragmented) == null, "frame boundary");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        roundTrip(key, false);
        roundTrip(key, true);
        System.out.println("PASS: frame bounds/truncation/fragmentation/coalescing, RSA exchange, salt rejection, next-message mapping");
    }

    private static void roundTrip(KeyPair key, boolean badSalt) throws Exception {
        byte[] plain = new byte[20];
        new SecureRandom().nextBytes(plain);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key.getPublic());
        byte[] salt = Arrays.copyOfRange(plain, 16, 20);
        if (badSalt) salt[0] ^= 1;
        byte[] request = request(cipher.doFinal(plain), salt);
        // Parsing rejects unsupported trailing fields rather than silently accepting a changed schema.
        expectFailure(() -> new HandshakeCodec().request(Arrays.copyOfRange(
            Arrays.copyOf(request, request.length + 1), 4, request.length + 1)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<List<String>> result = executor.submit(() -> {
                List<String> logs = new ArrayList<>();
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    try {
                        SecureHandshake.run(peer, key.getPrivate(), logs::add);
                        check(!badSalt, "bad salt accepted");
                    } catch (IOException e) {
                        if (!badSalt) throw e;
                        check(e.getMessage().equals("Invalid secret/salt"), "salt rejection reason");
                    }
                }
                return logs;
            });
            try (Socket peer = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                peer.setSoTimeout(3000);
                peer.getOutputStream().write(request, 0, 2);
                peer.getOutputStream().write(request, 2, request.length - 2);
                byte[] body = HandshakeCodec.readFrame(peer.getInputStream());
                if (badSalt) {
                    check(body == null, "response to invalid salt");
                } else {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
                    check(in.readShort() == -1 && in.readUTF().equals(PREFIX + "SecureResponse"), "response class");
                    check(in.readShort() == 7, "response correlation");
                    check(in.readShort() == -2 && in.readUTF().equals(PREFIX + "AuthResponseData"), "data class");
                    check(in.readInt() == 0 && in.readBoolean(), "data fields");
                    check(in.readUTF().matches("[0-9a-f]{32}") && in.available() == 0, "masked secret");
                    ByteArrayOutputStream next = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(next);
                    out.writeShort(-4); out.writeUTF(PREFIX + "AESAuthRequest");
                    peer.getOutputStream().write(frame(next.toByteArray()));
                    check(peer.getInputStream().read() == -1, "stop before authentication");
                }
            }
            List<String> logs = result.get(5, TimeUnit.SECONDS);
            check(badSalt || logs.stream().anyMatch(s -> s.startsWith("AESAuthRequest received")), "next request identified");
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] request(byte[] encrypted, byte[] salt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(-1); out.writeUTF(PREFIX + "SecureRequest"); out.writeShort(7);
        out.writeShort(-2); out.writeUTF(PREFIX + "PublicKeyCredentials");
        out.writeBoolean(true); out.writeInt(encrypted.length); out.write(encrypted);
        out.writeBoolean(true); out.writeInt(salt.length); out.write(salt);
        out.writeInt(1);
        out.writeBoolean(true); out.writeUTF("synthetic-build");
        out.writeBoolean(true); out.writeUTF("UTC");
        out.writeShort(-3); out.writeUTF("[Ljava.lang.String;"); out.writeInt(0); out.writeShort(0);
        return frame(bytes.toByteArray());
    }

    private static byte[] frame(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(intBytes(body.length + 4)); bytes.write(body);
        return bytes.toByteArray();
    }

    private static byte[] intBytes(int n) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(n);
        return bytes.toByteArray();
    }

    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected malformed input rejection");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
