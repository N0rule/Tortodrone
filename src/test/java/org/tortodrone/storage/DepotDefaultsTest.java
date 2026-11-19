package org.tortodrone.storage;

import java.nio.file.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.town.TownCatalog;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** The shipped Depot is a plain shop: no default featured, discounted or automatic offers. */
public final class DepotDefaultsTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();
        Path root=Files.createTempDirectory("tortodrone-depot-defaults-");
        try {
            var db=new Database(root.resolve("fresh"),false);var depot=new DepotStore(db,items,rules);
            var catalog=depot.catalog();
            check(catalog.offers().size()==328,"complete official catalogue installed");
            check(catalog.offers().stream().noneMatch(o->o.featured()),"no default offer is featured");
            check(catalog.offers().stream().noneMatch(o->o.sale()||o.originalPrice()!=0||o.promotionId()!=0),"no default offer is a sale or automatic special");
            check(new DepotStore(new Database(root.resolve("fresh"),false),items,rules).catalog().equals(catalog),"restart serves the same plain catalogue");

            // An already-installed database keeps the five seeded featured flags until the one-time alignment runs.
            Path legacy=root.resolve("legacy");var old=new Database(legacy,false);
            old.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_offers SET featured=1 WHERE id BETWEEN 0 AND 4");s.execute("DELETE FROM metadata WHERE key='supply-depot-defaults'");}return null;});
            long before=new DepotStore(old,items,rules).snapshot().revision();
            var aligned=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(aligned.offers().stream().noneMatch(o->o.featured()),"seeded featured flags are retired in an existing database");
            check(aligned.revision()==before+1,"retiring defaults advances the catalog revision once");
            var again=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(again.revision()==aligned.revision()&&again.offers().equals(aligned.offers()),"alignment never runs twice");

            // An administrator-repriced row is no longer the seeded row and keeps its explicit featured flag.
            old.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_offers SET featured=1,energy=1601 WHERE id=0");s.execute("DELETE FROM metadata WHERE key='supply-depot-defaults'");}return null;});
            var kept=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(kept.offers().getFirst().featured(),"administrator-edited rows keep their featured flag");

            // Promotions still provide temporary featured/discount offers for administrators who want them.
            var offer=catalog.offers().stream().filter(o->o.enabled()&&o.good().energy()>10).findFirst().orElseThrow();
            long now=System.currentTimeMillis();
            var scheduled=depot.schedule(catalog.revision(),new DepotStore.Promotion(0,offer.id(),now-1000,now+3600000,0,offer.good().energy()-10,null,true,false));
            var promoted=depot.catalog().offers().stream().filter(o->o.id()==offer.id()).findFirst().orElseThrow();
            check(promoted.featured()&&!promoted.sale()&&promoted.originalPrice()==0&&promoted.promotionId()>0,"promotion featured flag and direct price remain available");
            System.out.println("PASS: plain 328-offer defaults, existing featured flags retired once without touching edited rows, promotions still supported");
        }finally{ContentFixture.delete(root);}
    }
}
