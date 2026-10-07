package org.tortodrone.protocol;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/** Deliberately limited to the verified initial Presents handshake schema. */
public final class HandshakeCodec {
    public static final int MAX_FRAME = 65536;
    private static final String PREFIX = "com.threerings.presents.net.";
    private final Map<Integer, String> classes = new HashMap<>();

    public record Request(short messageId, byte[] encryptedSecret, byte[] salt,
                          String version, String timezone) { }

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

    private String type(DataInputStream in) throws IOException {
        int code = in.readShort();
        if (code < 0) {
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

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
