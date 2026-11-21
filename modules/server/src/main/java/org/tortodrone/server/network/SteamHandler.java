package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Steam partner service compatibility. No purchase, inventory or entitlement operation is
 *  supported; client reports are acknowledged and listener calls receive native failure. */
final class SteamHandler {
    private final SessionResponder responder;

    SteamHandler(WorldSession session) {
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.STEAM_SERVICE)
            return false;
        var args = request.arguments();
        if (request.method() == 2 || request.method() == 5) {
            Object value = args.size() == 1 ? args.get(0) : null;
            if (request.method() == 2 ? !(value instanceof Integer) : !(value instanceof Boolean))
                throw new SessionDecoder.Rejected("Invalid steam client report");
            return true;
        }
        for (Object argument : args)
            if (argument instanceof SessionDecoder.Listener listener)
                responder.failure(listener.requestId(), "Steam features are not available on this server.");
        return true;
    }
}
