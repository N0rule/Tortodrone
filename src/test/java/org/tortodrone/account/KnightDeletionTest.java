package org.tortodrone.account;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class KnightDeletionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-knight-delete-");
        try(var server=new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter());var manager=new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter())) {
            server.create("Alice","alice-password".toCharArray());server.create("Bob","bob-password".toCharArray());
            var alice=AccountStoreTest.login(server,"Alice","alice-password");
            var bob=AccountStoreTest.login(server,"Bob","bob-password");
            alice.characters().create(AccountStoreTest.choices("Delete-Knight"));
            alice.characters().create(AccountStoreTest.choices("Keep-Knight"));
            bob.characters().create(AccountStoreTest.choices("Other-Knight"));
            var removed=manager.worldProfile("Alice","Delete-Knight");
            var survivor=manager.currency("Alice","Keep-Knight",Currency.CROWNS,123,alice.id(),-1);
            manager.currency("Alice","Delete-Knight",Currency.ENERGY,45,alice.id(),removed.worldId());
            var database=new Database(home,false);
            var snapshot=database.rosterState(alice.id());
            refused(()->manager.deleteKnight("Alice","Delete-Knight",bob.id(),removed.worldId()));
            refused(()->manager.deleteKnight("Alice","Delete-Knight",alice.id(),survivor.worldId()));
            database.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON inventory_items BEGIN SELECT RAISE(ABORT,'fixture failure'); END");}return null;});
            refused(()->manager.deleteKnight("Alice","Delete-Knight",alice.id(),removed.worldId()));
            check(database.rosterState(alice.id()).equals(snapshot)&&server.current(alice),"failed cascade rolls back roster and auth revision");
            check(manager.worldProfile("Alice","Delete-Knight").energy()==45&&count(database,"currency_events",removed.worldId())==1,"failed deletion preserves balances/audit");
            database.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_delete");}return null;});
            manager.deleteKnight("Alice","Delete-Knight",alice.id(),removed.worldId());
            check(!server.current(alice)&&server.current(bob),"only deleted knight's account sessions are revoked");
            check(count(database,"world_characters",removed.worldId())==0&&count(database,"inventory_items",removed.worldId())==0&&count(database,"currency_events",removed.worldId())==0,"associated identity/items/balances/audit removed");
            check(manager.worldProfile("Alice","Keep-Knight").equals(survivor)&&manager.knights("Bob").size()==1,"other knights/accounts unchanged");
            // A cached pre-deletion save must never recreate deleted owned state.
            refused(()->database.saveRoster(alice.id(),snapshot.knights(),snapshot.next(),-1,snapshot.revision()));
            refused(()->alice.characters().create(AccountStoreTest.choices("Stale-Knight"),alice.authRevision()));
            var returning=AccountStoreTest.login(server,"Alice","alice-password");
            check(returning.characters().roster().stream().map(Knight::name).toList().equals(List.of("Keep-Knight")),"cached roster refreshes on reconnect");
            var next=returning.characters().create(AccountStoreTest.choices("Delete-Knight"),returning.authRevision());
            var recreated=manager.worldProfile("Alice",next.name());
            check(next.id()==3&&recreated.worldId()>removed.worldId(),"freed slot uses new roster/world/item identities");
            refused(()->manager.deleteKnight("Alice",next.name(),alice.id(),removed.worldId()));
            check(manager.knights("Alice").size()==2,"stale confirmation cannot delete name replacement");
            try(var restart=new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter())) {
                check(restart.worldProfile("Alice","Keep-Knight").equals(survivor),"surviving data persists on restart");
            }
        }finally{try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("PASS: local knight deletion, identity checks, cascades/rollback, account revocation, stale-roster protection and monotonic replacement");
    }
    private static long count(Database database,String table,int world)throws IOException {
        return database.read(c->{try(var s=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE world_id=?")){s.setInt(1,world);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}});
    }
    private static void refused(Action action)throws Exception {try{action.run();throw new AssertionError("Stale/invalid deletion accepted");}catch(IOException expected) { }}
    @FunctionalInterface interface Action{void run()throws Exception;}
}
