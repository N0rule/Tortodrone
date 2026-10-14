package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Disposable current-schema player inventory ownership/stack/binding regression. */
public final class PlayerItemTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-player-items-");
        try(var accounts=new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter())) {
            accounts.create("Alice","alice-password".toCharArray());accounts.create("Other","other-password".toCharArray());
            var db=new Database(home,false);var chars=new CharacterStore(db,1,"Alice");
            chars.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var saved=db.worldProfile(1,1,1);
            final Database store=db;
            var material=new ItemCatalog.Entry("Material/Test",101,"Craft","m.material",1,true,WorldProfile.Slot.OTHER,false,Map.of());
            var p=store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,7,-1,-1);long id=p.items().get(5).id();
            check(p.items().size()==6&&p.items().get(5).count()==7,"stackable grant creates one stack");
            refused(()->store.equip(1,1,1,id,6));refused(()->store.equip(2,1,1,id,null));
            var workers=Executors.newFixedThreadPool(2);
            try{for(var f:workers.invokeAll(List.<Callable<WorldProfile>>of(
                ()->store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,3,-1,-1),
                ()->new Database(home,false).grantItems("Alice","Saved-Knight",1,saved.worldId(),material,4,-1,-1))))f.get();}
            finally{workers.shutdownNow();}
            p=store.worldProfile(1,1,1);check(p.items().size()==6&&p.items().get(5).id()==id&&p.items().get(5).count()==14,"concurrent grants merge without lost quantity or ID changes");
            final var before=p;
            store.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON item_events BEGIN SELECT RAISE(ABORT,'test'); END");}return null;});
            refused(()->store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,2,-1,-1));
            check(store.worldProfile(1,1,1).equals(before),"audit failure rolls back stack merge and revision");
            store.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_audit");}return null;});
            for(var slot:List.of(WorldProfile.Slot.HELM_COSTUME,WorldProfile.Slot.ARMOR_COSTUME,WorldProfile.Slot.SHIELD_COSTUME,WorldProfile.Slot.TRINKET)) {
                var e=new ItemCatalog.Entry("Costume/"+slot,102,"Costume","m.costume",0,false,slot,true,true,Map.of("Helmet",new StarterCatalog.Model("test",Map.of())));
                p=store.grantItems("Alice","Saved-Knight",1,saved.worldId(),e,1,-1,-1);var item=p.items().get(p.items().size()-1);
                check(item.bound()&&item.permanent(),"permanent gear is bound at creation");
                refused(()->store.equip(1,1,1,item.id(),6));
                p=store.equip(1,1,1,item.id(),slot.initialIndex());check(p.equipment()[slot.initialIndex()]==item.id(),"costume/trinket uses verified slot");
                if(slot==WorldProfile.Slot.TRINKET)p=store.equip(1,1,1,item.id(),11);
                p=store.equip(1,1,1,item.id(),null);check(p.items().stream().filter(i->i.id()==item.id()).findFirst().orElseThrow().permanent(),"unequip retains permanent binding");
            }
            check(new Database(home,false).worldProfile(1,1,1).equals(p),"new item fields survive reconnect");
            System.out.println("PASS: persisted stacks, concurrent merging, audit rollback, ownership, costume/trinket slots and permanent binding");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void refused(Operation work)throws Exception{try{work.run();throw new AssertionError("Refusal expected");}catch(IOException expected){}}
    @FunctionalInterface interface Operation {void run()throws Exception;}
}
