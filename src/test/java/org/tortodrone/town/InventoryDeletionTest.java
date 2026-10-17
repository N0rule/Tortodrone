package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Whole-stack Trash, owned recipe learning, and deletion racing a final trade commit. */
public final class InventoryDeletionTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-trash-");
        try{
            var db=new Database(home,false);var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));
            var rules=TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json"));var store=new TownStore(db,items,rules);
            var password=new Passwords.Protection(new byte[16],new byte[32]);int a=db.create("Alice",password,false),b=db.create("Bob",password,false);
            new CharacterStore(db,a,"Alice",items).create(knight("Alice-Knight"));new CharacterStore(db,b,"Bob",items).create(knight("Bob-Knight"));
            var initial=db.worldProfile(a,1,1);
            for(var item:initial.items())if(item.permanent()||item.equippedSlot()!=null)refused(()->store.trash(a,1,1,item.id()));
            check(db.worldProfile(a,1,1).equals(initial),"permanent/equipped starter items preserved");
            db.grantItems("Alice","Alice-Knight",a,-1,items.require("Material/Gel Drop"),7,-1,-1);
            var owned=db.worldProfile(a,1,1);long id=id(owned,"Material/Gel Drop");
            refused(()->store.trash(b,1,1,id));refused(()->store.trash(a,1,99,id));refused(()->store.trash(a,1,1,Long.MAX_VALUE));
            check(db.worldProfile(a,1,1).equals(owned),"foreign/stale-auth/unknown deletion rolls back");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_trash BEFORE UPDATE OF state_revision ON world_characters BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->store.trash(a,1,1,id));check(db.worldProfile(a,1,1).equals(owned),"revision failure restores the whole deleted stack");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_trash");}return null;});
            var bob=db.worldProfile(b,1,1);
            try(var pool=Executors.newFixedThreadPool(2)){
                var gate=new CountDownLatch(1);
                var deleted=pool.submit(()->{gate.await();return attempt(()->store.trash(a,1,1,id));});
                var traded=pool.submit(()->{gate.await();return attempt(()->db.trade(owned,1,Map.of(id,7),0,0,bob,1,Map.of(),0,0));});
                gate.countDown();check(deleted.get(5,TimeUnit.SECONDS)!=traded.get(5,TimeUnit.SECONDS),"delete and trade commit exactly one owner transition");
            }
            var after=db.worldProfile(a,1,1);var recipient=db.worldProfile(b,1,1);
            check(after.items().stream().noneMatch(i->i.id()==id)&&recipient.items().stream().filter(i->i.id()==id).mapToInt(WorldProfile.Item::count).sum()<=7,"stack is wholly deleted or transferred without duplication");
            refused(()->store.trash(a,1,1,id));
            String recipe="Recipe/Armor/Padded Armor Recipe";
            db.grantItems("Alice","Alice-Knight",a,-1,items.require(recipe),2,-1,-1);var beforeLearn=db.worldProfile(a,1,1);long recipeId=id(beforeLearn,recipe);
            refused(()->store.learn(b,1,1,recipeId));refused(()->store.learn(a,1,99,recipeId));
            var learned=store.learn(a,1,1,recipeId).profile();
            check(learned.town().recipes().size()==1&&count(learned,recipe)==count(beforeLearn,recipe)-1,"Use consumes one recipe item");
            refused(()->store.learn(a,1,1,recipeId));check(db.worldProfile(a,1,1).equals(learned),"duplicate Use preserves remaining recipe and revision");
            System.out.println("PASS: Trash permanent/equipped/ownership/auth/rollback/replay/whole stacks, trade race and recipe learning");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static long id(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();}
    private static int count(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Work{void run()throws Exception;}
    private static boolean attempt(Work work)throws Exception{try{work.run();return true;}catch(IOException expected){return false;}}
    private static void refused(Work work)throws Exception{check(!attempt(work),"Expected refusal");}
}
