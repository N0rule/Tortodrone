package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Real two-owner transactions: rollback, conserved counts/currencies and stale accept races. */
public final class PlayerTradeTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-player-trade-");
        try {
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false);
            var as=new CharacterStore(db,alice,"Alice");var bs=new CharacterStore(db,bob,"Bob");
            as.create(choices("Alice-Knight"));bs.create(choices("Bob-Knight"));
            var catalog=ItemCatalog.load(Path.of("defaults/items/catalog.json"));
            var sword=catalog.require("Weapon/Sword/Calibur");
            var stack=catalog.entries().stream().filter(i->i.grantable()&&i.stackable()&&!i.permanent()).findFirst().orElseThrow();
            db.grantItems("Alice","Alice-Knight",alice,-1,sword,1,-1,-1);
            db.grantItems("Alice","Alice-Knight",alice,-1,stack,10,-1,-1);
            db.grantItems("Bob","Bob-Knight",bob,-1,sword,1,-1,-1);
            db.currency("Alice","Alice-Knight",alice,-1,Currency.CROWNS,100);
            db.currency("Bob","Bob-Knight",bob,-1,Currency.ENERGY,80);
            var a=db.worldProfile(alice,1,1);var b=db.worldProfile(bob,1,1);
            long swordA=nonstarter(a,WorldProfile.Slot.SWORD).id(),swordB=nonstarter(b,WorldProfile.Slot.SWORD).id(),stackA=nonstarter(a,WorldProfile.Slot.OTHER).id();
            final var initialA=a;final var initialB=b;
            refused(()->db.trade(initialA,1,Map.of(swordB,1),0,0,initialB,1,Map.of(),0,0));
            refused(()->db.trade(initialA,1,Map.of(initialA.items().getFirst().id(),1),0,0,initialB,1,Map.of(),0,0));
            refused(()->db.trade(initialA,1,Map.of(stackA,11),0,0,initialB,1,Map.of(),0,0));
            refused(()->db.trade(initialA,1,Map.of(),101,0,initialB,1,Map.of(),0,0));
            check(db.worldProfile(alice,1,1).equals(a)&&db.worldProfile(bob,1,1).equals(b),"invalid ownership/eligibility/count/balance leaves both owners unchanged");
            long counter=counter(db);
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_trade BEFORE INSERT ON currency_events BEGIN SELECT RAISE(ABORT,'test rollback'); END");}return null;});
            refused(()->db.trade(initialA,1,Map.of(swordA,1,stackA,3),40,0,initialB,1,Map.of(swordB,1),0,20));
            check(db.worldProfile(alice,1,1).equals(a)&&db.worldProfile(bob,1,1).equals(b)&&counter(db)==counter,"failed currency audit rolls back both inventories, split ID, balances and revisions");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_trade");}return null;});
            var result=db.trade(a,1,Map.of(swordA,1,stackA,3),40,0,b,1,Map.of(swordB,1),0,20);
            a=result.get(0);b=result.get(1);
            check(a.crowns()==60&&b.crowns()==40&&a.energy()==20&&b.energy()==60,"currency exchange is conserved");
            check(a.items().stream().anyMatch(i->i.id()==swordB)&&b.items().stream().anyMatch(i->i.id()==swordA),"whole item transfer preserves IDs");
            check(nonstarter(a,WorldProfile.Slot.OTHER).count()==7&&nonstarter(b,WorldProfile.Slot.OTHER).count()==3&&nonstarter(b,WorldProfile.Slot.OTHER).id()!=stackA,"partial stack creates one new owned ID and conserves count");
            check(Arrays.equals(a.equipment(),initialA.equipment())&&Arrays.equals(b.equipment(),initialB.equipment()),"equipped kit is preserved");
            refused(()->db.trade(initialA,1,Map.of(swordA,1),0,0,initialB,1,Map.of(),0,0));
            var savedA=a;var savedB=b;
            var pool=Executors.newFixedThreadPool(2);
            try {
                var attempts=pool.invokeAll(List.<Callable<Boolean>>of(
                    ()->attempt(()->db.trade(savedA,1,Map.of(swordB,1),0,0,savedB,1,Map.of(),0,0)),
                    ()->attempt(()->db.trade(savedA,1,Map.of(swordB,1),0,0,savedB,1,Map.of(),0,0))));
                check(attempts.get(0).get()!=attempts.get(1).get(),"concurrent final acceptance commits exactly once");
            } finally {pool.shutdownNow();}
            a=db.worldProfile(alice,1,1);b=db.worldProfile(bob,1,1);
            db.password("Bob",protection,bob);var currentA=a;var currentB=b;
            refused(()->db.trade(currentA,1,Map.of(),0,0,currentB,1,Map.of(),0,0));
            check(new Database(home,false).worldProfile(alice,1,1).equals(a)&&new Database(home,false).worldProfile(bob,1,2).equals(b),"credential revocation refuses trade and persistence remains intact");
            System.out.println("PASS: atomic player exchange, whole/split IDs, ownership/binding, currency conservation, rollback, replay/concurrency and authentication");
        } finally {try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static WorldProfile.Item nonstarter(WorldProfile p,WorldProfile.Slot slot){return p.items().stream().filter(i->!i.starter()&&i.slot()==slot).findFirst().orElseThrow();}
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static long counter(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='next-item-id'")){r.next();return r.getLong(1);}});}
    private static boolean attempt(Operation work)throws Exception{try{work.run();return true;}catch(IOException refused){return false;}}
    private static void refused(Operation work)throws Exception{check(!attempt(work),"operation must be refused");}
    @FunctionalInterface interface Operation{void run()throws Exception;}
}
