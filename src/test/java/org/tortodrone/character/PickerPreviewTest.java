package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** The character picker must render each knight's currently equipped appearance. */
public final class PickerPreviewTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-picker-test-");
        try {
            try(var accounts=new AccountStore(directory,false,org.tortodrone.character.ItemCatalog.starter())){accounts.create("Alice","alice-password".toCharArray());}
            var db=new Database(directory,false);
            CharacterStore store = new CharacterStore(db,1,"Alice");
            List<List<CharacterStore.RosterEntry>> published = new ArrayList<>();
            store.subscribe(published::add, published::add);
            Knight knight = store.create(choices("Picker-Knight"));
            store.create(choices("Second-Knight"));
            List<CharacterStore.RosterEntry> available = store.available();
            check(available.size() == 2 && available.get(1).knight().name().equals("Second-Knight"),
                "every saved knight is published with its identity");
            // Starter items are equipped on creation, so the picker matches the held composition.
            check(available.get(0).preview().arguments().containsKey("Helmet")
                    && available.get(0).preview().arguments().containsKey("Armor"),
                "a freshly created knight previews its equipped starter outfit");
            var profile = store.worldProfile("Picker-Knight", -1);
            check(available.get(0).preview().equals(StarterCatalog.equipped(knight, profile, store.catalog())),
                "the picker preview composes the same model the stock reader accepts");

            long starterHelm = profile.items().stream()
                .filter(item -> item.slot() == WorldProfile.Slot.HELM).findFirst().orElseThrow().id();
            store.equip(knight.id(), -1, starterHelm, null); // unequip
            check(!store.available().get(0).preview().arguments().containsKey("Helmet"),
                "an unequipped helm disappears from the picker preview");
            var granted = db.grantItems("Alice", "Picker-Knight", -1, -1,
                store.catalog().require("Gear/Helm/Spiral Crescent Helm (Red)"), 1, -1, -1);
            long crescent = granted.items().stream()
                .filter(item -> item.slot() == WorldProfile.Slot.HELM).findFirst().orElseThrow().id();
            store.equip(knight.id(), -1, crescent, 0);
            var composed = store.available().get(0).preview();
            var helmet = (StarterCatalog.Model) composed.arguments().get("Helmet");
            check(helmet != null && helmet.arguments().containsKey("Red") && composed.arguments().containsKey("Armor"),
                "the picker preview composes the newly equipped helm with the current armor");
            check(composed.equals(StarterCatalog.equipped(knight, store.worldProfile("Picker-Knight", -1), store.catalog())),
                "the picker preview follows every equipment change");
            check(!store.available().get(1).preview().arguments().containsKey("Red"),
                "one knight's equipment does not change another knight's preview");

            store.subscribe(published::add,published::add);
            check(!published.isEmpty() && published.get(published.size() - 1).get(0).knight().id() == knight.id()
                    && published.get(published.size() - 1).get(0).preview().equals(composed),
                "a fresh picker subscription receives current equipment");
            check(composed.arguments().get("Weapon Idle").equals("ready_sword"),"hover preview shows lowest equipped weapon slot");
            long sword=profile.items().stream().filter(item->item.slot()==WorldProfile.Slot.SWORD).findFirst().orElseThrow().id();
            store.equip(knight.id(),-1,sword,null);
            check(store.available().get(0).preview().arguments().get("Weapon Idle").equals("ready_pistol"),"unequipping sword reveals equipped gun in picker");
            long armor=profile.items().stream().filter(item->item.slot()==WorldProfile.Slot.ARMOR).findFirst().orElseThrow().id();
            store.equip(knight.id(),-1,armor,null);
            check(!store.available().get(0).preview().arguments().containsKey("Armor"),"unequipped armor does not fall back to creation armor");
            System.out.println("PASS: character select previews compose currently equipped appearance, unequip and re-equip");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static Knight choices(String name) { return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0); }
}
