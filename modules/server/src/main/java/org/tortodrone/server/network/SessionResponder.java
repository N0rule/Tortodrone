package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.SessionCodec;

/** Owns the durable outbound stream for one session. Feature handlers encode through this
 *  responder and never touch the socket, its queue or the connection thread directly. */
final class SessionResponder {
    private final SessionOutput output;
    private final SessionCodec codec;
    private final int oid;

    SessionResponder(SessionOutput output, SessionCodec codec, int oid) {
        this.output = output;
        this.codec = codec;
        this.oid = oid;
    }

    SessionCodec codec() {
        return codec;
    }

    int oid() {
        return oid;
    }

    /** Reliable state change; never dropped by the output budget. */
    void send(SessionOutput.Encoder frame) throws IOException {
        output.send(frame);
    }

    /** Liveness probe for cross-session delivery; it never writes a frame. */
    boolean open() {
        return output.open();
    }

    /** End the frame queue after the final frame; the native rename/kick path. */
    void disconnect() {
        output.disconnect();
    }

    /** Optional frames may skip encoding once this session is over budget. */
    boolean optionalOverBudget() {
        return output.optionalOverBudget();
    }

    boolean sendOptional(SessionOutput.Encoder frame) throws IOException {
        return output.sendOptional(frame);
    }

    void confirmed(short requestId) throws IOException {
        send(() -> codec.invocationConfirmed(oid, requestId));
    }

    void failure(short requestId, String reason) throws IOException {
        send(() -> codec.invocationFailure(oid, requestId, reason));
    }

    void result(short requestId, Object value, WorldProfile profile) throws IOException {
        send(() -> codec.townResult(oid, requestId, value, profile));
    }
}
