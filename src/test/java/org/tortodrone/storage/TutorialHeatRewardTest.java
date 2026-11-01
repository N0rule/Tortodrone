package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Collected floor heat and the tutorial receipt must settle in one SQLite transaction. */
public final class TutorialHeatRewardTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-tutorial-heat-");
        try {
            var db=new Database(home,true);var password=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("HeatOwner",password,false),foreign=db.create("HeatOther",password,false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            var characters=new CharacterStore(db,account,"HeatOwner",items);
            var knight=characters.create(new Knight(0,"Heat-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var before=characters.worldProfile(knight.name(),1);
            refused(()->db.completeTutorial(foreign,knight.id(),1,before.worldId(),before.revision(),25,rules));
            refused(()->characters.completeTutorial(knight.id(),2,before,25,rules));
            refused(()->characters.completeTutorial(knight.id(),1,before,Float.NaN,rules));
            refused(()->db.completeTutorial(account,knight.id(),1,before.worldId(),before.revision()+1,25,rules));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_heat_settlement BEFORE INSERT ON metadata WHEN NEW.key LIKE '%.tutorial-completed' BEGIN SELECT RAISE(ABORT,'reject settlement after heat'); END");}return null;});
            refused(()->characters.completeTutorial(knight.id(),1,before,25,rules));
            check(characters.worldProfile(knight.name(),1).equals(before),"failed receipt rolls back gear heat, ownership and revision together");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_heat_settlement");}return null;});
            var start=new CountDownLatch(1);var results=new LinkedBlockingQueue<WorldProfile>();var errors=new LinkedBlockingQueue<Throwable>();
            Runnable settle=()->{try{start.await();results.add(characters.completeTutorial(knight.id(),1,before,25,rules));}catch(Throwable e){errors.add(e);}};
            var one=Thread.ofVirtual().start(settle);var two=Thread.ofVirtual().start(settle);start.countDown();one.join();two.join();
            check(errors.isEmpty()&&results.size()==2,"concurrent settlement retries resolve through one durable receipt: "+errors);
            var after=characters.worldProfile(knight.name(),1);
            check(after.items().stream().filter(i->i.equippedSlot()!=null&&Set.of(0,1,2,6,7).contains(i.equippedSlot())).allMatch(i->i.heatProgress()==.5f),"25 collected heat uses existing native thresholds and five equipped eligible starters");
            check(after.revision()==before.revision()+1&&characters.lastTownScene(knight.id(),1)==217,"gear heat and Camp destination commit with one completion revision");
            characters.completeTutorial(knight.id(),1,before,25,rules);
            check(characters.worldProfile(knight.name(),1).equals(after),"duplicate completion cannot grant heat twice");
            var reopened=new CharacterStore(new Database(home,false),account,"HeatOwner",items);
            check(reopened.worldProfile(knight.name(),1).equals(after)&&reopened.lastTownScene(knight.id(),1)==217,"gear heat and Camp receipt survive reopening storage");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: tutorial floor heat -> native gear thresholds, ownership/auth/revision refusal, transactional rollback, concurrent settlement, duplicate protection and reopen");
    }
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid settlement accepted");}catch(IOException expected){}}
    private interface Action{void run()throws Exception;}
}
