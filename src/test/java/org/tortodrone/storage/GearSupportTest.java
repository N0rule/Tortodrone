package org.tortodrone.storage;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;

/** Formerly unsupported native gear now grants and equips in its real inventory slot. */
public final class GearSupportTest {
    private record Expected(String config, WorldProfile.Slot slot) {}

    private static final List<Expected> ITEMS = List.of(
            new Expected("Gear/Armor/Circuit Breaker Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Circuit Breaker Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Armor/Emberbreak Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Emberbreak Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Armor/Firebreak Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Firebreak Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Armor/Frostbreaker Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Frostbreaker Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Armor/Icebreaker Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Rooster Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Helm/Rooster King Helm", WorldProfile.Slot.HELM),
            new Expected("Gear/Shield/Scarlet Shield", WorldProfile.Slot.SHIELD),
            new Expected("Gear/Shield/Stop Sign", WorldProfile.Slot.SHIELD),
            new Expected("Gear/Armor/Volt Breaker Armor", WorldProfile.Slot.ARMOR),
            new Expected("Gear/Helm/Volt Breaker Helm", WorldProfile.Slot.HELM));

    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of("."));
        var items = content.items();
        Path root = Files.createTempDirectory("tortodrone-gear-support-");
        try {
            var db = new Database(root, false);
            int account = db.create("Gear", new Passwords.Protection(new byte[16], new byte[32]), false);
            new CharacterStore(db, account, "Gear", items)
                    .create(new Knight(
                            0,
                            "Gear-Knight",
                            "Gear/Helm/Spiral Sallet (Blue)",
                            "Gear/Armor/Spiral Culet (Blue)",
                            null,
                            null,
                            "Blue",
                            0,
                            2,
                            0,
                            0));
            for (var expected : ITEMS) {
                var definition = items.require(expected.config());
                check(
                        definition.grantable() && definition.slot() == expected.slot(),
                        "native identity and slot: " + expected.config());
                if (expected.slot() == WorldProfile.Slot.SHIELD)
                    check(
                            content.weapons().findShield(expected.config()) != null,
                            "shield mechanics retained: " + expected.config());
                var profile = db.grantItems(null, "Gear-Knight", -1, -1, definition, 1, -1, -1);
                long id = profile.items().stream()
                        .filter(i -> i.config().equals(expected.config()))
                        .findFirst()
                        .orElseThrow()
                        .id();
                profile = db.equip(account, 1, 1, id, expected.slot().initialIndex());
                check(
                        profile.items().stream()
                                .anyMatch(i -> i.id() == id
                                        && Integer.valueOf(expected.slot().initialIndex())
                                                .equals(i.equippedSlot())),
                        "grants and equips: " + expected.config());
            }
            var scarlet = items.require("Gear/Shield/Scarlet Shield");
            var profile = db.worldProfile(account, 1, 1);
            long id = profile.items().stream()
                    .filter(i -> i.config().equals(scarlet.config()))
                    .findFirst()
                    .orElseThrow()
                    .id();
            profile = db.equip(account, 1, 1, id, 14);
            check(
                    profile.items().stream()
                            .anyMatch(i -> i.id() == id && Integer.valueOf(14).equals(i.equippedSlot())),
                    "PvP shield slot 14 persists a normal shield");
            System.out.println(
                    "PASS: 15 formerly unsupported native gear items grant, keep their real slots and equip; PvP shield slot 14 persists");
        } finally {
            ContentFixture.delete(root);
        }
    }
}
