package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Authenticated objects, receiver registration, and limited invocation handling. */
final class ClientSession {
    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port, SessionLog log)
            throws Exception {
        run(socket, incoming, connectionId, host, port, log, new CharacterStore());
    }

    static void run(
            Socket socket,
            HandshakeCodec incoming,
            int connectionId,
            String host,
            int port,
            SessionLog log,
            CharacterStore characters)
            throws Exception {
        run(socket, incoming, connectionId, host, port, log, characters, -1);
    }

    static void run(
            Socket socket,
            HandshakeCodec incoming,
            int connectionId,
            String host,
            int port,
            SessionLog log,
            CharacterStore characters,
            long authRevision)
            throws Exception {
        run(socket, incoming, connectionId, host, port, log, characters, authRevision, () -> {});
    }

    static void run(
            Socket socket,
            HandshakeCodec incoming,
            int connectionId,
            String host,
            int port,
            SessionLog log,
            CharacterStore characters,
            long authRevision,
            AccountStore.Session session)
            throws Exception {
        run(socket, incoming, connectionId, host, port, log, characters, authRevision, session, "");
    }

    static void run(
            Socket socket,
            HandshakeCodec incoming,
            int connectionId,
            String host,
            int port,
            SessionLog log,
            CharacterStore characters,
            long authRevision,
            AccountStore.Session session,
            String welcomeMessage)
            throws Exception {
        if (!session.current()) throw new IOException("Session replaced during login");
        try (SessionOutput output = new SessionOutput(socket)) {
            run(
                    socket,
                    incoming,
                    connectionId,
                    host,
                    port,
                    log,
                    characters,
                    output,
                    authRevision,
                    session,
                    welcomeMessage);
        }
    }

    private static void run(
            Socket socket,
            HandshakeCodec incoming,
            int connectionId,
            String host,
            int port,
            SessionLog log,
            CharacterStore characters,
            SessionOutput output,
            long authRevision,
            AccountStore.Session session,
            String welcomeMessage)
            throws Exception {
        SessionCodec outgoing = new SessionCodec(characters.catalog());
        int clientOid = Math.addExact(1000, Math.multiplyExact(2, connectionId));
        output.send(() -> outgoing.bootstrap(connectionId, clientOid, host, port));
        log.debug("BootstrapNotification sent; clientOid=" + clientOid + "; services=" + SessionCodec.SERVICES.size());
        socket.setSoTimeout(60000); // Current client heartbeat period exceeds the handshake timeout.
        Map<String, Short> receivers = new HashMap<>();
        boolean firstPing = true;
        CharacterStore.Subscription subscription = null;
        WorldSession world = new WorldSession(
                socket,
                characters,
                output,
                outgoing,
                clientOid,
                authRevision,
                log,
                org.tortodrone.world.SceneRegistry.system(),
                session::gameMaster,
                welcomeMessage);
        SessionInvocationRouter router = new SessionInvocationRouter(world, world.responder(), log);
        session.onInvalidated(world::close);
        try {
            byte[] body;
            while ((body = HandshakeCodec.readFrame(socket.getInputStream())) != null) {
                long receivedAt = System.currentTimeMillis();
                for (HandshakeCodec.SessionRequest request : incoming.sessionRequests(body)) {
                    if (!request.type().equals("PingRequest") && !request.type().equals("ThrottleUpdatedMessage"))
                        log.activity();
                    if (!session.current()) throw new IOException("Session replaced");
                    switch (request.type()) {
                        case "ThrottleUpdatedMessage" -> log.debug("Client acknowledged TCP input throttle update");
                        case "PingRequest" -> {
                            output.send(() -> outgoing.pong(request.messageId(), receivedAt));
                            if (firstPing) {
                                log.debug("Authentication accepted by client; PingRequest received; PongResponse sent");
                                firstPing = false;
                            }
                        }
                        case "SubscribeRequest" -> {
                            log.debug("SubscribeRequest received; oid=" + request.objectId()
                                    + (request.objectId() == clientOid ? "; client bootstrap accepted" : ""));
                            if (request.objectId() == clientOid) {
                                if (subscription != null) subscription.close();
                                boolean gameMaster = session.gameMaster(); // Outside CharacterStore's publication lock.
                                subscription = characters.subscribe(
                                        roster -> {
                                            output.send(() -> outgoing.player(
                                                    request.messageId(),
                                                    clientOid,
                                                    characters.accountId(),
                                                    characters.username(),
                                                    receivers,
                                                    roster,
                                                    gameMaster,
                                                    world.externalSecret(),
                                                    world.lookingForGroup()));
                                        },
                                        roster -> output.send(() -> outgoing.rosterChanged(clientOid, roster)));
                                world.refresh();
                                log.debug("PlayerObject snapshot sent; available knights="
                                        + characters.roster().size());
                            } else if (request.objectId() == SessionCodec.SERVER_OID) {
                                output.send(() -> outgoing.server(request.messageId(), world.hasInteriors()));
                                log.debug("ServerObject snapshot sent");
                            } else if (request.objectId() == SessionCodec.CONFIG_OID) {
                                output.send(() -> outgoing.configuration(request.messageId()));
                                log.debug("DConfigObject snapshot sent");
                            } else if (request.objectId() == SessionCodec.LOVE_TEST_OID) {
                                output.send(() -> outgoing.loveTest(request.messageId()));
                                log.debug("LoveTestObject snapshot sent");
                            } else if (request.objectId() == SessionCodec.MISSION_OID) {
                                output.send(() -> outgoing.missionObject(request.messageId()));
                            } else if (request.objectId() == SessionCodec.EXCHANGE_OID) {
                                try {
                                    world.subscribeExchange(request.messageId());
                                } catch (IOException unavailable) {
                                    output.send(() -> outgoing.subscriptionFailure(
                                            request.messageId(), request.objectId(), unavailable.getMessage()));
                                }
                            } else if (world.ownsChannel(request.objectId())) {
                                try {
                                    world.subscribeChannel(request.messageId(), request.objectId());
                                } catch (IOException unavailable) {
                                    output.send(() -> outgoing.subscriptionFailure(
                                            request.messageId(), request.objectId(), unavailable.getMessage()));
                                }
                            } else if (world.ownsTrade(request.objectId())) {
                                try {
                                    world.subscribeTrade(request.objectId(), request.messageId());
                                } catch (IOException expired) {
                                    output.send(() -> outgoing.subscriptionFailure(
                                            request.messageId(), request.objectId(), "Trade is no longer available"));
                                }
                            } else if (world.ownsScene(request.objectId())) {
                                world.subscribe(request.messageId());
                            } else if (world.ownsInteriorParty(request.objectId())) {
                                world.subscribeInteriorParty(request.messageId());
                            } else if (world.ownsGuild(request.objectId())) {
                                try {
                                    world.subscribeGuild(request.messageId());
                                } catch (IOException unavailable) {
                                    output.send(() -> outgoing.subscriptionFailure(
                                            request.messageId(), request.objectId(), unavailable.getMessage()));
                                }
                            } else {
                                output.send(() -> outgoing.subscriptionFailure(
                                        request.messageId(), request.objectId(), "No such object"));
                                log.debug("Subscription to unknown object rejected");
                            }
                        }
                        case "UnsubscribeRequest" -> {
                            if (request.objectId() == clientOid && subscription != null) {
                                subscription.close();
                                subscription = null;
                            }
                            if (world.ownsScene(request.objectId())) world.unsubscribe();
                            if (world.ownsTrade(request.objectId())) world.unsubscribeTrade(request.objectId());
                            if (request.objectId() == SessionCodec.EXCHANGE_OID) world.unsubscribeExchange();
                            world.unsubscribeInteriorParty(request.objectId());
                            world.unsubscribeChannel(request.objectId());
                            world.unsubscribeGuild(request.objectId());
                            output.send(() -> outgoing.unsubscribe(request.messageId(), request.objectId()));
                        }
                        case "LogoffRequest" -> {
                            log.debug("Client logged off");
                            return;
                        }
                        case "ForwardEventRequest" ->
                            handleEvent(
                                    output,
                                    outgoing,
                                    request.event(),
                                    clientOid,
                                    receivers,
                                    log,
                                    characters,
                                    subscription != null,
                                    authRevision,
                                    world,
                                    router);
                        default -> throw new IllegalStateException("Unsupported session request");
                    }
                }
            }
        } finally {
            world.close();
            if (subscription != null) subscription.close();
        }
    }

    private static void handleEvent(
            SessionOutput output,
            SessionCodec outgoing,
            SessionDecoder.Event event,
            int clientOid,
            Map<String, Short> receivers,
            SessionLog log,
            CharacterStore characters,
            boolean subscribed,
            long authRevision,
            WorldSession world,
            SessionInvocationRouter router)
            throws IOException {
        if (event instanceof SessionDecoder.Compound compound) {
            if (compound.oid() != clientOid) throw new SessionDecoder.Rejected("Invalid compound event target");
            for (var child : compound.events())
                handleEvent(
                        output,
                        outgoing,
                        child,
                        clientOid,
                        receivers,
                        log,
                        characters,
                        subscribed,
                        authRevision,
                        world,
                        router);
        } else if (event instanceof SessionDecoder.Input input) {
            world.input(input);
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
                            || registration.code() == null
                            || registration.code().isBlank()
                            || registration.code().length() > 256
                            || (receivers.size() >= 128 && !receivers.containsKey(registration.code()))) {
                        throw new SessionDecoder.Rejected("Invalid receiver registration");
                    }
                    for (var existing : receivers.entrySet()) {
                        if (!existing.getKey().equals(registration.code())
                                && existing.getValue() == registration.id()) {
                            throw new SessionDecoder.Rejected("Duplicate receiver ID");
                        }
                    }
                    receivers.put(registration.code(), registration.id());
                    log.debug("Invocation receiver registered; count=" + receivers.size());
                }
                case "EntryRemovedEvent" -> {
                    if (!(mutation.value() instanceof String code))
                        throw new SessionDecoder.Rejected("Invalid receiver removal");
                    receivers.remove(code);
                }
                default -> throw new SessionDecoder.Rejected("Unsupported receiver mutation");
            }
            world.receiverChanged(receivers);
        } else if (event instanceof SessionDecoder.Invocation invocation) {
            router.route(
                    invocation,
                    request -> CharacterHandler.handle(
                            output,
                            outgoing,
                            request,
                            clientOid,
                            log,
                            characters,
                            subscribed,
                            authRevision,
                            world,
                            receivers));
        }
    }
}
