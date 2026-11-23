package org.tortodrone.server.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.world.MapScene;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.protocol.SessionDecoder;

/** Town-destination commands and their queued replay for one connection.
 *
 * <p>The stock destination marshaller may arrive before the scene is ready; that request is
 * kept and replayed once the receiver and place exist. Alchemy picks avoid the previous station
 * and only accept a placement validated against the live scene.
 */
final class DestinationHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private SessionDecoder.Invocation pendingTravel;
    private boolean auctionAfterArrival;
    private String alchemyTarget;

    DestinationHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    /** KnightMarshaller 7: stock town destination travel. */
    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            var args = request.arguments();
            if (args.size() != 2
                    || !(args.get(0) instanceof String destination)
                    || !(args.get(1) instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ResultMarshaller"))
                throw new SessionDecoder.Rejected("Invalid town destination request");
            try {
                if (!session.selected()) throw new IOException("Select a knight first");
                if (session.forcedReceiverValue() == null
                        || session.sceneTravelPending()
                        || (!session.inReadyRoom() && (!session.sceneEntered() || !session.sceneSubscribed()))) {
                    if (pendingTravel != null
                            && pendingTravel != request
                            && pendingTravel.arguments().getLast() instanceof SessionDecoder.Listener old) {
                        short oldId = old.requestId();
                        responder.send(() ->
                                responder.codec().invocationFailure(responder.oid(), oldId, "Travel request replaced"));
                    }
                    pendingTravel = request;
                    session.sessionLog()
                            .debug("Queued town destination; destination=" + destination
                                    + "; entered=" + session.sceneEntered() + "; subscribed="
                                    + session.sceneSubscribed()
                                    + "; receiver=" + session.forcedReceiverValue() + "; portal="
                                    + session.sceneTravelPending()
                                    + "; readyRoom=" + session.inReadyRoom());
                    return true;
                }
                session.charactersStore()
                        .worldProfile(
                                session.selectedKnight().name(), session.authRevision(), session.currentProfile());
                MapScene map = null;
                ScenePortals.Entrance entrance = null;
                AlchemyDestination alchemy = null;
                if (destination.equals("move_alchemy_machine")) {
                    alchemy = selectAlchemy();
                    if (alchemy != null) {
                        entrance = alchemy.entrance();
                        map = session.currentScene().map();
                    }
                }
                if (destination.equals("haven"))
                    map = session.sceneRegistry().catalog().entry();
                if (map == null) {
                    int candidate = switch (destination) {
                        case "training" -> 294;
                        case "lab" -> 30893;
                        case "hoh" -> 318;
                        default -> -1;
                    };
                    if (candidate > 0 && session.sceneRegistry().catalog().contains(candidate))
                        map = session.sceneRegistry().catalog().scene(candidate);
                    if (map == null) map = session.sceneRegistry().catalog().townByName("%town:m." + destination);
                }
                if (map == null && destination.matches("[a-zA-Z0-9_-]{1,96}"))
                    for (var candidate : session.sceneRegistry().catalog().towns()) {
                        String prefix = "destination." + destination + ".";
                        var stats = candidate.content().stats();
                        if (!stats.containsKey(prefix + "x")) continue;
                        float x = stats.get(prefix + "x"), y = stats.get(prefix + "y");
                        if (!candidate.collision().walkable(x, y)) continue;
                        if (candidate.portals().triggers().stream().anyMatch(t -> t.intersects(x, y))) continue;
                        map = candidate;
                        entrance = new ScenePortals.Entrance(
                                Math.round(stats.get(prefix + "id")),
                                x,
                                y,
                                stats.getOrDefault(prefix + "rotation", 0f));
                        break;
                    }
                if (map == null) throw new IOException("%chat:m.no_such_scene");
                var next = session.destination(map.id());
                if (entrance == null)
                    entrance = new ScenePortals.Entrance(1, map.spawnX(), map.spawnY(), map.spawnRotation());
                if (!next.simulation().placement(entrance.x(), entrance.y()))
                    throw new IOException("Destination is blocked");
                if (destination.equals("move_auction")) session.auctionSource(next);
                if (session.inReadyRoom()) {
                    auctionAfterArrival = destination.equals("move_auction");
                    short receiver = session.forcedReceiverValue();
                    session.enterScene(next, entrance);
                    if (alchemy != null) alchemyTarget = alchemy.key();
                    responder.send(() -> responder.codec().forceScene(responder.oid(), receiver, next));
                    responder.send(() -> responder
                            .codec()
                            .townResult(responder.oid(), listener.requestId(), "", session.currentProfile()));
                    return true;
                }
                if (next == session.currentScene()) {
                    if (!session.membership().teleport(entrance.x(), entrance.y(), entrance.rotation()))
                        throw new IOException("Destination is blocked or the knight is busy");
                    if (alchemy != null) alchemyTarget = alchemy.key();
                    session.closeConversation();
                    session.closeTownDialog();
                    if (destination.equals("move_auction")) session.openAuction();
                    responder.send(() -> responder
                            .codec()
                            .townResult(responder.oid(), listener.requestId(), "", session.currentProfile()));
                    return true;
                }
                auctionAfterArrival = destination.equals("move_auction");
                session.closeConversation();
                session.closeTownDialog();
                session.beginForcedTravel(next, entrance);
                short receiver = session.forcedReceiverValue();
                responder.send(() -> responder.codec().forceScene(responder.oid(), receiver, next));
                responder.send(() -> responder
                        .codec()
                        .townResult(responder.oid(), listener.requestId(), "", session.currentProfile()));
            } catch (CharacterStore.Refused | IOException refused) {
                responder.send(() -> responder
                        .codec()
                        .invocationFailure(responder.oid(), listener.requestId(), refused.getMessage()));
            }
            return true;
        }
    }

    /** KnightMarshaller 11 outside the Ready Room: the stock /haven return to town. The
     *  client confirms any party abandon locally and sends the request without a listener,
     *  so the only native answer is the forced scene load. */
    boolean returnToTown() throws IOException {
        synchronized (session) {
            if (session.inReadyRoom()
                    || session.forcedReceiverValue() == null
                    || session.sceneTravelPending()
                    || session.deferredMovePending()) return false;
            try {
                session.charactersStore()
                        .worldProfile(
                                session.selectedKnight().name(), session.authRevision(), session.currentProfile());
                var map = session.sceneRegistry().catalog().entry();
                var next = session.destination(map.id());
                var entrance = new ScenePortals.Entrance(1, map.spawnX(), map.spawnY(), map.spawnRotation());
                if (!next.simulation().placement(entrance.x(), entrance.y()))
                    throw new IOException("Destination is blocked");
                session.closeConversation();
                session.closeTownDialog();
                if (next == session.currentScene()) {
                    if (!session.membership().teleport(entrance.x(), entrance.y(), entrance.rotation()))
                        throw new IOException("Destination is blocked or the knight is busy");
                    return true;
                }
                session.beginForcedTravel(next, entrance);
                short receiver = session.forcedReceiverValue();
                responder.send(() -> responder.codec().forceScene(responder.oid(), receiver, next));
            } catch (CharacterStore.Refused | IOException refused) {
                responder.send(() ->
                        responder.codec().systemNotice(session.currentScene().oid(), refused.getMessage()));
            }
            return true;
        }
    }

    /** Replay a town destination command that arrived before the scene was ready. */
    void flush() throws IOException {
        synchronized (session) {
            if (pendingTravel == null
                    || session.isClosed()
                    || !session.selected()
                    || session.forcedReceiverValue() == null
                    || session.sceneTravelPending()
                    || session.deferredMovePending()) return;
            if (!session.inReadyRoom() && (!session.sceneEntered() || !session.sceneSubscribed())) return;
            var request = pendingTravel;
            pendingTravel = null;
            session.sessionLog().debug("Replaying queued town destination after scene entry");
            invocation(request);
        }
    }

    /** The queued destination request, if the stock client arrived before the place was ready. */
    SessionDecoder.Invocation queued() {
        return pendingTravel;
    }

    boolean auctionAfterArrivalPending() {
        return auctionAfterArrival;
    }
    /** Consumed once the destination place has finished entering. */
    boolean takeAuctionAfterArrival() {
        boolean value = auctionAfterArrival;
        auctionAfterArrival = false;
        return value;
    }

    void clearAlchemyTarget() {
        alchemyTarget = null;
    }

    private record AlchemyDestination(String key, ScenePortals.Entrance entrance) {}
    /** Archive TudeySceneManager.getPortalTransform picks a random tagged destination.
     * Avoid the last successful station when another usable station is available. */
    private AlchemyDestination selectAlchemy() throws IOException {
        var place = session.currentScene();
        var machines = place.map().content().entries().stream()
                .filter(e -> e.spec().text("town-kind", "").equals("OpenCrafter")
                        && e.spec().config().name().equals("Shop/Haven/Alchemy"))
                .toList();
        if (machines.isEmpty()) return null;
        var candidates = new ArrayList<>(machines);
        Collections.shuffle(candidates);
        if (machines.size() > 1 && alchemyTarget != null) {
            var previous = candidates.stream()
                    .filter(e -> e.key().equals(alchemyTarget))
                    .findFirst()
                    .orElse(null);
            if (previous != null) {
                candidates.remove(previous);
                candidates.add(previous);
            }
        }
        for (var machine : candidates) {
            // The actor's facing identifies its front. Validate the complete pawn placement,
            // including live props, before relocating; never land inside the machine.
            for (float distance : new float[] {1.7f, 2f, 2.5f, 3f}) {
                float x = machine.x() + (float) Math.cos(machine.rotation()) * distance;
                float y = machine.y() + (float) Math.sin(machine.rotation()) * distance;
                if (place.simulation().placement(x, y)) {
                    return new AlchemyDestination(
                            machine.key(), new ScenePortals.Entrance(1, x, y, (float) (machine.rotation() + Math.PI)));
                }
            }
        }
        throw new IOException("Alchemy machines are blocked");
    }
}
