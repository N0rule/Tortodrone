package org.tortodrone.storage;

import java.nio.file.*;
import java.util.List;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** The shipped Depot is a plain shop: no default featured, discounted or automatic offers. */
public final class DepotDefaultsTest {
    private static final List<Integer> WITHHELD=List.of(0,1,2,4,154,160,192,196,198);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();
        Path root=Files.createTempDirectory("tortodrone-depot-defaults-");
        try {
            var db=new Database(root.resolve("fresh"),false);var depot=new DepotStore(db,items,rules);
            var catalog=depot.catalog();
            check(catalog.offers().size()==328&&catalog.offers().stream().allMatch(o->o.enabled()),"complete supported official catalogue installed");
            check(catalog.offers().stream().noneMatch(o->o.featured()),"no default offer is featured");
            check(catalog.offers().stream().noneMatch(o->o.sale()||o.originalPrice()!=0||o.promotionId()!=0),"no default offer is a sale or automatic special");
            check(new DepotStore(new Database(root.resolve("fresh"),false),items,rules).catalog().equals(catalog),"restart serves the same plain catalogue");

            // An already-installed database retains old default flags and withheld rows until the one-time alignment runs.
            Path legacy=root.resolve("legacy");var old=new Database(legacy,false);
            old.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_offers SET featured=1 WHERE id BETWEEN 0 AND 4");s.execute("UPDATE town_depot_offers SET enabled=0 WHERE id IN (0,1,2,4,154,160,192,196,198)");s.execute("DELETE FROM metadata WHERE key='supply-depot-defaults'");}return null;});
            long before=new DepotStore(old,items,rules).snapshot().revision();
            var aligned=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(aligned.offers().stream().noneMatch(o->o.featured()),"seeded featured flags are retired in an existing database");
            check(aligned.offers().stream().filter(o->WITHHELD.contains(o.id())).allMatch(o->o.enabled()),"verified seeded offers are enabled in an existing database");
            check(aligned.revision()==before+1,"aligning defaults advances the catalog revision once");
            var again=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(again.revision()==aligned.revision()&&again.offers().equals(aligned.offers()),"alignment never runs twice");

            // A database already cleared by the earlier release only needs the withheld rows enabled.
            Path grants=root.resolve("grants");var pending=new Database(grants,false);
            pending.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_offers SET enabled=0 WHERE id IN (0,1,2,4,154,160,192,196,198)");s.execute("UPDATE metadata SET value=2 WHERE key='supply-depot-defaults'");}return null;});
            var enabled=new DepotStore(new Database(grants,false),items,rules).snapshot();
            check(enabled.offers().stream().filter(o->WITHHELD.contains(o.id())).allMatch(o->o.enabled()),"pending support alignment enables only the verified defaults");

            // An administrator-repriced row is no longer the seeded row and keeps its explicit featured flag.
            old.write(c->{try(var s=c.createStatement()){s.execute("UPDATE town_depot_offers SET featured=1,energy=1601 WHERE id=0");s.execute("DELETE FROM metadata WHERE key='supply-depot-defaults'");}return null;});
            var kept=new DepotStore(new Database(legacy,false),items,rules).snapshot();
            check(kept.offers().getFirst().featured(),"administrator-edited rows keep their featured flag");
            check(items.require("Gear/Shield/Heater Shield").grantable()&&items.require("Gear/Shield/Omega Shell").grantable()&&items.require("Weapon/Bomb/Big Angry Bomb").grantable(),"formerly unsupported native equipment is deliverable");

            // Promotions still provide temporary featured/discount offers for administrators who want them.
            var offer=catalog.offers().stream().filter(o->o.enabled()&&o.good().energy()>10).findFirst().orElseThrow();
            long now=System.currentTimeMillis();
            depot.schedule(catalog.revision(),new DepotStore.Promotion(0,offer.id(),now-1000,now+3600000,0,offer.good().energy()-10,null,true,false));
            var promoted=depot.catalog().offers().stream().filter(o->o.id()==offer.id()).findFirst().orElseThrow();
            check(promoted.featured()&&!promoted.sale()&&promoted.originalPrice()==0&&promoted.promotionId()>0,"promotion featured flag and direct price remain available");
            System.out.println("PASS: plain 328-offer defaults, existing featured flags and withheld rows aligned once without touching edits, promotions still supported");
        }finally{ContentFixture.delete(root);}
    }
}
