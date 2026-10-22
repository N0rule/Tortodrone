package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.town.*;
import static org.tortodrone.support.TestWire.check;

/** A native scene receipt is a global one-shot reward across accounts, retries and restarts. */
public final class SceneRewardTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-scene-reward-");
        try {
            var db=new Database(home,true);var password=new Passwords.Protection(new byte[16],new byte[32]);
            int a=db.create("Alice",password,false),b=db.create("Bob",password,false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            for(var account:Map.of(a,"Alice",b,"Bob").entrySet())new CharacterStore(db,account.getKey(),account.getValue(),items).create(new Knight(0,account.getValue()+"-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var town=new TownStore(db,items,TownCatalog.empty());String epoch=UUID.randomUUID().toString();
            int crowns=db.worldProfile(a,1,1).crowns();int expected=crowns;
            String[] names={"Copper Crown","Copper-Silver Crown","Silver Crown","Silver-Gold Crown","Gold Crown"};int[] values={1,5,10,25,50};
            for(int i=0;i<names.length;i++){
                var paid=town.pickup(a,1,1,epoch+":"+i,"Instant/Crown/"+names[i]);expected+=values[i];check(paid.profile().crowns()==expected,"original crown denomination: "+names[i]);
                check(town.pickup(a,1,1,epoch+":"+i,"Instant/Crown/"+names[i]).profile().equals(paid.profile()),"receipt retry changes neither reward nor revision");
            }
            var before=db.worldProfile(a,1,1);
            refused(()->town.pickup(a,1,999,epoch+":10","Material/Beast Scale"));
            refused(()->town.pickup(b,1,1,epoch+":0","Instant/Crown/Gold Crown"));
            check(db.worldProfile(a,1,1).equals(before),"stale auth and competing ownership cannot change the paid inventory");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER refuse_reward BEFORE INSERT ON inventory_items WHEN NEW.config='Material/Beast Scale' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");}return null;});
            refused(()->town.pickup(a,1,1,epoch+":10","Material/Beast Scale"));
            check(db.worldProfile(a,1,1).equals(before),"failed reward rolls back inventory and revision without burning the receipt");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER refuse_reward");}return null;});
            var reward=town.pickup(a,1,1,epoch+":10","Material/Beast Scale").profile();
            check(reward.items().stream().anyMatch(i->i.config().equals("Material/Beast Scale")&&i.count()==1),"failed reward can retry successfully");
            check(new TownStore(new Database(home,false),items,TownCatalog.empty()).pickup(a,1,1,epoch+":10","Material/Beast Scale").profile().equals(reward),"persisted receipt prevents duplicate reward after reopening");
            String race=epoch+":11";
            try(var executor=Executors.newFixedThreadPool(2)){
                var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
                for(int account:List.of(a,b))tasks.add(executor.submit(()->{start.await();try{town.pickup(account,1,1,race,"Instant/Crown/Gold Crown");return true;}catch(IOException refused){return false;}}));
                start.countDown();int wins=0;for(var task:tasks)if(task.get())wins++;
                check(wins==1,"two simultaneous accounts award exactly one pickup");
            }
            System.out.println("PASS: native crown denominations, persistent items, ownership, rollback, receipt replay/restart and competing claims");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
