package org.tortodrone.storage;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Automatic projections reuse the same authenticated quote/debit/grant transaction as manual sales. */
public final class DepotRotationTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();
        Path root=Files.createTempDirectory("tortodrone-depot-rotation-");
        try {
            var db=new Database(root,false);var depot=new DepotStore(db,items,rules);var base=depot.snapshot();
            long noon=instant("2026-10-03T19:00:00Z");
            var before=at(db,noon-1);var after=at(db,noon);
            check(sales(before).size()==2&&sales(after).size()==2,"two captured daily sales");
            check(sale(before,"Weapon/Sword/Troika").good().energy()==139&&sale(before,"Weapon/Sword/Ascended Calibur").good().energy()==1551,"captured old pair up to noon");
            check(sale(after,"Gear/Shield/Jelly Shield").good().energy()==143&&sale(after,"Gear/Helm/Mighty Cobalt Helm").good().energy()==1555,"captured new pair at noon inclusive");
            for(var o:sales(after)) {
                check(o.featured()&&o.originalPrice()>o.good().energy()&&o.good().count()==1&&DepotSpecials.automatic(o),"native Specials, original price, quantity and automatic identity");
                check(o.promotionEnds()==instant("2026-10-04T19:00:00Z"),"captured expiry/countdown boundary");
                check(TownCatalog.DepotGood.timed(o.good(),o.featured(),o.originalPrice(),o.sale(),o.promotionEnds(),noon).promoDetail().equals("m.promo_hours_left|~24"),"native countdown at reset");
            }
            check(DepotSpecials.policy(noon).startsWith("Captured")&&DepotSpecials.policy(instant("2027-01-01T22:00:00Z")).startsWith("Reconstructed"),"explicit evidence boundary");
            // Winter wall-clock interpretation is deliberately reconstructed; calendar days span DST correctly.
            long spring=instant("2027-03-13T21:00:00Z"),fall=instant("2026-10-31T20:00:00Z");
            check(DepotSpecials.window(spring).ends()-DepotSpecials.window(spring).starts()==23*3600000L,"spring transition");
            check(DepotSpecials.window(fall).ends()-DepotSpecials.window(fall).starts()==25*3600000L,"fall transition");
            for(int day=0;day<400;day++) {
                long now=instant("2027-01-01T22:00:00Z")+day*86400000L;
                var pair=new ArrayList<>(DepotSpecials.offers(base,now).values());check(pair.size()==2,"two reconstructed goods each day");
                check(pair.stream().filter(o->o.originalPrice()<=500).count()==1,"captured low/high price shape");
                check(new HashSet<>(pair.stream().map(o->o.good().item()).toList()).size()==2,"distinct items");
                for(var o:pair){int discount=o.originalPrice()-o.good().energy();check(discount>=5&&discount>=Math.round(o.originalPrice()*.02f)&&discount<=Math.max(5,Math.round(o.originalPrice()*.08f)),"native discount bounds");}
            }
            check(depot.snapshot().equals(base),"automatic selection never writes/reseeds the admin catalog");
            check(new DepotStore(new Database(root,false),items,rules).catalog().equals(depot.catalog()),"restart-stable selection and quote identities");
            // Admin precedence and explicit disable remain authoritative at a captured instant.
            var selected=sales(after).getFirst();
            var saved=depot.schedule(base.revision(),new DepotStore.Promotion(0,selected.id(),noon,noon+3600000,0,111,null,true,false));
            var manual=at(db,noon).offers().stream().filter(o->o.id()==selected.id()).findFirst().orElseThrow();
            check(manual.good().energy()==111&&manual.promotionId()>0&&!manual.sale(),"manual promotion overrides the selected automatic sale");
            check(sales(at(db,noon+3600000)).contains(selected),"manual expiry restores this day's automatic sale");
            var original=base.offers().stream().filter(o->o.id()==selected.id()).findFirst().orElseThrow();
            var disabledGood=new TownCatalog.Good(original.good().item(),1,0,original.good().energy(),0,"",0);
            saved=depot.save(saved.revision(),new DepotStore.Offer(original.id(),disabledGood,false,false));
            check(sales(at(db,noon)).stream().noneMatch(o->o.id()==original.id()),"disabled selected offer stays disabled, without reroll");
            saved=depot.save(saved.revision(),new DepotStore.Offer(original.id(),new TownCatalog.Good(original.good().item(),1,0,1234,0,"",0),false,true));
            check(sales(at(db,noon+3600000)).stream().noneMatch(o->o.id()==original.id()),"edited regular price is not overwritten");
            // Use a fresh disposable database to exercise authoritative purchases at the real current date.
            var purchaseDb=new Database(root.resolve("purchase"),false);var purchaseDepot=new DepotStore(purchaseDb,items,rules);
            int account=purchaseDb.create("Buyer",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(purchaseDb,account,"Buyer",items).create(new Knight(0,"Buyer-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            purchaseDb.currency("Buyer","Buyer-Knight",account,-1,org.tortodrone.character.Currency.ENERGY,10000);
            var snapshot=purchaseDepot.catalog();var current=sales(snapshot).getFirst();var quote=new DepotStore.Quote(snapshot.revision(),current);
            var town=new TownStore(purchaseDb,items,rules);var profile=purchaseDb.worldProfile(account,1,1);
            refused(()->town.depot(account,1,999,current.id(),current.good().energy(),quote));
            var stale=new DepotStore.Offer(current.id(),current.good(),current.featured(),current.enabled(),current.promotionOnly(),current.originalPrice(),true,current.promotionId()-1,current.promotionEnds()-86400000L);
            refused(()->town.depot(account,1,1,current.id(),current.good().energy(),new DepotStore.Quote(snapshot.revision(),stale)));
            purchaseDb.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_rotation BEFORE INSERT ON inventory_items BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->town.depot(account,1,1,current.id(),current.good().energy(),quote));
            check(profile.equals(purchaseDb.worldProfile(account,1,1)),"ownership, expired identity and grant failure leave balance/inventory unchanged");
            purchaseDb.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_rotation");}return null;});
            var bought=town.depot(account,1,1,current.id(),current.good().energy(),quote).profile();
            check(bought.energy()==profile.energy()-current.good().energy(),"sale debit committed: "+bought.energy()+" from "+profile.energy()+" at "+current.good().energy());
            var definition=items.require(current.good().item());
            check(bought.items().stream().anyMatch(i->i.config().equals(definition.config())&&i.bindOnEquip()==definition.bindOnEquip()),"sale retains authored equipment binding policy: "+definition.config());
            var costume=items.entries().stream().filter(i->i.type().endsWith("Costume")&&i.grantable()&&!i.locked()).findFirst().orElseThrow();
            var added=purchaseDepot.save(purchaseDepot.snapshot().revision(),new DepotStore.Offer(-1,new TownCatalog.Good(costume.config(),1,0,1,0,"",0),false,true));
            var costumeOffer=purchaseDepot.catalog().offers().getLast();
            var costumed=town.depot(account,1,1,costumeOffer.id(),1,new DepotStore.Quote(added.revision(),costumeOffer)).profile();
            check(costumed.items().stream().anyMatch(i->i.config().equals(costume.config())&&!i.bound()),"costume purchase retains its existing binding exemption");
            System.out.println("PASS: captured and reconstructed daily Depot pairs, noon/DST expiry, restart stability, admin precedence, stale quotes, ownership and purchase rollback");
        }finally{ContentFixture.delete(root);}
    }
    private static long instant(String value){return Instant.parse(value).toEpochMilli();}
    private static DepotStore.Snapshot at(Database db,long now)throws IOException{return db.read(c->DepotStore.effective(c,now));}
    private static List<DepotStore.Offer> sales(DepotStore.Snapshot snapshot){return snapshot.offers().stream().filter(DepotSpecials::automatic).toList();}
    private static DepotStore.Offer sale(DepotStore.Snapshot snapshot,String item){return sales(snapshot).stream().filter(o->o.good().item().equals(item)).findFirst().orElseThrow();}
    private interface Action {void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
