package org.tortodrone.support;

import java.io.*;
import java.net.Socket;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.tortodrone.protocol.HandshakeCodec;

/** Synthetic authentication and session client shared by socket and packaged-JAR tests. */
public final class TestClient {
    private static final String PREFIX = "com.threerings.presents.net.";

    /** The same synthetic client also exercises the packaged server in another process. */
    public static int authenticate(Socket peer, PublicKey publicKey, boolean valid) throws Exception {
        return authenticate(peer, publicKey, valid, TestClient::session);
    }

    @FunctionalInterface public interface Session { void run(Socket peer, TestWire wire, int oid) throws Exception; }

    public static int authenticate(Socket peer, PublicKey publicKey, boolean valid, Session session) throws Exception {
        return authenticate(peer, publicKey, "tortodrone", valid ? "tortodrone" : "wrong-synthetic-password", valid, session);
    }

    public static int authenticate(Socket peer, PublicKey publicKey, String username, String password,
            boolean valid, Session session) throws Exception {
        return authenticate(peer,publicKey,username,password,valid ? "success" : "m.invalid_password",session);
    }
    public static int authenticate(Socket peer, PublicKey publicKey, String username, String password,
            String expectedResult, Session session) throws Exception {
        return authenticate(peer,publicKey,username,password,expectedResult,8000,session);
    }
    public static int authenticate(Socket peer, PublicKey publicKey, String username, String password,
            String expectedResult, int timeoutMillis, Session session) throws Exception {
        boolean valid="success".equals(expectedResult);
        peer.setSoTimeout(timeoutMillis);
        byte[] plain = new byte[20];
        new SecureRandom().nextBytes(plain);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
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
        byte[] credentials = credentials(username, password);
        try (var decoded = HandshakeCodec.credentials(credentials)) {
            check(decoded.normalLogin() && decoded.username().equals(username), "normal credential envelope");
        }
        // Unknown fields must not be silently ignored in a credential envelope.
        byte[] trailing = Arrays.copyOf(credentials, credentials.length + 1);
        try {
            HandshakeCodec.credentials(trailing);
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
        check(response.readUTF().equals(expectedResult), "auth result: "+expectedResult);
        check(!response.readBoolean(), "accepted and rejected logins preserve the client's installation ident");
        check(response.readBoolean() == valid, "session presence");
        if (valid) UUID.fromString(response.readUTF());
        if (valid) {
            check(response.readShort() == -5 && response.readUTF().equals("com.threerings.util.Name"), "name mapping");
            check(response.readBoolean() && response.readUTF().equalsIgnoreCase(username), "authenticated account name");
        } else check(response.readShort() == 0, "rejected authentication has no account name");
        check(response.available() == 0, "response boundary");
        int clientOid = 0;
        if (valid) {
            TestWire wire = new TestWire();
            clientOid = wire.bootstrap(wire.frame(peer.getInputStream()), peer.getPort(), peer.getInetAddress().getHostAddress());
            session.run(peer, wire, clientOid);
        }
        check(peer.getInputStream().read() == -1, valid ? "clean logoff" : "rejected authentication closes connection");
        Arrays.fill(credentials, (byte) 0); Arrays.fill(shared, (byte) 0); Arrays.fill(plain, (byte) 0);
        return clientOid;
    }

    /** Closes an authenticated session cleanly when the scenario only needs the handshake. */
    public static void logoff(Socket peer) throws Exception {
        TestWire.Writer writer = new TestWire.Writer(PREFIX + "SecureRequest", PREFIX + "PublicKeyCredentials",
            "[Ljava.lang.String;", PREFIX + "AESAuthRequest");
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 30));
    }

    private static void session(Socket peer, TestWire wire, int clientOid) throws Exception {
        TestWire.Writer writer = new TestWire.Writer(PREFIX + "SecureRequest", PREFIX + "PublicKeyCredentials",
            "[Ljava.lang.String;", PREFIX + "AESAuthRequest");
        for (short id = 13; id <= 14; id++) {
            final short sequence = id;
            writer.send(peer.getOutputStream(), out -> writer.message(out, "PingRequest", sequence));
            pong(wire, wire.frame(peer.getInputStream()), id);
        }
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 15); out.writeInt(clientOid); });
        Map<String, Object> player = wire.snapshot(wire.frame(peer.getInputStream()), (short) 15, clientOid);
        check(player.get("username").equals("tortodrone") && player.get("availableKnights").equals(List.of()), "player snapshot");

        // The installed InvocationDirector resets and registers receivers in one transaction.
        writer.send(peer.getOutputStream(), out -> {
            writer.message(out, "ForwardEventRequest", 16);
            writer.type(out, TestWire.DOB + "CompoundEvent"); out.writeInt(clientOid);
            writer.type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(2);
            writer.type(out, TestWire.DOB + "AttributeChangedEvent"); out.writeInt(clientOid); string(out, "receivers");
            writer.type(out, TestWire.DOB + "DSet"); out.writeInt(0);
            writer.type(out, TestWire.DOB + "EntryAddedEvent"); out.writeInt(clientOid); string(out, "receivers");
            writer.type(out, "com.threerings.presents.client.InvocationReceiver$Registration"); string(out, "test-receiver"); out.writeShort(0);
        });
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 17); out.writeInt(clientOid); });
        player = wire.snapshot(wire.frame(peer.getInputStream()), (short) 17, clientOid);
        check(player.get("receivers").equals(List.of(Map.of("code", "test-receiver", "id", (short) 0))), "receiver registration retained");

        writer.send(peer.getOutputStream(), out -> {
            writer.message(out, "ForwardEventRequest", 18);
            writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3); out.writeInt(1); out.writeByte(3);
            out.writeBoolean(true); out.writeInt(2); writer.type(out, "com.threerings.util.Name"); string(out, "test-knight");
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(23);
        });
        DataInputStream failure = wire.frame(peer.getInputStream());
        check(wire.type(failure).equals(PREFIX + "EventNotification") && failure.readShort() == -1, "invocation event notification");
        check(wire.type(failure).equals(TestWire.DOB + "InvocationResponseEvent") && failure.readInt() == clientOid, "callback targets requesting player");
        check(failure.readShort() == 23 && failure.readByte() == 1, "name listener correlation and confirmation method");
        check(!failure.readBoolean(), "confirmation has no argument array");
        TestWire.end(failure);

        // Current non-Steam character creation uses fourteen arguments, including enums and a typed list.
        writer.send(peer.getOutputStream(), out -> {
            writer.message(out, "ForwardEventRequest", 18);
            writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3); out.writeInt(1); out.writeByte(5);
            out.writeBoolean(true); out.writeInt(14); out.writeShort(0);
            writer.type(out, "com.threerings.util.Name"); string(out, "x");
            for (String asset : List.of("Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", "shield", "weapon", "sprite")) {
                writer.type(out, "java.lang.String"); out.writeUTF(asset);
            }
            writer.type(out, "java.lang.Integer"); out.writeInt(1);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$KnightHeight"); out.writeByte(2);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$EyeType"); out.writeByte(0);
            out.writeShort(0); writer.type(out, "java.util.List"); out.writeInt(0); out.writeShort(0);
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(24);
        });
        failure = wire.frame(peer.getInputStream());
        check(wire.type(failure).equals(PREFIX + "EventNotification") && failure.readShort() == -1, "creation event response");
        check(wire.type(failure).equals(TestWire.DOB + "InvocationResponseEvent") && failure.readInt() == clientOid
            && failure.readShort() == 24 && failure.readByte() == 0, "creation failure callback correlation");
        check(failure.readBoolean() && failure.readInt() == 1 && wire.object(failure).equals("~Knight names need at least three letters"), "invalid creation does not mutate roster");
        TestWire.end(failure);

        // Batched upstream messages share the same class table and individual request IDs.
        writer.send(peer.getOutputStream(), out -> {
            writer.message(out, "CompoundUpstreamMessage", 19); out.writeBoolean(true); out.writeInt(2);
            writer.message(out, "PingRequest", 20);
            writer.message(out, "SubscribeRequest", 21); out.writeInt(1);
        });
        pong(wire, wire.frame(peer.getInputStream()), (short) 20);
        var server = wire.snapshot(wire.frame(peer.getInputStream()), (short) 21, 1);
        check(server.get("scopeVariables").equals(List.of()), "server initialization collections");
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 22); out.writeInt(2); });
        wire.snapshot(wire.frame(peer.getInputStream()), (short) 22, 2);
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 23); out.writeInt(clientOid + 1); });
        failure = wire.frame(peer.getInputStream());
        check(wire.type(failure).equals(PREFIX + "FailureResponse") && failure.readShort() == 23
            && failure.readInt() == clientOid + 1 && TestWire.string(failure).equals("No such object"), "other player object inaccessible");
        TestWire.end(failure);
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "UnsubscribeRequest", 24); out.writeInt(2); });
        DataInputStream unsub = wire.frame(peer.getInputStream());
        check(wire.type(unsub).equals(PREFIX + "UnsubscribeResponse") && unsub.readShort() == 24 && unsub.readInt() == 2, "unsubscribe correlation");
        TestWire.end(unsub);
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 25));
    }

    private static void pong(TestWire wire, DataInputStream pong, short id) throws IOException {
        check(wire.type(pong).equals(PREFIX + "PongResponse") && pong.readShort() == id, "pong correlation and persistent mapping");
        check(pong.readLong() > 0 && pong.readInt() >= 0, "pong timestamp"); TestWire.end(pong);
    }

    private static byte[] credentials(String username, String password) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(-1); out.writeUTF("com.threerings.projectx.data.ProjectXCredentials");
        out.writeShort(-2); out.writeUTF("com.threerings.util.Name"); string(out, username);
        string(out, passwordHash(password));
        string(out, "private-test-ident"); string(out, "en");
        out.writeBoolean(false); // session
        out.writeBoolean(false); // ticket
        out.writeBoolean(false); out.writeBoolean(false); out.writeInt(0); // platform flags and site
        out.writeBoolean(false); string(out, ""); // invite and region
        return bytes.toByteArray();
    }

    public static String passwordHash(String password) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(
                password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
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
