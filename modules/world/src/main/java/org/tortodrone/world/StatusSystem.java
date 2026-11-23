package org.tortodrone.world;

import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneStatus;

/** Status ticking and secondary pulse propagation for one scene instance. Direct damage
 *  and healing stay on the scene's own callbacks; this system only turns timer/status
 *  outcomes into those calls under the scene monitor. */
final class StatusSystem {
    private final SceneEntities scene;

    StatusSystem(SceneEntities scene) {
        this.scene = scene;
    }

    /** Advance one monster's status timers, routing damage/heal through the scene. */
    void tick(SceneEntity entity, int stamp, float elapsed) {
        entity.status.advance(
                stamp,
                (int) (elapsed * 1000),
                (entity.flags & 2) != 0,
                amount -> {
                    if (entity.health > 0) scene.hurtForCombat(entity, (float) amount, stamp, false, false);
                },
                amount -> {
                    if (entity.health > 0)
                        entity.health = Math.min(
                                entity.maximumHealth,
                                entity.health
                                        + (float) amount
                                                * entity.status
                                                        .state()
                                                        .modifiers()
                                                        .heal());
                });
    }

    /** Secondary burn/spasm damage retains its collision mask and excludes the affected source. */
    void pulse(int source, SceneStatus.Pulse pulse, float x, float y, int stamp) {
        if ((pulse.mask() & 1) != 0)
            for (var player : scene.occupants())
                if (player.id() != source
                        && scene.activePlayer(player.id())
                        && Math.hypot(player.x() - x, player.y() - y) <= pulse.radius() + 2 * ScenePortals.PAWN_RADIUS)
                    scene.damagePlayer(new SceneEntities.PlayerHit(player.id(), pulse.damage(), stamp, source, 1));
        if ((pulse.mask() & 128) != 0) {
            float pad = pulse.radius() + ScenePortals.PAWN_RADIUS + scene.spatialReach();
            for (var target : scene.candidatesNear(x - pad, y - pad, x + pad, y + pad))
                if (target.id != source
                        && target.kind.equals("Monster")
                        && target.health > 0
                        && Math.hypot(target.x - x, target.y - y)
                                <= pulse.radius() + target.spec.number("radius", 0) + ScenePortals.PAWN_RADIUS) {
                    Object accepted = SceneDamage.prune(pulse.damage(), target.spec.integer("damage-mask", 0));
                    float amount = SceneDamage.amount(accepted, scene.content(), target.spec);
                    amount += target.status.damaged(amount, stamp);
                    target.status.apply(accepted, stamp);
                    if (amount > 0)
                        scene.hurtForCombat(
                                target, amount, stamp, SceneDamage.reacts(accepted), SceneDamage.major(accepted));
                }
        }
    }
}
