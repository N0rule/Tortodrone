package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.world.SceneEntityState;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneStatus;

/** Native scene-handler execution: switches, cycles, conversations, variables, signals,
 *  tagged selection and spawned actors. Identity allocation and entity insertion stay in
 *  the entity model; this system decides what a native action does. */
final class SceneLogic {
    private final SceneEntities scene;
    private final Map<String, List<SceneContent.Entry>> anchors = new LinkedHashMap<>();
    private final Map<String, ActorState> effectSources = new LinkedHashMap<>();
    private final Map<String, Map<String, Boolean>> logicVariables = new LinkedHashMap<>();
    private final Set<String> anchorKeys = new HashSet<>();
    private int variableCount;
    private long nextEffectSource;

    SceneLogic(SceneEntities scene) {
        this.scene = scene;
    }

    void cycle(SceneEntity e, int stamp) {
        var cycle = scene.content().cycles().getOrDefault(e.cycle, e.spec);
        float down = cycle.number("down", e.spec.number("down-time", 0)),
                warn = cycle.number("warn", e.spec.number("warn-time", 0)),
                up = cycle.number("up", e.spec.number("up-time", 0));
        int period = Math.round((down + warn + up) * 1000);
        int offset = period <= 0 ? 0 : Math.floorMod(stamp - Math.round(cycle.number("offset", 0) * 1000), period);
        int state = e.cycle.equals("UP")
                ? 2
                : e.cycle.equals("DOWN") ? 0 : offset < down * 1000 ? 0 : offset < (down + warn) * 1000 ? 1 : 2;
        if (e.state != state) {
            e.state = state;
            e.stateEntered = stamp;
        }
        e.flags = state == 2 ? e.flags | 2 : e.flags & ~2;
    }

    void switches(SceneEntity e, int stamp, List<ActorState> players) {
        String mode = e.spec.text("switch-mode", "");
        if (e.platform != null) {
            var living = scene.occupants().stream()
                    .filter(p -> scene.activePlayer(p.id()))
                    .toList();
            int count = (int) living.stream()
                    .filter(p -> e.spec.contains(e.x, e.y, e.rotation, p.x(), p.y(), ScenePortals.PAWN_RADIUS))
                    .count();
            if (count != e.count) {
                e.count = count;
                if (count > 0 && count == living.size()) {
                    if (e.partyIntersection == null)
                        e.partyIntersection = scene.actions().prepare(e.platform.intersection());
                    scene.actions().perform(e.partyIntersection, stamp, "actor:" + e.id, "actor:" + e.id);
                }
            }
        } else if (mode.equals("pressure")) {
            boolean pressed = scene.occupants().stream()
                            .anyMatch(
                                    p -> e.spec.contains(e.x, e.y, e.rotation, p.x(), p.y(), ScenePortals.PAWN_RADIUS))
                    || scene.entityValues().stream()
                            .anyMatch(other -> SceneEntities.liftKind(other.kind)
                                    && Arrays.asList(other.spec.text("tags", "").split(","))
                                            .contains(e.spec.text("pressure-tag", ""))
                                    && other.carrier == 0
                                    && other.destroyed == Integer.MAX_VALUE
                                    && e.spec.contains(
                                            e.x, e.y, e.rotation, other.x, other.y, other.spec.number("radius", 0)));
            int state = pressed ? 2 : 0;
            if (state != e.state) setSwitch(e, state, stamp);
        } else if (mode.equals("button-once")) {
            if (e.state == 0
                    && scene.occupants().stream()
                            .anyMatch(
                                    p -> e.spec.contains(e.x, e.y, e.rotation, p.x(), p.y(), ScenePortals.PAWN_RADIUS)))
                setSwitch(e, 1, stamp);
        } else if (mode.equals("button") || mode.equals("damage-timed")) {
            if (e.state != 0 && SceneClock.reached(stamp, e.nextSwitch)) setSwitch(e, 0, stamp);
            if (mode.equals("button")
                    && e.state == 0
                    && SceneClock.reached(stamp, e.nextSwitch)
                    && scene.occupants().stream()
                            .anyMatch(p ->
                                    e.spec.contains(e.x, e.y, e.rotation, p.x(), p.y(), ScenePortals.PAWN_RADIUS))) {
                setSwitch(e, 1, stamp);
                e.nextSwitch = stamp + e.spec.integer("switch-delay", 0);
            }
        }
    }

    void setSwitch(SceneEntity e, int state, int stamp) {
        e.state = state;
        e.stateEntered = stamp;
        e.flags = state == 0 ? e.flags | 2 : e.flags & ~2; // Native Switch.setState: START set exactly in state 0.
        if (e.platform != null && state == 1)
            scene.actions().perform(e.platform.pressed(), stamp, "actor:" + e.id, "actor:" + e.id);
        String effect = e.spec.text("state." + state + ".effect", "");
        if (!effect.isBlank()) fireEffect(e, new StarterCatalog.Model(effect, Map.of()), stamp);
        var condition = e.spec.model("state." + state + ".status");
        if (condition != null) {
            String tag = e.spec.text("state." + state + ".status-tag", "");
            float radius = e.spec.number("state." + state + ".status-radius", 0);
            var damage = new FireCatalog.DataValue(
                    org.tortodrone.core.WireTypes.DAMAGE_STATUS, Map.of("statusCondition", condition));
            for (var target : scene.entityValues())
                if (target.kind.equals("Monster")
                        && target.destroyed == Integer.MAX_VALUE
                        && (tag.isBlank()
                                || Arrays.asList(target.spec.text("tags", "").split(","))
                                        .contains(tag))
                        && Math.hypot(target.x - e.x, target.y - e.y) <= radius + target.spec.number("radius", 0))
                    target.status.apply(damage, stamp);
        }
        signal(
                e.spec.text("state." + state + ".tag", ""),
                e.spec.text("state." + state + ".signal", ""),
                stamp,
                "actor:" + e.id);
    }

    void fireEffect(SceneEntity e, StarterCatalog.Model effect, int stamp) {
        scene.fireAttack(
                e.snapshot(),
                new FireCatalog.Action(
                        FireCatalog.Kind.FIRE_EFFECT,
                        effect,
                        null,
                        "Source",
                        "",
                        "",
                        0,
                        0,
                        0,
                        0,
                        false,
                        0,
                        0,
                        0,
                        0,
                        false,
                        List.of(),
                        ""));
    }

    ActorState scriptSource(String source, int stamp) {
        if (source.startsWith("effect:")) return effectSources.get(source);
        if (source.startsWith("actor:"))
            try {
                return scene.actor(Integer.parseInt(source.substring(6)));
            } catch (NumberFormatException invalid) {
                return null;
            }
        if (!source.startsWith("entry:")) return null;
        String key = source.substring(6);
        var binding = scene.content().entries().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .orElse(null);
        if (binding == null || !key.matches("[0-9]+")) return null;
        var state = new SceneEntityState(
                "Actor", Integer.MAX_VALUE, 0, 0, -1, 0, stamp, 0, 0, key, null, SceneStatus.State.NORMAL, 0, 0, true);
        // A native entry identity for presentation only; never inserted as an actor or occupant.
        return new ActorState(
                0,
                0,
                "",
                0,
                stamp,
                binding.x(),
                binding.y(),
                binding.rotation(),
                0,
                0,
                0,
                stamp,
                binding.spec().config(),
                false,
                null,
                null,
                state);
    }

    boolean startConversation(SceneActions.Conversation operation, int stamp, String source, String activator) {
        var definition = scene.content().conversations().get(operation.id());
        var origin = scriptSource(source, stamp);
        if (definition == null || origin == null) return false;
        int target = 0;
        if (operation.activatorOnly()) {
            var player = scene.occupants().stream()
                    .filter(p -> ("actor:" + p.id()).equals(activator))
                    .findFirst()
                    .orElse(null);
            if (player == null) return true;
            target = player.id();
        }
        return scene.startConversationSink(definition, origin, stamp, target);
    }

    boolean nextFloor(int stamp, String source, String activator) {
        var player = scene.occupants().stream()
                .filter(p -> activator.equals("actor:" + p.id()))
                .findFirst()
                .orElse(null);
        if (player == null) return true; // Native PartyBase succeeds with no resolved knight/party.
        var origin = scriptSource(source, stamp);
        if (origin == null) return false;
        return scene.requestFloorAdvance(player, origin, stamp);
    }

    boolean setMemory(
            org.tortodrone.core.character.KnightMemory operation, int stamp, String source, String activator) {
        var player = scene.occupants().stream()
                .filter(p -> activator.equals("actor:" + p.id()) && scene.activePlayer(p.id()))
                .findFirst()
                .orElse(null);
        var origin = scriptSource(source, stamp);
        return player != null && origin != null && scene.applyMemory(player, operation, origin, stamp);
    }

    boolean sourceEffect(SceneActions.SourceEffect operation, int stamp, String source) {
        if (operation.locationTag().isEmpty()) {
            var origin = scriptSource(source, stamp);
            if (origin == null) return false;
            scene.fireEffectSink(operation.config(), origin, stamp);
        } else {
            var origins = taggedSources(operation.locationTag(), stamp);
            if (origins.size() > 256) return false;
            for (var origin : origins) scene.fireEffectSink(operation.config(), origin, stamp);
        }
        return true; // Native FireEffect succeeds even when its Tagged selection is empty.
    }

    boolean gameplayEffect(SceneActions.GameplayEffect operation, int stamp, String source) {
        List<ActorState> origins;
        if (operation.locationTag().isEmpty()) {
            var origin = scriptSource(source, stamp);
            if (origin == null) return false;
            origins = List.of(origin);
        } else origins = taggedSources(operation.locationTag(), stamp);
        if (origins.size() > 256
                || effectSources.size() + origins.size() > 4096
                || nextEffectSource > Long.MAX_VALUE - origins.size()) return false;
        for (var origin : origins) {
            String identity = "effect:" + (++nextEffectSource);
            effectSources.put(identity, origin);
            if (!scene.actions().perform(operation.action(), stamp, identity, logicIdentity(origin))) {
                effectSources.remove(identity);
                return false;
            }
            scene.fireEffectSink(operation.config(), origin, stamp);
        }
        return true;
    }

    List<ActorState> taggedSources(String tag, int stamp) {
        var result = new ArrayList<ActorState>();
        for (var binding : anchors.getOrDefault(tag, List.of())) result.add(entrySource(binding, stamp));
        // Native Tagged maps retain a destroyed actor until its actual removal.
        for (var entity : scene.entityValues())
            if (Arrays.asList(entity.spec.text("tags", "").split(",")).contains(tag)) result.add(entity.snapshot());
        return result;
    }

    List<String> variableTargets(SceneActions.LogicTarget target, int stamp, String source, String activator) {
        return switch (target.mode()) {
            case "source" -> scriptSource(source, stamp) == null ? List.of() : List.of(source);
            case "activator" ->
                scriptSource(activator, stamp) != null
                                || scene.occupants().stream().anyMatch(p -> activator.equals("actor:" + p.id()))
                        ? List.of(activator)
                        : List.of();
            case "tagged" ->
                taggedSources(target.tag(), stamp).stream()
                        .map(SceneLogic::logicIdentity)
                        .distinct()
                        .toList();
            default -> throw new IllegalArgumentException("Invalid variable target");
        };
    }

    boolean setVariable(SceneActions.SetVariable operation, int stamp, String source, String activator) {
        if (scriptSource(source, stamp) == null) return false;
        var targets = variableTargets(operation.target(), stamp, source, activator);
        int additions = 0;
        for (String target : targets) {
            var values = logicVariables.get(target);
            if (values == null || !values.containsKey(operation.name())) {
                if (values != null && values.size() >= 256) return false;
                additions++;
            }
        }
        if (variableCount + additions > 4096) return false;
        for (String target : targets)
            logicVariables
                    .computeIfAbsent(target, key -> new LinkedHashMap<>())
                    .put(operation.name(), operation.value());
        variableCount += additions;
        return true;
    }

    boolean variable(String name, SceneActions.LogicTarget target, int stamp, String source, String activator) {
        for (String identity : variableTargets(target, stamp, source, activator)) {
            var values = logicVariables.get(identity);
            if (values != null && values.containsKey(name)) return values.get(name);
        }
        return false;
    }

    ActorState entrySource(SceneContent.Entry binding, int stamp) {
        var state = new SceneEntityState(
                "Actor",
                Integer.MAX_VALUE,
                0,
                0,
                -1,
                0,
                stamp,
                0,
                0,
                binding.key(),
                null,
                SceneStatus.State.NORMAL,
                0,
                0,
                true);
        return new ActorState(
                0,
                0,
                "",
                0,
                stamp,
                binding.x(),
                binding.y(),
                binding.rotation(),
                0,
                0,
                0,
                stamp,
                binding.spec().config(),
                false,
                null,
                null,
                state);
    }

    String conversationSource(SceneConversation definition, ActorState source, int responder, int stamp) {
        if (scene.content().conversations().get(definition.actor()) != definition || source == null) return null;
        if (scene.occupants().stream().noneMatch(p -> p.id() == responder) || !scene.activePlayer(responder))
            return null;
        String identity;
        if (source.entity() != null && source.entity().entrySource())
            identity = "entry:" + source.entity().key();
        else identity = "actor:" + source.id();
        return scriptSource(identity, stamp) == null ? null : identity;
    }

    boolean conversationValid(
            SceneConversation definition, String node, int choice, ActorState source, int responder, int stamp) {
        var current = definition.nodes().get(node);
        return current != null
                && choice >= 0
                && choice < current.responses().size()
                && conversationSource(definition, source, responder, stamp) != null;
    }

    boolean conversationStart(SceneConversation definition, ActorState source, int responder, int stamp) {
        String identity = conversationSource(definition, source, responder, stamp);
        if (identity == null) return false;
        if (definition.initialAction() == null) return true;
        return scene.actions().perform(definition.initialAction(), stamp, identity, "actor:" + responder);
    }

    boolean conversationResponse(
            SceneConversation definition, String node, int choice, ActorState source, int responder, int stamp) {
        if (!conversationValid(definition, node, choice, source, responder, stamp)) return false;
        var current = definition.nodes().get(node);
        String identity = source.entity() != null && source.entity().entrySource()
                ? "entry:" + source.entity().key()
                : "actor:" + source.id();
        var action = current.responses().get(choice).action();
        return action == null || scene.actions().perform(action, stamp, identity, "actor:" + responder);
    }

    boolean applyPawnStatuses(SceneActions.PawnStatus operation, int stamp, String source) {
        boolean applied = false;
        for (var player : scene.occupants())
            applied = scene.applyPawnStatusSink(player.id(), operation, stamp) || applied;
        return operation.clear() || applied; // Native ClearStatus succeeds even with no targets.
    }

    boolean destroyTagged(String tag, int stamp, String source) {
        boolean found = false;
        for (var e : scene.entityValues())
            if (Arrays.asList(e.spec.text("tags", "").split(",")).contains(tag)) {
                found = true;
                if (e.destroyed != Integer.MAX_VALUE) continue;
                // Destruction is not damage: no hit, death animation, loot or combat reward.
                e.destroyed = stamp;
                e.scriptDestroyed = true;
                e.status.clear();
                e.path = List.of();
                e.impulse = null;
            }
        return found;
    }
    /** A signal addressed directly to one source identity. */
    boolean sourceSignal(String name, int stamp, String source) {
        var receiver = scriptSource(source, stamp);
        if (receiver == null) return false;
        scene.actions().signalSource(source, name, stamp, source);
        if (source.startsWith("actor:")) {
            var target = scene.entity(receiver.id());
            if (target != null) entitySignal(target, name, stamp);
        }
        return true;
    }
    /** Bounded SceneActions signal entry; the sink always accepts after dispatch. */
    boolean signalSink(String tag, String name, int stamp, String source) {
        signal(tag, name, stamp, source);
        return true;
    }

    void signal(String tag, String name, int stamp, String source) {
        if (tag.isEmpty() || name.isEmpty()) return;
        scene.actions().signal(tag, name, stamp, source);
        for (var target : scene.entityValues())
            if (Arrays.asList(target.spec.text("tags", "").split(",")).contains(tag)) entitySignal(target, name, stamp);
    }

    static String logicIdentity(ActorState actor) {
        return actor.entity() != null && actor.entity().entrySource()
                ? "entry:" + actor.entity().key()
                : "actor:" + actor.id();
    }

    boolean nearestSignal(SceneActions.NearestSignal operation, int stamp, String source) {
        var origin = scriptSource(source, stamp);
        if (origin == null) return false;
        var targets = new LinkedHashMap<String, ActorState>();
        for (var target : taggedSources(operation.tag(), stamp)) {
            String identity = logicIdentity(target);
            if (!operation.excludeSource() || !identity.equals(source)) targets.putIfAbsent(identity, target);
        }
        if (targets.size() > 2048) return false;
        // Clyde DistanceSubset sorts float distances from Source. Excluding removes
        // source identity, not its position. Equal distances retain server content
        // order; original tie ordering has no capture witness.
        var selected = targets.values().stream()
                .sorted(Comparator.comparingDouble(a -> {
                    float dx = a.x() - origin.x(), dy = a.y() - origin.y();
                    return (float) Math.sqrt(dx * dx + dy * dy);
                }))
                .limit(operation.count())
                .toList();
        for (var target : selected) {
            scene.actions().signalTo(operation.tag(), operation.name(), stamp, source, logicIdentity(target));
            if (!target.entity().entrySource()) {
                var entity = scene.entity(target.id());
                if (entity != null) entitySignal(entity, operation.name(), stamp);
            }
        }
        return true; // Native Signal succeeds for an empty resolved target set.
    }

    void entitySignal(SceneEntity target, String name, int stamp) {
        if (target.kind.equals("Door") && name.equals("increment")) {
            if (target.doorIncrements == 0 || --target.doorIncrements != 0) return;
            name = "open";
        }
        if (target.script != null && name.equals(target.spec.text("script.activate-signal", "")))
            target.scriptActivated = true;
        if (target.program != null && name.equals(target.spec.text("script.activate-signal", "")))
            setVariable(
                    new SceneActions.SetVariable("activate", true, new SceneActions.LogicTarget("source", "")),
                    stamp,
                    "actor:" + target.id,
                    "actor:" + target.id);
        if (target.platform != null && name.equals("next") && target.state == 0) setSwitch(target, 1, stamp);
        if (target.kind.equals("Switch") && target.platform == null && target.spec.integer("switch-states", 0) > 0) {
            // Native DumbSwitchLogic.signal: "next"/"prev" step with wrap, numeric names
            // target a state, other names are swallowed; only prepared dumb switches carry
            // their native state count, so authored smart-switch modes stay untouched.
            Integer next = null;
            if (name.equals("next")) next = target.state + 1;
            else if (name.equals("prev")) next = target.state - 1;
            else
                try {
                    next = Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                }
            if (next != null) {
                int states = target.spec.integer("switch-states", 0);
                if (next < 0) next = states - 1;
                else if (next >= states) next = 0;
                if (next != target.state) setSwitch(target, next, stamp);
            }
        }
        if (target.kind.equals("Door") && Set.of("open", "close", "toggle").contains(name)) {
            boolean open = name.equals("open") || name.equals("toggle") && (target.flags & 2) == 0;
            target.flags = (open ? 2 : 0) | 4;
            target.stateEntered = stamp;
            for (var mover : scene.entityValues())
                if (mover.kind.equals("Monster")) {
                    mover.path = List.of();
                    mover.nextPath = 0;
                }
        } else if (target.kind.equals("Trap")) {
            if (name.equals("RESET")) {
                target.cycle = target.spec.text("cycle", "");
                int split = target.cycle.lastIndexOf('|');
                if (split >= 0) target.cycle = target.cycle.substring(split + 1);
            } else if (name.equals("TOGGLE")) {
                var cycle = scene.content().cycles().get(target.cycle);
                if (cycle != null) target.cycle = cycle.text("inverse", target.cycle);
            } else if (scene.content().cycles().containsKey(name)) target.cycle = name;
            cycle(target, stamp);
        }
    }
    /** Run one destroyed actor's shutdown handler against a bounded source identity. */
    void shutdown(SceneEntity entity, int stamp) {
        if (entity.shutdown == null) return;
        if (effectSources.size() >= 4096 || nextEffectSource == Long.MAX_VALUE)
            throw new IllegalStateException("Too many scene shutdown contexts");
        String identity = "effect:" + (++nextEffectSource);
        effectSources.put(identity, entity.snapshot());
        if (!scene.actions().perform(entity.shutdown, stamp, identity, "actor:" + entity.id))
            effectSources.remove(identity);
    }
    /** Drop an actor's logic variables when it leaves the scene. */
    void deactivate(SceneEntity entity) {
        var removed = logicVariables.remove("actor:" + entity.id);
        if (removed != null) variableCount -= removed.size();
    }
    /** Live frozen effect contexts; bounded by SceneActions' own source accounting. */
    int effectSourceCount() {
        return effectSources.size();
    }
    /** Source identities only survive while SceneActions still references them. */
    void retainSources(SceneActions actions) {
        if (!effectSources.isEmpty()) effectSources.keySet().retainAll(actions.workSources());
    }
    /** Validate and group one native scene anchor by source tag. */
    void anchor(SceneContent.Entry binding) {
        if (!binding.key().matches("[0-9]+")
                || Integer.parseInt(binding.key()) < 1
                || !anchorKeys.add(binding.key())
                || !Float.isFinite(binding.x())
                || !Float.isFinite(binding.y())
                || !Float.isFinite(binding.rotation()))
            throw new IllegalArgumentException("Invalid scene anchor identity/transform");
        var tags = binding.spec().text("source-tags", "").split(",");
        if (tags.length > 256) throw new IllegalArgumentException("Too many scene anchor tags");
        for (String tag : tags)
            if (!tag.isBlank()) {
                if (tag.length() > 256 || tag.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid scene anchor tag");
                anchors.computeIfAbsent(tag, key -> new ArrayList<>()).add(binding);
            }
    }
}
