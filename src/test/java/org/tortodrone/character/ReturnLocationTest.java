package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class ReturnLocationTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-return-location-");
        try{
            var db=new Database(home,false);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("ReturnUser",protection,false);int other=db.create("OtherUser",protection,false);
            var store=new CharacterStore(db,account,"ReturnUser");
            var knight=store.create(new Knight(0,"Return-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            long auth=db.identity("ReturnUser").authRevision();
            var before=store.worldProfile(knight.name(),auth);
            check(store.lastTownScene(knight.id(),auth)==0,"legacy characters default to safe entry");
            store.lastTownScene(knight.id(),auth,100);
            refuse(()->db.lastTownScene(other,knight.id(),auth,200));
            refuse(()->store.lastTownScene(knight.id(),-1,200));
            refuse(()->store.lastTownScene(knight.id(),auth+1,200));
            refuse(()->store.lastTownScene(knight.id(),auth,Integer.MAX_VALUE));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_return BEFORE UPDATE ON metadata WHEN NEW.key GLOB 'world.*.last-town-scene' BEGIN SELECT RAISE(ABORT,'injected rollback'); END");}return null;});
            refuse(()->store.lastTownScene(knight.id(),auth,200));
            check(store.lastTownScene(knight.id(),auth)==100,"failed write rolls back return destination");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_return");}return null;});
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                var tasks=new ArrayList<java.util.concurrent.Future<?>>();
                for(int scene=200;scene<210;scene++){int target=scene;tasks.add(executor.submit(()->{store.lastTownScene(knight.id(),auth,target);return null;}));}
                for(var task:tasks)task.get();
            }
            int saved=store.lastTownScene(knight.id(),auth);
            check(saved>=200&&saved<210,"concurrent writes settle one complete destination");
            var reopened=new Database(home,false);
            check(reopened.lastTownScene(account,knight.id(),auth)==saved,"destination survives database reopening");
            check(store.worldProfile(knight.name(),auth).equals(before),"routing preserves inventory, rank and currency");
            db.password("ReturnUser",protection,account);
            refuse(()->store.lastTownScene(knight.id(),auth));
            refuse(()->store.lastTownScene(knight.id(),auth,300));
            db.delete("ReturnUser",account);
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM metadata WHERE key GLOB 'world.*.last-town-scene'")){r.next();return r.getInt(1);}})==0,"deletion cleans return metadata");
            System.out.println("PASS: durable return location, ownership, revocation, rollback, concurrency, unchanged character state and cleanup");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void refuse(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
    private interface Operation{void run()throws Exception;}
}
