package org.tortodrone.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.town.AuctionState;
import static org.tortodrone.support.TestWire.check;

/** Recipient presence never replaces DB authentication, atomic escrow or offline recovery. */
public final class AuctionRefundTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-auction-refund-");
        try {
            var content=ContentManager.load(Path.of("."));var db=new Database(root,false);var items=content.items();
            int alice=create(db,items,"Alice"),bob=create(db,items,"Bobby");
            int aw=db.worldProfile(alice,1,1).worldId(),bw=db.worldProfile(bob,1,1).worldId();
            var house=new AuctionHouse(db,items,content.town());
            var listing=house.createFeatured("Material/Gel Drop",1,100,0,0,1);
            bid(house,alice,listing.id(),100,Map.of());
            var beforeAlice=db.worldProfile(alice,1,1);var beforeBob=db.worldProfile(bob,1,1);
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_outbid BEFORE INSERT ON metadata WHEN NEW.key LIKE 'auction.outbid.%' BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->bid(house,bob,listing.id(),110,Map.of(aw,1L)));
            check(beforeAlice.equals(db.worldProfile(alice,1,1))&&beforeBob.equals(db.worldProfile(bob,1,1))&&house.deliveries(listing.id()).isEmpty(),"failed notice rolls back both balances and the bid");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_outbid");}return null;});
            bid(house,bob,listing.id(),110,Map.of(aw,1L));
            var refunded=db.worldProfile(alice,1,1);
            check(refunded.crowns()==beforeAlice.crowns()+100&&refunded.town().mail().isEmpty()&&refunded.town().outbids().size()==1,"online refund is direct with durable indicator, no attached mail");
            check(refunded.town().outbids().getFirst().crowns()==100,"notice carries original escrow amount");
            var reopened=new AuctionHouse(new Database(root,false),items,content.town());
            check(db.worldProfile(alice,1,1).town().outbids().equals(refunded.town().outbids()),"disconnect/restart retains credit and unacknowledged indicator");
            var ack=reopened.operate(alice,1,1,4,List.of(),false);
            check(ack.profile().town().outbids().isEmpty()&&((List<?>)ack.result()).size()==1,"My Bids acknowledges the indicator and returns participated listings");
            bid(house,alice,listing.id(),121,Map.of());
            var offline=db.worldProfile(bob,1,1);var refund=offline.town().mail().getFirst();
            check(offline.crowns()==beforeBob.crowns()-110&&refund.crowns()==110&&offline.town().outbids().isEmpty(),"offline refund attaches escrow once rather than directly crediting");
            var claimed=house.mail(bob,1,1,2,List.of(refund.id())).profile();
            refused(()->house.mail(bob,1,1,2,List.of(refund.id())));
            check(claimed.crowns()==beforeBob.crowns()&&db.worldProfile(bob,1,1).equals(claimed),"offline refund claims exactly once");
            bid(house,bob,listing.id(),132,Map.of(aw,999L));
            check(db.worldProfile(alice,1,1).town().mail().stream().anyMatch(m->m.crowns()==121),"stale recipient authentication routes safely to mail");
            var before=db.worldProfile(alice,1,1);refused(()->house.operate(alice,1,999,1,List.of(listing.id(),145),false,Map.of(bw,1L)));
            check(db.worldProfile(alice,1,1).equals(before),"stale bidder cannot debit or refund");
            // Full balances cannot invalidate a legitimate competing bid; escrow stays recoverable in mail.
            db.setCurrency("Bobby","Bobby-Knight",bob,-1,org.tortodrone.character.Currency.CROWNS,Integer.MAX_VALUE);
            bid(house,alice,listing.id(),145,Map.of(bw,1L));
            check(db.worldProfile(bob,1,1).crowns()==Integer.MAX_VALUE&&db.worldProfile(bob,1,1).town().mail().stream().anyMatch(m->!m.claimed()&&m.crowns()==132),"overflow falls back to durable mail");
            var won=db.worldProfile(alice,1,1);bid(house,alice,listing.id(),160,Map.of(aw,1L));
            check(db.worldProfile(alice,1,1).crowns()==won.crowns()-15,"self-raise reserves only the delta and creates no refund");
            house.settleExpired();house.settleExpired();
            check(db.worldProfile(bob,1,1).town().mail().stream().filter(m->!m.claimed()&&m.crowns()==132).count()==1,"repeated sweeps cannot replay outbid delivery");
            System.out.println("PASS: direct/mailed outbid refunds, authenticated presence, reconnect/acknowledgement, overflow fallback, rollback and exactly-once claim");
        }finally{ContentFixture.delete(root);}
    }
    private static int create(Database db,ItemCatalog items,String name)throws Exception {
        int id=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);
        new CharacterStore(db,id,name,items).create(new Knight(0,name+"-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
        db.setCurrency(name,name+"-Knight",id,-1,org.tortodrone.character.Currency.CROWNS,10000);return id;
    }
    private static void bid(AuctionHouse house,int account,long id,int amount,Map<Integer,Long> online)throws IOException {house.operate(account,1,1,1,List.of(id,amount),false,online);}
    private interface Action {void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
