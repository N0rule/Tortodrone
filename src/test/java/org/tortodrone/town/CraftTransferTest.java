package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Upgrade transfer and post-debit rollback on real, disposable SQLite inventory. */
public final class CraftTransferTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-craft-transfer-");
        try {
            var db=new Database(home,true);int account=db.create("Crafter",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            new CharacterStore(db,account,"Crafter",items).create(new Knight(0,"Craft-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var store=new TownStore(db,items,rules);var recipe=rules.recipe("Armor/Dusker Coat");
            db.setCurrency("Crafter","Craft-Knight",account,-1,org.tortodrone.character.Currency.CROWNS,100000);
            var ids=new ArrayList<Long>();long precursor=0;
            for(var i:recipe.ingredients()) {
                db.grantItems("Crafter","Craft-Knight",account,-1,items.require(i.item()),i.count(),-1,-1);
                long id=db.worldProfile(account,1,1).items().stream().filter(v->v.config().equals(i.item())).findFirst().orElseThrow().id();ids.add(id);
                if(i.kind().endsWith("LevelItem"))precursor=id;
            }
            String orb="Rarity/Orb of Alchemy/"+new String[]{"","Flawed","Simple","Advanced","Elite","Eternal"}[items.require(recipe.item()).rarity()];db.grantItems("Crafter","Craft-Knight",account,-1,items.require(orb),3,-1,-1);
            ids.add(db.worldProfile(account,1,1).items().stream().filter(i->i.config().equals(orb)).findFirst().orElseThrow().id());
            String ticket=items.entries().stream().filter(e->{var a=rules.accessory(rules.item(e.config()).getOrDefault("accessory",""));return a!=null&&!a.remove()&&a.location().equals("ARMOR_BACK")&&Collections.disjoint(a.blocks(),rules.blockers(recipe.item()));}).findFirst().orElseThrow().config();
            db.grantItems("Crafter","Craft-Knight",account,-1,items.require(ticket),1,-1,-1);
            long accessory=db.worldProfile(account,1,1).items().stream().filter(i->i.config().equals(ticket)).findFirst().orElseThrow().id();
            store.accessorize(account,1,1,1,precursor,accessory,"Blue");
            final long source=precursor;
            db.write(c->{try(var s=c.prepareStatement("UPDATE inventory_items SET bound=1,level=9,heat_progress=1,variants='[\"Gear/Normal Defense 1\"]' WHERE item_id=?")){s.setLong(1,source);s.executeUpdate();}return null;});
            long[] selected=ids.stream().mapToLong(Long::longValue).toArray();var before=db.worldProfile(account,1,1);
            refused(()->store.craft(account,1,99,recipe.config(),selected,true,true));
            refused(()->store.craft(account,1,1,recipe.config(),new long[]{source,source},true,true));
            check(db.worldProfile(account,1,1).equals(before),"stale authentication and duplicate selection leave inventory/revision unchanged");
            // Fail after debit and ingredient deletion, while inserting the crafted output.
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_craft BEFORE INSERT ON inventory_items WHEN NEW.config='Gear/Armor/Dusker Coat' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");}return null;});
            refused(()->store.craft(account,1,1,recipe.config(),selected,true,true));
            check(db.worldProfile(account,1,1).equals(before),"output failure rolls back crowns, all ingredients and revision");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_craft");}return null;});
            var result=store.craft(account,1,1,recipe.config(),selected,true,true);
            var output=result.profile().items().stream().filter(i->i.id()==(Long)result.result()).findFirst().orElseThrow();
            var input=before.items().stream().filter(i->i.id()==source).findFirst().orElseThrow();
            check(output.bound()&&output.bindOnEquip()&&output.variants().equals(input.variants())&&output.additionalAccessories().equals(input.additionalAccessories()),"upgrade retains UV, supported accessories and bound state");
            check(output.level()==0&&output.heatProgress()==0,"ordinary upgrade starts at the recipe's native fresh heat state");
            check(result.profile().items().stream().noneMatch(i->i.id()==source||i.config().equals(orb)),"precursor and exactly three orbs consumed");
            refused(()->store.craft(account,1,1,recipe.config(),selected,true,true));
            check(db.worldProfile(account,1,1).equals(result.profile()),"replaying consumed selection grants no second result");
            check(new Database(home,false).worldProfile(account,1,1).equals(result.profile()),"transferred equipment persists after reopening");
            System.out.println("PASS: crafting UV/accessory/binding transfers, fresh heat, three orbs, ownership, rollback and replay safety");
        }finally{try(var files=Files.walk(home)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
