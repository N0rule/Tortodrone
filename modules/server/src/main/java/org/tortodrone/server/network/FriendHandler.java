package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.tortodrone.core.WireTypes;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Social service requests (friends, party suggestions, joins, party finder and unfriend), the
 *  empty feed answer and event-log telemetry for one connection. Travel and friend-list
 *  mutation stay in {@link WorldSession}; this handler validates the native shapes and writes
 *  only through the responder. */
final class FriendHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    FriendHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        int method = request.method();
        // The friend service shares service 12 with scene chat. Scene chat addresses the scene
        // object (not the global invocation object), so anything without the global oid is not a
        // friend request and must fall through to the chat dispatcher instead of being rejected.
        // Current SocialMarshaller: 1 request, 2 party suggestions, 3 join friend, 4 join secret,
        // 6 looking-for-group, 8 unfriend. Methods 5 and 7 are not served yet.
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.SOCIAL_SERVICE
                || !Set.of(1, 2, 3, 4, 6, 8).contains(method)) return false;
        var args = request.arguments();
        boolean result = method == 2;
        if (args.isEmpty() || !(args.getLast() instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(WireTypes.LISTENER_PREFIX + (result ? "ResultMarshaller" : "ConfirmMarshaller")))
            throw new SessionDecoder.Rejected("Invalid friend-list listener");
        boolean valid = switch (method) {
            case 1 -> args.size() == 4 && args.get(0) instanceof String
                && args.get(1) instanceof String && args.get(2) instanceof String;
            case 2 -> args.size() == 3 && args.get(0) instanceof Integer
                && args.get(1) instanceof List<?> names && names.size() <= 256;
            case 3, 8 -> args.size() == 2 && args.get(0) instanceof String;
            // joinOrSpectate: the secret is PlayerObject.Nq(), spectate is carried but unobserved.
            case 4 -> args.size() == 3 && args.get(0) instanceof String && args.get(1) instanceof Boolean;
            // setLookingForGroup: current build sends only the inverted toggle and difficulty.
            case 6 -> args.size() == 3 && args.get(0) instanceof Boolean
                && (args.get(1) == null || args.get(1) instanceof org.tortodrone.core.wire.EnumValue e
                    && e.type().equals(WireTypes.DIFFICULTY));
            default -> false;
        };
        if (!valid) throw new SessionDecoder.Rejected("Invalid friend-list arguments");
        try {
            if (result) {
                responder.send(() -> responder.codec().invocationResult(responder.oid(), listener.requestId(), List.of()));
                return true;
            }
            switch (method) {
                case 3 -> session.joinFriend((String) args.get(0));
                case 4 -> session.joinBySecret((String) args.get(0), (Boolean) args.get(1));
                case 6 -> session.setLookingForGroup((Boolean) args.get(0), (org.tortodrone.core.wire.EnumValue) args.get(1));
                case 8 -> session.removeFriend((String) args.get(0));
                default -> session.requestFriend((String) args.get(0), (String) args.get(1), (String) args.get(2));
            }
            responder.confirmed(listener.requestId());
        } catch (org.tortodrone.character.CharacterStore.Refused | IOException refused) {
            responder.failure(listener.requestId(), refused.getMessage());
        }
        return true;
    }

    /** Current FeedMarshaller method one; no feed is stored yet, so a bounded empty list is honest. */
    boolean feedInvocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.FEED_SERVICE
                || request.method() != 1) return false;
        var args = request.arguments();
        if (args.size() != 3 || !(args.get(0) instanceof Boolean) || !(args.get(1) instanceof Long)
                || !(args.getLast() instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(WireTypes.RESULT_LISTENER))
            throw new SessionDecoder.Rejected("Invalid feed request");
        responder.send(() -> responder.codec().invocationResult(responder.oid(), listener.requestId(), List.of()));
        return true;
    }

    /** Current EventLogMarshaller methods; view telemetry has no gameplay effect. */
    boolean eventLogInvocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.EVENT_LOG_SERVICE)
            return false;
        int method = request.method();
        var args = request.arguments();
        if (method == 1) {
            if (args.size() != 3 || !(args.get(0) instanceof org.tortodrone.core.wire.EnumValue event)
                    || !event.type().equals("com.threerings.projectx.client.EventLogService$EventHubEntry")
                    || !(args.get(1) instanceof Integer) || !(args.get(2) instanceof String))
                throw new SessionDecoder.Rejected("Invalid event log entry");
            return true;
        }
        if (method == 2) {
            if (!args.isEmpty()) throw new SessionDecoder.Rejected("Invalid event log request");
            return true;
        }
        return false;
    }
}
