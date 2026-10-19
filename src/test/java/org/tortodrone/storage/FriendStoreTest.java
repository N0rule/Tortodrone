package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.town.AuctionState;
import static org.tortodrone.support.TestWire.check;

public final class FriendStoreTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-friends-");
        try {
            var content=ContentManager.load(Path.of("."));var db=new Database(home,true);var secret=new Passwords.Protection(new byte[16],new byte[32]);
            for(String name:List.of("Alice","Bob","Carol")){int account=db.create(name,secret,false);new CharacterStore(db,account,name,content.items()).create(knight(name));}
            var friends=new FriendStore(db);var mail=new AuctionHouse(db,content.items(),content.town());
            friends.session(1,1,1);friends.request(1,1,1,"bOb","Hello","Play together? 🌍\nMeet in Haven.");
            var request=inbox(db,2).getFirst();
            check(request.friendWorld()==1&&request.friendName().equals("Alice")&&request.nativeType()==1&&request.crowns()==0&&request.item()==null,"offline request is native friend mail without rewards");
            check(friends.list(1,1,1).isEmpty()&&friends.list(2,1,1).isEmpty(),"request alone does not add either friend");
            var before=db.worldProfile(2,1,1);
            refused(()->friends.request(1,1,1,"Bob","Again",""));refused(()->friends.request(1,1,1,"Alice","Self",""));
            refused(()->friends.request(1,1,99,"Carol","Stale",""));refused(()->friends.request(1,1,1,"Missing","Missing",""));
            refused(()->friends.request(1,1,1,"Carol","Bad","\ud800"));refused(()->friends.list(1,2,1));
            refused(()->mail.mail(3,1,1,2,List.of(request.id())));
            check(db.worldProfile(2,1,1).equals(before)&&inbox(db,3).isEmpty(),"duplicate/self/auth/ownership/name/text failures leave mail unchanged");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_friend BEFORE INSERT ON metadata WHEN NEW.key='social.friend.1.2' BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->mail.mail(2,1,1,2,List.of(request.id())));
            check(friends.list(1,1,1).isEmpty()&&friends.list(2,1,1).isEmpty()&&db.worldProfile(2,1,1).equals(before),"failed second friendship write rolls back both lists and attachment claim");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_friend");}return null;});
            try(var workers=Executors.newFixedThreadPool(2)) {
                var first=workers.submit(()->accept(mail,2,request.id()));var second=workers.submit(()->accept(mail,2,request.id()));
                check(first.get()!=second.get(),"concurrent attachment acceptance succeeds exactly once");
            }
            check(friends.list(1,1,1).getFirst().world()==2&&friends.list(2,1,1).getFirst().world()==1,"accept adds both sides atomically");
            check(new FriendStore(new Database(home,true)).list(2,1,1).getFirst().lastSession()>0,"friends and session timestamps survive reopen");
            friends.remove(1,1,1,"bOB");refused(()->mail.mail(2,1,1,2,List.of(request.id())));
            check(friends.list(1,1,1).isEmpty()&&friends.list(2,1,1).isEmpty(),"removal is mutual and old accepted mail cannot recreate it");
            friends.request(1,1,1,"Bob","Decline","");long declined=inbox(db,2).getFirst().id();mail.mail(2,1,1,4,List.of(declined));
            check(inbox(db,2).stream().noneMatch(m->m.id()==declined)&&friends.list(2,1,1).isEmpty(),"deleting an unaccepted friend request declines without accepting");
            friends.request(1,1,1,"Bob","Cross one","");friends.request(2,1,1,"Alice","Cross two","");
            long crossed=inbox(db,1).getFirst().id();mail.mail(1,1,1,1,List.of(crossed));
            check(inbox(db,1).isEmpty()&&inbox(db,2).stream().allMatch(AuctionState.Mail::claimed),"accept-and-delete removes current mail and claims crossed requests");
            friends.request(1,1,1,"Carol","Pending","");
            db.password("Alice",secret,1);refused(()->friends.remove(1,1,1,"Bob"));refused(()->friends.request(1,1,1,"Carol","Revoked",""));
            check(friends.snapshot().authentication().get(1)==2L,"batched snapshots carry current authentication revisions");
            db.delete("Alice",1);
            check(friends.list(2,1,1).isEmpty()&&inbox(db,3).isEmpty(),"account deletion clears mutual friendships and its pending requests");
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM metadata WHERE key LIKE 'social.request.%' OR key LIKE 'social.friend.%' OR key='social.seen.1'")){r.next();return r.getInt(1)==0;}}),"deleted social state leaves no orphan metadata");
            System.out.println("PASS: offline friend mail, mutual persistence/removal, decline, ownership/auth, malformed requests, concurrent acceptance, rollback, replay, crossed requests and deletion cleanup");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static List<AuctionState.Mail> inbox(Database db,int account)throws IOException{return db.worldProfile(account,1,-1).town().mail();}
    private static boolean accept(AuctionHouse house,int account,long id)throws IOException {try{house.mail(account,1,1,2,List.of(id));return true;}catch(IOException refused){return false;}}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
