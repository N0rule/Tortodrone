package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

public final class KnightMemoriesTest {
    private static final String INTRO="talkto_rhendon_icon";
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-knight-memories-");
        try {
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("MemoryOwner",protection,false),other=db.create("MemoryPeer",protection,false);
            var owner=new CharacterStore(db,account,"MemoryOwner");var peer=new CharacterStore(db,other,"MemoryPeer");
            var knight=owner.create(knight("Memory-Knight"));var peerKnight=peer.create(knight("Memory-Peer"));
            var initial=owner.worldProfile(knight.name(),1);var peerInitial=peer.worldProfile(peerKnight.name(),1);
            check(owner.knightMemories(knight.id(),1).isEmpty(),"no invented initial memories");
            for(long auth:new long[]{-1,0,2}){
                refused(()->owner.knightMemories(knight.id(),auth));
                refused(()->db.knightMemories(account,knight.id(),auth,initial.worldId(),initial.revision(),List.of(set(INTRO))));
            }
            refused(()->db.knightMemories(other,peerKnight.id(),1,initial.worldId(),peerInitial.revision(),List.of(set(INTRO))));
            refused(()->db.knightMemories(account,knight.id(),1,initial.worldId(),initial.revision()+1,List.of(set(INTRO))));
            var first=owner.knightMemories(knight.id(),1,initial,List.of(set(INTRO)));
            check(first.memories().equals(Set.of(INTRO))&&first.profile().revision()==initial.revision()+1,"sourced memory and revision commit together");
            var duplicate=owner.knightMemories(knight.id(),1,first.profile(),List.of(set(INTRO)));
            check(duplicate.equals(first),"repeated setting has no extra write or revision");
            var ordered=owner.knightMemories(knight.id(),1,first.profile(),List.of(new KnightMemory(INTRO,false),set(INTRO)));
            check(ordered.equals(first),"authored erase/set sequence settles its net state atomically");
            String prefix="world."+initial.worldId()+".memory.";
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_memory BEFORE INSERT ON metadata WHEN NEW.key='"+prefix+"rollback_second' BEGIN SELECT RAISE(ABORT,'injected memory failure'); END");}return null;});
            refused(()->owner.knightMemories(knight.id(),1,first.profile(),List.of(new KnightMemory(INTRO,false),set("rollback_first"),set("rollback_second"))));
            check(owner.knightMemories(knight.id(),1).equals(Set.of(INTRO))&&owner.worldProfile(knight.name(),1).equals(first.profile()),"failed insert restores prior erased memory, all additions and world revision");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_memory");}return null;});
            var start=new CountDownLatch(1);var successes=new LinkedBlockingQueue<String>();var refusals=new LinkedBlockingQueue<String>();var errors=new LinkedBlockingQueue<Throwable>();
            var workers=new ArrayList<Thread>();
            for(String name:List.of("concurrent_a","concurrent_b"))workers.add(Thread.ofVirtual().start(()->{
                try{start.await();owner.knightMemories(knight.id(),1,first.profile(),List.of(set(name)));successes.add(name);}
                catch(IOException expected){refusals.add(name);}catch(Throwable unexpected){errors.add(unexpected);}
            }));
            start.countDown();for(var worker:workers)worker.join();
            check(errors.isEmpty()&&successes.size()==1&&refusals.size()==1,"concurrent stale writers cannot lose a memory: "+errors);
            var current=owner.worldProfile(knight.name(),1);var retried=owner.knightMemories(knight.id(),1,current,List.of(set(refusals.remove())));
            check(retried.memories().equals(Set.of(INTRO,"concurrent_a","concurrent_b")),"retry with a fresh revision preserves both writers");
            var cleared=owner.knightMemories(knight.id(),1,retried.profile(),List.of(new KnightMemory(INTRO,false)));
            var reopened=new CharacterStore(new Database(home,false),account,"MemoryOwner");
            check(reopened.knightMemories(knight.id(),1).equals(Set.of("concurrent_a","concurrent_b"))&&reopened.worldProfile(knight.name(),1).equals(cleared.profile()),"cleared memory stays removed across fresh storage objects");
            check(cleared.profile().items().equals(initial.items())&&cleared.profile().progress().equals(initial.progress())&&cleared.profile().crowns()==initial.crowns()&&cleared.profile().energy()==initial.energy(),"memory actions grant no inventory, rank or currency");
            check(peer.knightMemories(peerKnight.id(),1).isEmpty()&&peer.worldProfile(peerKnight.name(),1).equals(peerInitial),"peer knight is untouched");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS: KNIGHT memory set/erase, native name validation, auth/ownership/revision, idempotency, ordered batch, injected rollback, concurrent writers and reopen; no rewards");
    }
    private static KnightMemory set(String name){return new KnightMemory(name,true);}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Operation{void run()throws Exception;}
    private static void refused(Operation operation)throws Exception {try{operation.run();throw new AssertionError("Invalid memory write accepted");}catch(IOException expected){}}
}
