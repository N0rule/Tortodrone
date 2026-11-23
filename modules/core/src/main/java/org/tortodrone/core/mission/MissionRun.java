package org.tortodrone.core.mission;

import java.util.*;
import org.tortodrone.core.world.SceneCatalogView;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;

/** Server-issued solo mission attempt and its ordered floors.
 *
 * <p>The route is fixed when the attempt starts. Each floor carries the native entry that
 * completes it and the authored entrance the party appears through. Settlement happens once,
 * on the final floor; earlier floors only report and travel onward. The opening gate is the
 * same shape with one floor and a pinned return town.
 */
public record MissionRun(
        String missionId,
        int gateId,
        List<Floor> floors,
        int floor,
        int worldId,
        int partyOid,
        String difficulty,
        org.tortodrone.core.world.MapScene destination,
        ScenePortals.Entrance arrival) {
    public static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");
    public static final int MAX_FLOORS = 64;

    /** One authored floor, the entry key of its completing elevator and its arrival point. */
    public record Floor(
            org.tortodrone.core.world.MapScene scene, String exit, ScenePortals.Entrance entrance, String type) {
        public Floor {
            Objects.requireNonNull(scene);
            Objects.requireNonNull(entrance);
            if (exit == null
                    || exit.isBlank()
                    || exit.length() > 64
                    || exit.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid mission exit key");
            if (type == null || !MissionGates.LEVEL_TYPES.contains(type))
                throw new IllegalArgumentException("Invalid mission floor type");
        }
    }

    public MissionRun {
        Objects.requireNonNull(missionId);
        Objects.requireNonNull(floors);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(arrival);
        if (missionId.isBlank() || missionId.length() > 128) throw new IllegalArgumentException("Invalid mission id");
        if (gateId < 0) throw new IllegalArgumentException("Invalid mission gate id");
        if (floors.isEmpty() || floors.size() > MAX_FLOORS)
            throw new IllegalArgumentException("Invalid mission floor count");
        floors = List.copyOf(floors);
        for (var value : floors) Objects.requireNonNull(value);
        if (floor < 0 || floor >= floors.size()) throw new IllegalArgumentException("Invalid mission floor position");
        if (worldId < 1 || partyOid < 1) throw new IllegalArgumentException("Invalid mission owner");
        if (!DIFFICULTIES.contains(difficulty)) throw new IllegalArgumentException("Invalid mission difficulty");
    }

    /** The pinned single-floor opening gate. */
    public static MissionRun opening(OpeningGate gate, int worldId, int partyOid, String difficulty) {
        Objects.requireNonNull(gate);
        return new MissionRun(
                gate.mission().id(),
                gate.id(),
                List.of(new Floor(
                        gate.source(), Integer.toString(OpeningGate.GENERATOR_EXIT), gate.entrance(), "LEVEL")),
                0,
                worldId,
                partyOid,
                difficulty,
                gate.destination(),
                gate.arrival());
    }

    /** An imported gate route whose every floor scene, elevator exit and entrance is present.
     *  Returns null when the route is not playable with the bundled content. */
    public static MissionRun route(
            MissionGates.Gate gate,
            SceneCatalogView catalog,
            int worldId,
            int partyOid,
            String difficulty,
            org.tortodrone.core.world.MapScene home) {
        Objects.requireNonNull(gate);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(home);
        if (!home.town()) return null;
        var floors = new ArrayList<Floor>();
        boolean playable = false;
        for (var wedge : gate.wedges()) {
            if (wedge.scene() <= 0 || !catalog.contains(wedge.scene())) return null;
            org.tortodrone.core.world.MapScene scene;
            try {
                scene = catalog.scene(wedge.scene());
            } catch (java.io.IOException unavailable) {
                return null;
            }
            if (scene.town()) return null;
            var exit = exits(scene);
            if (exit.size() != 1) return null;
            if (!wedge.type().equals("LOBBY")) playable = true;
            floors.add(new Floor(scene, exit.getFirst(), spawn(scene), wedge.type()));
        }
        if (!playable) return null;
        return new MissionRun(
                gate.missionId(), gate.gateId(), floors, 0, worldId, partyOid, difficulty, home, spawn(home));
    }

    /** The entry keys of the scene's own completing elevators. */
    public static List<String> exits(org.tortodrone.core.world.MapScene scene) {
        var result = new ArrayList<String>();
        for (var entry : scene.content().entries()) {
            if (!entry.spec().fields().containsKey("elevator-mode")) continue;
            var elevator = SceneElevator.read(entry.spec());
            if (elevator != null && elevator.completesGate()) result.add(entry.key());
        }
        return List.copyOf(result);
    }

    private static ScenePortals.Entrance spawn(org.tortodrone.core.world.MapScene scene) {
        return scene.portals().entrances().values().stream()
                .filter(e -> e.x() == scene.spawnX()
                        && e.y() == scene.spawnY()
                        && Math.abs(e.rotation() - scene.spawnRotation()) < .00001f)
                .findFirst()
                .orElse(new ScenePortals.Entrance(1, scene.spawnX(), scene.spawnY(), scene.spawnRotation()));
    }

    public Floor current() {
        return floors.get(floor);
    }

    public org.tortodrone.core.world.MapScene source() {
        return current().scene();
    }

    public ScenePortals.Entrance entrance() {
        return current().entrance();
    }

    public String exitKey() {
        return current().exit();
    }

    public String type() {
        return current().type();
    }

    public boolean lastFloor() {
        return floor == floors.size() - 1;
    }

    public int difficultyOrdinal() {
        return DIFFICULTIES.indexOf(difficulty);
    }

    /** The next floor of this same attempt; only valid when this is not the final floor. */
    public MissionRun advance() {
        if (lastFloor()) throw new IllegalStateException("The final mission floor has no successor");
        return new MissionRun(
                missionId, gateId, floors, floor + 1, worldId, partyOid, difficulty, destination, arrival);
    }

    /** Native NextFloor on this floor's authored completing elevator. The elevator is a placed
     *  {@code EntryState} entry with a numeric scene-entry identity even though it is a live,
     *  published actor rather than a static interaction target, so a pending ride source is the
     *  authored exit when its entry key matches. */
    public boolean exit(org.tortodrone.core.world.FloorAdvanceRequest request) {
        if (request == null) return false;
        var entity = request.source().entity();
        return entity != null
                && (entity.entrySource() || entity.kind().equals("EntryState"))
                && entity.key().equals(exitKey());
    }
}
