package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Transaction tests only; no claim of a completed gameplay playthrough. */
public final class TutorialCompletionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-tutorial-receipt-");
        try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
            accounts.create("TutorialOwner","password-owner".toCharArray());
            accounts.create("TutorialPeer","password-peer".toCharArray());
            var db=new Database(home,false);var owner=new CharacterStore(db,1,"TutorialOwner");var peer=new CharacterStore(db,2,"TutorialPeer");
            var knight=owner.create(knight("Tutorial-Knight"));var peerKnight=peer.create(knight("Tutorial-Peer"));
            var before=owner.worldProfile(knight.name(),1);var peerBefore=peer.worldProfile(peerKnight.name(),1);
            String prefix="world."+before.worldId()+".";
            refused(()->db.completeTutorial(1,knight.id(),2,before.worldId(),before.revision()));
            refused(()->db.completeTutorial(2,peerKnight.id(),1,before.worldId(),peerBefore.revision()));
            refused(()->db.completeTutorial(1,knight.id(),1,before.worldId(),before.revision()+1));
            check(noReceipt(db,prefix)&&owner.lastTownScene(knight.id(),1)==0&&owner.worldProfile(knight.name(),1).equals(before),"bad authentication, ownership and revision cannot alter progress or return location");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_camp BEFORE INSERT ON metadata WHEN NEW.key='"+prefix+"last-town-scene' BEGIN SELECT RAISE(ABORT,'injected camp write failure'); END");}return null;});
            refused(()->owner.completeTutorial(knight.id(),1,before));
            check(noReceipt(db,prefix)&&owner.lastTownScene(knight.id(),1)==0&&owner.worldProfile(knight.name(),1).equals(before),"a failure after receipt insertion rolls back the entire settlement");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_camp");}return null;});
            var start=new CountDownLatch(1);var results=new LinkedBlockingQueue<WorldProfile>();var failures=new LinkedBlockingQueue<Throwable>();
            Runnable settle=()->{try{start.await();results.add(owner.completeTutorial(knight.id(),1,before));}catch(Throwable failure){failures.add(failure);}};
            var one=Thread.ofVirtual().start(settle);var two=Thread.ofVirtual().start(settle);start.countDown();one.join();two.join();
            check(failures.isEmpty()&&results.size()==2,"concurrent authorized settlement retries both resolve safely: "+failures);
            var after=owner.worldProfile(knight.name(),1);
            check(results.stream().allMatch(p->p.revision()==before.revision()+1)&&after.revision()==before.revision()+1&&!noReceipt(db,prefix),"one receipt and one revision increment for concurrent completions");
            check(after.items().equals(before.items())&&after.crowns()==before.crowns()&&after.energy()==before.energy()&&after.progress().equals(before.progress()),"no invented reward, equipment, rank or prestige changes");
            check(owner.lastTownScene(knight.id(),1)==217,"receipt commits with the sourced initial Rescue Camp destination");
            owner.lastTownScene(knight.id(),1,2);owner.completeTutorial(knight.id(),1,before);
            check(owner.lastTownScene(knight.id(),1)==2&&owner.worldProfile(knight.name(),1).revision()==after.revision(),"replayed completion cannot overwrite a later town or increment progress again");
            var reopened=new CharacterStore(new Database(home,false),1,"TutorialOwner");
            check(reopened.worldProfile(knight.name(),1).equals(after)&&reopened.lastTownScene(knight.id(),1)==2,"completion and subsequent return survive fresh storage/session objects");
            check(peer.worldProfile(peerKnight.name(),1).equals(peerBefore),"another knight's state is unchanged");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS: tutorial receipt/return atomic commit, authentication, ownership, stale revision, injected rollback, concurrency, replay and reopen; no rewards invented");
    }
    private static boolean noReceipt(Database db,String prefix)throws IOException{return db.read(c->{try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){s.setString(1,prefix+"tutorial-completed");try(var r=s.executeQuery()){return !r.next();}}});}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Operation {void run()throws Exception;}
    private static void refused(Operation operation)throws Exception {try{operation.run();throw new AssertionError("Unauthorized or failed settlement accepted");}catch(IOException expected){}}
}
