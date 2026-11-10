package org.tortodrone.protocol;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/** Verified Presents handshake and connection-local mappings for the bounded session decoder. */
public final class HandshakeCodec {
    public static final int MAX_FRAME = 65536;
    private static final String PREFIX = "com.threerings.presents.net.";
    private final Map<Integer, String> classes = new HashMap<>();
    private final Map<Integer, String> interns = new HashMap<>();

    public record Request(short messageId, byte[] encryptedSecret, byte[] salt,
                          String version, String timezone) { }
    public record Authentication(short messageId, String version, int groupCount, byte[] encryptedCredentials) { }
    public record Credentials(String username, char[] passwordHash, boolean normalLogin, String language, String region) implements AutoCloseable {
        public Credentials(String username,char[] passwordHash,boolean normalLogin){this(username,passwordHash,normalLogin,null,null);}
        @Override public void close() { java.util.Arrays.fill(passwordHash, '\0'); }
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record SessionRequest(String type, short messageId, int objectId, SessionDecoder.Event event) { }

    /** Returns a frame body, or null on clean EOF. Partial frames are errors. */
    public static byte[] readFrame(InputStream source) throws IOException {
        int first = source.read();
        if (first < 0) return null;
        DataInputStream in = new DataInputStream(source);
        int size = (first << 24) | (in.readUnsignedByte() << 16)
            | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (size < 6 || size > MAX_FRAME) throw new IOException("Invalid frame length");
        byte[] body = new byte[size - 4];
        in.readFully(body);
        return body;
    }

    public Request request(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        require(type(in).equals(PREFIX + "SecureRequest"), "Expected SecureRequest");
        short id = in.readShort();
        require(type(in).equals(PREFIX + "PublicKeyCredentials"), "Expected public key credentials");
        byte[] encrypted = bytes(in, 256);
        byte[] salt = bytes(in, 4);
        require(in.readInt() == 1, "Unsupported secure version");
        String version = string(in);
        String timezone = string(in);
        require(type(in).equals("[Ljava.lang.String;"), "Expected bootstrap groups");
        require(in.readInt() == 0 && in.readUnsignedShort() == 0, "Only empty bootstrap groups supported");
        require(in.available() == 0, "Unexpected request fields");
        return new Request(id, encrypted, salt, version, timezone);
    }

    /** Only identifies the next object; authentication fields remain uninterpreted. */
    public boolean isAuthRequest(byte[] body) throws IOException {
        return type(new DataInputStream(new ByteArrayInputStream(body)))
            .equals(PREFIX + "AESAuthRequest");
    }

    public Authentication authentication(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        require(type(in).equals(PREFIX + "AESAuthRequest"), "Expected AESAuthRequest");
        short id = in.readShort();
        require(in.readShort() == 0, "Expected null outer credentials");
        String version = string(in);
        string(in); // timezone
        require(type(in).equals("[Ljava.lang.String;"), "Expected bootstrap groups");
        int groups = in.readInt();
        require(groups >= 0 && groups <= 16, "Unsupported bootstrap group count");
        int maskSize = in.readUnsignedShort();
        require(maskSize == (groups + 7) / 8, "Invalid array null mask");
        byte[] mask = new byte[maskSize];
        in.readFully(mask);
        for (int i = 0; i < groups; i++) {
            if ((mask[i / 8] & (1 << (i % 8))) != 0) in.readUTF();
        }
        int size = in.readInt();
        require(size > 0 && size <= 4096 && size % 16 == 0, "Invalid encrypted credential length");
        byte[] encrypted = new byte[size];
        in.readFully(encrypted);
        require(in.available() == 0, "Unexpected authentication fields");
        return new Authentication(id, version, groups, encrypted);
    }

    /** Uses a fresh mapping stream, as verified inside the encrypted credential envelope. */
    public static Credentials credentials(byte[] plaintext) throws IOException {
        require(plaintext.length <= 4096, "Credential envelope exceeds size limit");
        HandshakeCodec nested = new HandshakeCodec();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plaintext));
        require(nested.type(in).equals("com.threerings.projectx.data.ProjectXCredentials"), "Unsupported credentials");
        require(nested.type(in).equals("com.threerings.util.Name"), "Expected credential name");
        String username = string(in);
        String passwordHash = string(in);
        nullableString(in); // installation ident; deliberately neither retained nor logged
        String language=nullableString(in);
        String session = nullableString(in);
        // Only normal username/password authentication is supported.
        require(in.readUnsignedByte() == 0, "Platform ticket not supported");
        boolean sega = in.readBoolean();
        boolean steam = in.readBoolean();
        int site = in.readInt();
        String invite = nullableString(in);
        String region=nullableString(in);
        require(in.available() == 0, "Unexpected credential fields");
        return new Credentials(username, passwordHash.toCharArray(),
            session == null && !sega && site == 0 && invite == null,language,region);
    }

    public String nextMessageType(byte[] body) throws IOException {
        return type(new DataInputStream(new ByteArrayInputStream(body)));
    }

    /** Verified post-authentication request subset, preserving the incoming mapping table. */
    public SessionRequest sessionRequest(byte[] body) throws IOException {
        var requests = sessionRequests(body);
        require(requests.size() == 1, "Expected a single session request");
        return requests.get(0);
    }

    public java.util.List<SessionRequest> sessionRequests(byte[] body) throws IOException {
        require(body.length <= MAX_FRAME - 4, "Session frame too large");
        return new SessionDecoder(this::nullableType, this::interned).decode(body);
    }

    /** Stock ConfigReference names and argument keys are interned per connection, not per frame. */
    private String interned(DataInputStream in) throws IOException {
        int code = in.readShort();
        if (code == 0) return null;
        if (code < 0) {
            require(interns.size() < 256, "Too many string intern mappings");
            require(code != Short.MIN_VALUE && -code == interns.size() + 1, "Invalid string intern mapping");
            String value = in.readUTF();
            require(value.length() <= 256, "Interned string too long");
            interns.put(-code, value);
            return value;
        }
        String value = interns.get(code);
        require(value != null, "Known string intern mapping");
        return value;
    }

    private static String nullableString(DataInputStream in) throws IOException {
        int present = in.readUnsignedByte();
        require(present == 0 || present == 1, "Invalid string presence flag");
        return present == 0 ? null : in.readUTF();
    }

    private String type(DataInputStream in) throws IOException {
        String name = nullableType(in);
        require(name != null, "Missing class mapping");
        return name;
    }

    private String nullableType(DataInputStream in) throws IOException {
        int code = in.readShort();
        if (code == 0) return null;
        if (code < 0) {
            require(classes.size() < 256, "Too many class mappings");
            require(code != Short.MIN_VALUE && -code == classes.size() + 1,
                    "Invalid class mapping");
            String name = in.readUTF();
            require(name.length() <= 256, "Class name too long");
            classes.put(-code, name);
            return name;
        }
        String name = classes.get(code);
        require(name != null, "Unknown/null class mapping");
        return name;
    }

    private static byte[] bytes(DataInputStream in, int length) throws IOException {
        require(in.readUnsignedByte() == 1, "Missing byte array");
        require(in.readInt() == length, "Unsupported byte array length");
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static String string(DataInputStream in) throws IOException {
        require(in.readUnsignedByte() == 1, "Missing string");
        return in.readUTF();
    }

    /** First downstream object on a fresh connection; its mapping space is independent. */
    public static byte[] response(short messageId, String maskedSecret) throws IOException {
        require(maskedSecret.matches("[0-9a-f]{32}"), "Expected 16-byte masked secret");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeShort(-1);
        out.writeUTF(PREFIX + "SecureResponse");
        out.writeShort(messageId);
        out.writeShort(-2);
        out.writeUTF(PREFIX + "AuthResponseData");
        out.writeInt(0); // DObject ID: this response is not a subscribed game object.
        out.writeBoolean(true);
        out.writeUTF(maskedSecret);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        framed.writeInt(body.size() + 4);
        body.writeTo(framed);
        return frame.toByteArray();
    }

    /** Second downstream frame, after response(): preserve mappings 1 and 2. */
    public static byte[] authenticationResponse(short messageId, boolean accepted, String sessionId, String username) throws IOException {
        return authenticationResponse(messageId,accepted ? "success" : "m.invalid_password",sessionId,username);
    }
    public static byte[] authenticationResponse(short messageId, String result, String sessionId, String username) throws IOException {
        boolean accepted="success".equals(result);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeShort(-3);
        out.writeUTF(PREFIX + "AuthResponse");
        out.writeShort(messageId);
        out.writeShort(-4);
        out.writeUTF("com.threerings.projectx.data.ProjectXAuthResponseData");
        out.writeInt(0);
        out.writeBoolean(true);
        out.writeUTF(result);
        // The stock login observer persists a non-null ident outside the game
        // directory. Preserve the client's installation identity on every result.
        out.writeBoolean(false);
        out.writeBoolean(accepted);
        if (accepted) out.writeUTF(sessionId);
        if (username == null) out.writeShort(0);
        else {
            out.writeShort(-5); out.writeUTF("com.threerings.util.Name");
            out.writeBoolean(true); out.writeUTF(username);
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        framed.writeInt(body.size() + 4);
        body.writeTo(framed);
        return frame.toByteArray();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new SessionDecoder.Rejected(message);
    }
}
