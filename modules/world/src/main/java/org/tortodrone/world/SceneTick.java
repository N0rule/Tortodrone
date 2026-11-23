package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneShape;

/** One tick of the shared mutable entity model: staged insertion, station proximity, linked
 *  destruction, per-kind lifecycle and scripted behaviour, status pulses, sensor signals and
 *  handler advancement. The entity model owns identity, insertion and the stored state. */
final class SceneTick {
    private final SceneEntities scene;

    SceneTick(SceneEntities scene) {
        this.scene = scene;
    }

    void advance(int stamp, List<ActorState> occupants) {
        // Publish only the batch staged before this tick. Work queued by spawned
        // actors cannot recursively drain this batch; capacity also bounds later ticks.
        scene.drainPending();
        var players = occupants.stream()
                .filter(p -> p.activity() != TownMovement.DYING
                        && p.activity() != TownMovement.DEAD
                        && p.activity() != TownMovement.RESURRECT)
                .toList();
        scene.tickPlayers(players);
        scene.beginCombatTick(scene.engagedMonsters());
        scene.advanceWaves(stamp);
        scene.tickStations(stamp, players);
        scene.drainLinks(stamp);
        float elapsed = scene.advanceClock(stamp);
        for (var e : List.copyOf(scene.entityValues())) {
            if (e.destroyed != Integer.MAX_VALUE) {
                if (e.trip != null) scene.cancelElevator(e.trip, stamp);
                // Monster destroyed time already includes the native death animation.
                // Retain one short publication grace, rather than counting it twice.
                if (e.scriptDestroyed ? stamp - e.destroyed > 0 : stamp - e.destroyed >= 100) {
                    scene.actions().cancelSource("actor:" + e.id);
                    scene.shutdownEntity(e, stamp);
                    scene.unlink(e);
                    scene.deactivateEntity(e);
                    scene.actions()
                            .removed(
                                    new HashSet<>(Arrays.asList(
                                            e.spec.text("tags", "").split(","))),
                                    tag -> scene.entityValues().stream()
                                            .anyMatch(other -> Arrays.asList(other.spec
                                                            .text("tags", "")
                                                            .split(","))
                                                    .contains(tag)),
                                    stamp,
                                    "actor:" + e.id);
                    if (e.pad != null) e.pad.nextGeneration = e.destroyed + e.pad.spec.integer("delay", 0);
                }
                continue;
            }
            if (e.kind.equals("Pickup")
                    && !e.claimTaken
                    && e.spec.integer("lifespan", 0) > 0
                    && stamp - e.created >= e.spec.integer("lifespan", 0)) {
                e.destroyed = stamp;
                e.claim = 0;
                continue;
            }
            // Native PickupLogic flashes expiring pickups during their last five seconds.
            if (e.kind.equals("Pickup")
                    && e.spec.integer("lifespan", 0) > 0
                    && stamp - e.created > e.spec.integer("lifespan", 0) - 5000) e.flags |= 2;
            if (e.kind.equals("Generator") && e.nextGeneration > 0) {
                e.flags = stamp + e.spec.integer("warning", 0) >= e.nextGeneration ? 2 : 0;
                if (SceneClock.reached(stamp, e.nextGeneration)) {
                    e.flags &= ~2;
                    scene.generate(e, e.nextGeneration);
                }
            }
            if (e.kind.equals("Trap")) scene.cycle(e, stamp);
            if (e.spec.fields().containsKey("hazard-lifetime")
                    && stamp - e.created >= e.spec.integer("hazard-lifetime", 0)) {
                e.destroyed = e.created + e.spec.integer("hazard-lifetime", 0);
                continue;
            }
            if (e.hazardStatus != null
                    && stamp - e.created
                            < e.spec.integer(
                                    "hazard-active-lifetime", e.spec.integer("hazard-lifetime", Integer.MAX_VALUE))
                    && stamp >= e.hazardNext) {
                // Native Extra Action torch: applies its status damage region to everything
                // inside the patch on the Floor trap cadence (hazard-refractory, default 500).
                e.hazardNext = stamp + e.spec.integer("hazard-refractory", 500);
                var shape = new FireCatalog.DataValue(
                        org.tortodrone.core.WireTypes.SHAPE_CIRCLE,
                        List.of(new FireCatalog.FieldValue("radius", "float", e.spec.number("radius", 0))));
                var hitArgs = new LinkedHashMap<String, Object>(Map.of(
                        "damage",
                        e.hazardStatus,
                        "region",
                        new FireCatalog.DataValue(
                                org.tortodrone.core.WireTypes.REGION_DEFAULT,
                                Map.of(
                                        "location",
                                        new FireCatalog.DataValue(
                                                "com.threerings.tudey.config.TargetConfig$Source", List.of()),
                                        "expansion",
                                        0f))));
                hitArgs.put("shape", shape);
                scene.damage(
                        new StarterCatalog.Model("DamageRegionFromPoint", hitArgs),
                        e.x,
                        e.y,
                        e.rotation,
                        stamp,
                        e.id,
                        null);
            }
            if (e.detonateAt > 0 && SceneClock.reached(stamp, e.detonateAt)) {
                e.destroyed = e.detonateAt;
                String graph = e.spec.text("detonation-action", "");
                if (!graph.isBlank())
                    try {
                        scene.fireAttack(e.snapshot(), FireCatalog.actionJson(graph));
                    } catch (java.io.IOException invalid) {
                        throw new IllegalStateException("Invalid block detonation", invalid);
                    }
                continue;
            }
            if (e.kind.equals("Door") && (e.flags & 4) != 0 && stamp - e.stateEntered > e.spec.integer("door-speed", 0))
                e.flags &= ~4;
            if (e.kind.equals("Switch")) scene.switches(e, stamp, players);
            if (e.elevator != null) scene.advanceElevator(e, stamp, players);
            if (e.spec.flag("heal-pad", false) && SceneClock.reached(stamp, e.nextHeal)) {
                e.nextHeal = stamp + Math.round(e.spec.number("heal-interval", 0) * 1000);
                for (var player : players)
                    if (e.spec.contains(e.x, e.y, e.rotation, player.x(), player.y(), ScenePortals.PAWN_RADIUS)
                            && scene.healPlayer(player.id(), e.spec.integer("heal", 0))) {
                        String graph = e.spec.text("heal-action", "");
                        if (!graph.isBlank())
                            try {
                                scene.fireAttack(e.snapshot(), FireCatalog.actionJson(graph));
                            } catch (java.io.IOException invalid) {
                                throw new IllegalStateException("Invalid prepared heal action", invalid);
                            }
                    }
            }
            if (e.kind.equals("Trap") && e.state == 2 && e.spec.fields().containsKey("sensor")) {
                Object sensor = e.spec.model("sensor").arguments().get("value");
                float c = (float) Math.cos(e.rotation), s = (float) Math.sin(e.rotation);
                for (var player : players) {
                    float dx = player.x() - e.x, dy = player.y() - e.y;
                    if (SceneClock.reached(stamp, e.hazardHits.getOrDefault(player.id(), 0))
                            && SceneShape.intersects(
                                    sensor, dx * c + dy * s, -dx * s + dy * c, ScenePortals.PAWN_RADIUS)) {
                        try {
                            scene.fireAttack(e.snapshot(), FireCatalog.actionJson(e.spec.text("hazard-action", "")));
                        } catch (java.io.IOException invalid) {
                            throw new IllegalStateException("Invalid prepared trap action", invalid);
                        }
                        e.hazardHits.put(player.id(), stamp + e.spec.integer("hazard-refractory", 0));
                    }
                }
                e.hazardHits.keySet().removeIf(id -> players.stream().noneMatch(p -> p.id() == id));
            }
            // PickupLogic's minimum activation time lets the spawn visual complete
            // before a knight standing on the pad can collect its replacement.
            if (e.kind.equals("Pickup") && e.claim == 0 && stamp - e.created >= 1000)
                scene.collectPickup(e, elapsed, players);
            if (e.kind.equals("Monster")) {
                scene.tickStatus(e, stamp, elapsed);
                if (e.program != null) scene.scriptedProgram(e, stamp, elapsed);
                else if (e.script != null) scene.scriptedMovement(e, stamp, elapsed);
                else if (e.health == 0) e.status.clear();
                else {
                    if (e.status.state().modifiers().canMove()) scene.advanceImpulse(e, stamp);
                    else e.impulse = null;
                    // Idle wander and party follow are authored motion, not combat pursuit,
                    // and keep their every-tick integration without players.
                    if (e.wander != null || e.follow != null) {
                        e.band = SceneMonsterBand.ENGAGED;
                        scene.move(e, stamp, elapsed, players);
                    } else scene.think(e, stamp, elapsed, players);
                }
            }
            if (SceneEntities.liftKind(e.kind)) scene.liftTick(e, stamp, elapsed, players);
            if (e.activity == 1 && stamp - e.activityStarted >= e.spec.integer("react-time", 0)
                    || e.activity == 3 && stamp - e.activityStarted >= e.spec.integer("activate-time", 0))
                e.activity = 0;
        }
        for (var e : List.copyOf(scene.entityValues()))
            for (var pulse : e.status.takePulses()) scene.statusPulse(e.id, pulse, e.x, e.y, stamp);
        for (var e : List.copyOf(scene.entityValues()))
            for (var action : e.status.takeActions()) scene.fireAttack(e.snapshot(), action);
        for (var sensor : scene.content().entries())
            if (sensor.spec().flag("scene-sensor", false)) {
                int mask = sensor.spec().integer("sensor-mask", 0),
                        pawnFlags = Math.round(scene.content().stats().getOrDefault("player.collision-flags", 4f));
                if ((mask & pawnFlags) == 0) continue;
                for (var player : players)
                    if (scene.activePlayer(player.id())
                            && sensor.spec()
                                    .contains(
                                            sensor.x(),
                                            sensor.y(),
                                            sensor.rotation(),
                                            player.x(),
                                            player.y(),
                                            ScenePortals.PAWN_RADIUS))
                        scene.actions()
                                .signal(
                                        sensor.spec().text("watch-tag", ""),
                                        sensor.spec().text("signal", ""),
                                        stamp,
                                        "actor:" + player.id());
            }
        scene.actions().advance(stamp);
        scene.retainActionSources();
    }
}
