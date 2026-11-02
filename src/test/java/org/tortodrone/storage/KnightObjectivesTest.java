package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

public final class KnightObjectivesTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-objectives-");
        try {
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("StoryOwner",protection,false),other=db.create("StoryPeer",protection,false);
            var owner=new CharacterStore(db,account,"StoryOwner");var peer=new CharacterStore(db,other,"StoryPeer");
            var knight=owner.create(knight("Story-Knight"));var peerKnight=peer.create(knight("Story-Peer"));
            var before=owner.worldProfile(knight.name(),1);var peerBefore=peer.worldProfile(peerKnight.name(),1);
            var memory=List.of(new KnightMemory("talkedGreta2",true));var objectives=List.of(new KnightObjective("findRhendon","m.find_rhendon"));
            for(long auth:new long[]{-1,0,2})refused(()->db.knightStory(account,knight.id(),auth,before.worldId(),before.revision(),memory,objectives));
            refused(()->db.knightStory(other,peerKnight.id(),1,before.worldId(),peerBefore.revision(),memory,objectives));
            refused(()->db.knightStory(account,knight.id(),1,before.worldId(),before.revision()+1,memory,objectives));
            String prefix="world."+before.worldId()+".objective.";
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_objective BEFORE INSERT ON metadata WHEN NEW.key='"+prefix+"findRhendon' BEGIN SELECT RAISE(ABORT,'injected objective failure'); END");}return null;});
            refused(()->owner.knightStory(knight.id(),1,before,memory,objectives));
            check(owner.knightMemories(knight.id(),1).isEmpty()&&owner.knightObjectives(knight.id(),1).isEmpty()&&owner.worldProfile(knight.name(),1).equals(before),"objective failure rolls back the accompanying memory, counter and revision");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_objective");}return null;});
            var first=owner.knightStory(knight.id(),1,before,memory,objectives);
            check(first.memories().equals(Set.of("talkedGreta2"))&&first.objectives().get("findRhendon").index()==0&&first.profile().revision()==before.revision()+1,"Greta's authored memory and objective commit exactly once together");
            check(owner.knightStory(knight.id(),1,first.profile(),memory,objectives).equals(first),"duplicate response makes no allocation or revision change");
            var updated=owner.knightStory(knight.id(),1,first.profile(),List.of(),List.of(new KnightObjective("findRhendon","m.find_rhendon_updated")));
            check(updated.objectives().get("findRhendon").index()==0,"updating a named objective retains its native key");
            var cleared=owner.knightStory(knight.id(),1,updated.profile(),List.of(),List.of(new KnightObjective("findRhendon",null)));
            check(cleared.objectives().isEmpty(),"native null configuration removes only the named objective");
            var second=owner.knightStory(knight.id(),1,cleared.profile(),List.of(),objectives);
            check(second.objectives().get("findRhendon").index()==1,"removed objective keys are never accidentally reused");
            var reopened=new CharacterStore(new Database(home,false),account,"StoryOwner");
            check(reopened.knightObjectives(knight.id(),1).equals(second.objectives())&&reopened.knightMemories(knight.id(),1).equals(first.memories()),"objectives and memories survive reopened storage");
            check(peer.knightObjectives(peerKnight.id(),1).isEmpty()&&peer.worldProfile(peerKnight.name(),1).equals(peerBefore),"another account remains unchanged");
            check(second.profile().items().equals(before.items())&&second.profile().progress().equals(before.progress()),"no invented reward or mission/rank completion");
            var failures=new java.util.concurrent.LinkedBlockingQueue<Throwable>();var gate=new java.util.concurrent.CountDownLatch(1);
            var current=second.profile();Runnable write=()->{try{gate.await();owner.knightStory(knight.id(),1,current,memory,objectives);}catch(Throwable e){failures.add(e);}};
            var a=Thread.ofVirtual().start(write);var b=Thread.ofVirtual().start(write);gate.countDown();a.join();b.join();check(failures.isEmpty(),"concurrent idempotent requests keep one objective");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS: atomic memory/objective state, ownership/auth/revision, injected rollback, stable keys, replay, concurrent idempotence and reconnect");
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Operation{void run()throws Exception;}
    private static void refused(Operation op)throws Exception{try{op.run();throw new AssertionError("Invalid story operation accepted");}catch(IOException expected){}}
}
