package org.tortodrone.world;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ProjectileState;
import org.tortodrone.core.world.SceneEntityState;
import org.tortodrone.world.DiskgunPolicy.PiercePolicy;

/** Scene-owned live projectile set: identity allocation, per-tick kinematics, contact,
 *  attachment and destruction. Owns {@link Shot} state only; the fire-action interpreter
 *  in {@link SceneWeapons} executes shutdown, detonation and timer actions through the
 *  {@link Host} contract so action ordering stays on the scene tick. */
final class SceneShots {
    /** Interpreter callbacks the projectile set needs; all run on the owning scene tick. */
    interface Host {
        boolean execute(FireCatalog.Action action, Pose source, int stamp, int depth);

        boolean clear(FireCatalog.ActorSpec spec, float fromX, float fromY, float toX, float toY, float rotation);
    }

    static final int MAX_ACTORS = 512;
    /** Destroyed shots stay tracked this long so late client frames can still resolve them. */
    private static final int DESTROYED_GRACE_MILLIS = 100;
    /** Orbit integration step: one native render frame (1/60 s). */
    private static final float ORBIT_STEP_SECONDS = .016666668f;

    private final Host host;
    private final Map<Integer, Shot> shots = new LinkedHashMap<>();
    private FireCatalog catalog = FireCatalog.empty();
    private SceneWeapons.Targets targets;
    private Map<Integer, ActorState> currentOwners = Map.of();
    private int nextId = 1_500_000_000;

    SceneShots(Host host) {
        this.host = host;
    }

    void catalog(FireCatalog value) {
        catalog = value;
    }

    void targets(SceneWeapons.Targets value) {
        targets = value;
    }

    void owners(Map<Integer, ActorState> value) {
        currentOwners = value;
    }

    ActorState owner(int id) {
        return currentOwners.get(id);
    }

    final class Shot {
        final int id;
        final FireCatalog.ActorSpec spec;
        final Pose source;
        final int created;
        final float originX, originY, rotation;
        final IdentityHashMap<FireCatalog.Action, Set<Integer>> hits = new IdentityHashMap<>();
        final PiercePolicy pierce;
        final Set<Integer> pierced = new HashSet<>();
        final int[] timers;
        float x, y, relativeX, relativeY;
        Object contactShape;
        int advanced, followId;
        int destroyed = Integer.MAX_VALUE, collided, pierceRemaining;
        boolean fizzle, returning;

        Shot(int id, FireCatalog.ActorSpec spec, Pose pose, int created, float x, float y, float rotation) {
            this(id, spec, pose, created, x, y, rotation, PiercePolicy.NONE);
        }

        Shot(
                int id,
                FireCatalog.ActorSpec spec,
                Pose pose,
                int created,
                float x,
                float y,
                float rotation,
                PiercePolicy pierce) {
            this.id = id;
            this.spec = spec;
            source = pose;
            this.created = created;
            originX = this.x = x;
            originY = this.y = y;
            this.rotation = rotation;
            this.pierce = pierce;
            pierceRemaining = pierce.extraPierces();
            advanced = created;
            timers = new int[spec.timers().size()];
            followId = spec.orbit() != null && spec.orbit().follow().equals("SOURCE") ? pose.actorId : pose.ownerId;
        }

        ActorState snapshot() {
            if (spec.nativeType().equals("AGENT"))
                return new ActorState(
                        id,
                        0,
                        "",
                        0,
                        created,
                        x,
                        y,
                        rotation,
                        rotation,
                        spec.flags(),
                        0,
                        created,
                        spec.config(),
                        false,
                        null,
                        null,
                        new SceneEntityState(
                                "Agent", destroyed, spec.speed(), spec.maxStep(), 0, 0, 0, 0, 0, Integer.toString(id)));
            var orbit = spec.orbit() == null
                    ? null
                    : new ProjectileState.Orbit(
                            spec.orbit().radius(),
                            spec.nativeType().equals("ORBITAL") ? followId : 0,
                            relativeX,
                            relativeY,
                            collided,
                            rotation,
                            source.ownerId);
            // Native _z is the source's floor elevation, not the authored bullet
            // height. Knights have no explicit Z; their client resolves the floor.
            var p = new ProjectileState(
                    spec.nativeType(),
                    spec.config(),
                    source.ownerOid,
                    created,
                    destroyed,
                    originX,
                    originY,
                    ProjectileState.UNSPECIFIED_Z,
                    spec.speed(),
                    spec.maxStep(),
                    source.ownerId,
                    fizzle,
                    orbit);
            int flags = spec.flags()
                    | (spec.nativeType().equals("BULLET") ? 2 : 0)
                    | (fizzle ? (orbit == null ? 4 : 8) : 0)
                    | (returning ? 4 : 0);
            return new ActorState(
                    id,
                    source.ownerOid,
                    "",
                    0,
                    created,
                    x,
                    y,
                    rotation,
                    rotation,
                    flags,
                    0,
                    created,
                    spec.config(),
                    false,
                    null,
                    p);
        }
    }

    Shot create(
            FireCatalog.ActorSpec spec, Pose pose, int stamp, float x, float y, float rotation, PiercePolicy pierce) {
        return new Shot(allocateId(), spec, pose, stamp, x, y, rotation, pierce);
    }

    void add(Shot shot) {
        shots.put(shot.id, shot);
    }

    Shot get(int id) {
        return shots.get(id);
    }

    boolean contains(int id) {
        return shots.containsKey(id);
    }

    int size() {
        return shots.size();
    }

    int allocateId() {
        if (nextId == Integer.MAX_VALUE) nextId = 1_500_000_000;
        while (shots.containsKey(nextId)) nextId++;
        return nextId++;
    }

    List<ActorState> actors() {
        return shots.values().stream().map(Shot::snapshot).toList();
    }

    void removeIfOwner(int owner) {
        shots.values().removeIf(shot -> shot.source.ownerId == owner);
    }

    Set<Integer> hits(Shot shot, FireCatalog.Action action) {
        return shot.hits.computeIfAbsent(action, key -> new HashSet<>());
    }

    boolean destroySource(int id, int stamp) {
        var shot = shots.get(id);
        if (shot == null) return false;
        destroy(shot, stamp, false);
        return true;
    }
    /** Condition flag lookup for the interpreter; sizes stay owned by {@link Shot}. */
    Boolean flag(int id, String name) {
        var shot = shots.get(id);
        if (shot == null) return null;
        return "FIZZLE".equals(name) ? shot.fizzle : "RETURNING".equals(name) ? shot.returning : null;
    }

    /** Advance every live projectile to {@code stamp}, firing due timers and fuses. */
    void advance(int stamp) {
        // Each actor owns its clock: children born during another actor's
        // detonation catch up next tick without losing their first movement step.
        for (var shot : List.copyOf(shots.values())) {
            if (shot.destroyed != Integer.MAX_VALUE) {
                if (stamp - shot.destroyed >= DESTROYED_GRACE_MILLIS) shots.remove(shot.id);
                continue;
            }
            boolean sourceBound = sourceBound(shot.spec);
            if (sourceBound) {
                var parent = currentOwners.get(shot.source.actorId);
                if (parent == null && targets != null) parent = targets.actor(shot.source.actorId);
                if (parent == null || parent.entity() != null && parent.entity().destroyed() != Integer.MAX_VALUE) {
                    destroy(shot, stamp, false);
                    continue;
                }
            }
            int end = sourceBound ? stamp : Math.min(stamp, shot.created + shot.spec.fuse());
            while (shot.destroyed == Integer.MAX_VALUE) {
                int selected = -1, due = Integer.MAX_VALUE;
                for (int i = 0; i < shot.timers.length; i++) {
                    var timer = shot.spec.timers().get(i);
                    int fired = shot.timers[i];
                    if (fired > timer.limit() || fired == timer.limit() && timer.endAction() == null) continue;
                    int deadline = shot.created + timer.delay() + timer.interval() * fired;
                    if (deadline <= end && deadline < due) {
                        selected = i;
                        due = deadline;
                    }
                }
                if (selected < 0) break;
                advanceShot(shot, due, currentOwners.get(shot.source.ownerId));
                if (shot.destroyed != Integer.MAX_VALUE) break;
                var timer = shot.spec.timers().get(selected);
                int fired = shot.timers[selected]++;
                host.execute(fired < timer.limit() ? timer.action() : timer.endAction(), shotPose(shot), due, 0);
            }
            if (shot.destroyed == Integer.MAX_VALUE) advanceShot(shot, end, currentOwners.get(shot.source.ownerId));
            if (!sourceBound && shot.destroyed == Integer.MAX_VALUE && stamp - shot.created >= shot.spec.fuse())
                destroy(
                        shot,
                        shot.created + shot.spec.fuse(),
                        !shot.spec.nativeType().equals("BOMB"));
        }
    }

    /** Inert native helpers have no retained lifetime; bound their ownership to the source. */
    private static boolean sourceBound(FireCatalog.ActorSpec spec) {
        return spec.nativeType().equals("ACTOR")
                && spec.fuse() == 0
                && spec.timers().isEmpty()
                && spec.startup() == null
                && spec.shutdown() == null
                && spec.detonation() == null
                && spec.attachment() == null
                && spec.activation() == null
                && spec.collisionMask() == 0;
    }

    Pose pose(Shot shot) {
        return shotPose(shot);
    }

    private Pose shotPose(Shot shot) {
        Object shape = shot.contactShape != null
                ? shot.contactShape
                : shot.spec.nativeType().equals("LASER")
                        ? new FireCatalog.DataValue(
                                "com.threerings.tudey.shape.config.ShapeConfig$Segment",
                                Map.of("length", shot.spec.speed()))
                        : shot.spec.collisionShape();
        return new Pose(
                shot.x,
                shot.y,
                shot.rotation,
                shot.source.ownerId,
                0,
                shape,
                shot.id,
                shot.source.damage,
                shot.source.sceneSource,
                shot.pierce);
    }

    private void advanceShot(Shot shot, int end, ActorState home) {
        if (end <= shot.advanced) return;
        if (shot.spec.nativeType().equals("ORBITAL")) {
            var followed = shots.get(shot.followId);
            home = followed != null && followed.destroyed == Integer.MAX_VALUE
                    ? followed.snapshot()
                    : currentOwners.get(shot.followId);
            if (home == null && targets != null) home = targets.actor(shot.followId);
            if (home == null) {
                destroy(shot, end, true);
                return;
            }
            var orbit = shot.spec.orbit();
            float angle = orbit.startingRotation() + spin(end - shot.created, orbit.angularSpeed(), false);
            shot.relativeX = orbit.radius() * (float) Math.cos(angle);
            shot.relativeY = orbit.radius() * (float) Math.sin(angle);
            shot.x = home.x() + shot.relativeX;
            shot.y = home.y() + shot.relativeY;
            shot.advanced = end;
            if (shot.spec.collisionMask() != 0
                    && targets != null
                    && targets.contact(
                            shot.x,
                            shot.y,
                            shot.rotation,
                            shot.source.ownerId,
                            shot.spec.collisionShape(),
                            shot.spec.collisionMask())) destroy(shot, end, false);
            return;
        }
        if (shot.spec.nativeType().equals("BULLET") || shot.spec.nativeType().equals("AGENT")) {
            int from = shot.advanced;
            float distance = shot.spec.speed() * (end - from) / 1000f;
            int steps = Math.max(1, (int) Math.ceil(Math.abs(distance) / Math.max(.05f, shot.spec.maxStep())));
            for (int n = 0; n < steps; n++) {
                int time = from + (int) ((long) (end - from) * (n + 1) / steps);
                float x = shot.x + (float) Math.cos(shot.rotation) * distance / steps,
                        y = shot.y + (float) Math.sin(shot.rotation) * distance / steps;
                if (!host.clear(shot.spec, shot.x, shot.y, x, y, shot.rotation)) {
                    destroy(shot, time, false);
                    break;
                }
                if (targets != null
                        && targets.contact(
                                x,
                                y,
                                shot.rotation,
                                shot.source.ownerId,
                                shot.spec.collisionShape(),
                                shot.spec.collisionMask())) {
                    impact(shot, x, y, time);
                    break;
                }
                shot.x = x;
                shot.y = y;
            }
        } else if (shot.spec.orbit() != null) advanceOrbit(shot, end, shot.advanced, home);
        shot.advanced = end;
    }

    void impact(Shot shot, float x, float y, int stamp) {
        int target = targets == null
                ? 0
                : targets.contactActor(
                        x,
                        y,
                        shot.rotation,
                        shot.source.ownerId,
                        shot.spec.collisionShape(),
                        shot.spec.collisionMask());
        // Custom forward-only Diskgun saws: damage each enemy once, keep travelling, and
        // stop after the authored pierce budget. Detonation is applied at the hit pose.
        if (target != 0 && shot.pierce.pierces()) {
            if (!shot.pierced.add(target)) return;
            shot.x = x;
            shot.y = y;
            shot.contactShape = null;
            detonate(shot, stamp);
            if (shot.pierce.extraPierces() == Integer.MAX_VALUE) return;
            if (shot.pierceRemaining == 0) destroy(shot, stamp, false, false);
            else shot.pierceRemaining--;
            return;
        }
        if (target != 0 && targets.attachable(target)) {
            if (shot.spec.attachment() != null && shots.size() < MAX_ACTORS) {
                var victim = targets.actor(target);
                var spec = shot.spec.attachment();
                var attached = new Shot(allocateId(), spec, shot.source, stamp, victim.x(), victim.y(), shot.rotation);
                attached.followId = target;
                shots.put(attached.id, attached);
                if (spec.startup() != null) host.execute(spec.startup(), shotPose(attached), stamp, 0);
            }
            // Capture the impact pose before any explosion can kill/remove the victim.
            // Later tags still detonate at that pose, retaining their original owner.
            var victim = targets.actor(target);
            if (shot.spec.activatesAttachments() && victim != null)
                for (var attached : List.copyOf(shots.values()))
                    if (attached.followId == target
                            && attached.destroyed == Integer.MAX_VALUE
                            && attached.spec.activation() != null) {
                        attached.x = victim.x();
                        attached.y = victim.y();
                        // An orb keeps its original owner's damage and credit even when another knight triggers it.
                        var action = attached.spec.activation();
                        attached.destroyed = stamp;
                        host.execute(action, shotPose(attached), stamp, 0);
                    }
        }
        // Keep the carrier outside its victim so its backward shards can escape.
        // Archive BulletLogic retains the colliding substep's shape for damage;
        // otherwise a tangent impact can miss the victim through rounding.
        if (shot.spec.collisionShape() != null) {
            float dx = x - shot.x,
                    dy = y - shot.y,
                    c = (float) Math.cos(shot.rotation),
                    s = (float) Math.sin(shot.rotation);
            var translation = new FireCatalog.DataValue(
                    org.tortodrone.core.WireTypes.VECTOR2F, Map.of("x", dx * c + dy * s, "y", -dx * s + dy * c));
            var transform = new FireCatalog.DataValue(
                    "com.threerings.math.Transform2D",
                    Map.of("_translation", translation, "_rotation", 0f, "_scale", 1f));
            var end = new FireCatalog.DataValue(
                    "com.threerings.tudey.shape.config.ShapeConfig$TransformedShape",
                    Map.of("shape", shot.spec.collisionShape(), "transform", transform));
            shot.contactShape = new FireCatalog.DataValue(
                    "com.threerings.tudey.shape.config.ShapeConfig$Compound",
                    Map.of(
                            "shapes",
                            new FireCatalog.ListValue(
                                    "com.threerings.tudey.shape.config.ShapeConfig",
                                    List.of(shot.spec.collisionShape(), end))));
        } else {
            shot.x = x;
            shot.y = y;
        }
        destroy(shot, stamp, false);
    }
    // Transcribed from the archive's 2026-compatible OrbitalBulletAdvancer:
    // fly the centre out, home to the owner, and compute the orbit at 1/60 s steps.
    private void advanceOrbit(Shot shot, int end, int from, ActorState home) {
        var orbit = shot.spec.orbit();
        float remaining = Math.max(0, end - from) / 1000f;
        while (remaining > 0 && shot.destroyed == Integer.MAX_VALUE) {
            float rest = Math.max(remaining - ORBIT_STEP_SECONDS, 0), elapsed = remaining - rest;
            int next = end - (int) (rest * 1000);
            remaining = rest;
            float cx, cy;
            if (next - shot.created <= orbit.outDuration()) {
                float distance = orbit.flightSpeed() * (next - shot.created) / 1000f;
                cx = shot.originX + (float) Math.cos(shot.rotation) * distance;
                cy = shot.originY + (float) Math.sin(shot.rotation) * distance;
            } else {
                shot.returning = true;
                cx = shot.x - shot.relativeX;
                cy = shot.y - shot.relativeY;
                if (home != null) {
                    float dx = home.x() - cx,
                            dy = home.y() - cy,
                            distance = (float) Math.hypot(dx, dy),
                            step = orbit.flightSpeed() * elapsed;
                    if (distance <= step) {
                        cx = home.x();
                        cy = home.y();
                    } else if (distance > 0) {
                        cx += dx * step / distance;
                        cy += dy * step / distance;
                    }
                }
            }
            float angle = orbit.startingRotation()
                    + shot.rotation
                    + spin(next - shot.created, orbit.angularSpeed(), orbit.alternateSpin() && (shot.created & 1) == 1);
            float rx = orbit.radius() * (float) Math.cos(angle), ry = orbit.radius() * (float) Math.sin(angle);
            if (orbit.compound()) {
                float second = orbit.secondStartingRotation()
                        + shot.rotation
                        + spin(
                                next - shot.created,
                                orbit.secondSpeed(),
                                orbit.alternateSpin() && (shot.created & 1) == 1);
                rx += orbit.secondOrbitRadius() * (float) Math.cos(second);
                ry += orbit.secondOrbitRadius() * (float) Math.sin(second);
            }
            float x = cx + rx, y = cy + ry;
            if ((shot.spec.flags() & 2) == 0 && !host.clear(shot.spec, shot.x, shot.y, x, y, shot.rotation)) {
                shot.collided = next;
                destroy(shot, next, false);
                break;
            }
            // Preserve the colliding substep for shutdown damage, as for ordinary bullets.
            // Merely publishing the tangent endpoint can miss a destructible's hit shape.
            if (targets != null
                    && targets.contact(
                            x,
                            y,
                            shot.rotation,
                            shot.source.ownerId,
                            shot.spec.collisionShape(),
                            shot.spec.collisionMask())) {
                shot.collided = next;
                impact(shot, x, y, next);
                break;
            }
            shot.relativeX = rx;
            shot.relativeY = ry;
            shot.x = x;
            shot.y = y;
            from = next;
        }
    }

    private static float spin(int elapsed, float speed, boolean reverse) {
        int rate = speed == 0 ? 0 : (int) (1000 / speed);
        return rate == 0 ? 0 : (reverse ? -1 : 1) * (float) (2 * Math.PI) * (elapsed % rate) / rate;
    }

    void destroy(Shot shot, int stamp, boolean fizzle) {
        destroy(shot, stamp, fizzle, true);
    }

    private void destroy(Shot shot, int stamp, boolean fizzle, boolean detonate) {
        if (shot.destroyed != Integer.MAX_VALUE) return;
        shot.destroyed = stamp;
        shot.fizzle = fizzle;
        if (shot.spec.shutdown() != null) host.execute(shot.spec.shutdown(), shotPose(shot), stamp, 0);
        // Piercing saws apply their damage at each hit pose; an expiry/miss does not
        // create an extra ground burst. Non-piercing shots retain the native fuse detonation.
        if (detonate && !shot.pierce.pierces()) detonate(shot, stamp);
    }

    private void detonate(Shot shot, int stamp) {
        var detonation = catalog.detonation(shot.spec);
        // Stock BulletLogic executes the detonation even when its advancer marked
        // fuse expiry as FIZZLE. Tortofist's invisible carrier relies on this to
        // release missiles; the flag only chooses the client's destruction visual.
        if (detonation != null) host.execute(detonation, shotPose(shot), stamp, 0);
    }
}
