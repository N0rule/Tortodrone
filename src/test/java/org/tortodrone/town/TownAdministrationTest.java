package org.tortodrone.town;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Local-admin gear grants and live depot quote/transaction boundaries. */
public final class TownAdministrationTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("tortodrone-town-admin-");
        try{
            var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));var rules=TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json"));
            try(var accounts=new AccountStore(root,false,items,rules)){
                accounts.create("Alice","alice-password".toCharArray());accounts.create("Bob","bob-password".toCharArray());
                var db=new Database(root,false);new CharacterStore(db,1,"Alice",items).create(knight("Alice-Knight"));new CharacterStore(db,2,"Bob",items).create(knight("Bob-Knight"));
                var original=accounts.worldProfile("Alice","Alice-Knight");int world=original.worldId();
                var uv=rules.availableVariants("SWORD").getFirst();var modifiers=new ItemModifiers(4,.75f,List.of(uv));
                var granted=accounts.grantItems("Alice","Alice-Knight","Weapon/Sword/Calibur",2,1,world,modifiers);var swords=granted.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")).toList();
                check(swords.size()==2&&swords.stream().allMatch(i->i.level()==4&&i.heatProgress()==.75f&&i.variants().getFirst().name().equals(uv)),"each granted equipment instance carries its explicit level, heat and UV");
                long id=swords.getFirst().id();
                refused(()->accounts.grantItems("Alice","Alice-Knight","Weapon/Sword/Calibur",1,1,world,new ItemModifiers(9,1,List.of())));
                refused(()->accounts.grantItems("Alice","Alice-Knight","Weapon/Sword/Calibur",1,1,world,new ItemModifiers(0,Float.NaN,List.of())));
                refused(()->accounts.grantItems("Alice","Alice-Knight","Weapon/Sword/Calibur",1,1,world,new ItemModifiers(0,0,List.of(uv,uv))));
                refused(()->accounts.grantItems("Alice","Alice-Knight","Material/Gel Drop",1,1,world,new ItemModifiers(1,0,List.of())));
                refused(()->accounts.itemModifiers("Bob","Bob-Knight",id,modifiers,2,accounts.worldProfile("Bob","Bob-Knight").worldId(),accounts.worldProfile("Bob","Bob-Knight").revision()));
                refused(()->accounts.itemModifiers("Alice","Alice-Knight",id,modifiers,1,world,original.revision()));
                check(accounts.worldProfile("Alice","Alice-Knight").equals(granted),"invalid modifiers, ownership and stale revisions leave items/revision untouched");
                var changed=accounts.itemModifiers("Alice","Alice-Knight",id,new ItemModifiers(9,0,List.of()),1,world,granted.revision());
                check(changed.items().stream().anyMatch(i->i.id()==id&&i.level()==9&&i.variants().isEmpty()),"existing gear can be edited without replacing its identity");
                db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_admin BEFORE INSERT ON account_events BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
                refused(()->accounts.itemModifiers("Alice","Alice-Knight",id,modifiers,1,world,changed.revision()));check(accounts.worldProfile("Alice","Alice-Knight").equals(changed),"audit failure rolls back all modifier fields and revision");
                db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_admin");}return null;});
                accounts.setCurrency("Alice","Alice-Knight",Currency.CROWNS,500,1,world);var bob=accounts.worldProfile("Bob","Bob-Knight");accounts.setCurrency("Bob","Bob-Knight",Currency.ENERGY,100,2,bob.worldId());
                var depot=accounts.depot();var defaults=depot.snapshot();check(defaults.revision()==0&&defaults.offers().size()==rules.depot().size(),"original depot remains the initial catalogue");
                var offer=new DepotStore.Offer(-1,new TownCatalog.Good("Material/Gel Drop",3,70,0,1,"",0),true,true);var created=depot.save(0,offer);var saved=created.offers().getLast();var quote=new DepotStore.Quote(created.revision(),saved);
                refused(()->depot.save(0,offer));refused(()->depot.save(created.revision(),new DepotStore.Offer(-1,new TownCatalog.Good("Material/Gel Drop",1,10,10,0,"",0),false,true)));
                var town=new TownStore(db,items,rules);var bought=town.depot(1,1,1,saved.id(),70,quote).profile();
                check(bought.crowns()==430&&bought.items().stream().anyMatch(i->i.config().equals("Material/Gel Drop")&&i.count()==3),"configured depot charges the quoted currency and grants the exact quantity");
                refused(()->town.depot(1,1,1,saved.id(),70,quote));check(db.worldProfile(1,1,1).equals(bought),"per-knight limit prevents duplicate spend/delivery");
                var disabled=depot.save(created.revision(),new DepotStore.Offer(saved.id(),saved.good(),false,false));
                refused(()->town.depot(2,1,1,saved.id(),70,quote));
                var repriced=depot.save(disabled.revision(),new DepotStore.Offer(saved.id(),new TownCatalog.Good(saved.good().item(),3,0,2,1,"",0),true,true));var energyOffer=repriced.offers().getLast();
                refused(()->town.depot(2,1,1,saved.id(),2,quote));var energyQuote=new DepotStore.Quote(repriced.revision(),energyOffer);refused(()->town.depot(2,1,1,saved.id(),1,energyQuote));
                check(town.depot(2,1,1,saved.id(),2,energyQuote).profile().energy()==98,"new quote uses the configured Energy price and rejects a player override");
                check(new DepotStore(new Database(root,false),items,rules).snapshot().equals(repriced),"depot edits survive reopening the same SQLite database");
            }
            // A real schema-10 layout contains no depot table; preserve its character state.
            Path old=root.resolve("old");var db=new Database(old,false);int account=db.create("Old",new org.tortodrone.account.Passwords.Protection(new byte[16],new byte[32]),false);new CharacterStore(db,account,"Old",items).create(knight("Old-Knight"));var before=db.worldProfile(account,1,1);
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TABLE town_depot_offers");s.execute("DROP TABLE town_energy_offers");s.execute("PRAGMA user_version=10");}return null;});
            var migrated=new Database(old,false);check(migrated.worldProfile(account,1,1).equals(before)&&new DepotStore(migrated,items,rules).snapshot().revision()==0,"schema-10 migration preserves existing characters and imports no invented stock");
            System.out.println("PASS: admin gear grants/edits, UV validation, ownership/stale revision/audit rollback, depot prices/limits/quotes/persistence and schema-10 upgrade");
        }finally{try(var files=Files.walk(root)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
