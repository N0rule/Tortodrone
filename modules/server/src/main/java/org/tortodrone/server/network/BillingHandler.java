package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Set;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Billing service compatibility for the stock Depot panel. No payment operation, account lookup
 *  or entitlement mutation is supported. */
final class BillingHandler {
    /** Native refusal text used wherever the client accepts a plain failure. */
    static final String DISABLED = "Sorry, but P2W features are disabled on this server.";
    private final WorldSession session;
    private final SessionResponder responder;

    BillingHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    /** Current stock IDs: 6=[int,String,Confirm], 7=[int,String,Result],
     *  8=[long,boolean,Confirm]. Older archives incorrectly place method 8 at 7. */
    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.BILLING_SERVICE)
            return false;
        var a = request.arguments();
        if (a.isEmpty() || !(a.getLast() instanceof SessionDecoder.Listener listener)
                || !Set.of(org.tortodrone.core.WireTypes.CONFIRM_LISTENER,
                    org.tortodrone.core.WireTypes.RESULT_LISTENER).contains(listener.type())) return true;
        boolean result = listener.type().endsWith("$ResultMarshaller");
        boolean buy = result && switch (request.method()) {
            case 1 -> a.size() == 4 && a.get(0) instanceof String && a.get(1) instanceof Integer && a.get(2) instanceof Boolean;
            case 2 -> a.size() == 9 && a.get(0) instanceof String && a.get(1) instanceof String
                && a.get(2) instanceof Integer && a.get(3) instanceof Integer && a.get(4) instanceof String
                && a.get(5) instanceof String && a.get(6) instanceof Integer && a.get(7) instanceof Boolean;
            default -> false;
        };
        // Native buy callbacks accept BillingResponseCode.ERROR (-1). All other calls,
        // including getBillingInfo and unknown shapes, use the native requestFailed path.
        if (buy) {
            var profile = session.currentProfile();
            responder.send(() -> responder.codec().townResult(responder.oid(), listener.requestId(), -1, profile));
        } else responder.failure(listener.requestId(), DISABLED);
        return true;
    }
}
