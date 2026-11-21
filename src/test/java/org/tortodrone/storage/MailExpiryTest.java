package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.core.character.Currency;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.core.town.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.town.AuctionState;
import org.tortodrone.core.town.TownCatalog;

/** Stock 30-day mail retention: purge, return unaccepted player attachments and never recover. */
public final class MailExpiryTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-mail-expiry-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false);
            var items=ItemCatalog.load(ContentFixture.path("items/catalog.json"));
            var rules=TownCatalog.load(ContentFixture.path("town/catalog.json"));
            new CharacterStore(db,alice,"Alice",items).create(knight("Alice-Knight"));
            new CharacterStore(db,bob,"Bob",items).create(knight("Bob-Knight"));
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,20000);
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.ENERGY,200);
            db.grantItems("Alice","Alice-Knight",alice,-1,items.require("Material/Gel Drop"),10,-1,-1);
            var house=new AuctionHouse(db,items,rules);
            int aliceWorld=db.worldProfile(alice,1,1).worldId(),bobWorld=db.worldProfile(bob,1,1).worldId();
            long now=System.currentTimeMillis();
            long gel=db.worldProfile(alice,1,1).items().stream().filter(i->i.config().equals("Material/Gel Drop")).findFirst().orElseThrow().id();

            house.mail(alice,1,1,5,List.of(mail("pending","not yet",Map.of(gel,1),0,0)));
            var pending=delivered(db,bob,"pending");
            age(db,pending.id(),now-29L*86_400_000L);
            check(house.expireMail()==0,"a letter younger than 30 days stays");
            check(mailbox(db,bob).stream().anyMatch(m->m.id()==pending.id()),"the recipient keeps a young letter");

            age(db,pending.id(),now-30L*86_400_000L-1);
            long aliceRevision=db.worldProfile(alice,1,1).revision();
            check(house.expireMail()==1,"a letter older than 30 days is purged");
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==pending.id()),"the original leaves the recipient");
            var returned=letter(db,alice,"pending");
            check(returned.sender().equals("Postmaster")&&returned.nativeType()==3,"the return comes from the postmaster as SYSTEM mail");
            check(returned.items().size()==1&&returned.items().getFirst().config().equals("Material/Gel Drop")&&returned.items().getFirst().count()==1,"the unaccepted item comes back");
            check(returned.crowns()==0&&returned.energy()==0&&!returned.claimed()&&!returned.read(),"the returned letter is unclaimed and unread");
            check(returned.sent()>now-1000,"the return gets a fresh 30-day window");
            check(db.worldProfile(alice,1,1).revision()>aliceRevision,"the sender revision advances");
            check(house.expireMail()==0,"a second sweep does not duplicate the return");

            age(db,returned.id(),now-31L*86_400_000L);
            check(house.expireMail()==1,"a returned letter is purged like any other mail");
            check(mailbox(db,alice).stream().noneMatch(m->m.subject().equals("pending")),"a returned letter is never returned again");

            house.mail(alice,1,1,5,List.of(mail("read","read but not accepted",Map.of(gel,1),0,0)));
            var read=delivered(db,bob,"read");
            house.mail(bob,1,1,11,List.of(new long[]{read.id()}));
            age(db,read.id(),now-31L*86_400_000L);
            check(house.expireMail()==1,"reading a letter does not prevent expiry");
            check(mailbox(db,alice).stream().anyMatch(m->m.subject().equals("read")),"a read but unaccepted attachment still returns");

            house.mail(alice,1,1,5,List.of(mail("claimed","accepted",Map.of(gel,1),5,2)));
            var claimed=delivered(db,bob,"claimed");
            house.mail(bob,1,1,2,List.of(claimed.id()));
            age(db,claimed.id(),now-31L*86_400_000L);
            check(house.expireMail()==1,"a claimed letter is purged");
            check(mailbox(db,alice).stream().noneMatch(m->m.subject().equals("claimed")),"a claimed letter is not returned");

            long auctionMail=insert(db,bobWorld,now-31L*86_400_000L,"%uplink:s.auction_sold","%uplink:m.auction_sold",50,0,null,-1,3);
            long systemMail=insert(db,bobWorld,now-31L*86_400_000L,"%uplink:s.orientation","%uplink:m.orientation",0,0,"[]",-6,3);
            long friendMail=insert(db,bobWorld,now-31L*86_400_000L,"fixture","friend request",0,0,null,aliceWorld,1);
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){
                s.setString(1,"social.request."+friendMail);s.setInt(2,aliceWorld);s.executeUpdate();
            }
            check(house.expireMail()==3,"auction, system and friend mail are purged");
            check(mailbox(db,alice).stream().noneMatch(m->m.subject().startsWith("%uplink:s.auction_sold")),"auction mail is never returned");
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==auctionMail||m.id()==systemMail||m.id()==friendMail),"the expired rows are gone");
            check(!metadata(db,"social.request."+friendMail),"a purged friend request clears its metadata");

            long corrupt=insert(db,bobWorld,now-31L*86_400_000L,"corrupt","bad attachment",0,0,"not-json",aliceWorld,4);
            check(house.expireMail()==1,"a corrupt player attachment is purged instead of blocking the sweep");
            check(mailbox(db,alice).stream().noneMatch(m->m.subject().equals("corrupt")),"a corrupt attachment is not returned");

            long missingSender=insert(db,bobWorld,now-31L*86_400_000L,"missing","sender deleted",10,0,"[]",999999,4);
            check(house.expireMail()==1,"a deleted sender cannot receive a return");
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==missingSender),"the orphaned letter is deleted");

            house.mail(alice,1,1,5,List.of(mail("coins","currency only",Map.of(),15,4)));
            var coins=delivered(db,bob,"coins");
            age(db,coins.id(),now-31L*86_400_000L);
            check(house.expireMail()==1,"an unaccepted currency-only letter expires");
            var returnedCoins=letter(db,alice,"coins");
            check(returnedCoins.crowns()==15&&returnedCoins.energy()==4&&returnedCoins.items().isEmpty(),"crowns and energy return with the letter");
            int beforeClaim=db.worldProfile(alice,1,1).crowns();
            house.mail(alice,1,1,2,List.of(returnedCoins.id()));
            check(db.worldProfile(alice,1,1).crowns()==beforeClaim+15,"the sender can accept a returned letter normally");

            var disguise=items.require("Costume/Helm/Trojan Disguise");
            db.grantItems("Alice","Alice-Knight",alice,-1,disguise,1,-1,-1);
            var timed=db.worldProfile(alice,1,1).items().stream().filter(i->i.config().equals(disguise.config())).findFirst().orElseThrow();
            check(timed.expiresAt()!=null,"the fixture item has a stock deadline");
            house.mail(alice,1,1,5,List.of(mail("timed","temporary disguise",Map.of(timed.id(),1),0,0)));
            var timedMail=delivered(db,bob,"timed");
            age(db,timedMail.id(),now-31L*86_400_000L);
            check(house.expireMail()==1,"a timed attachment letter expires");
            var returnedTimed=letter(db,alice,"timed");
            check(returnedTimed.items().getFirst().expiresAt().equals(timed.expiresAt()),"the return preserves the absolute item deadline");

            house.mail(alice,1,1,5,List.of(mail("swept","scheduled sweep",Map.of(),0,0)));
            var swept=delivered(db,bob,"swept");
            age(db,swept.id(),now-31L*86_400_000L);
            house.settleExpired();
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==swept.id()),"the production sweep entry point purges expired mail");

            System.out.println("PASS: 30-day mail purge, postmaster return, claimed/system/auction/friend cleanup and corrupt/missing-sender handling");
        }finally{try(var files=Files.walk(home)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }

    private static org.tortodrone.core.wire.SentMail mail(String subject,String body,Map<Long,Integer> items,int crowns,int energy){
        return new org.tortodrone.core.wire.SentMail("Bob-Knight",subject,body,items,crowns,energy);
    }
    private static List<AuctionState.Mail> mailbox(Database db,int account)throws IOException{return db.worldProfile(account,1,1).town().mail();}
    private static AuctionState.Mail delivered(Database db,int account,String subject)throws IOException{return mailbox(db,account).stream().filter(m->m.subject().equals(subject)).findFirst().orElseThrow();}
    private static AuctionState.Mail letter(Database db,int account,String subject)throws IOException{return mailbox(db,account).stream().filter(m->m.subject().equals(subject)).findFirst().orElseThrow();}
    private static void age(Database db,long id,long sent)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.prepareStatement("UPDATE town_mail SET sent=? WHERE id=?")){
            s.setLong(1,sent);s.setLong(2,id);s.executeUpdate();
        }
    }
    private static long insert(Database db,int world,long sent,String subject,String body,int crowns,int energy,String attachments,int sender,int type)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,?,?,?) RETURNING id")){
            s.setInt(1,world);s.setLong(2,sent);s.setString(3,subject);s.setString(4,body);s.setInt(5,crowns);s.setInt(6,energy);s.setString(7,attachments);s.setInt(8,sender);s.setInt(9,type);
            try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    private static boolean metadata(Database db,String key)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.prepareStatement("SELECT 1 FROM metadata WHERE key=?")){
            s.setString(1,key);try(var r=s.executeQuery()){return r.next();}
        }
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
}
