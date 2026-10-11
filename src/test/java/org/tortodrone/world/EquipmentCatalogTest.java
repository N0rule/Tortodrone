package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.WorldProfile;
import static org.tortodrone.support.TestWire.check;

public final class EquipmentCatalogTest {
    private static EquipmentCatalog.Entry entry(String config, WorldProfile.Slot slot, float value) {
        var modifiers = new EnumMap<EquipmentCatalog.WeaponClass, EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
        for (var type : EquipmentCatalog.WeaponClass.values())
            modifiers.put(type, new EquipmentCatalog.Modifiers(value, value * 2, value * 3, value * 4));
        return new EquipmentCatalog.Entry(config, slot, modifiers);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-equipment-catalog-");
        try {
            var entries = List.of(entry("Gear/Helm", WorldProfile.Slot.HELM, .1f),
                entry("Gear/Shield", WorldProfile.Slot.SHIELD, .2f), entry("Weapon/Sword", WorldProfile.Slot.SWORD, .3f));
            Path file = root.resolve("equipment.tsv"); EquipmentCatalog.write(file, entries);
            EquipmentCatalog loaded = EquipmentCatalog.load(file);
            check(loaded.entries().equals(entries), "four numeric modifier fields and per-class contexts round trip");
            check(loaded.find("gear/helm").forClass(EquipmentCatalog.WeaponClass.BOMB)
                .equals(entries.getFirst().forClass(EquipmentCatalog.WeaponClass.BOMB)), "case-insensitive item lookup");

            var items = List.of(
                new WorldProfile.Item(1, WorldProfile.Slot.HELM, "Gear/Helm", null, 0, 0, true),
                new WorldProfile.Item(2, WorldProfile.Slot.SHIELD, "Gear/Shield", null, 0, 2, true),
                new WorldProfile.Item(3, WorldProfile.Slot.HELM, "Gear/Helm", null, 0, 5, true),
                new WorldProfile.Item(4, WorldProfile.Slot.HELM, "Gear/Helm", null, 0, 12, true),
                new WorldProfile.Item(5, WorldProfile.Slot.SWORD, "Weapon/Sword", null, 0, 6, true));
            var profile = new WorldProfile(1, 1, 1, 0, 0, 0, items);
            var gear = loaded.equipment(profile, EquipmentCatalog.WeaponClass.SWORD);
            check(Math.abs(gear.speed() - 1f) < .00001f, "equipment aggregation uses stock gear slots 0, 2, 5 and 12");
            check(loaded.weapon(items.getLast()).equals(entries.getLast().forClass(EquipmentCatalog.WeaponClass.SWORD)),
                "selected weapon modifier is separate from GEAR_SLOTS");
            check(loaded.forKnight(profile, items.getLast()).equipment().equals(gear), "combined lookup selects weapon-class gear modifiers");
            check(Math.abs(EquipmentCatalog.speedMultiplier(gear, loaded.weapon(items.getLast())) - 1.24f) < .00001f,
                "stock speed modifier clamps at six SPEED steps of 0.04");
            check(EquipmentCatalog.chargeTimeMillis(1000,
                new EquipmentCatalog.Modifiers(.6f, 0, 0, 0), EquipmentCatalog.Modifiers.ZERO) == 520,
                "stock charge modifier clamps at six CHARGE steps of 0.08 and truncates milliseconds");
            check(EquipmentCatalog.defenseSpeedMultiplier(new EquipmentCatalog.Modifiers(0, 0, 0, -.5f)) == .5f,
                "shield defending speed is a direct multiplier");

            Path malformed = root.resolve("bad.tsv");
            Files.writeString(malformed, "TORTODRONE-EQUIPMENT\t1\nE\tGear/X\tHELM\tNaN\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\n");
            try { EquipmentCatalog.load(malformed); throw new AssertionError("Non-finite modifier accepted"); }
            catch (IOException expected) { }
            try { loaded.require("Gear/Missing"); throw new AssertionError("Unknown item accepted"); }
            catch (IOException expected) { }
            System.out.println("PASS: bounded equipment modifiers, class-aware aggregation and stock caps");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
