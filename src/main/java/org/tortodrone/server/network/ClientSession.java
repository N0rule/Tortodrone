package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Authenticated objects, receiver registration, and limited invocation handling. */
final class ClientSession {
    static final String SAVED_WITHOUT_WORLD = "Knight saved. Reconnect to view it; world entry is not available yet.";
    static final String UPDATED_WITHOUT_WORLD = "Knight updated. Reconnect to view it; world entry is not available yet.";
    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log) throws Exception {
        run(socket, incoming, connectionId, host, port, log, new CharacterStore());
    }

    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log, CharacterStore characters) throws Exception {
        SessionCodec outgoing = new SessionCodec();
        int clientOid = Math.addExact(1000, connectionId);
        send(socket, outgoing.bootstrap(connectionId, clientOid, host, port));
        log.debug("BootstrapNotification sent; clientOid=" + clientOid + "; services=" + SessionCodec.SERVICES.size());
        socket.setSoTimeout(60000); // Current client heartbeat period exceeds the handshake timeout.
        Map<String, Short> receivers = new HashMap<>();
        boolean firstPing = true;
        byte[] body;
        while ((body = HandshakeCodec.readFrame(socket.getInputStream())) != null) {
            long receivedAt = System.currentTimeMillis();
            for (HandshakeCodec.SessionRequest request : incoming.sessionRequests(body)) {
                switch (request.type()) {
                    case "PingRequest" -> {
                        send(socket, outgoing.pong(request.messageId(), receivedAt));
                        if (firstPing) {
                            log.debug("Authentication accepted by client; PingRequest received; PongResponse sent");
                            firstPing = false;
                        }
                    }
                    case "SubscribeRequest" -> {
                        log.debug("SubscribeRequest received; oid=" + request.objectId()
                            + (request.objectId() == clientOid ? "; client bootstrap accepted" : ""));
                        if (request.objectId() == clientOid) {
                            var roster = characters.roster();
                            send(socket, outgoing.player(request.messageId(), clientOid, 1, CharacterStore.USERNAME, receivers, roster));
                            log.debug("PlayerObject snapshot sent; available knights=" + roster.size());
                        } else if (request.objectId() == SessionCodec.SERVER_OID) {
                            send(socket, outgoing.server(request.messageId()));
                            log.debug("ServerObject snapshot sent");
                        } else if (request.objectId() == SessionCodec.CONFIG_OID) {
                            send(socket, outgoing.configuration(request.messageId()));
                            log.debug("DConfigObject snapshot sent");
                        } else {
                            send(socket, outgoing.subscriptionFailure(request.messageId(), request.objectId(), "No such object"));
                            log.debug("Subscription to unknown object rejected");
                        }
                    }
                    case "UnsubscribeRequest" -> send(socket,
                        outgoing.unsubscribe(request.messageId(), request.objectId()));
                    case "LogoffRequest" -> {
                        log.debug("Client logged off");
                        return;
                    }
                    case "ForwardEventRequest" -> handleEvent(socket, outgoing, request.event(), clientOid, receivers, log, characters);
                    default -> throw new IllegalStateException("Unsupported session request");
                }
            }
        }
        log.debug("Client disconnected");
    }

    private static void handleEvent(Socket socket, SessionCodec outgoing, SessionDecoder.Event event,
            int clientOid, Map<String, Short> receivers, SessionLog log, CharacterStore characters) throws IOException {
        if (event instanceof SessionDecoder.Compound compound) {
            if (compound.oid() != clientOid) throw new SessionDecoder.Rejected("Invalid compound event target");
            for (var child : compound.events()) handleEvent(socket, outgoing, child, clientOid, receivers, log, characters);
        } else if (event instanceof SessionDecoder.Mutation mutation) {
            if (mutation.oid() != clientOid || !"receivers".equals(mutation.field())) {
                throw new SessionDecoder.Rejected("Unauthorized object mutation");
            }
            switch (mutation.operation()) {
                case "AttributeChangedEvent" -> {
                    if (!(mutation.value() instanceof List<?> values) || !values.isEmpty()) {
                        throw new SessionDecoder.Rejected("Unsupported receiver reset");
                    }
                    receivers.clear();
                }
                case "EntryAddedEvent", "EntryUpdatedEvent" -> {
                    if (!(mutation.value() instanceof SessionDecoder.Registration registration)
                            || registration.code() == null || registration.code().isBlank()
                            || registration.code().length() > 256
                            || (receivers.size() >= 128 && !receivers.containsKey(registration.code()))) {
                        throw new SessionDecoder.Rejected("Invalid receiver registration");
                    }
                    for (var existing : receivers.entrySet()) {
                        if (!existing.getKey().equals(registration.code()) && existing.getValue() == registration.id()) {
                            throw new SessionDecoder.Rejected("Duplicate receiver ID");
                        }
                    }
                    receivers.put(registration.code(), registration.id());
                    log.debug("Invocation receiver registered; count=" + receivers.size());
                }
                case "EntryRemovedEvent" -> {
                    if (!(mutation.value() instanceof String code)) throw new SessionDecoder.Rejected("Invalid receiver removal");
                    receivers.remove(code);
                }
                default -> throw new SessionDecoder.Rejected("Unsupported receiver mutation");
            }
        } else if (event instanceof SessionDecoder.Invocation invocation) {
            if (invocation.oid() != SessionCodec.INVOCATION_OID || invocation.service() > SessionCodec.SERVICES.size()) {
                throw new SessionDecoder.Rejected("Invalid invocation target");
            }
            String service = SessionCodec.SERVICES.get(invocation.service() - 1);
            log.debug("InvocationRequestEvent received; service=" + service.substring(service.lastIndexOf('.') + 1)
                + "; method=" + invocation.method() + "; arguments=" + invocation.arguments().size());
            // Initial hardware statistics are advisory and are deliberately not retained.
            if (invocation.service() == 1 && invocation.method() == 10 && invocation.arguments().size() == 9) return;
            if (invocation.service() == 1 && (invocation.method() == 3 || invocation.method() == 5 || invocation.method() == 6)) {
                characterRequest(socket, outgoing, invocation, clientOid, log, characters);
                return;
            }
            for (Object argument : invocation.arguments()) {
                if (argument instanceof SessionDecoder.Listener listener) {
                    String reason = invocation.service() == 1 && invocation.method() == 4
                        ? "World entry is not available yet" : "Server operation not implemented yet";
                    send(socket, outgoing.invocationFailure(clientOid, listener.requestId(), reason));
                }
            }
        }
    }

    private static void characterRequest(Socket socket, SessionCodec outgoing, SessionDecoder.Invocation invocation,
            int clientOid, SessionLog log, CharacterStore characters) throws IOException {
        var args = invocation.arguments();
        if (args.isEmpty() || !(args.get(args.size() - 1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Missing character confirmation listener");
        }
        String failure = null;
        if (invocation.method() == 3) {
            failure = args.size() == 2 && args.get(0) instanceof String name
                ? characters.nameFailure(name) : "Invalid name validation request";
        } else if (invocation.method() == 6) {
            if (args.size() != 2 || !(args.get(0) instanceof String name)) {
                failure = "Invalid character deletion request";
            } else {
                try {
                    characters.delete(name);
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character deletion failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be deleted; try again";
                }
                if (failure == null) {
                    send(socket, outgoing.rosterChanged(clientOid, characters.roster()));
                    log.info("Character deleted; available knights=" + characters.roster().size());
                }
            }
        } else {
            if (args.size() != 14 || !optionalString(args.get(0)) || !(args.get(1) instanceof String)
                    || !(args.get(2) instanceof String) || !(args.get(3) instanceof String)
                    || !optionalString(args.get(4)) || !optionalString(args.get(5)) || !(args.get(6) instanceof String)
                    || !(args.get(7) instanceof Integer) || !enumeration(args.get(8), "KnightHeight")
                    || !enumeration(args.get(9), "EyeType") || args.get(10) != null
                    || !(args.get(11) instanceof List<?> friends) || !friends.isEmpty() || args.get(12) != null) {
                failure = "Invalid character save request";
            } else {
                try {
                    Knight choices = new Knight(0, (String) args.get(1), (String) args.get(2), (String) args.get(3),
                        (String) args.get(4), (String) args.get(5), (String) args.get(6), (Integer) args.get(7),
                        ((SessionDecoder.EnumValue) args.get(8)).ordinal(), ((SessionDecoder.EnumValue) args.get(9)).ordinal(), 0);
                    if (args.get(0) == null) characters.create(choices);
                    else characters.update((String) args.get(0), choices);
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character save failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be saved; try again";
                }
                if (failure == null) {
                    send(socket, outgoing.rosterChanged(clientOid, characters.roster()));
                    log.info("Character saved; available knights=" + characters.roster().size());
                    // The stock creator's success handler leaves its busy state active,
                    // expecting selected-character/world events that we cannot provide yet.
                    // Its failure handler clears that state and displays this explicit result.
                    failure = args.get(0) == null ? SAVED_WITHOUT_WORLD : UPDATED_WITHOUT_WORLD;
                }
            }
        }
        send(socket, failure == null ? outgoing.invocationConfirmed(clientOid, listener.requestId())
            : outgoing.invocationFailure(clientOid, listener.requestId(), failure));
    }

    private static boolean optionalString(Object value) { return value == null || value instanceof String; }
    private static boolean enumeration(Object value, String name) {
        return value instanceof SessionDecoder.EnumValue enumeration
            && enumeration.type().equals("com.threerings.projectx.data.ProjectXCodes$" + name);
    }

    private static void send(Socket socket, byte[] frame) throws java.io.IOException {
        socket.getOutputStream().write(frame);
        socket.getOutputStream().flush();
    }
}
