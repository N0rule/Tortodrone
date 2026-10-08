package org.tortodrone.character;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Disposable schema upgrade and inventory fixtures; never touches deployment data. */
public final class WorldProfileTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-world-profile-");
        try {
            Database db = new Database(home,false);
            try (AccountStore accounts = new AccountStore(home)) {
                accounts.create("Alice","alice-password".toCharArray()); accounts.create("Bob","bob-password".toCharArray());
            }
            CharacterStore alice = new CharacterStore(db,1,"Alice"), bob = new CharacterStore(db,2,"Bob");
            alice.create(choices("Alice-Knight")); bob.create(choices("Bob-Knight"));
            // Reconstruct a real v1 deployment with colliding account-local knight IDs.
            db.write(c -> { try (var s = c.createStatement()) {
                s.execute("DROP TABLE item_events");s.execute("DROP TABLE currency_events"); s.execute("DROP TABLE inventory_items"); s.execute("DROP TABLE world_characters");
                s.execute("DELETE FROM metadata WHERE key IN('next-world-id','next-item-id')"); s.execute("PRAGMA user_version=1");
            } return null; });
            db.write(c -> { try(var s=c.createStatement()) { s.execute("UPDATE knights SET helm='unknown-legacy-helm' WHERE account_id=1"); } return null; });
            var savedAlice=db.roster(1); var savedBob=db.roster(2); var salt=db.identity("Alice").salt(); var verifier=db.identity("Alice").verifier();
            // Failed DDL/backfill must roll back tables, assignments and version together.
            db.write(c -> { try (var s=c.createStatement()) { s.execute("CREATE TABLE inventory_items(blocked INTEGER)"); } return null; });
            refused(() -> new Database(home,false));
            check(version(db)==1 && !hasTable(db,"world_characters"),"failed upgrade rolls back added schema and identity counters");
            db.write(c -> { try(var s=c.createStatement()) { s.execute("DROP TABLE inventory_items"); } return null; });
            try (var channel=FileChannel.open(home.resolve("server.lock"),StandardOpenOption.WRITE); var lock=channel.lock()) {
                refused(() -> new Database(home,false)); check(version(db)==1,"migration requires stopped deployment");
            }
            db=new Database(home,false);
            check(version(db)==Database.SCHEMA_VERSION && db.roster(1).equals(savedAlice) && db.roster(2).equals(savedBob),"upgrade preserves every saved appearance field and local ID");
            check(Arrays.equals(salt,db.identity("Alice").salt()) && Arrays.equals(verifier,db.identity("Alice").verifier()),"upgrade preserves password verifiers");
            var a=db.worldProfile(1,1,1); var b=db.worldProfile(2,1,1);
            check(a.worldId()!=b.worldId() && a.knightId()==b.knightId(),"colliding roster IDs receive distinct global identities");
            check(a.items().size()==5 && b.items().size()==5,"exactly one starter kit per existing knight");
            check(a.items().get(0).config().equals("unknown-legacy-helm"),"legacy unknown references remain saved without replacement guesses");
            Set<Long> itemIds=new HashSet<>(); for(var item:a.items())check(itemIds.add(item.id()),"unique item ID"); for(var item:b.items())check(itemIds.add(item.id()),"globally unique item ID");
            check(new Database(home,false).worldProfile(1,1,1).equals(a),"repeat open does not regrant items or change identity");
            refused(() -> new Database(home,false).worldProfile(1,2,1));
            refused(() -> new Database(home,false).worldProfile(1,1,999));
            alice = new CharacterStore(db,1,"Alice");
            var edited=new Knight(0,"Renamed-Knight","Gear/Helm/Spiral Round Helm (Red)","Gear/Armor/Spiral Flak Jacket (Red)","Helm/Top/Headband/Headband","Helm/Back/Plume/Plume","Red",7,4,29,0);
            alice.update("Alice-Knight",edited,1); var updated=alice.worldProfile("renamed-knight",1);
            check(updated.worldId()==a.worldId() && updated.items().stream().map(WorldProfile.Item::id).toList().equals(a.items().stream().map(WorldProfile.Item::id).toList()),"edits preserve global character/item identity");
            check(updated.items().get(0).config().equals(edited.helm()) && updated.items().get(0).accessory().equals(edited.helmAccessory()),"edited creator appearance updates starter gear");
            // A starter insert failure must not consume identity counters or publish a knight.
            Database active=db;
            long counter=counter(db,"next-world-id"), itemCounter=counter(db,"next-item-id");
            db.write(c -> { try(var s=c.createStatement()) { s.execute("CREATE TRIGGER refuse_kit BEFORE INSERT ON inventory_items BEGIN SELECT RAISE(ABORT,'fixture'); END"); } return null; });
            CharacterStore current=alice; refused(() -> current.create(choices("Retry-Knight"),1));
            check(alice.roster().size()==1 && db.roster(1).size()==1 && counter(db,"next-world-id")==counter && counter(db,"next-item-id")==itemCounter,"failed kit rolls back roster, kit and both counters");
            db.write(c -> { try(var s=c.createStatement()) { s.execute("DROP TRIGGER refuse_kit"); } return null; });
            Knight retry=alice.create(choices("Retry-Knight"),1); var retryProfile=db.worldProfile(1,retry.id(),1);
            check(retryProfile.worldId()==counter && retryProfile.items().get(0).id()==itemCounter,"retry uses rolled-back IDs");
            long exhaustedCounter=counter(db,"next-world-id");
            db.write(c -> { try(var s=c.createStatement()) {s.execute("UPDATE metadata SET value=2147483647 WHERE key='next-world-id'");}return null; });
            refused(() -> current.create(choices("Exhausted-Knight"),1));
            check(alice.roster().size()==2 && db.roster(1).size()==2,"exhaustion cannot publish a character without identity");
            db.write(c -> {try(var s=c.prepareStatement("UPDATE metadata SET value=? WHERE key='next-world-id'")){s.setLong(1,exhaustedCounter);s.executeUpdate();}return null;});
            alice.delete("Renamed-Knight",1); refused(() -> active.worldProfile(1,1,1));
            check(count(db,"inventory_items")==10,"knight deletion cascades only its own inventory");
            alice.create(choices("Next-Knight"),1); check(alice.worldProfile("Next-Knight",1).worldId()>retryProfile.worldId(),"deleted world IDs never recycled");
            check(db.worldProfile(2,1,1).equals(b),"another account's identity and items are unchanged");
            try(AccountStore accounts=new AccountStore(home)) {
                accounts.setPassword("Alice","new-password".toCharArray()); refused(() -> active.worldProfile(1,retry.id(),1));
                accounts.delete("Alice");
            }
            check(count(db,"inventory_items")==5 && count(db,"world_characters")==1,"account deletion cascades only owned profiles/items");
            // Concurrent accounts allocate from one serialized durable sequence.
            try(AccountStore accounts=new AccountStore(home)) { accounts.create("Carol","carol-password".toCharArray()); accounts.create("David","david-password".toCharArray()); }
            var carol=new CharacterStore(db,3,"Carol"); var david=new CharacterStore(db,4,"David");
            var workers=Executors.newFixedThreadPool(2);
            try {
                workers.invokeAll(List.<Callable<Knight>>of(() -> carol.create(choices("Carol-Knight")),()->david.create(choices("David-Knight")))).forEach(f -> { try { f.get(); } catch(Exception e) { throw new AssertionError(e); } });
                var c=db.worldProfile(3,1,1);var d=db.worldProfile(4,1,1);
                check(c.worldId()!=d.worldId() && Collections.disjoint(c.items().stream().map(WorldProfile.Item::id).toList(),d.items().stream().map(WorldProfile.Item::id).toList()),"concurrent accounts cannot collide");
                db.write(connection -> {try(var statement=connection.prepareStatement("DELETE FROM inventory_items WHERE item_id=?")){statement.setLong(1,c.items().get(0).id());statement.executeUpdate();}return null;});
                refused(() -> active.worldProfile(3,1,1));
                new Database(home,false);
                check(count(db,"inventory_items")==14,"incomplete inventory is reported without silently regranting items");
            } finally { workers.shutdownNow(); }
            System.out.println("PASS: v1 migration, lock/rollback, global character and item IDs, one-time kits, edits, restart, ownership, auth revisions, cascades and concurrent allocation");
        } finally { try(var paths=Files.walk(home)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p); } }
    }
    private static Knight choices(String name) { return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0); }
    private static boolean hasTable(Database db,String name)throws IOException { return db.read(c->{try(var s=c.prepareStatement("SELECT name FROM sqlite_master WHERE name=?")){s.setString(1,name);try(var r=s.executeQuery()){return r.next();}}}); }
    private static int version(Database db)throws IOException {return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}});}
    private static long count(Database db,String table)throws IOException {return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}});}
    private static long counter(Database db,String key)throws IOException {return db.read(c->{try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){s.setString(1,key);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}});}
    private static void refused(Operation operation)throws Exception {try{operation.run();throw new AssertionError("Invalid operation accepted");}catch(IOException expected){}}
    @FunctionalInterface interface Operation {void run()throws Exception;}
}
