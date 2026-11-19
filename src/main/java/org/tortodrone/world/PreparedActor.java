package org.tortodrone.world;

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
        org.tortodrone.character.StarterCatalog.Model config,
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
    private static final Map<SceneContent.Spec, PreparedActor> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

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
        var meleeRangeCaps = SceneEntities.meleeRangeCaps(spec);
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
        return new PreparedActor(kind, config, script, program, targeting, wander, follow,
            attackMotion, meleeRangeCaps, platform, elevator, shutdown, sceneFireActions);
    }
}
