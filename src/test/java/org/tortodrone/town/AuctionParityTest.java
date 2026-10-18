package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Native increment boundaries, per-unit ordering and compatible scheduled featured publication. */
public final class AuctionParityTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-auction-parity-");
        try {
            var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();
            var db=new Database(root,false);int owner=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,owner,"Alice",items).create(new Knight(0,"Auction-Alice","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            db.currency("Alice","Auction-Alice",owner,-1,org.tortodrone.character.Currency.CROWNS,10000);
            var house=new AuctionHouse(db,items,rules);
            check(AuctionHouse.nextBid(1,0,false)==2&&AuctionHouse.nextBid(99,0,false)==103&&AuctionHouse.nextBid(1000,0,false)==1050&&AuctionHouse.nextBid(Integer.MAX_VALUE,0,false)==Integer.MAX_VALUE,"native rounded increment boundaries");
            check(AuctionHouse.nextBid(1000,1020,false)==1020&&AuctionHouse.nextBid(1000,0,true)==1000,"buyout cap and first bid");
            var cheapLot=house.createFeatured("Material/Gel Drop",2,40,40,0,2);
            var expensiveUnit=house.createFeatured("Material/Gel Drop",1,30,30,0,2);
            var search=new SessionDecoder.AuctionSearch(true,null,Set.of(items.find("Material/Gel Drop").stockId()),1,true,false);
            var page=(AuctionState.Page)house.operate(owner,1,1,5,List.of(search,0),false).result();
            check(page.listings().getFirst().id()==cheapLot.id(),"bid sorting compares price per item, not per lot");
            var buySearch=new SessionDecoder.AuctionSearch(true,null,search.configs(),2,false,false);
            page=(AuctionState.Page)house.operate(owner,1,1,5,List.of(buySearch,0),false).result();check(page.listings().getFirst().id()==expensiveUnit.id(),"descending buyout price per item");
            long now=System.currentTimeMillis();var future=house.scheduleFeatured("Material/Gel Drop",1,5,0,1,now+3600000,now+7200000);
            var saved=db.worldProfile(owner,1,1);refused(()->house.operate(owner,1,1,1,List.of(future.id(),5),false));check(db.worldProfile(owner,1,1).equals(saved),"unpublished schedule cannot reserve crowns");
            page=(AuctionState.Page)house.operate(owner,1,1,5,List.of(search,0),false).result();check(page.listings().stream().noneMatch(a->a.id()==future.id()),"scheduled listing absent from native search");
            check(new AuctionHouse(new Database(root,false),items,rules).overview().stream().anyMatch(a->a.listing().id()==future.id()&&a.listing().availableAt()==future.availableAt()),"schedule survives restart");
            db.write(c->{try(var s=c.prepareStatement("UPDATE town_auctions SET available_at=0 WHERE id=?")){s.setLong(1,future.id());s.executeUpdate();}return null;});
            house.operate(owner,1,1,1,List.of(future.id(),5),false);
            db.write(c->{try(var s=c.prepareStatement("UPDATE town_auctions SET expires=0 WHERE id=?")){s.setLong(1,future.id());s.executeUpdate();}return null;});
            var reopened=new AuctionHouse(new Database(root,false),items,rules);reopened.settleExpired();reopened.settleExpired();check(reopened.deliveries(future.id()).size()==1,"scheduled bid settles exactly once after restart");
            // The older listing must remain findable even after over 5,000 newer ended listings.
            db.write(c->{try(var s=c.prepareStatement("INSERT INTO town_auctions(seller,item,start_bid,buy_price,bid,expires,ended,featured_total,featured_remaining) SELECT seller,item,start_bid,buy_price,bid,0,1,featured_total,featured_remaining FROM town_auctions WHERE id=?")){s.setLong(1,expensiveUnit.id());for(int i=0;i<5001;i++)s.executeUpdate();}return null;});
            page=(AuctionState.Page)house.operate(owner,1,1,5,List.of(search,0),false).result();check(page.listings().stream().anyMatch(a->a.id()==cheapLot.id()),"ended history cannot hide active search results before pagination");
            System.out.println("PASS: native increment boundaries, per-unit bid/buy sorting, filter-before-pagination, scheduled publication, stale/early refusal, restart and exactly-once featured settlement");
        }finally{ContentFixture.delete(root);}
    }
    private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception {try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
