package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Authenticated objects, receiver registration, and limited invocation handling. */
final class ClientSession {
    static final String SAVED_WITHOUT_WORLD = "Knight saved. World entry requires a scene receiver.";
    static final String UPDATED_WITHOUT_WORLD = "Knight updated. World entry requires a scene receiver.";
    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log) throws Exception {
        run(socket, incoming, connectionId, host, port, log, new CharacterStore());
    }

    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log, CharacterStore characters) throws Exception {
        run(socket,incoming,connectionId,host,port,log,characters,-1);
    }

    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log, CharacterStore characters, long authRevision) throws Exception {
        run(socket,incoming,connectionId,host,port,log,characters,authRevision,()->{});
    }

    static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log, CharacterStore characters, long authRevision, AccountStore.Session session) throws Exception {
        if(!session.current())throw new IOException("Session replaced during login");
        try (SessionOutput output = new SessionOutput(socket)) {
            run(socket, incoming, connectionId, host, port, log, characters, output, authRevision,session);
        }
    }

    private static void run(Socket socket, HandshakeCodec incoming, int connectionId, String host, int port,
            SessionLog log, CharacterStore characters, SessionOutput output, long authRevision, AccountStore.Session session) throws Exception {
        SessionCodec outgoing = new SessionCodec(characters.catalog());
        int clientOid = Math.addExact(1000, Math.multiplyExact(2,connectionId));
        output.send(() -> outgoing.bootstrap(connectionId, clientOid, host, port));
        log.debug("BootstrapNotification sent; clientOid=" + clientOid + "; services=" + SessionCodec.SERVICES.size());
        socket.setSoTimeout(60000); // Current client heartbeat period exceeds the handshake timeout.
        Map<String, Short> receivers = new HashMap<>();
        boolean firstPing = true;
        CharacterStore.Subscription subscription = null;
        WorldSession world=new WorldSession(socket,characters,output,outgoing,clientOid,authRevision,log,
            org.tortodrone.world.SceneRegistry.system(),session::gameMaster);
        session.onInvalidated(world::close);
        try {
            byte[] body;
            while ((body = HandshakeCodec.readFrame(socket.getInputStream())) != null) {
                long receivedAt = System.currentTimeMillis();
                for (HandshakeCodec.SessionRequest request : incoming.sessionRequests(body)) {
                    if (!request.type().equals("PingRequest") && !request.type().equals("ThrottleUpdatedMessage")) log.activity();
                    if(!session.current())throw new IOException("Session replaced");
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
                                boolean gameMaster=session.gameMaster(); // Outside CharacterStore's publication lock.
                                subscription = characters.subscribe(
                                    roster -> {
                                        output.send(() -> outgoing.player(request.messageId(), clientOid,
                                            characters.accountId(), characters.username(), receivers, roster,gameMaster));
                                    },
                                    roster -> output.send(() -> outgoing.rosterChanged(clientOid, roster)));
                                world.refresh();
                                log.debug("PlayerObject snapshot sent; available knights=" + characters.roster().size());
                            } else if (request.objectId() == SessionCodec.SERVER_OID) {
                                output.send(() -> outgoing.server(request.messageId(),world.hasInteriors()));
                                log.debug("ServerObject snapshot sent");
                            } else if (request.objectId() == SessionCodec.CONFIG_OID) {
                                output.send(() -> outgoing.configuration(request.messageId()));
                                log.debug("DConfigObject snapshot sent");
                            } else if (request.objectId() == SessionCodec.EXCHANGE_OID) {
                                try{world.subscribeExchange(request.messageId());}catch(IOException unavailable){output.send(()->outgoing.subscriptionFailure(request.messageId(),request.objectId(),unavailable.getMessage()));}
                            } else if(world.ownsTrade(request.objectId())) {
                                try{world.subscribeTrade(request.objectId(),request.messageId());}
                                catch(IOException expired){output.send(()->outgoing.subscriptionFailure(request.messageId(),request.objectId(),"Trade is no longer available"));}
                            } else if(world.ownsScene(request.objectId())) {
                                world.subscribe(request.messageId());
                            } else if(world.ownsInteriorParty(request.objectId())) {
                                world.subscribeInteriorParty(request.messageId());
                            } else {
                                output.send(() -> outgoing.subscriptionFailure(request.messageId(), request.objectId(), "No such object"));
                                log.debug("Subscription to unknown object rejected");
                            }
                        }
                        case "UnsubscribeRequest" -> {
                            if (request.objectId() == clientOid && subscription != null) {
                                subscription.close(); subscription = null;
                            }
                            if(world.ownsScene(request.objectId()))world.unsubscribe();
                            if(world.ownsTrade(request.objectId()))world.unsubscribeTrade(request.objectId());
                            if(request.objectId()==SessionCodec.EXCHANGE_OID)world.unsubscribeExchange();
                            world.unsubscribeInteriorParty(request.objectId());
                            output.send(() -> outgoing.unsubscribe(request.messageId(), request.objectId()));
                        }
                        case "LogoffRequest" -> {
                            log.debug("Client logged off");
                            return;
                        }
                        case "ForwardEventRequest" -> handleEvent(output, outgoing, request.event(), clientOid, receivers, log, characters, subscription != null, authRevision,world);
                        default -> throw new IllegalStateException("Unsupported session request");
                    }
                }
            }
        } finally {
            world.close();
            if (subscription != null) subscription.close();
        }
    }

    private static void handleEvent(SessionOutput output, SessionCodec outgoing, SessionDecoder.Event event,
            int clientOid, Map<String, Short> receivers, SessionLog log, CharacterStore characters, boolean subscribed, long authRevision,WorldSession world) throws IOException {
        if (event instanceof SessionDecoder.Compound compound) {
            if (compound.oid() != clientOid) throw new SessionDecoder.Rejected("Invalid compound event target");
            for (var child : compound.events()) handleEvent(output, outgoing, child, clientOid, receivers, log, characters, subscribed, authRevision,world);
        } else if(event instanceof SessionDecoder.Input input) {
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
            world.receiverChanged(receivers);
        } else if (event instanceof SessionDecoder.Invocation invocation) {
            if(world.partyInvocation(invocation))return;
            if(world.interiorPartyInvocation(invocation))return;
            if(world.exchangeInvocation(invocation))return;
            if(world.auctionInvocation(invocation))return;
            if(world.townInvocation(invocation)){
                if(world.takeRenameDisconnect()){world.close();output.disconnect();}
                return;
            }
            if(world.conversationInvocation(invocation))return;
            if(world.tradeInvocation(invocation))return;
            if(world.departingScene(invocation.oid())&&invocation.service()==10
                    &&invocation.method()==1&&invocation.arguments().isEmpty())return;
            if((invocation.service()==org.tortodrone.world.SceneChat.SERVICE||invocation.service()==org.tortodrone.world.SceneChat.PARTY_SERVICE)&&invocation.oid()!=SessionCodec.INVOCATION_OID) {
                world.speak(invocation);return;
            }
            if (world.ownsScene(invocation.oid()) && invocation.service()>=6 && invocation.service()<=11) {
                if(invocation.service()==6&&invocation.method()==2){world.inspect(invocation);return;}
                if (invocation.service() == 6 && invocation.method() == 3) {
                    world.emote(invocation);return;
                }
                if (invocation.service() == 10 && invocation.method() == 1 && invocation.arguments().isEmpty()) {
                    world.entered();
                    return;
                }
                for(Object arg:invocation.arguments())if(arg instanceof SessionDecoder.Listener listener)output.send(()->outgoing.invocationFailure(clientOid,listener.requestId(),"Town operation is not available yet"));
                return;
            }
            if (invocation.oid() != SessionCodec.INVOCATION_OID || invocation.service() > SessionCodec.SERVICES.size()) {
                throw new SessionDecoder.Rejected("Invalid invocation target");
            }
            if(invocation.service()==3&&invocation.method()==2){world.broadcast(invocation);return;}
            if(invocation.service()==3&&invocation.method()==3){world.tell(invocation);return;}
            if(invocation.service()==SessionCodec.ADMIN_SERVICE) {
                if(invocation.oid()!=SessionCodec.INVOCATION_OID)
                    throw new SessionDecoder.Rejected("Invalid admin invocation target");
                world.admin(invocation);return;
            }
            String service = SessionCodec.SERVICES.get(invocation.service() - 1);
            log.debug("InvocationRequestEvent received; service=" + service.substring(service.lastIndexOf('.') + 1)
                + "; method=" + invocation.method() + "; arguments=" + invocation.arguments().size());
            if(world.invocation(invocation))return;
            // Initial hardware statistics are advisory and are deliberately not retained.
            if (invocation.service() == 1 && invocation.method() == 10 && invocation.arguments().size() == 9) return;
            if (invocation.service() == 1 && (invocation.method() == 3 || invocation.method() == 4 || invocation.method() == 5 || invocation.method() == 6)) {
                characterRequest(output, outgoing, invocation, clientOid, log, characters, subscribed, authRevision,world,receivers);
                return;
            }
            for (Object argument : invocation.arguments()) {
                if (argument instanceof SessionDecoder.Listener listener) {
                    String reason = "Server operation not implemented yet";
                    output.send(() -> outgoing.invocationFailure(clientOid, listener.requestId(), reason));
                }
            }
        }
    }

    private static void characterRequest(SessionOutput output, SessionCodec outgoing, SessionDecoder.Invocation invocation,
            int clientOid, SessionLog log, CharacterStore characters, boolean subscribed, long authRevision,WorldSession world,Map<String,Short> receivers) throws IOException {
        var args = invocation.arguments();
        if (args.isEmpty() || !(args.get(args.size() - 1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Missing character confirmation listener");
        }
        String failure = null;
        if (invocation.method() == 3) {
            try { failure = args.size() == 2 && args.get(0) instanceof String name
                ? characters.nameFailure(name) : "Invalid name validation request"; }
            catch(IOException unavailable){failure="Knight name could not be checked; try again";}
        } else if (invocation.method() == 4) {
            if (args.size() != 3 || !(args.get(0) instanceof String name) || !optionalString(args.get(1))) {
                failure = "Invalid character selection request";
            } else if (characters.roster().stream().noneMatch(knight -> knight.name().equalsIgnoreCase(name))) {
                failure = "No such knight";
            } else {
                if(!receivers.containsKey(org.tortodrone.world.SceneRegistry.ZONE_RECEIVER))failure="World entry requires a scene receiver";
                else try { world.select(name,receivers); }
                catch(CharacterStore.Refused|IOException refused){failure=refused.getMessage();}
            }
        } else if (invocation.method() == 6) {
            if (args.size() != 2 || !(args.get(0) instanceof String name)) {
                failure = "Invalid character deletion request";
            } else {
                try {
                    synchronized(characters) {
                        if(characters.roster().stream().anyMatch(k->k.name().equalsIgnoreCase(name)&&WorldSession.active(characters.accountId(),k.id())))throw new IOException("Knight is online; disconnect before deleting it");
                        characters.delete(name,authRevision);
                    }
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character deletion failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be deleted; try again";
                }
                if (failure == null) {
                    if (!subscribed) {
                        var roster=characters.available();
                        output.send(() -> outgoing.rosterChanged(clientOid, roster));
                    }
                    log.info("Character deleted; accountId="+characters.accountId()+"; knight="+args.get(0)+"; available knights=" + characters.roster().size());
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
                    if (args.get(0) == null) characters.create(choices,authRevision);
                    else {
                        String old=(String)args.get(0);
                        synchronized(characters) {
                            if(characters.roster().stream().anyMatch(k->k.name().equalsIgnoreCase(old)&&WorldSession.active(characters.accountId(),k.id())))throw new IOException("Knight is online; disconnect before editing it");
                            characters.update(old, choices,authRevision);
                        }
                    }
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character save failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be saved; try again";
                }
                if (failure == null) {
                    if (!subscribed) {
                        var roster=characters.available();
                        output.send(() -> outgoing.rosterChanged(clientOid, roster));
                    }
                    var savedName=(String)args.get(1);
                    var saved=characters.roster().stream().filter(k->k.name().equalsIgnoreCase(savedName)).findFirst();
                    log.info("Character saved; accountId="+characters.accountId()+"; knight="+savedName+"; rosterId="+(saved.isEmpty()?"unavailable":saved.get().id())+"; available knights="+characters.roster().size());
                    try {if(characters.hasWorldStorage())log.info("Knight stored; knightId="+characters.worldProfile(savedName,authRevision).worldId());}
                    catch(IOException|CharacterStore.Refused unavailable){log.debug("Stored knight identity unavailable after save; "+unavailable.getClass().getSimpleName());}
                    // The stock creator waits for selection and scene events after success.
                    if(characters.hasWorldStorage() && receivers.containsKey(org.tortodrone.world.SceneRegistry.ZONE_RECEIVER)) {
                        try { world.select((String)args.get(1),receivers); }
                        catch(CharacterStore.Refused|IOException refused){failure=refused.getMessage();}
                    } else failure = args.get(0) == null ? SAVED_WITHOUT_WORLD : UPDATED_WITHOUT_WORLD;
                }
            }
        }
        String result = failure;
        output.send(() -> result == null ? outgoing.invocationConfirmed(clientOid, listener.requestId())
            : outgoing.invocationFailure(clientOid, listener.requestId(), result));
    }

    private static boolean optionalString(Object value) { return value == null || value instanceof String; }
    private static boolean enumeration(Object value, String name) {
        return value instanceof SessionDecoder.EnumValue enumeration
            && enumeration.type().equals("com.threerings.projectx.data.ProjectXCodes$" + name);
    }
}
