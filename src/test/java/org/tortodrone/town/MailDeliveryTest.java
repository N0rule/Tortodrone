package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Disposable player-to-player mail: fees, escrow, ownership, bounce and schema-16 migration. */
public final class MailDeliveryTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-mail-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false),gm=db.create("Gm",protection,true);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            new CharacterStore(db,alice,"Alice",items).create(choices("Alice-Knight"));
            new CharacterStore(db,bob,"Bob",items).create(choices("Bob-Knight"));
            new CharacterStore(db,gm,"Gm",items).create(choices("Gm-Knight"));
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,10000);
            db.setCurrency("Bob","Bob-Knight",bob,-1,Currency.CROWNS,10000);
            db.setCurrency("Gm","Gm-Knight",gm,-1,Currency.CROWNS,10000);
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.ENERGY,100);
            db.grantItems("Alice","Alice-Knight",alice,-1,items.require("Material/Gel Drop"),5,-1,-1);
            db.grantItems("Alice","Alice-Knight",alice,-1,items.require("Weapon/Sword/Calibur"),1,-1,-1);
            var house=new AuctionHouse(db,items,rules);
            var aliceProfile=db.worldProfile(alice,1,1);
            long gel=aliceProfile.items().stream().filter(i->i.config().equals("Material/Gel Drop")).findFirst().orElseThrow().id();
            long sword=aliceProfile.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")&&!i.starter()).findFirst().orElseThrow().id();
            int crowns=aliceProfile.crowns();

            house.mail(alice,1,1,5,List.of(mail("Bob-Knight","hello","a plain letter",Map.of(),0,0)));
            check(db.worldProfile(alice,1,1).crowns()==crowns-AuctionHouse.MAIL_FEE,"a plain letter costs the 25-crown mail fee");
            var letter=mailbox(db,bob).stream().filter(m->m.subject().equals("hello")).findFirst().orElseThrow();
            check(letter.sender().equals("Alice-Knight")&&letter.nativeType()==4&&letter.items().isEmpty()&&!letter.read(),"recipient sees the knight sender and USER mail type");
            house.mail(bob,1,1,11,List.of(new long[]{letter.id()}));
            check(mailbox(db,bob).stream().filter(m->m.id()==letter.id()).findFirst().orElseThrow().read(),"set-read records the open letter");
            house.mail(bob,1,1,4,List.of(letter.id()));
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==letter.id()),"a bare letter deletes without a separate accept step");

            int beforeCrowns=db.worldProfile(alice,1,1).crowns(),beforeEnergy=db.worldProfile(alice,1,1).energy();
            house.mail(alice,1,1,5,List.of(mail("Bob-Knight","parcel","an item, crowns and energy",Map.of(gel,3,sword,1),40,20)));
            var afterSend=db.worldProfile(alice,1,1);
            check(afterSend.crowns()==beforeCrowns-AuctionHouse.MAIL_FEE-AuctionHouse.MAIL_ATTACH_FEE-40,"an attachment adds 75 crowns plus the attached crowns");
            check(afterSend.energy()==beforeEnergy-20,"attached energy leaves the sender exactly once");
            check(itemCount(afterSend,"Material/Gel Drop")==2&&itemCount(afterSend,"Weapon/Sword/Calibur")==0,"attached item units leave the sender");
            var parcel=mailbox(db,bob).stream().filter(m->m.subject().equals("parcel")).findFirst().orElseThrow();
            check(parcel.items().size()==2&&parcel.crowns()==40&&parcel.energy()==20&&parcel.nativeType()==4,"the parcel carries both stacks, crowns and energy");
            refused(()->house.mail(gm,1,1,2,List.of(parcel.id())));
            refused(()->house.mail(bob,1,1,4,List.of(parcel.id())));
            check(mailbox(db,bob).stream().anyMatch(m->m.id()==parcel.id()),"an unclaimed attachment cannot be deleted");

            int bobCrowns=db.worldProfile(bob,1,1).crowns(),bobEnergy=db.worldProfile(bob,1,1).energy();
            house.mail(bob,1,1,2,List.of(parcel.id()));
            var bobProfile=db.worldProfile(bob,1,1);
            check(bobProfile.crowns()==bobCrowns+40&&bobProfile.energy()==bobEnergy+20,"claim credits crowns and energy without duplicating the fee");
            check(itemCount(bobProfile,"Material/Gel Drop")==3&&itemCount(bobProfile,"Weapon/Sword/Calibur")==1,"claim materializes every attached stack");
            check(mailbox(db,bob).stream().filter(m->m.id()==parcel.id()).findFirst().orElseThrow().claimed(),"claim is recorded durably");
            refused(()->house.mail(bob,1,1,2,List.of(parcel.id())));
            check(db.worldProfile(bob,1,1).crowns()==bobProfile.crowns(),"a second claim pays nothing");
            house.mail(bob,1,1,4,List.of(parcel.id()));
            check(mailbox(db,bob).stream().noneMatch(m->m.id()==parcel.id()),"delete removes the claimed parcel");
            var wire=new org.tortodrone.support.TestWire();var out=wire.frame(new SessionCodec().townResult(1001,(short)7,List.of(parcel),null));
            check(wire.type(out).equals(org.tortodrone.support.TestWire.NET+"EventNotification")&&out.readShort()==-1,"mail result notification envelope");
            check(wire.type(out).equals(org.tortodrone.support.TestWire.DOB+"InvocationResponseEvent")&&out.readInt()==1001&&out.readShort()==7&&out.readByte()==1,"mail result callback");
            check(out.readBoolean()&&out.readInt()==1&&wire.type(out).equals("java.util.ArrayList")&&out.readInt()==1,"one mail result");
            check(wire.type(out).equals("com.threerings.projectx.uplink.data.ReceivedMail")&&out.readLong()==parcel.id(),"native received-mail identity");
            check(wire.object(out).equals("Alice-Knight")&&out.readLong()==parcel.sent(),"sender name and timestamp");
            check(org.tortodrone.support.TestWire.string(out).equals("parcel")&&org.tortodrone.support.TestWire.string(out).equals("an item, crowns and energy"),"mail text");
            check(wire.type(out).equals("com.threerings.projectx.uplink.data.MultiAttachment")&&out.readBoolean()&&out.readInt()==parcel.items().size(),"multi-attachment envelope");
            for(var item:parcel.items()){
                @SuppressWarnings("unchecked") var reference=(Map<String,Object>)wire.object(out);
                check(reference.get("_name").equals(item.config())&&out.readInt()==item.count(),"each attachment keeps its config and units");
            }
            check(out.readInt()==40&&out.readInt()==20,"crowns and energy");
            check(wire.type(out).equals("com.threerings.projectx.uplink.data.MailType")&&out.readByte()==4,"USER mail type");
            check(out.readByte()==1,"unread and unclaimed flags");
            org.tortodrone.support.TestWire.end(out);

            int gmCrowns=db.worldProfile(gm,1,1).crowns(),aliceCrowns=db.worldProfile(alice,1,1).crowns(),gelBefore=itemCount(db.worldProfile(alice,1,1),"Material/Gel Drop");
            house.mail(alice,1,1,5,List.of(mail("Gm-Knight","probe to a gm","returned",Map.of(gel,1),5,0)));
            var bounced=mailbox(db,alice).stream().filter(m->m.subject().startsWith("%uplink:s.bounced|Gm-Knight|probe to a gm")).findFirst().orElseThrow();
            check(bounced.sender().equals(" 2")&&bounced.nativeType()==3&&bounced.body().equals("%uplink:m.bounced|returned"),"GM mail bounces from the postmaster without an attachment");
            check(db.worldProfile(alice,1,1).crowns()==aliceCrowns&&itemCount(db.worldProfile(alice,1,1),"Material/Gel Drop")==gelBefore&&db.worldProfile(gm,1,1).crowns()==gmCrowns,"a bounce charges neither fee, crowns nor items");

            var snapshot=db.worldProfile(alice,1,1);
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,10);
            refused(()->house.mail(alice,1,1,5,List.of(mail("Bob-Knight","poor","no crowns",Map.of(),0,0))));
            check(db.worldProfile(alice,1,1).crowns()==10,"insufficient crowns roll the whole send back");
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,5000);
            db.setCurrency("Alice","Alice-Knight",alice,-1,Currency.ENERGY,5);
            refused(()->house.mail(alice,1,1,5,List.of(mail("Bob-Knight","poor energy","no energy",Map.of(),0,10))));
            check(db.worldProfile(alice,1,1).crowns()==5000&&db.worldProfile(alice,1,1).energy()==5,"insufficient energy rolls the whole send back");
            var starter=db.worldProfile(alice,1,1).items().stream().filter(i->i.starter()).findFirst().orElseThrow();
            refused(()->house.mail(alice,1,1,5,List.of(mail("Bob-Knight","starter","bound",Map.of(starter.id(),1),0,0))));
            refused(()->house.mail(alice,1,1,5,List.of(mail("Bob-Knight","too many","units",Map.of(gel,15),0,0))));
            refused(()->house.mail(alice,1,1,5,List.of(mail("Nobody","unknown","recipient",Map.of(),0,0))));
            refused(()->house.mail(alice,1,1,5,List.of(mail("Bob-Knight","x".repeat(51),"long subject",Map.of(),0,0))));
            check(db.worldProfile(alice,1,1).items().equals(snapshot.items())||itemCount(db.worldProfile(alice,1,1),"Material/Gel Drop")==gelBefore,"refused sends never consume an item");

            int preserved=mailbox(db,bob).size();
            downgradeTo15(db,home);
            var upgraded=org.tortodrone.support.SchemaFixture.upgrade(home);
            check(version(upgraded)==Database.SCHEMA_VERSION,"schema 15 upgrades to the current schema");
            check(hasMailColumn(upgraded,"sender")&&hasMailColumn(upgraded,"energy")&&hasMailColumn(upgraded,"attachments")&&hasMailColumn(upgraded,"mail_type"),"the mail migration adds sender, energy, attachments and type");
            check(mailbox(upgraded,bob).size()==preserved,"pre-migration mail survives the candidate export");
            var recovered=new AuctionHouse(upgraded,items,rules);
            recovered.mail(bob,1,1,5,List.of(mail("Alice-Knight","after upgrade","still deliverable",Map.of(),0,0)));
            check(mailbox(upgraded,alice).stream().anyMatch(m->m.subject().equals("after upgrade")),"mail works on the migrated database");
            System.out.println("PASS: player mail fees, multi-item/crown/energy escrow, ownership, GM bounce, rollback and schema-16 migration");
        }finally{try(var files=Files.walk(home)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }

    private static SessionDecoder.SentMail mail(String recipient,String subject,String body,Map<Long,Integer> items,int crowns,int energy){
        return new SessionDecoder.SentMail(recipient,subject,body,items,crowns,energy);
    }
    private static List<AuctionState.Mail> mailbox(Database db,int account)throws IOException{
        return db.worldProfile(account,1,1).town().mail();
    }
    private static int itemCount(WorldProfile profile,String config){
        return profile.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();
    }
    private static void downgradeTo15(Database db,Path home)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.file());var s=c.createStatement()){
            s.execute("PRAGMA foreign_keys=OFF");s.execute("BEGIN IMMEDIATE");
            s.execute("ALTER TABLE town_mail RENAME TO town_mail_v15");
            s.execute("CREATE TABLE town_mail(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,sent INTEGER NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,crowns INTEGER NOT NULL DEFAULT 0,item TEXT,claimed INTEGER NOT NULL DEFAULT 0,is_read INTEGER NOT NULL DEFAULT 0,auction INTEGER REFERENCES town_auctions(id))");
            s.execute("INSERT INTO town_mail(id,world_id,sent,subject,body,crowns,item,claimed,is_read,auction) SELECT id,world_id,sent,subject,body,crowns,item,claimed,is_read,auction FROM town_mail_v15");
            s.execute("DROP TABLE town_mail_v15");
            s.execute("PRAGMA user_version=15");s.execute("COMMIT");
        }
    }
    private static int version(Database db)throws IOException{
        return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}});
    }
    private static boolean hasMailColumn(Database db,String name)throws IOException{
        return db.read(c->{try(var s=c.prepareStatement("SELECT 1 FROM pragma_table_info('town_mail') WHERE name=?")){s.setString(1,name);try(var r=s.executeQuery()){return r.next();}}});
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Checked{void run()throws Exception;}
    private static void refused(Checked work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
