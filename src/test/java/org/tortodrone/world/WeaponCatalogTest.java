package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

public final class WeaponCatalogTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-weapon-catalog-");
        try {
            var normal = new WeaponCatalog.Attack(350, 83, 833, false, 20, 300, 1.5f, -0.5f);
            var charged = new WeaponCatalog.Attack(900, 500, 1400, false, -1, -1, 0, 0);
            var bash = new WeaponCatalog.Attack(400, 250, 800, false, 0, 625, 5, 0);
            var weapon = new WeaponCatalog.Weapon("Weapon/Sword/Proto Sword", 3000, 3, 500, true, false,
                1f, 1.25f, List.of(normal, normal, normal), null, charged, null);
            var shield = new WeaponCatalog.Shield("Shield/Proto", 100, 8000, 3000, -0.5f, 0.5f, bash,9000);
            Path file = root.resolve("weapons.tsv");
            WeaponCatalog.write(file, List.of(weapon), List.of(shield), 2500);
            var loaded = WeaponCatalog.load(file);
            check(loaded.weapons().equals(List.of(weapon)), "weapon and ordered combo timings round trip");
            check(loaded.find("weapon/sword/proto sword").equals(weapon), "weapon lookup is case insensitive");
            check(loaded.findShield("shield/proto").equals(shield), "shield depth-zero health, hit delay, defending speed, bash cost and timing round trip");
            check(loaded.dodgeCooldown() == 2500, "stock knight dodge cooldown round trips");
            check(loaded.findShield("Shield/Proto").breakDelay()==9000,"shield break delay is distinct from its hit delay");
            Path legacy=root.resolve("legacy.tsv");Files.writeString(legacy,"TORTODRONE-COMBAT\t1\nC\t0\nS\tShield/Old\t100\t5000\t2000\t0\t0\n");
            check(WeaponCatalog.load(legacy).findShield("Shield/Old").breakDelay()==7000,"legacy shield rows retain the native default break delay");
            try { loaded.require("missing"); throw new AssertionError("Unknown weapon accepted"); }
            catch (IOException expected) { }

            Path malformed = root.resolve("bad.tsv");
            Files.writeString(malformed, "TORTODRONE-COMBAT\t1\nC\t0\nW\tWeapon/X\t0\t0\t0\ttrue\tfalse\tNaN\t1\n");
            try { WeaponCatalog.load(malformed); throw new AssertionError("Non-finite weapon speed accepted"); }
            catch (IOException expected) { }
            Path missingCooldown = root.resolve("missing-cooldown.tsv");
            Files.writeString(missingCooldown, "TORTODRONE-COMBAT\t1\n");
            try { WeaponCatalog.load(missingCooldown); throw new AssertionError("Missing cooldown accepted"); }
            catch (IOException expected) { }
            System.out.println("PASS: bounded weapon TSV parsing, immutable metadata, lookup");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
