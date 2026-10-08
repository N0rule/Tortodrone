package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Global names are arbitrated in SQLite, including separate tools and migration. */
public final class GlobalKnightNameTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-global-names-");
        try(var accounts=new AccountStore(home)) {
            accounts.create("Alice","alice-password".toCharArray());accounts.create("Bob","bob-password".toCharArray());
            var db=new Database(home,false);var alice=new CharacterStore(db,1,"Alice");
            var bob=new CharacterStore(new Database(home,false),2,"Bob");
            var original=alice.create(choices("World-Knight"));
            check(bob.nameFailure("WORLD-KNIGHT").contains("already in use"),"picker checks names across accounts and case");
            long[] counters=counters(db);var before=db.rosterState(2);int[] publications={0};
            try(var subscription=bob.subscribe(roster->{},roster->publications[0]++)) {
                refused(()->bob.create(choices("world-knight")));
                check(db.rosterState(2).equals(before)&&Arrays.equals(counters,counters(db))&&publications[0]==0,"failed duplicate creation consumes no IDs/revisions and publishes nothing");
            }
            var other=bob.create(choices("Other-Knight"));var owned=db.worldProfile(2,other.id(),1);
            refused(()->bob.update(other.name(),choices("WORLD-KNIGHT")));
            check(db.worldProfile(2,other.id(),1).equals(owned)&&bob.roster().get(0).equals(other),"rename collision preserves owned state and roster");
            var casing=alice.update(original.name(),choices("WORLD-KNIGHT"));
            check(casing.id()==original.id()&&casing.createdAt()==original.createdAt(),"case-only rename retains identity");

            var workers=Executors.newFixedThreadPool(2);
            try {
                var gate=new CyclicBarrier(2);
                var results=workers.invokeAll(List.<Callable<Boolean>>of(
                    ()->claim(home,1,"Alice","Race-Knight",gate),()->claim(home,2,"Bob","RACE-KNIGHT",gate)));
                check(results.get(0).get()!=results.get(1).get(),"simultaneous cross-account claims succeed once");
            }finally{workers.shutdownNow();}
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM knights WHERE name_key='race-knight'")){r.next();return r.getInt(1);}})==1,"one globally owned name persisted");
            // Bypass advisory validation to cover a claim arriving between validation and save.
            var state=db.rosterState(2);var invalid=new ArrayList<>(state.knights());
            int otherIndex=0;while(invalid.get(otherIndex).id()!=other.id())otherIndex++;
            invalid.set(otherIndex,new Knight(other.id(),"World-Knight",other.helm(),other.armor(),null,null,"Blue",1,2,0,other.createdAt()));
            counters=counters(db);
            try{db.saveRoster(2,invalid,state.next(),1,state.revision());throw new AssertionError("Unique index bypassed");}
            catch(Database.KnightNameInUse expected){}
            check(db.rosterState(2).equals(state)&&Arrays.equals(counters,counters(db)),"authoritative conflict rolls back the transaction");
            alice.delete("WORLD-KNIGHT");bob.refresh();bob.create(choices("world-knight"));
            check(alice.nameFailure("World-Knight")!=null,"deleted name can be claimed by another account");

            var migrationKnight=alice.create(choices("Migration-Knight"));

            // Reconstruct schema 5, which allowed duplicate names between accounts.
            db.write(c->{try(var s=c.createStatement()) {
                s.execute("DROP INDEX global_knight_names");s.execute("PRAGMA user_version=5");
                s.execute("UPDATE knights SET name='WORLD-KNIGHT',name_key='world-knight' WHERE account_id=1 AND id="+migrationKnight.id());
            }return null;});
            var savedAlice=db.rosterState(1);var savedBob=db.rosterState(2);
            var profiles=new ArrayList<WorldProfile>();for(var k:savedAlice.knights())profiles.add(db.worldProfile(1,k.id(),1));for(var k:savedBob.knights())profiles.add(db.worldProfile(2,k.id(),1));
            var identity=db.identity("Alice");
            try{new Database(home,false);throw new AssertionError("Conflicting migration accepted");}
            catch(IOException expected){check(expected.getMessage().contains("Duplicate knight name"),"migration explains duplicate-name conflict");}
            check(version(db)==5&&db.rosterState(1).equals(savedAlice)&&db.rosterState(2).equals(savedBob),"failed migration preserves schema and every saved knight");
            db.write(c->{try(var s=c.createStatement()){s.execute("UPDATE knights SET name='Resolved-Knight',name_key='resolved-knight' WHERE account_id=1 AND id="+migrationKnight.id());}return null;});
            var upgraded=new Database(home,false);
            check(version(upgraded)==6,"collision-free schema-5 upgrade succeeds");
            for(var p:profiles)check(upgraded.worldProfile(p.accountId(),p.knightId(),1).equals(p),"upgrade preserves inventory, equipment, balances, revisions and identities");
            check(Arrays.equals(identity.salt(),upgraded.identity("Alice").salt())&&Arrays.equals(identity.verifier(),upgraded.identity("Alice").verifier()),"migration preserves credentials");
            check(upgraded.knightNameInUse("RaCe-KnIgHt",-1,-1),"global names remain reserved after restart");
            System.out.println("PASS: global case-insensitive knight names, picker validation, creation races, rename collisions, atomic refusals, deletion reuse and safe schema-5 migration");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static boolean claim(Path home,int account,String user,String name,CyclicBarrier gate)throws Exception {
        var store=new CharacterStore(new Database(home,false),account,user);gate.await();
        try{store.create(choices(name));return true;}catch(CharacterStore.Refused expected){return false;}
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static long[] counters(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key IN('next-world-id','next-item-id') ORDER BY key")){r.next();long item=r.getLong(1);r.next();return new long[]{item,r.getLong(1)};}});}
    private static int version(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}});}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Duplicate name accepted");}catch(CharacterStore.Refused expected){check(expected.getMessage().contains("already in use"),"useful picker refusal");}}
    @FunctionalInterface private interface Action {void run()throws Exception;}
}
