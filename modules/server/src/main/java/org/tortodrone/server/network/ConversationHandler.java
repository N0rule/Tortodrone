package org.tortodrone.server.network;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.OpeningMissionStore;
import org.tortodrone.world.OpeningComlinks;

/** Native conversation dialogs for one connection: owned nodes, responses and terminal settlement.
 *
 * <p>The dialog stamp is this connection's monotonic id; a stale client close cannot dismiss a
 * replacement. Terminal settlement commits through the session before any callback publishes,
 * so a refused write leaves the dialog open and never emits a settlement.
 */
final class ConversationHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private SceneConversation conversation;
    private String mission;
    private String node;
    private ActorState source;
    private long created;
    private float x;
    private float y;
    private final Set<Short> responses = new LinkedHashSet<>();

    ConversationHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean active() {
        return conversation != null;
    }

    SceneConversation current() {
        return conversation;
    }

    String mission() {
        return mission;
    }

    String node() {
        return node;
    }

    ActorState source() {
        return source;
    }

    long created() {
        return created;
    }

    /** Advance the dialog stamp so a stale close cannot dismiss the replacement. */
    long touch() {
        created = Math.max(System.currentTimeMillis(), created + 1);
        return created;
    }

    boolean expired(ActorState own) {
        return conversation != null && (own == null || mission == null && Math.hypot(own.x() - x, own.y() - y) > 1);
    }

    /** Open a conversation where the knight stands; the caller has already resolved eligibility. */
    void open(SceneConversation definition, String missionId, ActorState source) throws IOException {
        open(definition, missionId, source, source, definition.initial());
    }

    void open(SceneConversation definition, String missionId, ActorState source, String initialNode)
            throws IOException {
        open(definition, missionId, source, source, initialNode);
    }

    /** Open a scripted or placed conversation. {@code source} is the authored speaker/trigger;
     *  {@code own} is the knight whose current position anchors the native walk-away close. */
    void open(SceneConversation definition, String missionId, ActorState source, ActorState own, String initialNode)
            throws IOException {
        session.closeTownDialog();
        close();
        this.mission = missionId;
        this.conversation = definition;
        this.source = source;
        this.node = initialNode;
        touch();
        x = own.x();
        y = own.y();
        int place = session.currentScene().oid();
        responder.send(() ->
                responder.codec().conversationDialog(responder.oid(), place, conversation, node, source, created));
    }

    /** Any imported conversation mission, opened where the knight currently is. */
    void openComlink(String missionId) throws IOException {
        var definition = session.comlink(missionId);
        if (definition == null
                || session.isClosed()
                || !session.selected()
                || !session.sceneEntered()
                || !session.sceneSubscribed()
                || session.membership() == null) throw new IOException("m.internal_error");
        session.reloadProfile();
        if (!session.openingEligible(missionId)) throw new IOException("m.internal_error");
        var own = session.selfActor();
        if (own == null
                || session.currentScene().simulation().snapshot().portal(own.id()) != null
                || session.membership().health().current() <= 0) throw new IOException("m.internal_error");
        if (definition == current() && missionId.equals(mission)) return;
        open(definition, missionId, own);
    }

    /** Arrival comlink delivered behind the move response; the client is still loading, so
     *  enteredPlace cannot be required (see the session's forceScene comment). */
    void openArrivalComlink(OpeningMission target) throws IOException {
        if (target == null
                || OpeningComlinks.get(target) == null
                || session.isClosed()
                || !session.selected()
                || session.membership() == null
                || session.currentScene().map().id() != OpeningComlinks.scene(target))
            throw new IOException("m.internal_error");
        session.reloadProfile();
        if (!session.openingEligible(target.id())) throw new IOException("m.internal_error");
        var own = session.selfActor();
        if (own == null
                || session.currentScene().simulation().snapshot().portal(own.id()) != null
                || session.membership().health().current() <= 0) throw new IOException("m.internal_error");
        if (OpeningComlinks.owns(target, current()) && target.id().equals(mission)) return;
        open(OpeningComlinks.get(target), target.id(), own);
    }

    void close() throws IOException {
        if (conversation == null) return;
        conversation = null;
        source = null;
        node = null;
        mission = null;
        int place = session.currentScene().oid();
        responder.send(() -> responder.codec().conversationDialog(responder.oid(), place, null, null, null, created));
    }

    /** Acknowledge a close for a mission dialog this connection already replaced.
     *  The client can still hold the previous stamp; answer idempotently instead of
     *  disconnecting or dismissing the current dialog. */
    boolean dismissStaleClose(SessionDecoder.Invocation request) throws IOException {
        var args = request.arguments();
        if (conversation != null
                || session.townDialogOpen()
                || !session.ownsScene(request.oid())
                || request.service() != 6
                || request.method() != 1
                || args.size() != 1
                || !(args.getFirst() instanceof Long stamp)) return false;
        if (session.story.missionDialogCreated() != 0 && stamp != session.story.missionDialogCreated()) return true;
        session.story.missionDialogCreated(0);
        int place = session.currentScene().oid();
        responder.send(() -> responder.codec().conversationDialog(responder.oid(), place, null, null, null, 0));
        return true;
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if (conversation != null
                && session.ownsScene(request.oid())
                && request.service() == 6
                && request.method() == 1) {
            if (request.arguments().size() != 1 || !(request.arguments().getFirst() instanceof Long stamp))
                throw new SessionDecoder.Rejected("Invalid conversation close");
            if (stamp != created) return true;
            close();
            return true;
        }
        if (!session.ownsScene(request.oid()) || request.service() != SceneConversation.SERVICE) return false;
        var args = request.arguments();
        if (!session.ownsScene(request.oid())
                || !session.sceneEntered()
                || request.method() != 1
                || args.size() != 2
                || !(args.get(0) instanceof Integer choice)
                || !(args.get(1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller"))
            throw new SessionDecoder.Rejected("Invalid conversation response");
        if (responses.contains(listener.requestId())) {
            responder.confirmed(listener.requestId());
            return true;
        }
        // A response can cross the native 1.0-unit auto-close: the player keeps walking
        // until the panel appears and the queued confirm lands after the server dropped
        // the conversation. ProjectX answers that listener with m.internal_error rather
        // than treating it as a protocol violation, so never disconnect for it.
        if (conversation == null) {
            responder.failure(listener.requestId(), "m.internal_error");
            return true;
        }
        var replies = conversation.nodes().get(node).responses();
        if (choice < 0 || choice >= replies.size()) {
            responder.failure(listener.requestId(), "Invalid conversation choice");
            return true;
        }
        var response = replies.get(choice);
        String destination = response.destination();
        var openingTerminal = mission == null ? null : OpeningMission.byId(mission);
        var map = session.currentScene().map();
        boolean securing = map.id() == 217
                && conversation.actor().equals(WorldSession.RHENDON_CAMP)
                && node.equals("part 3a")
                && choice == 0;
        String terminalId = securing
                ? OpeningMission.SECURING_CAMP.id()
                : openingTerminal != null
                        ? OpeningComlinks.terminal(openingTerminal, conversation, node, choice) ? mission : null
                        : mission != null && response.terminal() ? mission : null;
        if (terminalId != null) {
            var own = session.selfActor();
            var membership = session.membership();
            int world = session.currentProfile().worldId();
            boolean validSource = securing
                    ? membership.conversationValid(conversation, node, choice, source)
                    : openingTerminal != null
                            ? session.sceneEntered()
                                    && session.sceneSubscribed()
                                    && map.id() == OpeningComlinks.scene(openingTerminal)
                                    && membership.health().current() > 0
                                    && source != null
                                    && source.id() == world
                                    && session.currentScene()
                                                    .simulation()
                                                    .snapshot()
                                                    .portal(world)
                                            == null
                            : session.sceneEntered()
                                    && session.sceneSubscribed()
                                    && membership.health().current() > 0
                                    && source != null
                                    && source.id() == world
                                    && session.currentScene()
                                                    .simulation()
                                                    .snapshot()
                                                    .portal(world)
                                            == null;
            if (own == null || securing && Math.hypot(own.x() - x, own.y() - y) > 1 || !validSource) {
                responder.failure(listener.requestId(), "Conversation source is unavailable");
                return true;
            }
            try {
                var expectedTerminal = new SceneActions.Sequence(
                        List.of(
                                new SceneActions.Memory(new KnightMemory("talkto_rhendon_icon", false)),
                                new SceneActions.Objective(new KnightObjective("findRhendon", null))),
                        false);
                if (securing ? !expectedTerminal.equals(response.action()) : response.action() != null)
                    throw new IOException("Unsupported mission terminal action graph");
                var prepared = prepareSettlement(terminalId);
                close();
                long dialog = prepared.settled().first() ? touch() : created;
                applySettlement(prepared, terminalId, own, dialog);
                remember(listener.requestId());
                responder.confirmed(listener.requestId());
            } catch (IOException failure) {
                responder.failure(listener.requestId(), failure.getMessage());
            }
            return true;
        }
        if (response.action() != null
                && !session.membership().conversationResponse(conversation, node, choice, source)) {
            responder.failure(listener.requestId(), "Conversation source is unavailable");
            return true;
        }
        if (!response.station().isEmpty()) {
            var station = session.sceneRegistry().town().station(response.station());
            if (station == null) {
                responder.failure(listener.requestId(), "Town service is unavailable");
                return true;
            }
            try {
                session.openTownDialog(station, source);
            } catch (IOException failure) {
                responder.failure(listener.requestId(), failure.getMessage());
                return true;
            }
        } else if (!response.command().isEmpty()) {
            var origin = source;
            close();
            responder.send(() -> responder.codec().controllerAction(responder.oid(), response.command(), origin));
        } else if (destination.isEmpty()) close();
        else {
            node = destination;
            int place = session.currentScene().oid();
            responder.send(() ->
                    responder.codec().conversationDialog(responder.oid(), place, conversation, node, source, created));
        }
        remember(listener.requestId());
        responder.confirmed(listener.requestId());
        return true;
    }

    /** The saved half of one conversation terminal: the pre-write profile and what committed. */
    private record PreparedSettlement(WorldProfile before, OpeningMissionStore.Settlement settled, boolean firstEver) {}

    private PreparedSettlement prepareSettlement(String terminalId) throws IOException {
        session.applyProfile(session.loadProfile(session.currentProfile()));
        var before = session.currentProfile();
        boolean firstEver = session.completedOpeningEmpty();
        var store = session.charactersStore();
        var settled = store.services()
                .openingMissions(
                        session.sceneRegistry().town(), session.sceneRegistry().missions())
                .complete(
                        store.accountId(),
                        session.selectedKnight().id(),
                        session.authRevision(),
                        before,
                        terminalId,
                        null);
        return new PreparedSettlement(before, settled, firstEver);
    }

    /** Publish the committed settlement in the native order; the dialog stays open on failure. */
    private void applySettlement(PreparedSettlement prepared, String terminalId, ActorState own, long dialog)
            throws IOException {
        var settled = prepared.settled();
        var before = prepared.before();
        session.applyProfile(settled.profile());
        session.applySettlementStory(settled.memories(), settled.objectives());
        session.recordOpeningCompletion(terminalId, settled.completion());
        if (settled.first()) {
            session.membership().equipment(session.visibleProfileNow());
            session.missionDialogCreated(dialog);
            var entry = session.sceneRegistry().missions().mission(terminalId);
            boolean immediate = entry == null || !entry.requirement().type().equals("gate");
            responder.send(() -> responder
                    .codec()
                    .missionSettlement(
                            responder.oid(),
                            before,
                            session.currentProfile(),
                            settled.completion(),
                            session.sceneRegistry().missions().grants(terminalId),
                            session.sceneRegistry().missions().prestige(terminalId),
                            immediate,
                            dialog));
            responder.send(() -> responder
                    .codec()
                    .itemChanges(responder.oid(), session.selectedKnight(), before, session.currentProfile()));
            if (prepared.firstEver())
                responder.send(() -> responder.codec().controllerAction(responder.oid(), "new_mission", own));
            session.publishPartyState();
        }
    }

    private void remember(short id) {
        responses.add(id);
        if (responses.size() > 256) {
            var oldest = responses.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
