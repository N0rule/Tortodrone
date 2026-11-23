package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.MonsterArchetype;
import org.tortodrone.core.world.PreparedActor;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneAttackMotion;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.SceneEntityState;
import org.tortodrone.core.world.SceneFollow;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.SceneMovementScript;
import org.tortodrone.core.world.ScenePartyPlatform;
import org.tortodrone.core.world.ScenePickupState;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneScript;
import org.tortodrone.core.world.SceneShape;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.SceneTargeting;
import org.tortodrone.core.world.SceneWander;

/** The shared mutable entity model for one scene instance. Directly placed actors, wave
 *  spawns, scripted props, lifts and pickups all use this one representation; systems
 *  operate on it under the scene monitor and never replace it with a copy. */
final class SceneEntity {
    final int id, created;
    final SceneContent.Spec spec;
    final SceneContent.Entry binding;
    final String kind;
    final StarterCatalog.Model config;
    final SceneEntity pad;
    float x, y, rotation, direction, health, speed, attackSpeed = 1;
    int flags,
            activity,
            activityStarted,
            state,
            stateEntered,
            count,
            hitTime,
            destroyed = Integer.MAX_VALUE,
            nextGeneration,
            claim;
    boolean claimTaken, ignited, scriptDestroyed;
    int doorIncrements;
    String scriptSource = "";
    final SceneMovementScript script;
    final SceneScript program;
    final Map<Integer, SceneActions.Prepared> scriptActions = new HashMap<>();
    final Map<String, SceneActions.Action> sceneFireActions;
    final PreparedActor prepared;
    final SceneActions.Action shutdown;
    final ScenePartyPlatform platform;
    final SceneElevator elevator;
    SceneEntities.ElevatorTrip trip;
    boolean elevatorOffered;
    int elevatorDrop;
    SceneActions.Prepared partyIntersection;
    float scriptRotation;
    final SceneTargeting targeting;
    /** Per attack slot, the effective edge range a melee damage region can reach.
     *  Only slots whose first fire action is a transformed damage circle carry an
     *  entry; ranged/projectile slots keep their native targeting range. */
    final Map<Integer, Float> meleeRangeCaps;

    SceneTargeting.Mode targetMode;
    boolean targetModeUpdate, idleAttackDone;
    float targetRotation;
    final SceneWander wander;
    final SceneFollow follow;
    /** Catalogue type id for wave/admin spawns; blank for a directly placed monster. */
    String monsterType = "";

    int nextFollow, followTarget;
    ScenePickupState pickupState;
    final Map<Integer, SceneAttackMotion> attackMotion;
    int wanderPhase, wanderNext;
    float wanderRotation;
    final Map<Integer, Threat> victims;

    record Threat(int hits, float damage) {}

    int scriptStep, scriptStarted = Integer.MIN_VALUE;
    ActorState scriptGoal;
    /** A wait-move-rotate script whose Condition step waits for its activate signal. */
    boolean scriptActivated;

    int target,
            nextAcquire,
            nextAttack,
            nextSwitch,
            nextHeal,
            carrier,
            owner,
            ownerOid,
            thrownAt,
            faction,
            detonateAt,
            affectedType,
            affectedTime;
    float z;
    int[] excluded;
    /** Activation scheduling; combat work is skipped while DORMANT. */
    SceneMonsterBand band = SceneMonsterBand.DORMANT;

    int nextThink, engagedUntil;
    /** Scheduling policy; prepared specs may override its radii but not its identity. */
    final MonsterArchetype archetype;

    final SceneMonsterBand minimumBand;
    final float activationRadius;
    /** Placement anchor for leash return; wave spawns use their authored anchor. */
    final float homeX, homeY, leashRadius, leashHeal;

    boolean returning;
    /** Non-null when a wave controller owns this monster. */
    String waveId;
    /** Cached line of sight from the current position to a recent target point. */
    int sightStamp = Integer.MIN_VALUE;

    float sightX, sightY;
    boolean sightResult;
    /** Spatial-index bookkeeping, kept in entity insertion order for deterministic queries. */
    long order;

    long[] spatialCells;
    int spatialMinX, spatialMaxX, spatialMinY, spatialMaxY;
    final float spatialExtent;
    List<ScenePathing.Point> path = List.of();
    float pathX, pathY;
    int nextPath;
    SceneLiftState.Point dropPoint;
    SceneContent.Spec vial;
    String cycle;
    Set<String> fired = new HashSet<>();
    Object hazardStatus;
    int hazardNext;
    /** Native MonsterLogic._reactDamage: each hurt is remembered for one second. */
    final ArrayDeque<int[]> reactDamage = new ArrayDeque<>();

    final SceneStatus status;
    final Object shape;
    final int collisionFlags;
    final boolean circularPawnBounds, compoundPawnBounds, interactive;
    final float maximumHealth;
    final float pawnRadius, pawnMinX, pawnMinY, pawnMaxX, pawnMaxY;
    final SceneCollision.PawnShape pawnShape;
    SceneCollision.PawnShape posedPawnShape;
    float pawnX, pawnY, pawnRotation;
    KnightCombat.Impulse impulse;
    int impulseAdvanced;
    final Map<Integer, Integer> hazardHits = new HashMap<>();
    final Set<Integer> refusedPickups = new HashSet<>();

    SceneEntity(
            int id,
            int stamp,
            SceneContent.Entry binding,
            SceneContent.Spec spec,
            SceneEntity pad,
            SceneContent content) {
        spec = content.actor(spec);
        this.id = id;
        created = stamp;
        this.binding = binding;
        this.spec = spec;
        this.pad = pad;
        nextThink = Integer.MIN_VALUE;
        if (spec.fields().containsKey("door-increments")) {
            doorIncrements = spec.integer("door-increments", 0);
            if (!spec.text("kind", "").equals("Door") || doorIncrements < 1 || doorIncrements > 1_000_000)
                throw new IllegalArgumentException("Invalid native door counter");
        }
        if (spec.fields().containsKey("door-lock")
                && (!spec.text("kind", "").equals("Door")
                        || !spec.text("door-lock", "").equals("gold")))
            throw new IllegalArgumentException("Unsupported scene door lock");
        var prepared = PreparedActor.of(spec);
        this.prepared = prepared;
        script = prepared.script();
        program = prepared.program();
        targeting = prepared.targeting();
        wander = prepared.wander();
        follow = prepared.follow();
        attackMotion = prepared.attackMotion();
        meleeRangeCaps = prepared.meleeRangeCaps();
        platform = prepared.platform();
        elevator = prepared.elevator();
        shutdown = prepared.shutdown();
        sceneFireActions = prepared.sceneFireActions();
        if (follow != null && (targeting == null || targeting.monsterMask() == 0 || script != null || wander != null))
            throw new IllegalArgumentException("Conflicting native follow routine");
        if ((script != null || program != null) && (targeting != null || wander != null || follow != null))
            throw new IllegalArgumentException("Conflicting scene actor behaviors");
        victims = targeting == null ? Map.of() : new LinkedHashMap<>();
        config = prepared.config();
        kind = prepared.kind();
        String type = spec.text("native-type", "");
        int heat = spec.integer("pickup-heat", 0);
        if (heat < 0
                || heat > 1_000_000
                || heat > 0
                        && (!kind.equals("Pickup")
                                || !spec.text("pickup-item", "").isEmpty()))
            throw new IllegalArgumentException("Invalid native heat pickup");
        if ((targeting != null || wander != null || !attackMotion.isEmpty()) && !kind.equals("Monster"))
            throw new IllegalArgumentException("Native combat behavior requires a monster actor");
        interactive = content.conversations().containsKey(spec.text("dialogue", config.name()))
                || spec.fields().containsKey("town-station")
                || spec.fields().containsKey("town-kind");
        x = binding.x();
        y = binding.y();
        rotation = binding.rotation();
        health = spec.number("health", 0);
        maximumHealth = health;
        activityStarted = stateEntered = stamp;
        status = new SceneStatus(content, true);
        var preparedShape = spec.model("source-shape");
        shape = preparedShape == null ? null : preparedShape.arguments().get("value");
        collisionFlags = spec.integer("collision-flags", 0);
        circularPawnBounds = spec.fields().containsKey("radius");
        compoundPawnBounds = spec.text("shape", "").equals("Compound") && shape != null;
        pawnRadius = spec.number("radius", 0);
        pawnMinX = spec.number("bounds-min-x", -spec.number("width", 0) / 2);
        pawnMaxX = spec.number("bounds-max-x", spec.number("width", 0) / 2);
        pawnMinY = spec.number("bounds-min-y", -spec.number("height", 0) / 2);
        pawnMaxY = spec.number("bounds-max-y", spec.number("height", 0) / 2);
        pawnShape = collisionFlags == 0 ? null : SceneCollision.pawnShape(spec, shape);
        float shapeExtent = pawnShape != null ? pawnShape.extent() : 0;
        // Non-colliding actors (for example a Global music block) carry sentinel bounds
        // that are not geometry. Only colliding entities contribute bounds to the index.
        float boundsExtent = collisionFlags == 0
                ? 0
                : (float) Math.hypot(
                        Math.max(Math.abs(pawnMinX), Math.abs(pawnMaxX)),
                        Math.max(Math.abs(pawnMinY), Math.abs(pawnMaxY)));
        // Every query that scans an entity uses its radius, bounds or shape; the index
        // must cover all three so a cell lookup never misses a real contact.
        spatialExtent = Math.max(shapeExtent, Math.max(pawnRadius, boundsExtent));
        if (!Float.isFinite(spatialExtent) || spatialExtent < 0)
            throw new IllegalArgumentException("Invalid prepared actor spatial extent: " + binding.key());
        homeX = binding.x();
        homeY = binding.y();
        archetype = MonsterArchetype.of(spec.text("archetype", MonsterArchetype.DEFAULT_ID));
        minimumBand = switch (spec.text("think", "dormant")) {
            case "alert" -> SceneMonsterBand.ALERT;
            case "engaged" -> SceneMonsterBand.ENGAGED;
            default -> SceneMonsterBand.DORMANT;
        };
        float configuredActivation = spec.fields().containsKey("activation-radius")
                ? spec.number("activation-radius", 0)
                : archetype.activationRadius();
        activationRadius = Float.isFinite(configuredActivation) && configuredActivation >= 1 ? configuredActivation : 1;
        // Leashing is opt-in per placement; an archetype may supply a default.
        float configuredLeash =
                spec.fields().containsKey("leash-radius") ? spec.number("leash-radius", 0) : archetype.leashRadius();
        leashRadius = Float.isFinite(configuredLeash) && configuredLeash > 0 ? configuredLeash : 0;
        float configuredHeal = spec.number("leash-heal", 0);
        leashHeal = Float.isFinite(configuredHeal) && configuredHeal > 0 ? configuredHeal : 0;
        speed = spec.number("speed", 0);
        faction = spec.integer("faction", 0);
        if (kind.equals("TerritoryProp")) {
            if (!type.equals("com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp")
                    || !config.name().startsWith("Static/PvP/Territory Props/Lamps and Lighting/")
                    || !spec.flag("is-static", false)
                    || !spec.text("shape", "").equals("Point")
                    || collisionFlags != 0
                    || spec.integer("collision-mask", 0) != 0
                    || spec.integer("territory-color", 99) != 99)
                throw new IllegalArgumentException(
                        "Unsupported territory control actor; only neutral static lighting is implemented");
            state = 99; // Installed native TerritoryProp constructor's neutral colorId.
        }
        if (kind.equals("Switch")) {
            flags |= 2; // Native Switch constructor's START flag, including help bubbles.
            int startup = spec.integer("startup-state", 0);
            if (startup != 0) {
                // Authored native Startup State: the placeable's Startup handler signals
                // the switch into this state at its creation tick, and Switch.setState
                // then clears START exactly outside state 0.
                if (startup < 0 || startup >= spec.integer("switch-states", 0))
                    throw new IllegalArgumentException("Invalid native switch startup state");
                state = startup;
                flags &= ~2;
            }
        }
        if (spec.flag("start-open", false)) flags |= 2;
        cycle = spec.text("cycle", "");
        int split = cycle.lastIndexOf('|');
        if (split >= 0) cycle = cycle.substring(split + 1);
    }

    boolean containsPawn(float px, float py) {
        if (circularPawnBounds) return Math.hypot(x - px, y - py) <= pawnRadius + ScenePortals.PAWN_RADIUS;
        float dx = px - x,
                dy = py - y,
                c = (float) Math.cos(rotation),
                s = (float) Math.sin(rotation),
                tx = dx * c + dy * s,
                ty = -dx * s + dy * c;
        if (compoundPawnBounds) return SceneShape.intersects(shape, tx, ty, ScenePortals.PAWN_RADIUS);
        float gapX = Math.max(pawnMinX - tx, Math.max(0, tx - pawnMaxX)),
                gapY = Math.max(pawnMinY - ty, Math.max(0, ty - pawnMaxY));
        return gapX * gapX + gapY * gapY <= ScenePortals.PAWN_RADIUS * ScenePortals.PAWN_RADIUS;
    }

    SceneCollision.Penetration penetration(float px, float py) {
        if (posedPawnShape == null || pawnX != x || pawnY != y || pawnRotation != rotation) {
            posedPawnShape = pawnShape.at(x, y, rotation);
            pawnX = x;
            pawnY = y;
            pawnRotation = rotation;
        }
        return posedPawnShape.penetration(px, py, ScenePortals.PAWN_RADIUS);
    }

    static boolean liftKind(String kind) {
        return kind.equals("DungeonLift") || kind.equals("Lift");
    }

    boolean entrySource() {
        return interactive
                && spec.integer("handler-index", 0) < 0
                && binding.key().matches("[0-9]+");
    }

    ActorState snapshot() {
        return new ActorState(
                id,
                0,
                "",
                0,
                created,
                x,
                y,
                rotation,
                direction,
                flags,
                activity,
                activityStarted,
                config,
                false,
                null,
                null,
                new SceneEntityState(
                        kind,
                        destroyed,
                        speed,
                        pawnRadius,
                        maximumHealth > 0 ? health / maximumHealth : -1,
                        state,
                        stateEntered,
                        count,
                        hitTime,
                        binding.key(),
                        liftKind(kind) ? new SceneLiftState(z, faction, excluded, dropPoint) : null,
                        status.state(),
                        affectedType,
                        affectedTime,
                        entrySource(),
                        pickupState));
    }
}
