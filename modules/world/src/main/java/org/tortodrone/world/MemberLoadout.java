package org.tortodrone.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.WeaponCatalog;

/** A member's owned inventory and equipped publication: carried items, one pending item use,
 *  the combat controller and the sprite/harness/progress a knight publishes. All calls run
 *  under the owning simulation monitor; payment commits and network I/O stay with the session. */
final class MemberLoadout {
    private final SceneMembership member;
    List<org.tortodrone.core.character.WorldProfile.Item> carriedItems = List.of();
    private final ArrayDeque<Long> usedItems = new ArrayDeque<>();
    private long pendingItem;
    private int itemLand;
    private SceneContent.Spec itemSpec;
    KnightCombat combat;
    org.tortodrone.core.character.WorldProfile combatProfile;
    org.tortodrone.core.character.KnightProgress progress = org.tortodrone.core.character.KnightProgress.RECRUIT;
    org.tortodrone.core.town.TownState.Sprite sprite;
    org.tortodrone.core.town.TownState.Harness harness;
    int auraOwner;

    MemberLoadout(SceneMembership member) {
        this.member = member;
    }

    boolean busy() {
        return pendingItem != 0;
    }

    long pendingItem() {
        return pendingItem;
    }

    int itemLand() {
        return itemLand;
    }

    SceneContent.Spec itemSpec() {
        return itemSpec;
    }

    void markUsed(long id) {
        usedItems.add(id);
    }

    void settlePending() {
        pendingItem = 0;
        itemSpec = null;
    }

    void cancelPending() {
        pendingItem = 0;
        itemSpec = null;
    }

    List<Long> takeUsedItems() {
        synchronized (member.scene) {
            member.check();
            var used = List.copyOf(usedItems);
            usedItems.clear();
            return used;
        }
    }

    boolean useItem(long id, SceneContent.Spec spec) {
        synchronized (member.scene) {
            member.check();
            if (!member.entered
                    || !member.portalOpen()
                    || !member.health.alive()
                    || pendingItem != 0
                    || member.lift != 0
                    || usedItems.contains(id)) return false;
            if (spec.text("kind", "").equals("vial")) {
                int stamp = SceneClock.now();
                if (!member.movement.useLift(stamp)) return false;
                member.lift = member.scene.entities.vial(member.actor(), spec, stamp);
                if (member.lift == 0) {
                    member.movement.resetInput(stamp);
                    return false;
                }
                usedItems.add(id);
                member.health.curseItem(id, stamp);
                member.updateLife(stamp);
                return true;
            }
            if (!spec.text("kind", "").equals("capsule")) return false;
            if (spec.integer("heal", 0) <= 0)
                try {
                    if (!SceneWeapons.usable(FireCatalog.actionJson(spec.text("use-action", "")))) return false;
                } catch (java.io.IOException invalid) {
                    return false;
                }
            int stamp = SceneClock.now();
            if (!member.movement.useItem(
                    new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config", ""), Map.of()), stamp))
                return false;
            // The native use activity is accepted even when a Health Capsule has
            // nothing to heal. This attempt has no item action or consumption.
            if (spec.integer("heal", 0) > 0
                    && member.health.state().current() >= member.health.state().maximum()) return true;
            member.health.curseItem(id, stamp);
            if (!member.health.alive()) {
                usedItems.add(id);
                member.updateLife(stamp);
                return true;
            }
            pendingItem = id;
            itemLand = stamp + spec.integer("delay", 0);
            itemSpec = spec;
            return true;
        }
    }
    /** Validate, charge and apply one persistent bag item while the scene lock is held.
     *  Payment is committed only after the effect can be applied, and an item entity is
     *  reclaimed when the transaction is refused. */
    <T> SceneMembership.ItemUse<T> useOwnedItem(
            long id, SceneContent.Spec spec, SceneSimulation.RevivePayment<T> payment) throws java.io.IOException {
        synchronized (member.scene) {
            member.check();
            if (!member.entered
                    || !member.portalOpen()
                    || !member.health.alive()
                    || pendingItem != 0
                    || member.lift != 0
                    || usedItems.contains(id)) throw new java.io.IOException("e.not_use_busy");
            String kind = spec.text("kind", "");
            int stamp = SceneClock.now();
            switch (kind) {
                case "heal" -> {
                    int heal = spec.integer("heal", 0);
                    if (heal <= 0) throw new java.io.IOException("m.internal_error");
                    if (!member.movement.useItem(
                            new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config", ""), Map.of()),
                            stamp)) throw new java.io.IOException("e.not_use_busy");
                    if (member.health.state().current() >= member.health.state().maximum())
                        return new SceneMembership.ItemUse<>(false, null);
                    T result = payment.commit();
                    member.health.heal(heal);
                    return new SceneMembership.ItemUse<>(true, result);
                }
                case "lethal" -> {
                    Object damage;
                    try {
                        damage = FireCatalog.valueJson(spec.text("damage", ""));
                    } catch (java.io.IOException invalid) {
                        throw new java.io.IOException("m.internal_error", invalid);
                    }
                    if (!member.movement.useItem(
                            new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config", ""), Map.of()),
                            stamp)) throw new java.io.IOException("e.not_use_busy");
                    T result = payment.commit();
                    var hit = member.health.hit(damage, stamp);
                    if (hit.amount() > 0)
                        member.scene.feedback(new SceneCombatEvent(
                                0,
                                stamp,
                                member.appearance.id(),
                                hit.feedback(),
                                0,
                                member.movement.x(),
                                member.movement.y(),
                                member.movement.rotation(),
                                "NORMAL"));
                    member.updateLife(stamp);
                    return new SceneMembership.ItemUse<>(true, result);
                }
                case "spawn-actor" -> {
                    if (!member.movement.useItem(
                            new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config", ""), Map.of()),
                            stamp)) throw new java.io.IOException("e.not_use_busy");
                    int actor = member.scene.entities.spawnItemActor(member.actor(), spec.section("actor."), stamp);
                    if (actor == 0) throw new java.io.IOException("e.not_use_busy");
                    T result;
                    try {
                        result = payment.commit();
                    } catch (java.io.IOException | RuntimeException failed) {
                        member.scene.entities.discard(actor);
                        throw failed;
                    }
                    return new SceneMembership.ItemUse<>(true, result);
                }
                case "lift" -> {
                    if (!member.movement.useLift(stamp)) throw new java.io.IOException("e.not_use_busy");
                    int carried = member.scene.entities.liftItem(member.actor(), spec.section("lift."), stamp);
                    if (carried == 0) {
                        member.movement.resetInput(stamp);
                        throw new java.io.IOException("e.not_use_busy");
                    }
                    T result;
                    try {
                        result = payment.commit();
                    } catch (java.io.IOException | RuntimeException failed) {
                        member.scene.entities.discard(carried);
                        member.movement.resetInput(stamp);
                        throw failed;
                    }
                    member.lift = carried;
                    return new SceneMembership.ItemUse<>(true, result);
                }
                default -> throw new java.io.IOException("m.internal_error");
            }
        }
    }
    /** Server-only notification after a committed item/economy operation. */
    void effect(org.tortodrone.core.character.StarterCatalog.Model effect) {
        synchronized (member.scene) {
            member.check();
            if (member.entered && member.portalOpen())
                member.scene.weapons.effect(effect, member.actor(), SceneClock.now());
        }
    }

    void inventory(org.tortodrone.core.character.WorldProfile profile) {
        synchronized (member.scene) {
            member.check();
            carriedItems = profile.items();
            member.health.inventory(profile);
            member.scene.entities.reconsiderPickups(member.appearance.id());
        }
    }
    /**
     * Native transient-pickup capacity: consumables are stackable into a single Item
     * per distinct type (wire captures show repeated EntryUpdated, never a new slot),
     * the HUD belt has exactly 4 quickbar slots (interface_quickbar.dat slot0..3), and
     * every capsule/vial Pickup actor carries failureEffect=Player/Full Inventory.
     * The per-type cap of 3 matches stock-server behavior (wiki-corroborated); it is
     * not present as a client constant. So: at most 3 of a held type, at most 4 types.
     */
    boolean canPickup(SceneContent.Spec spec) {
        if (!spec.flag("pickup-transient", false)) return true;
        String item = spec.text("pickup-item", "");
        if (spec.fields().containsKey("pickup-equip-slot")) {
            int slot = spec.integer("pickup-equip-slot", 0);
            var equipped = carriedItems.stream()
                    .filter(i -> Objects.equals(i.equippedSlot(), slot))
                    .findFirst()
                    .orElse(null);
            var stats = member.scene.instance.map().content().stats();
            return equipped == null
                    || equipped.id() < 0
                            && stats.getOrDefault("gear." + item + ".health-bonus", 0f)
                                    > stats.getOrDefault("gear." + equipped.config() + ".health-bonus", 0f);
        }
        var counts = new HashMap<String, Integer>();
        for (var carried : carriedItems)
            if (carried.id() < 0 && carried.equippedSlot() == null)
                counts.merge(carried.config(), carried.count(), Integer::sum);
        // Claims reserve capacity until the session commits or refuses them.
        for (var reserved : member.scene.entities.pendingPickups(member.appearance.id()))
            if (reserved.temporary() && reserved.equippedSlot() == null) counts.merge(reserved.item(), 1, Integer::sum);
        return counts.getOrDefault(item, 0) < 3 && (counts.containsKey(item) || counts.size() < 4);
    }

    void equipment(org.tortodrone.core.character.WorldProfile profile) {
        synchronized (member.scene) {
            member.check();
            int priorSprite = sprite == null ? 0 : sprite.id();
            combatProfile = profile;
            carriedItems = profile.items();
            progress = profile.progress();
            sprite = profile.town().sprites().stream()
                    .filter(s -> s.id() == profile.town().equippedSprite())
                    .findFirst()
                    .orElse(null);
            if (priorSprite != (sprite == null ? 0 : sprite.id())) {
                member.pet.changeSprite();
                // Preserve the recharge deadlines: swapping sprites cannot bypass cooldowns.
            }
            harness = sprite == null
                    ? null
                    : profile.town().harnesses().stream()
                            .filter(h -> h.id() == sprite.harness())
                            .findFirst()
                            .orElse(new org.tortodrone.core.town.TownState.Harness(0, "Iron Harness"));
            if (combat != null) {
                var before = combat.state().weapon();
                combat.equipment(profile);
                if (!Objects.equals(before, combat.state().weapon()))
                    member.scene.weapons.cancelAttacks(member.appearance.id());
            }
            member.health.equipment(profile, member.scene.town);
        }
    }

    void combat(WeaponCatalog catalog, org.tortodrone.core.character.WorldProfile profile) {
        combat(catalog, EquipmentCatalog.empty(), profile);
    }

    void combat(WeaponCatalog catalog, EquipmentCatalog modifiers, org.tortodrone.core.character.WorldProfile profile) {
        synchronized (member.scene) {
            member.check();
            if (member.scene.instance.map().town()) throw new IllegalStateException("Town Knights are peaceful");
            combatProfile = profile;
            progress = profile.progress();
            combat = new KnightCombat(catalog, modifiers);
            combat.equipment(profile);
            member.health.equipment(profile, member.scene.town);
            combat.attacks(attack -> {
                member.health.breakCloak();
                if (attack.activity() >= KnightCombat.INCOMPLETE
                        && combat.state().weapon() != null)
                    member.health.curseItem(combat.state().weapon().id(), attack.started());
                if (member.health.alive()) member.scene.weapons.attack(member.appearance.id(), attack);
            });
            member.movement.combat(combat);
        }
    }
}
