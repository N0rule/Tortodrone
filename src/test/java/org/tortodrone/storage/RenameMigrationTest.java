package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Real predecessor layout, deployment exclusion, atomic rename and persisted cooldown. */
public final class RenameMigrationTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-rename-");
        try{
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            var db=new Database(home,false);
            var protection=Passwords.protect("rename-test-password".toCharArray());
            int a=db.create("Alice",protection,false),b=db.create("Bob",protection,false);
            new CharacterStore(db,a,"Alice",items).create(knight("Alice-Knight"));
            new CharacterStore(db,b,"Bob",items).create(knight("Bob-Knight"));
            db.grantItems("Alice","Alice-Knight",a,-1,items.require("Upgrade/Rename Knight"),2,-1,-1);
            var before=db.worldProfile(a,1,1);
            db.write(c->{try(var s=c.createStatement()){org.tortodrone.support.SchemaFixture.depotBefore15(c);s.execute("ALTER TABLE knights DROP COLUMN last_renamed");s.execute("PRAGMA user_version=13");}return null;});
            try(var channel=FileChannel.open(home.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
                refused(()->new Database(home,false));
                check(version(db)==13,"locked predecessor is untouched");
            }
            db=org.tortodrone.support.SchemaFixture.upgrade(home);check(version(db)==15&&db.worldProfile(a,1,1).equals(before),"schema 13 upgrades without losing items or identity");
            var store=new TownStore(db,items,rules);store.canRename(a,1,1);
            refused(()->store.rename(a,1,1,"Bob-Knight"));
            refused(()->store.rename(a,1,1,"Alice-Knight"));
            refused(()->store.rename(a,1,99,"Other-Knight"));
            refused(()->store.rename(b,1,1,"Other-Knight"));
            check(db.worldProfile(a,1,1).equals(before)&&clock(db,a)==0,"failed renames preserve tickets, state revision and clock");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_rename BEFORE DELETE ON inventory_items BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            var rollback=db.worldProfile(a,1,1);
            refused(()->store.rename(a,1,1,"Other-Knight"));
            check(db.worldProfile(a,1,1).equals(rollback)&&clock(db,a)==0,"ticket failure rolls back name, clock and revisions");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_rename");}return null;});
            var result=store.rename(a,1,1,"Other-Knight");
            check(tickets(result.profile())==tickets(rollback)-1,"successful rename consumes exactly one ticket");
            var reopened=new Database(home,false);var again=new TownStore(reopened,items,rules);
            refused(()->again.canRename(a,1,1));refused(()->again.rename(a,1,1,"Third-Knight"));
            check(reopened.worldProfile(a,1,1).equals(result.profile())&&clock(reopened,a)>0,"reopening retains cooldown and committed inventory");
            check(new CharacterStore(reopened,a,"Alice",items).roster().getFirst().name().equals("Other-Knight"),"same account reloads new knight identity");
            try(var accounts=new AccountStore(home,false,items,rules)){
                char[] hash=AccountStore.transform("rename-test-password".toCharArray());
                try{
                    var login=accounts.authenticate("Alice",hash);
                    check(login!=null&&login.characters().worldProfile("Other-Knight",login.authRevision()).worldId()==before.worldId(),"username/password login resolves renamed knight with the same owner identity");
                    check(accounts.authenticate("Other-Knight",hash)==null,"knight rename does not replace the account login name");
                }finally{Arrays.fill(hash,'\0');}
            }
            reopened.password("Alice",protection,a);refused(()->again.canRename(a,1,1));
            int carol=reopened.create("Carol",protection,false);new CharacterStore(reopened,carol,"Carol",items).create(knight("Carol-Knight"));
            reopened.grantItems("Bob","Bob-Knight",b,-1,items.require("Upgrade/Rename Knight"),1,-1,-1);
            reopened.grantItems("Carol","Carol-Knight",carol,-1,items.require("Upgrade/Rename Knight"),1,-1,-1);
            try(var pool=Executors.newFixedThreadPool(2)){
                var gate=new CountDownLatch(1);
                var first=pool.submit(()->{gate.await();return attempt(()->again.rename(b,1,1,"Shared-Knight"));});
                var second=pool.submit(()->{gate.await();return attempt(()->again.rename(carol,1,1,"Shared-Knight"));});
                gate.countDown();check(first.get(5,TimeUnit.SECONDS)!=second.get(5,TimeUnit.SECONDS),"concurrent names commit one winner");
            }
            check(tickets(reopened.worldProfile(b,1,1))+tickets(reopened.worldProfile(carol,1,1))==1&&(clock(reopened,b)>0)!=(clock(reopened,carol)>0),"losing duplicate retains ticket and unused clock");
            reopened.write(c->{try(var s=c.createStatement()){s.execute("PRAGMA user_version=999");}return null;});
            refused(()->new Database(home,false));check(version(reopened)==999,"unknown schema remains untouched");
            System.out.println("PASS: fresh/schema-13/reopen/unknown migrations, lock exclusion, rename ownership/auth/duplicate/ticket rollback and persistent cooldown");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static int tickets(WorldProfile p){return p.items().stream().filter(i->i.config().equals("Upgrade/Rename Knight")).mapToInt(WorldProfile.Item::count).sum();}
    private static int version(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}});}
    private static long clock(Database db,int account)throws IOException{return db.read(c->{try(var s=c.prepareStatement("SELECT last_renamed FROM knights WHERE account_id=? AND id=1")){s.setInt(1,account);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}});}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Work{void run()throws Exception;}
    private static boolean attempt(Work work)throws Exception{try{work.run();return true;}catch(IOException expected){return false;}}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
