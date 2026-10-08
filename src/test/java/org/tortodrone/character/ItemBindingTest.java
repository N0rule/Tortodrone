package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class ItemBindingTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-binding-");
        try {
            try (var accounts = new AccountStore(home)) { accounts.create("Alice", "alice-password".toCharArray()); }
            var db = new Database(home, false);
            var characters = new CharacterStore(db, 1, "Alice");
            var knight = characters.create(new Knight(0,"Bound-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var original = characters.worldProfile(knight.name(), 1);
            check(original.items().stream().allMatch(WorldProfile.Item::bound), "new equipped kit is bound");
            long helm = original.equipment()[0];
            var removed = db.equip(1, knight.id(), 1, helm, null);
            check(removed.items().stream().allMatch(WorldProfile.Item::bound), "unequip retains binding");
            check(new Database(home,false).worldProfile(1,knight.id(),1).items().equals(removed.items()),
                "restart preserves unequipped bound inventory");
            db.equip(1, knight.id(), 1, helm, 0);
            check(db.worldProfile(1,knight.id(),1).equipment()[0] == helm, "bound item can be re-equipped");
            db.equip(1, knight.id(), 1, helm, null);
            // Simulate a future unbound item: equipping binds transactionally; removing never clears it.
            db.write(c -> { try(var s=c.prepareStatement("UPDATE inventory_items SET bound=0 WHERE item_id=?")) {
                s.setLong(1,helm); s.executeUpdate(); } return null; });
            check(!db.worldProfile(1,knight.id(),1).items().get(0).bound(), "unbound fixture");
            check(db.equip(1,knight.id(),1,helm,0).items().get(0).bound(), "equipping binds the owned item");
            removed = db.equip(1,knight.id(),1,helm,null);
            var itemIds = removed.items().stream().map(WorldProfile.Item::id).toList();
            db.currency("Alice",knight.name(),1,original.worldId(),Currency.CROWNS,42);
            var saved = db.worldProfile(1,knight.id(),1);
            check(saved.worldId()==original.worldId() && saved.items().stream().map(WorldProfile.Item::id).toList().equals(itemIds)
                && saved.crowns()==42 && saved.equipment()[0]==0, "binding retains identities, balances and unequipped loadout");
            check(new Database(home,false).worldProfile(1,knight.id(),1).equals(saved), "reopen preserves binding and loadout");
            System.out.println("PASS: binding on equip, durable unequip/re-equip and unchanged identities/balances");
        } finally { try(var paths=Files.walk(home)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
