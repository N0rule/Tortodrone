package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.*;
import org.tortodrone.support.*;
import org.tortodrone.town.*;
import static org.tortodrone.support.TestWire.check;

/** Captured seed fidelity, explicit upgrades and timed authenticated transaction safety. */
public final class DepotStoreTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-depot-");
        try {
            var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();var seed=DepotSeed.bundled();
            check(seed.offers().size()==328&&seed.unsupported().size()==9,"complete captured snapshot retained, including exact unsupported records");
            for(var o:seed.offers()) {
                var d=items.find(o.good().item());check(d!=null&&d.grantable()==o.enabled()&&o.good().count()<=(d.stackable()?9999:1),"seed identity/quantity/grantability: "+o.id());
                check(o.good().price()==rules.price(d.config(),d.rarity(),true),"captured native price sentinel resolved: "+o.id());
            }
            var db=new Database(root.resolve("new"),false);var depot=new DepotStore(db,items,rules);
            check(depot.snapshot().offers().equals(seed.offers())&&depot.snapshot().revision()==1,"new DB publishes complete ordered catalog immediately");
            check(new DepotStore(new Database(root.resolve("new"),false),items,rules).snapshot().equals(depot.snapshot()),"restart never seeds again");
            for(String invalid:List.of("{}",Files.readString(Path.of("src/main/resources/seeds/supply-depot.json")).replaceFirst("\\\"count\\\":1","\\\"count\\\":0")))refused(()->DepotSeed.parse(invalid));
            int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,account,"Alice",items).create(knight("Depot-Alice"));
            db.currency("Alice","Depot-Alice",account,-1,org.tortodrone.character.Currency.CROWNS,1000);
            var saved=depot.save(1,new DepotStore.Offer(-1,new TownCatalog.Good("Material/Gel Drop",3,20,0,2,"",0),false,true));
            var base=saved.offers().getLast();var town=new TownStore(db,items,rules);var quote=new DepotStore.Quote(saved.revision(),base);
            saved=depot.save(saved.revision(),new DepotStore.Offer(base.id(),new TownCatalog.Good("Material/Gel Drop",3,25,0,2,"",0),false,true));
            var before=db.worldProfile(account,1,1);refused(()->town.depot(account,1,1,base.id(),20,quote));check(before.equals(db.worldProfile(account,1,1)),"price edit rejects old quote without debit/grant");
            long now=System.currentTimeMillis();saved=depot.schedule(saved.revision(),new DepotStore.Promotion(0,base.id(),now-1000,now+3600000,10,0,25,true,false));
            var active=depot.catalog().offers().getLast();check(active.sale()&&active.originalPrice()==25&&active.featured()&&active.good().price()==10,"native effective discount fields");
            long rev=saved.revision();refused(()->depot.schedule(rev,new DepotStore.Promotion(0,base.id(),now,now+7200000,5,0,null,true,false)));
            var p=depot.promotions().getFirst();
            check(db.read(c->DepotStore.effective(c,p.starts()-1)).offers().getLast().good().price()==25,"start not yet active");
            check(db.read(c->DepotStore.effective(c,p.starts())).offers().getLast().sale(),"start inclusive");
            check(db.read(c->DepotStore.effective(c,p.ends())).offers().getLast().good().price()==25,"end exclusive restores base price");
            var saleQuote=new DepotStore.Quote(saved.revision(),active);
            db.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_promotions SET starts=0,ends=1");}return null;});
            refused(()->town.depot(account,1,1,base.id(),10,saleQuote));check(before.equals(db.worldProfile(account,1,1)),"expired displayed offer cannot charge");
            saved=depot.cancelPromotion(saved.revision(),p.id());
            saved=depot.save(saved.revision(),new DepotStore.Offer(base.id(),base.good(),false,true,true));
            check(!depot.catalog().offers().getLast().enabled(),"temporary offer absent without active promotion");
            saved=depot.schedule(saved.revision(),new DepotStore.Promotion(0,base.id(),now-1000,now+3600000,7,0,null,true,false));
            var temporary=depot.catalog().offers().getLast();check(temporary.enabled()&&!temporary.sale()&&temporary.originalPrice()==0,"direct temporary price is not advertised as a discount");
            var current=new DepotStore.Quote(saved.revision(),temporary);
            refused(()->town.depot(account,1,999,base.id(),7,current));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_depot_grant BEFORE INSERT ON inventory_items BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->town.depot(account,1,1,base.id(),7,current));check(before.equals(db.worldProfile(account,1,1)),"grant failure rolls back debit and ledger");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_depot_grant");}return null;});
            try(var pool=Executors.newFixedThreadPool(3)) {
                var gate=new CountDownLatch(1);var attempts=new ArrayList<Future<Boolean>>();for(int i=0;i<3;i++)attempts.add(pool.submit(()->{gate.await();try{town.depot(account,1,1,base.id(),7,current);return true;}catch(IOException refused){return false;}}));
                gate.countDown();int successes=0;for(var f:attempts)if(f.get(10,TimeUnit.SECONDS))successes++;check(successes==2,"concurrent buys honor shared lifetime limit atomically");
            }
            check(db.worldProfile(account,1,1).crowns()==before.crowns()-14&&db.worldProfile(account,1,1).items().stream().filter(i->i.config().equals("Material/Gel Drop")).mapToInt(WorldProfile.Item::count).sum()==6,"exactly two debits/grants");
            saved=depot.save(saved.revision(),new DepotStore.Offer(-1,new TownCatalog.Good("Material/Gel Drop",1,1,0,2,"",0),false,true));
            var shared=saved.offers().getLast();var sharedQuote=new DepotStore.Quote(saved.revision(),shared);refused(()->town.depot(account,1,1,shared.id(),1,sharedQuote));
            check(new DepotStore(new Database(root.resolve("new"),false),items,rules).promotions().equals(depot.promotions()),"schedules and cancellations survive restart");
            // An administrator-managed empty catalog is intentionally empty, even after upgrade.
            Path empty=root.resolve("empty");var emptyDb=new Database(empty,false);emptyDb.write(c->{try(var s=c.createStatement()){s.execute("DELETE FROM town_depot_offers");}return null;});
            check(new DepotStore(new Database(empty,false),items,rules).snapshot().offers().isEmpty(),"empty managed catalog never reseeds");
            emptyDb.write(c->{SchemaFixture.depotBefore15(c);try(var s=c.createStatement()){s.execute("PRAGMA user_version=14");}return null;});
            check(new DepotStore(EconomyUpgrade.export(empty,root.resolve("empty-candidate"),null),items,rules).snapshot().offers().isEmpty(),"empty managed upgrade stays empty");
            Path cli=root.resolve("managed-cli");Files.createDirectories(cli.resolve("data"));Files.copy(emptyDb.file(),cli.resolve("data/tortodrone.db"));
            EconomyUpgrade.main(new String[]{cli.toString(),root.resolve("managed-cli-candidate").toString()});
            check(new DepotStore(new Database(root.resolve("managed-cli-candidate"),false),items,rules).snapshot().offers().isEmpty(),"managed CLI upgrade never reads missing or obsolete YAML");
            // Managed edits, revision and historical ledger survive a supported offline upgrade.
            var historical=db.worldProfile(account,1,1);long revision=depot.snapshot().revision();
            db.write(c->{SchemaFixture.depotBefore15(c);try(var s=c.createStatement()){s.execute("PRAGMA user_version=14");}return null;});
            byte[] original=Files.readAllBytes(db.file());refused(()->new Database(root.resolve("new"),false));
            var upgraded=EconomyUpgrade.export(root.resolve("new"),root.resolve("managed-candidate"),null);
            check(upgraded.worldProfile(account,1,1).equals(historical)&&new DepotStore(upgraded,items,rules).snapshot().revision()==revision,"managed state/revision survive candidate export");
            check(Arrays.equals(original,Files.readAllBytes(db.file())),"source DB bytes unchanged");
            check(upgraded.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT SUM(purchased) FROM town_purchases WHERE catalog='Supply Depot'")){r.next();return r.getInt(1);}})==2,"historical purchase counts survive unchanged");
            // Revision-zero YAML fallback imports its exact modified order/prices once, never the stock seed.
            Path old=root.resolve("legacy");var oldDb=new Database(old,false);
            oldDb.write(c->{SchemaFixture.depotBefore15(c);try(var s=c.createStatement()){s.execute("DELETE FROM town_depot_offers");s.execute("DELETE FROM metadata WHERE key='supply-depot-revision'");s.execute("PRAGMA user_version=14");}return null;});
            refused(()->EconomyUpgrade.export(old,root.resolve("missing-import"),null));check(!Files.exists(root.resolve("missing-import")),"missing legacy source never substitutes seed");
            var imported=new DepotSeed.Data(0,"Fixture edited YAML",List.of(new DepotStore.Offer(0,new TownCatalog.Good("Material/Gel Drop",4,123,0,2,"",0),true,true)),Map.of());
            var migrated=EconomyUpgrade.export(old,root.resolve("legacy-candidate"),imported);check(new DepotStore(migrated,items,rules).snapshot().offers().equals(imported.offers()),"exact edited YAML imported with existing handles");
            var bad=new DepotSeed.Data(0,"invalid fixture",List.of(imported.offers().getFirst(),imported.offers().getFirst()),Map.of());
            refused(()->EconomyUpgrade.export(old,root.resolve("bad-candidate"),bad));check(!Files.exists(root.resolve("bad-candidate")),"failed import publishes no partially initialized catalog");
            try(var channel=FileChannel.open(old.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){refused(()->EconomyUpgrade.export(old,root.resolve("locked"),imported));}
            var shops=Yaml.read(Path.of("content/town/shops.yml"));check(!shops.containsKey("depot"),"no editable Depot defaults");
            System.out.println("PASS: complete 328-offer seed, explicit marker, restart/empty/managed/legacy upgrades, source preservation, rollback, temporal boundaries, native sale fields, stale quotes, shared limits, ownership and concurrent purchases");
        }finally{ContentFixture.delete(root);}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Action {void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
