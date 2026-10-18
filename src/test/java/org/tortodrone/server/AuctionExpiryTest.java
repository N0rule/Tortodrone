package org.tortodrone.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.storage.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** The server-owned worker settles with no account, character, session or auction UI open. */
public final class AuctionExpiryTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-idle-auctions-");
        try(var log=new ServerLog(home.resolve("logs"))){
            var db=new Database(home,true);var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var house=new AuctionHouse(db,items,TownCatalog.empty());var listing=house.createFeatured("Weapon/Sword/Calibur",1,100,0,0,1);
            db.write(c->{try(var s=c.prepareStatement("UPDATE town_auctions SET expires=0 WHERE id=?")){s.setLong(1,listing.id());s.executeUpdate();}return null;});
            var committed=new CountDownLatch(1);db.observe((operation,millis,failed)->{if(operation.equals("write")&&!failed)committed.countDown();});
            try(var expiry=new AuctionExpiry(house,log)){
                check(committed.await(5,TimeUnit.SECONDS),"idle auction worker commits without connected players");
                check(house.overview().getFirst().listing().ended(),"idle featured auction expires without a UI operation");
            }
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.getName().equals("AuctionExpiry")&&t.isAlive()),"worker terminates before deployment resources close");
            System.out.println("PASS: auction expiry without clients and worker lifecycle cleanup");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
