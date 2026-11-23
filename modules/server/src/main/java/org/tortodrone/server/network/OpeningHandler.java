package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.mission.MissionRun;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.core.wire.EnumValue;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.world.SceneRegistry;

/** Native mission service for one connection: comlinks, exchange settlement, route preview and
 *  gate start. Validation and routing live here; the session owns travel, attachment and
 *  portal state, and storage commits before the forced move publishes. */
final class OpeningHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private MissionRun lastGateStart;
    private Short lastGateStartRequest;

    OpeningHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.MISSION_SERVICE)
                return false;
            var args = request.arguments();
            int method = request.method();
            if (method == 8) {
                if (args.size() != 1
                        || !(args.getFirst() instanceof List<?> values)
                        || values.size() > 128
                        || values.stream().anyMatch(v -> !(v instanceof org.tortodrone.core.wire.ConfigReference)))
                    throw new SessionDecoder.Rejected("Invalid mission crafting intent");
                return true; // Native no-listener operation; no unsupported item mutation.
            }
            boolean result = method == 7;
            if (args.isEmpty()
                    || !(args.getLast() instanceof SessionDecoder.Listener listener)
                    || !listener.type()
                            .equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX
                                    + (result ? "ResultMarshaller" : "ConfirmMarshaller")))
                throw new SessionDecoder.Rejected("Invalid mission listener");
            boolean valid = switch (method) {
                case 1 -> args.size() == 3 && args.getFirst() instanceof String && args.get(1) instanceof long[];
                case 2 ->
                    args.size() == 6
                            && args.getFirst() instanceof String
                            && args.get(1) instanceof EnumValue band
                            && band.type().equals(org.tortodrone.core.WireTypes.DIFFICULTY)
                            && args.get(2) instanceof Boolean
                            && args.get(3) instanceof Boolean
                            && args.get(4) instanceof Boolean;
                // Current build dropped the third boolean from the preview request.
                case 7 ->
                    args.size() == 5
                            && args.getFirst() instanceof String
                            && args.get(1) instanceof EnumValue band
                            && band.type().equals(org.tortodrone.core.WireTypes.DIFFICULTY)
                            && args.get(2) instanceof Boolean
                            && args.get(3) instanceof Boolean;
                case 3, 9 -> args.size() == 2 && args.getFirst() instanceof String;
                default -> false;
            };
            if (!valid) throw new SessionDecoder.Rejected("Invalid mission arguments");
            if (method == 3) {
                try {
                    var missionId = (String) args.getFirst();
                    var mission = session.sceneRegistry().missions().mission(missionId);
                    if (mission == null
                            || !mission.requirement().type().equals("conversation")
                                    && !mission.requirement().type().equals("gate_conversation"))
                        throw new IOException("m.internal_error");
                    session.conversations().openComlink(missionId);
                    session.clearArrivalComlink();
                    responder.confirmed(listener.requestId());
                } catch (IOException failure) {
                    responder.failure(listener.requestId(), failure.getMessage());
                }
                return true;
            }
            if (method == 1) {
                try {
                    if (session.isClosed() || !session.selected()) throw new IOException("m.internal_error");
                    var missionId = (String) args.getFirst();
                    var itemIds = (long[]) args.get(1);
                    if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                    settleMissionExchange(missionId, itemIds);
                    responder.confirmed(listener.requestId());
                } catch (IOException failure) {
                    responder.failure(listener.requestId(), failure.getMessage());
                }
                return true;
            }
            if (method == 2 || method == 7) {
                try {
                    String missionId = (String) args.getFirst();
                    int band = ((EnumValue) args.get(1)).ordinal();
                    if (band < 0
                            || band > 2
                            || !Boolean.TRUE.equals(args.get(2))
                            || method == 2
                                    && (!Boolean.FALSE.equals(args.get(3)) || !Boolean.FALSE.equals(args.get(4))))
                        throw new IOException("m.internal_error");
                    String difficulty = List.of("EASY", "MEDIUM", "HARD").get(band);
                    if (method == 2 && sameGateStart(missionId, difficulty, listener.requestId())) {
                        session.reloadProfile();
                        responder.confirmed(listener.requestId());
                        return true;
                    }
                    var opening = OpeningMission.byId(missionId);
                    MissionRun run;
                    ScenePortals.Entrance start;
                    if (opening != null && session.sceneRegistry().openingGate(opening) != null) {
                        var gate = preflightOpeningGate(opening);
                        run = MissionRun.opening(
                                gate, session.currentProfile().worldId(), session.partyOid(), difficulty);
                        start = gate.entrance();
                    } else {
                        run = preflightMissionRoute(missionId, difficulty);
                        start = run.entrance();
                    }
                    if (method == 7) {
                        responder.result(listener.requestId(), Boolean.TRUE, session.currentProfile());
                        return true;
                    }
                    beginMissionRun(run, start, listener.requestId());
                } catch (IOException failure) {
                    responder.failure(listener.requestId(), failure.getMessage());
                }
                return true;
            }
            // A served object/service must fail unsupported cards through the native listener.
            // Gate start is enabled only when its actual floor gameplay/route is implemented.
            responder.failure(listener.requestId(), "m.internal_error");
            return true;
        }
    }

    /** Re-check readiness, reload the roster snapshot and reserve the solo gate. */
    private MissionRun preflightMissionRoute(String missionId, String difficulty) throws IOException {
        if (session.isClosed()
                || !session.selected()
                || session.forcedReceiverValue() == null
                || session.sceneTravelPending()
                || session.deferredMovePending()
                || session.currentScene().map().kind().equals("level")
                || !session.inReadyRoom()
                        && (!session.sceneEntered()
                                || !session.sceneSubscribed()
                                || session.membership() == null
                                || session.membership().health().current() <= 0))
            throw new IOException("m.internal_error");
        if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
        session.reloadProfile();
        session.loadOpeningCompletions();
        var progress = session.currentProfile().progress();
        var route = session.sceneRegistry()
                .missionRoute(missionId, session.currentProfile().worldId(), session.partyOid(), difficulty);
        if (route == null
                || !session.sceneRegistry().missions().eligible(missionId, progress, session.completedOpeningKeys()))
            throw new IOException("m.internal_error");
        session.sceneRegistry().parties().soloGate(session.partyMember(), session.partyOid(), () -> Boolean.TRUE);
        return route;
    }

    private OpeningGate preflightOpeningGate(OpeningMission mission) throws IOException {
        if (session.isClosed()
                || !session.selected()
                || session.forcedReceiverValue() == null
                || session.sceneTravelPending()
                || session.deferredMovePending()
                || session.currentScene().map().id() != 217
                || !session.currentScene().map().town()
                || !session.inReadyRoom()
                        && (!session.sceneEntered()
                                || !session.sceneSubscribed()
                                || session.membership() == null
                                || session.membership().health().current() <= 0))
            throw new IOException("m.internal_error");
        if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
        session.reloadProfile();
        session.loadOpeningCompletions();
        var progress = session.currentProfile().progress();
        var gate = session.sceneRegistry().openingGate(mission);
        if (gate == null
                || !session.sceneRegistry().missions().eligible(mission.id(), progress, session.completedOpeningKeys()))
            throw new IOException("m.internal_error");
        session.sceneRegistry().parties().soloGate(session.partyMember(), session.partyOid(), () -> Boolean.TRUE);
        return gate;
    }

    /** A retried start for the same listener and live run is answered without a second lease. */
    private boolean sameGateStart(String missionId, String difficulty, short requestId) {
        var pending = session.pendingPortalDestination();
        var active = pending != null && pending.run() != null ? pending : session.currentScene();
        return !session.isClosed()
                && lastGateStartRequest != null
                && requestId == lastGateStartRequest
                && active.run() == lastGateStart
                && lastGateStart.missionId().equals(missionId)
                && lastGateStart.difficulty().equals(difficulty)
                && session.sceneRegistry()
                        .authorized(active, session.currentProfile().worldId(), session.partyMember());
    }

    /** Commit, acknowledge and force the move in that native order. */
    private void beginMissionRun(MissionRun run, ScenePortals.Entrance start, short requestId) throws IOException {
        var next =
                session.sceneRegistry().startRun(run, session.currentProfile().worldId(), session.partyMember());
        lastGateStart = run;
        lastGateStartRequest = requestId;
        beginForcedMissionTravel(next, start, requestId);
    }

    /** Publish the acknowledged move after the run is committed and the old scene is retired. */
    private void beginForcedMissionTravel(SceneRegistry.Instance next, ScenePortals.Entrance start, short requestId)
            throws IOException {
        synchronized (session) {
            session.closeConversation();
            session.closeTownDialog();
            var runtime = session.runtime();
            runtime.beginPortal(next, start);
            session.publishPartyState();
            runtime.retireScene(runtime.scene().oid());
            short receiver = session.forcedReceiverValue();
            responder.confirmed(requestId);
            responder.send(() -> responder.codec().forceScene(responder.oid(), receiver, next));
        }
    }

    private void settleMissionExchange(String missionId, long[] itemIds) throws IOException {
        session.reloadProfile();
        var before = session.currentProfile();
        boolean firstEver = session.completedOpeningEmpty();
        var store = session.charactersStore();
        var settled = store.services()
                .openingMissions(
                        session.sceneRegistry().town(), session.sceneRegistry().missions())
                .exchange(
                        store.accountId(),
                        session.selectedKnight().id(),
                        session.authRevision(),
                        before,
                        missionId,
                        itemIds);
        session.applyProfile(settled.profile());
        session.recordOpeningCompletion(missionId, settled.completion());
        if (settled.first()) {
            session.membership().equipment(session.visibleProfileNow());
            long dialog = session.noteMissionDialog();
            var settledProfile = session.currentProfile();
            responder.send(() -> responder
                    .codec()
                    .missionSettlement(
                            responder.oid(),
                            before,
                            settledProfile,
                            settled.completion(),
                            session.sceneRegistry().missions().grants(missionId),
                            session.sceneRegistry().missions().prestige(missionId),
                            true,
                            dialog));
            responder.send(() ->
                    responder.codec().itemChanges(responder.oid(), session.selectedKnight(), before, settledProfile));
            if (firstEver)
                responder.send(() -> responder.codec().controllerAction(responder.oid(), "new_mission", null));
            session.publishPartyState();
        }
    }
}
