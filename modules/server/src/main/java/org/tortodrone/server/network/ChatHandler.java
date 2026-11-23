package org.tortodrone.server.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.server.ServerPolicy;

/** Scene, zone and direct chat for one connection. Membership and rate limits follow the
 *  stock chat model; routing happens outside the session monitor so peer delivery never
 *  nests another connection's lock. */
final class ChatHandler {
    private static final SceneChat CHAT = new SceneChat();
    private final WorldSession session;
    private final SessionResponder responder;
    private final SceneChat.RateLimit rate = new SceneChat.RateLimit();
    private SceneChat.Membership chat;
    private String awayMessage;
    private final Map<Integer, SceneChat.Membership> channels = new HashMap<>();

    ChatHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    SceneChat.RateLimit rate() {
        return rate;
    }

    /** Join the scene chat for this place/generation; a previous membership is replaced. */
    void enter(int place, int generation) throws IOException {
        chat = CHAT.join(
                place,
                responder.oid(),
                session.selectedKnight().name(),
                rate,
                message -> deliver(place, generation, message));
        CHAT.away(chat, awayMessage);
    }

    /** Close scene and channel memberships when the knight leaves for another place. */
    void leave() {
        if (chat != null) {
            chat.close();
            chat = null;
        }
        for (var member : channels.values()) member.close();
        channels.clear();
    }

    /** ProjectXSceneMarshaller.emote(String, Name, ConfirmListener), stock method three. */
    void emote(SessionDecoder.Invocation request) throws IOException {
        var args = request.arguments();
        if (request.service() != 6
                || request.method() != 3
                || args.size() != 3
                || !(args.get(0) instanceof String)
                || !(args.get(1) == null || args.get(1) instanceof String)
                || !(args.get(2) instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
            throw new SessionDecoder.Rejected("Invalid emote request");
        String failure = null, target = null;
        SceneChat.Membership sender = null;
        var descriptor = PlayerEmotes.find((String) args.getFirst());
        synchronized (session) {
            if (session.isClosed()
                    || !session.responder().open()
                    || !session.sceneSubscribed()
                    || !session.sceneEntered()
                    || !session.ownsScene(request.oid())) failure = "Enter a scene before using an emote";
            else {
                try {
                    session.charactersStore()
                            .worldProfile(session.selectedKnight().name(), session.authRevision());
                } catch (CharacterStore.Refused e) {
                    throw new IOException("Knight changed; reconnect", e);
                }
                if (descriptor == null) failure = "This emote is not available";
                else if (descriptor.targetable() && args.get(1) != null) {
                    String supplied = (String) args.get(1);
                    target = session.currentScene().simulation().snapshot().actors().stream()
                            .map(org.tortodrone.core.world.ActorState::name)
                            .filter(n -> n.equalsIgnoreCase(supplied))
                            .findFirst()
                            .orElse(null);
                    if (target == null) failure = "That knight is not in this scene";
                    else if (target.equalsIgnoreCase(session.selectedKnight().name()))
                        failure = "You can't do that to yourself";
                }
                if (failure == null) {
                    if (!rate.allow(System.nanoTime())) failure = "Please slow down before using another emote";
                    else if (!session.membership().emote(descriptor, SceneClock.now(), session.monotonicTime()))
                        failure = "Finish your current action before using an emote";
                    else sender = chat;
                }
            }
        }
        // Route outside the session lock: peer delivery acquires its own session monitor.
        if (failure == null)
            try {
                CHAT.emote(sender, descriptor, target);
            } catch (IOException departed) {
                failure = "Scene changed before the emote could be delivered";
            }
        if (failure == null) responder.confirmed(listener.requestId());
        else responder.failure(listener.requestId(), failure);
    }

    synchronized boolean ownsChannel(int channel) {
        return session.selected()
                && !session.inReadyRoom()
                && (channel
                                == session.sceneRegistry()
                                        .channelOid(session.currentScene().map().zone(), false)
                        || channel
                                == session.sceneRegistry()
                                        .channelOid(session.currentScene().map().zone(), true));
    }

    synchronized void subscribeChannel(short message, int channel) throws IOException {
        if (session.isClosed() || !ownsChannel(channel))
            throw new IOException("This chat channel belongs to another zone");
        try {
            session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
        } catch (CharacterStore.Refused refused) {
            throw new IOException("Knight changed; reconnect", refused);
        }
        if (!channels.containsKey(channel))
            channels.put(
                    channel,
                    session.sceneRegistry()
                            .channel(channel)
                            .join(
                                    channel,
                                    responder.oid(),
                                    session.selectedKnight().name(),
                                    rate,
                                    m -> deliverChannel(channel, m)));
        responder.send(() -> responder
                .codec()
                .chatChannel(message, channel, session.sceneRegistry().channelType(channel)));
    }

    synchronized void unsubscribeChannel(int channel) {
        var member = channels.remove(channel);
        if (member != null) member.close();
    }

    private synchronized boolean deliverChannel(int channel, SceneChat.Message message) throws IOException {
        if (session.isClosed()
                || !session.sceneSubscribed()
                || !session.sceneEntered()
                || !ownsChannel(channel)
                || !channels.containsKey(channel)) return false;
        try {
            session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
        } catch (CharacterStore.Refused refused) {
            return false;
        }
        responder.send(() -> responder.codec().chat(channel, message));
        return true;
    }

    boolean channelSpeak(SessionDecoder.Invocation request) throws IOException {
        if (session.sceneRegistry().channelType(request.oid()) < 0) return false;
        var args = request.arguments();
        SceneChat.Membership member;
        synchronized (session) {
            if (session.isClosed()
                    || !session.sceneSubscribed()
                    || !session.sceneEntered()
                    || !ownsChannel(request.oid())
                    || (member = channels.get(request.oid())) == null)
                throw new SessionDecoder.Rejected("Unauthorized zone chat");
            if (request.service() != SceneChat.SERVICE
                    || request.method() != 1
                    || args.size() != 2
                    || !(args.get(0) instanceof String)
                    || !(args.get(1) instanceof Byte)) throw new SessionDecoder.Rejected("Invalid zone chat request");
            try {
                session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
            } catch (CharacterStore.Refused refused) {
                throw new IOException("Knight changed; reconnect", refused);
            }
        }
        try {
            if (!session.sceneRegistry().channel(request.oid()).speak(member, (String) args.get(0), (Byte) args.get(1)))
                session.unavailable("Please slow down before sending another message");
        } catch (IOException refused) {
            session.unavailable(refused.getMessage());
        }
        return true;
    }

    void speak(SessionDecoder.Invocation request) throws IOException {
        if (!ServerPolicy.chatEnabled()) throw new IOException("Chat is disabled on this server.");
        var partyMember = session.partyMember();
        if (request.service() == SceneChat.PARTY_SERVICE
                && session.sceneRegistry().parties().owns(partyMember, request.oid())) {
            if (request.method() != 1
                    || request.arguments().size() != 2
                    || !(request.arguments().get(0) instanceof String text)
                    || !(request.arguments().get(1) instanceof Byte mode))
                throw new SessionDecoder.Rejected("Invalid party chat");
            synchronized (session) {
                if (session.isClosed() || !session.selected()) throw new IOException("Session closed");
                try {
                    session.charactersStore()
                            .worldProfile(session.selectedKnight().name(), session.authRevision());
                } catch (CharacterStore.Refused refused) {
                    throw new IOException("Knight changed; reconnect", refused);
                }
            }
            if (rate.allow(System.nanoTime())) session.sceneRegistry().parties().speak(partyMember, text, mode);
            return;
        }
        SceneChat.Membership sender;
        int place, generation;
        synchronized (session) {
            if (session.departingScene(request.oid())) return;
            boolean party = session.ownsInteriorParty(request.oid()) && request.service() == SceneChat.PARTY_SERVICE;
            if (session.isClosed()
                    || !session.responder().open()
                    || !session.sceneSubscribed()
                    || !session.sceneEntered()
                    || !party && !session.ownsScene(request.oid()))
                throw new SessionDecoder.Rejected("Unauthorized scene chat");
            if ((!party && request.service() != SceneChat.SERVICE)
                    || request.method() != 1
                    || request.arguments().size() != 2
                    || !(request.arguments().get(0) instanceof String)
                    || !(request.arguments().get(1) instanceof Byte))
                throw new SessionDecoder.Rejected("Invalid scene chat request");
            try {
                session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
            } catch (CharacterStore.Refused e) {
                throw new IOException("Knight changed; reconnect", e);
            }
            sender = chat;
            place = session.currentScene().oid();
            generation = session.sceneGenerationValue();
        }
        try {
            if (!CHAT.speak(sender, (String) request.arguments().get(0), (Byte)
                    request.arguments().get(1)))
                session.chatFeedback(place, generation, "Please slow down before sending another message.");
        } catch (IOException refused) {
            session.chatFeedback(place, generation, refused.getMessage());
        }
    }

    /** Crowd ChatService method 2. ListenerMarshaller has failure only, no success response. */
    void broadcast(SessionDecoder.Invocation request) throws IOException {
        if (!ServerPolicy.chatEnabled()) throw new IOException("Chat is disabled on this server.");
        var args = request.arguments();
        if (request.oid() != SessionCodec.INVOCATION_OID
                || request.service() != SessionCodec.CHAT_SERVICE
                || request.method() != 2
                || args.size() != 2
                || !(args.get(0) instanceof String text)
                || !(args.get(1) instanceof SessionDecoder.Listener listener)
                || !listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ListenerMarshaller"))
            throw new SessionDecoder.Rejected("Invalid broadcast request");
        SceneChat.Membership sender;
        try {
            synchronized (session) {
                if (session.isClosed()
                        || !session.responder().open()
                        || !session.sceneSubscribed()
                        || !session.sceneEntered()) throw new IOException("Enter a scene before broadcasting");
                if (!session.gameMasterRole()) throw new IOException("Game Master permission required");
                try {
                    session.charactersStore()
                            .worldProfile(session.selectedKnight().name(), session.authRevision());
                } catch (CharacterStore.Refused e) {
                    throw new IOException("Knight changed; reconnect", e);
                }
                sender = chat;
            }
            if (!CHAT.broadcast(sender, text))
                throw new IOException("Please slow down before sending another message.");
        } catch (IOException refused) {
            responder.failure(listener.requestId(), refused.getMessage());
        }
    }

    synchronized void away(SessionDecoder.Invocation request) throws IOException {
        var args = request.arguments();
        if (request.oid() != SessionCodec.INVOCATION_OID
                || request.service() != SessionCodec.CHAT_SERVICE
                || request.method() != 1
                || args.size() != 1
                || args.getFirst() != null && !(args.getFirst() instanceof String))
            throw new SessionDecoder.Rejected("Invalid away request");
        if (session.isClosed() || !session.selected()) throw new IOException("Select a knight first");
        try {
            session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
        } catch (CharacterStore.Refused refused) {
            throw new IOException("Knight changed; reconnect", refused);
        }
        if (!rate.allow(System.nanoTime())) {
            session.unavailable("Please slow down before changing your away message");
            return;
        }
        awayMessage = args.getFirst() == null ? null : SceneChat.validate((String) args.getFirst());
        if (chat != null) CHAT.away(chat, awayMessage);
        responder.send(() -> responder.codec().awayMessage(responder.oid(), awayMessage));
    }

    void tell(SessionDecoder.Invocation request) throws IOException {
        if (!ServerPolicy.chatEnabled()) throw new IOException("Chat is disabled on this server.");
        var args = request.arguments();
        if (request.oid() != SessionCodec.INVOCATION_OID
                || request.service() != SessionCodec.CHAT_SERVICE
                || request.method() != 3
                || args.size() != 3
                || !(args.get(0) instanceof String target)
                || !(args.get(1) instanceof String text)
                || !(args.get(2) instanceof SessionDecoder.Listener listener)
                || !listener.type().equals("com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller"))
            throw new SessionDecoder.Rejected("Invalid tell request");
        SceneChat.Membership sender;
        try {
            synchronized (session) {
                if (session.isClosed()
                        || !session.responder().open()
                        || !session.sceneSubscribed()
                        || !session.sceneEntered()) throw new IOException("Enter a scene before sending a tell");
                try {
                    session.charactersStore()
                            .worldProfile(session.selectedKnight().name(), session.authRevision());
                } catch (CharacterStore.Refused e) {
                    throw new IOException("Knight changed; reconnect", e);
                }
                sender = chat;
            }
            String away = CHAT.tell(sender, target, text);
            responder.send(() -> responder.codec().tellSucceeded(responder.oid(), listener.requestId(), away));
        } catch (IOException refused) {
            responder.failure(listener.requestId(), refused.getMessage());
        }
    }

    /** Native chat feedback only for a scene that is still current. */
    void feedback(int place, int generation, String reason) throws IOException {
        synchronized (session) {
            if (!session.isClosed()
                    && session.sceneSubscribed()
                    && session.sceneEntered()
                    && session.currentScene().oid() == place
                    && session.sceneGenerationValue() == generation) {
                int channel = session.currentScene().map().town() ? place : session.partyOid();
                responder.send(() -> responder.codec().chatFeedback(channel, reason));
            }
        }
    }

    void unavailable(String reason) throws IOException {
        synchronized (session) {
            if (!session.isClosed() && session.selected() && session.sceneSubscribed() && session.sceneEntered())
                feedback(session.currentScene().oid(), session.sceneGenerationValue(), reason);
        }
    }

    private synchronized boolean deliver(int place, int generation, SceneChat.Message message) throws IOException {
        if (session.isClosed()
                || !session.responder().open()
                || !session.sceneSubscribed()
                || !session.sceneEntered()
                || session.currentScene().oid() != place
                || session.sceneGenerationValue() != generation) return false;
        if (message.personal()) {
            try {
                session.charactersStore().worldProfile(session.selectedKnight().name(), session.authRevision());
            } catch (CharacterStore.Refused refused) {
                return false;
            }
        }
        int channel = message.personal()
                ? responder.oid()
                : session.currentScene().map().town() ? place : session.partyOid();
        responder.send(() -> responder.codec().chat(channel, message));
        return true;
    }
}
