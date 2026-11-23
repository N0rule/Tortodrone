package org.tortodrone.core.world;

import java.util.*;

/**
 * Immutable behaviour parsed once from a prepared actor spec and shared by every instance.
 *
 * <p>The scene runtime reuses one prepared actor for an identical {@link SceneContent.Spec}.
 * Nothing here is mutable: per-instance state stays in {@code SceneEntities.Entity}, and the
 * weak cache only keeps entries while a scene's prepared specs are reachable.
 */
public record PreparedActor(
        String kind,
        org.tortodrone.core.character.StarterCatalog.Model config,
        SceneMovementScript script,
        SceneScript program,
        SceneTargeting targeting,
        SceneWander wander,
        SceneFollow follow,
        Map<Integer, SceneAttackMotion> attackMotion,
        Map<Integer, Float> meleeRangeCaps,
        ScenePartyPlatform platform,
        SceneElevator elevator,
        SceneActions.Action shutdown,
        Map<String, SceneActions.Action> sceneFireActions) {
    private static final Map<SceneContent.Spec, PreparedActor> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    public PreparedActor {
        attackMotion = Map.copyOf(attackMotion);
        meleeRangeCaps = Map.copyOf(meleeRangeCaps);
        sceneFireActions = Map.copyOf(sceneFireActions);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(config, "config");
    }

    /** Parse every native graph once; repeated spawns reuse the immutable result. */
    public static PreparedActor of(SceneContent.Spec spec) {
        return CACHE.computeIfAbsent(spec, PreparedActor::parse);
    }

    private static PreparedActor parse(SceneContent.Spec spec) {
        SceneMovementScript script = SceneMovementScript.read(spec);
        SceneScript program = SceneScript.read(spec);
        SceneTargeting targeting = SceneTargeting.read(spec);
        SceneWander wander = SceneWander.read(spec);
        SceneFollow follow = SceneFollow.read(spec);
        var attackMotion = SceneAttackMotion.readAll(spec);
        var meleeRangeCaps = meleeRangeCaps(spec);
        var platform = ScenePartyPlatform.read(spec);
        var elevator = SceneElevator.read(spec);
        SceneActions.Action shutdown;
        var sceneFireActions = new HashMap<String, SceneActions.Action>();
        try {
            String graph = spec.text("scene-shutdown-action", "");
            shutdown = graph.isEmpty() ? null : SceneActions.parse(graph);
            for (var field : spec.fields().entrySet())
                if (field.getKey().startsWith("attack.") && field.getKey().endsWith(".scene-action"))
                    sceneFireActions.put(field.getKey(), SceneActions.parse(field.getValue()));
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Invalid native actor scene action", invalid);
        }
        var config = spec.config();
        String nativeType = spec.text("native-type", "");
        String kind = nativeType.substring(nativeType.lastIndexOf('.') + 1);
        return new PreparedActor(
                kind,
                config,
                script,
                program,
                targeting,
                wander,
                follow,
                attackMotion,
                meleeRangeCaps,
                platform,
                elevator,
                shutdown,
                sceneFireActions);
    }

    /** Damage-circle caps for native melee slots, read from prepared attack metadata. */
    private static Map<Integer, Float> meleeRangeCaps(SceneContent.Spec spec) {
        var caps = new HashMap<Integer, Float>();
        for (String key : spec.fields().keySet()) {
            if (!key.startsWith("attack.") || !key.endsWith(".fire.0.action")) continue;
            String slot = key.substring("attack.".length(), key.length() - ".fire.0.action".length());
            if (slot.isEmpty() || slot.length() > 3 || !slot.chars().allMatch(Character::isDigit)) continue;
            int index = Integer.parseInt(slot);
            float radius = meleeDamageRadius(spec, index);
            if (radius != Float.MAX_VALUE) caps.put(index, radius);
        }
        return Map.copyOf(caps);
    }

    private static float meleeDamageRadius(SceneContent.Spec spec, int slot) {
        try {
            String json = spec.text("attack." + slot + ".fire.0.action", "");
            if (json.isEmpty()) return Float.MAX_VALUE;
            var action = FireCatalog.actionJson(json);
            if (action.kind() != FireCatalog.Kind.DAMAGE_REGION) return Float.MAX_VALUE;
            Object shape = action.config().arguments().get("shape");
            if (shape instanceof FireCatalog.DataValue dv && dv.type().endsWith("$TransformedShape")) {
                Object inner = SceneDamage.field(shape, "shape");
                if (inner instanceof FireCatalog.DataValue idv && idv.type().endsWith("$Circle")) {
                    float radius = SceneDamage.number(inner, "radius", 0);
                    if (radius == 1f
                            && ROUNDHOUSE_MEDIUM_CONFIG.equals(spec.config().name())) radius = ROUNDHOUSE_MEDIUM_RADIUS;
                    return radius;
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return Float.MAX_VALUE;
    }

    private static final String ROUNDHOUSE_MEDIUM_CONFIG = "Character/NPC/Monster/Slime/Jelly Cube (Intro)";
    private static final float ROUNDHOUSE_MEDIUM_RADIUS = 1.5f;
}
