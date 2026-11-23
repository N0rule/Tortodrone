package org.tortodrone.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.tortodrone.core.WireTypes;

/**
 * Trusted outbound handshake frames for {@link HandshakeCodec}.
 *
 * <p>The codec keeps the stateful, untrusted-input parsing and the per-connection class/intern
 * tables. This class owns only the two fixed downstream frames and their mapping sequence
 * ({@code -1/-2} for the secure response, then {@code -3/-4/-5} for the authentication result).
 */
final class HandshakeWire {
    private static final String PREFIX = WireTypes.NET_PREFIX;
    private HandshakeWire() { }

    /** First downstream object on a fresh connection; its mapping space is independent. */
    public static byte[] response(short messageId, String maskedSecret) throws IOException {
        HandshakeCodec.require(maskedSecret.matches("[0-9a-f]{32}"), "Expected 16-byte masked secret");
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
            out.writeShort(-5); out.writeUTF(WireTypes.NAME);
            out.writeBoolean(true); out.writeUTF(username);
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        framed.writeInt(body.size() + 4);
        body.writeTo(framed);
        return frame.toByteArray();
    }

}
