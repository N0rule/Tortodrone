package org.tortodrone.town;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Disposable escrow regression, including the exact schema-9 predecessor. */
public final class FeaturedAuctionTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-featured-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            new CharacterStore(db,alice,"Alice",items).create(choices("Alice-Knight"));new CharacterStore(db,bob,"Bob",items).create(choices("Bob-Knight"));
            db.currency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,10000);db.currency("Bob","Bob-Knight",bob,-1,Currency.CROWNS,10000);
            var house=new AuctionHouse(db,items,rules);
            var featured=house.createFeatured("Weapon/Sword/Calibur",1,100,500,0,1);
            check(featured.featured()&&featured.seller()==0&&!featured.stock(),"featured bid is system owned without a character");
            var before=db.worldProfile(alice,1,1);
            refused(()->house.operate(alice,1,1,2,List.of(featured.id()),true));check(before.equals(db.worldProfile(alice,1,1)),"even GM game-client cancellation cannot administer featured auctions");
            house.operate(alice,1,1,1,List.of(featured.id(),100),false);house.operate(bob,1,1,1,List.of(featured.id(),200),false);
            check(house.deliveries(featured.id()).stream().filter(d->d.crowns()==100).count()==1,"outbid crowns have one durable refund");
            var filtered=(AuctionState.Page)house.operate(bob,1,1,5,List.of(new SessionDecoder.AuctionSearch(true,null,null,0,true,false),0),false).result();
            check(filtered.listings().stream().anyMatch(a->a.id()==featured.id()),"featured tab returns the system listing");
            expire(db,featured.id());house.settleExpired();house.settleExpired();
            var deliveries=house.deliveries(featured.id());check(deliveries.size()==2&&deliveries.stream().filter(AuctionHouse.Delivery::item).count()==1,"featured settlement delivers once and never pays a nonexistent seller");
            var stock=house.createFeatured("Material/Gel Drop",2,50,50,0,2);
            var crowns=db.worldProfile(bob,1,1).crowns();house.operate(bob,1,1,1,List.of(stock.id(),50),false);
            var bought=(AuctionState.Update)house.operate(alice,1,1,1,List.of(stock.id(),50),false).result();
            check(bought.listing().ended()&&bought.listing().featuredRemaining()==0,"stock decrements atomically and ends at zero");
            house.operate(bob,1,1,1,List.of(stock.id(),50),false);
            check(db.worldProfile(bob,1,1).crowns()==crowns-50&&house.deliveries(stock.id()).size()==2,"exhausted stock cannot spend or deliver again");
            long attachment=house.deliveries(stock.id()).stream().filter(d->d.recipient()==dbWorld(db,bob)).findFirst().orElseThrow().id();
            refused(()->house.mail(alice,1,1,1,List.of(attachment)));house.mail(bob,1,1,1,List.of(attachment));refused(()->house.mail(bob,1,1,1,List.of(attachment)));
            var cancelled=house.createFeatured("Weapon/Sword/Calibur",1,100,0,0,1);house.operate(bob,1,1,1,List.of(cancelled.id(),100),false);house.cancel(cancelled.id());house.cancel(cancelled.id());
            check(house.deliveries(cancelled.id()).size()==1&&house.deliveries(cancelled.id()).getFirst().crowns()==100,"trusted cancellation refunds bids once without minting a return item");
            refused(()->house.createFeatured("missing",1,1,0,0,1));refused(()->house.createFeatured("Weapon/Sword/Calibur",2,1,0,0,1));
            // Preserve a player escrow listing and watch/bid rows across the real predecessor upgrade.
            db.grantItems("Alice","Alice-Knight",alice,-1,items.require("Weapon/Sword/Calibur"),1,-1,-1);
            long sword=db.worldProfile(alice,1,1).items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")).findFirst().orElseThrow().id();
            var player=(AuctionState.Listing)house.operate(alice,1,1,6,List.of(sword,1,100,1000,new SessionDecoder.EnumValue("com.threerings.projectx.auction.data.Duration",0),false),false).result();
            house.operate(bob,1,1,1,List.of(player.id(),150),false);
            refused(()->house.operate(alice,1,1,6,List.of(sword,1,100,1000,new SessionDecoder.EnumValue("com.threerings.projectx.auction.data.Duration",0),true),true));
            // The predecessor has no system listings; remove only this fixture's finished system history.
            downgradeFixture(db);
            try(var channel=FileChannel.open(home.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
                refused(()->new Database(home,false));
            }
            var reopened=new Database(home,false);var recovered=new AuctionHouse(reopened,items,rules);
            var migrated=recovered.overview().getFirst().listing();check(migrated.id()==player.id()&&migrated.bid()==150&&!migrated.featured(),"schema-9 player escrow and bid survive migration");
            recovered.cancel(player.id());recovered.settleExpired();check(recovered.deliveries(player.id()).size()==2,"migrated cancellation returns item and bidder refund atomically");
            check(new Database(home,false).worldProfile(bob,1,1).town().mail().stream().anyMatch(m->m.crowns()==150),"pending refunds survive restart");
            System.out.println("PASS: featured bids/stock, permissions, escrow refunds, mail ownership, idempotent expiry and schema-9 migration");
        }finally{try(var files=Files.walk(home)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static int dbWorld(Database db,int account){try{return db.worldProfile(account,1,1).worldId();}catch(IOException failure){throw new UncheckedIOException(failure);}}
    private static void expire(Database db,long id)throws IOException{db.write(c->{try(var s=c.prepareStatement("UPDATE town_auctions SET expires=0 WHERE id=?")){s.setLong(1,id);s.executeUpdate();}return null;});}
    private static void downgradeFixture(Database db)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.createStatement()){
            s.execute("PRAGMA foreign_keys=OFF");s.execute("BEGIN IMMEDIATE");s.execute("DROP TABLE town_gifts");s.execute("DROP TABLE town_depot_offers");s.execute("DROP TABLE town_energy_offers");
            s.execute("DELETE FROM town_mail WHERE auction IN(SELECT id FROM town_auctions WHERE seller IS NULL)");s.execute("DELETE FROM town_auction_bids WHERE auction IN(SELECT id FROM town_auctions WHERE seller IS NULL)");s.execute("DELETE FROM town_auctions WHERE seller IS NULL");
            s.execute("CREATE TABLE old_auctions(id INTEGER PRIMARY KEY AUTOINCREMENT,seller INTEGER NOT NULL REFERENCES world_characters(world_id),item TEXT NOT NULL,start_bid INTEGER NOT NULL,buy_price INTEGER NOT NULL,bid INTEGER NOT NULL,bidder INTEGER REFERENCES world_characters(world_id),expires INTEGER NOT NULL,ended INTEGER NOT NULL DEFAULT 0,cancelled INTEGER NOT NULL DEFAULT 0,fee INTEGER NOT NULL DEFAULT 0)");
            s.execute("INSERT INTO old_auctions SELECT id,seller,item,start_bid,buy_price,bid,bidder,expires,ended,cancelled,fee FROM town_auctions");s.execute("DROP TABLE town_auctions");s.execute("ALTER TABLE old_auctions RENAME TO town_auctions");s.execute("ALTER TABLE town_mail DROP COLUMN auction");s.execute("PRAGMA user_version=9");s.execute("COMMIT");
        }
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Checked{void run()throws Exception;}
    private static void refused(Checked work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
