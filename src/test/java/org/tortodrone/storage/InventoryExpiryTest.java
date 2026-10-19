package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Timed box prizes retain their absolute deadline through persistence and ownership changes. */
public final class InventoryExpiryTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-box-expiry-");
        try {
            var items=ItemYaml.load(Path.of("content/items"));var db=new Database(home,true);
            int a=db.create("ExpireAlice",new Passwords.Protection(new byte[16],new byte[32]),false),b=db.create("ExpireBob",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,a,"ExpireAlice",items).create(knight("Expire-Alice"));new CharacterStore(db,b,"ExpireBob",items).create(knight("Expire-Bob"));
            var d=items.require("gremlin_disguise");String scythe=items.require("untimely_scythe").config();
            var representations=items.entries().stream().filter(e->e.expireDays()>0||e.variant()==ItemCatalog.Variant.FURNITURE_ITEM||e.config().equals(scythe)).toList();
            Path roundtrip=home.resolve("catalog.json");ItemCatalog.write(roundtrip,representations);
            check(new HashSet<>(ItemCatalog.load(roundtrip).entries()).equals(new HashSet<>(representations)),"duration, furniture variant and custom color arguments survive catalog round trip");
            long before=System.currentTimeMillis();
            db.grantItems("ExpireAlice","Expire-Alice",-1,-1,d,1,-1,-1);
            var first=db.worldProfile(a,1,1);var costume=find(first,d.config());Long deadline=costume.expiresAt();
            check(d.expireDays()==30&&deadline!=null&&deadline>=before+30L*86_400_000L&&deadline<=System.currentTimeMillis()+30L*86_400_000L,"native 30-day deadline begins at grant");
            check(new Database(home,true).worldProfile(a,1,1).equals(first),"deadline survives database reopen");
            db.trade(first,1,Map.of(costume.id(),1),0,0,db.worldProfile(b,1,1),1,Map.of(),0,0,items);
            var received=db.worldProfile(b,1,1);check(find(received,d.config()).expiresAt().equals(deadline),"trade preserves exact deadline and instance");
            check(AuctionHouse.decode(AuctionHouse.encode(costume)).expiresAt().equals(deadline),"escrow/mail encoding retains deadline");
            String paper="Upgrade/Wrapping Paper/Checker Wrapping (Blue)";db.grantItems("ExpireBob","Expire-Bob",-1,-1,items.require(paper),1,-1,-1);
            var town=new TownStore(db,items,TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            var p=db.worldProfile(b,1,1);var wrapped=town.wrap(b,1,1,find(p,paper).id(),Map.of(costume.id(),1),"Timed prize","Blue").profile();
            var gift=wrapped.items().stream().filter(i->i.gift()!=null).findFirst().orElseThrow();
            check(gift.gift().contents().getFirst().expiresAt().equals(deadline),"gift retains original deadline");
            var unwrapped=town.unwrap(b,1,1,gift.id()).profile();var restored=find(unwrapped,d.config());
            check(restored.expiresAt().equals(deadline),"unwrap does not restart timer");
            long expired=System.currentTimeMillis()-1;
            db.write(c->{InventoryExpiry.save(c,restored.id(),expired);TownStore.touch(c,unwrapped.worldId());return null;});
            try {db.write(c->{InventoryExpiry.prune(c,System.currentTimeMillis());throw new IOException("injected rollback");});throw new AssertionError("rollback expected");}catch(IOException expected){ }
            check(db.read(c->{try(var s=c.prepareStatement("SELECT count(*) FROM inventory_items WHERE item_id=?")){s.setLong(1,restored.id());try(var r=s.executeQuery()){r.next();return r.getInt(1)==1;}}}),"failed transaction rolls back expiration deletion");
            db.worldStates();var after=db.worldProfile(b,1,1);
            check(after.items().stream().noneMatch(i->i.id()==restored.id())&&after.revision()>unwrapped.revision(),"observer maintenance removes expired item and advances revision");
            check(new Database(home,true).worldProfile(b,1,1).equals(after),"expired item remains absent after restart");
            System.out.println("PASS: native timed prize deadlines, reconnect/restart, trade, escrow, gift, rollback and revision-based expiration publication");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static WorldProfile.Item find(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
}
