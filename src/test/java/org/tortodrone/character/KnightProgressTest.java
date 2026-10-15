package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class KnightProgressTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-progress-");
        try{
            try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){accounts.create("Alice","alice-password".toCharArray());accounts.create("Bob","bob-password".toCharArray());}
            var db=new Database(home,false);var characters=new CharacterStore(db,1,"Alice");
            var knight=characters.create(new Knight(0,"Rank-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var initial=db.worldProfile(1,knight.id(),1);
            check(initial.progress().equals(KnightProgress.RECRUIT),"existing schema supplies Recruit 1-1 with zero prestige");
            refuse(()->db.progress("Alice",knight.name(),2,initial.worldId(),10,3,45000,"Drakon"));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId()+1,10,3,45000,"Drakon"));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId(),0,3,45000,"Drakon"));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId(),10,3,45000,"Unknown"));
            check(db.worldProfile(1,knight.id(),1).equals(initial),"rejected starter grants leave progression and sprite ownership unchanged");
            WorldProfile updated;
            try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
                updated=accounts.progress("Alice",knight.name(),10,3,45000,1,initial.worldId(),"Drakon");
            }
            check(updated.progress().rankPair().equals("10-3")&&updated.progress().prestigeRank()==5,"Vanguard and native prestige thresholds are represented");
            var starter=updated.town().sprites().getFirst();
            check(updated.town().sprites().size()==1&&starter.config().equals("Drakon")&&starter.level()==0&&starter.heat()==0&&updated.town().equippedSprite()==starter.id(),"testing grant unlocks the native pod ownership gate and equips a newborn starter");
            check(db.progress("Alice",knight.name(),1,initial.worldId(),10,3,45000,"Maskeraith").equals(updated),"repeated or stale dialog submissions cannot grant a second starter");
            check(db.progress("Alice",knight.name(),1,initial.worldId(),null,null,null).equals(updated),"ordinary progression edits preserve the starter grant");
            check(db.worldProfile(1,knight.id(),1,initial).equals(updated)&&updated.revision()>initial.revision(),"saved progress invalidates cached profiles");
            refuse(()->db.progress("Alice",knight.name(),2,initial.worldId(),1,1,0));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId()+1,1,1,0));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId(),0,1,0));
            refuse(()->db.progress("Alice",knight.name(),1,initial.worldId(),1,1,-1));
            check(new Database(home,false).worldProfile(1,knight.id(),1).equals(updated),"restart and rejected writes preserve saved progress");
            check(characters.available().getFirst().rank()==10,"character picker uses the saved mission rank");
            long helm=updated.equipment()[0],armor=updated.equipment()[1],shield=updated.equipment()[2];
            db.equip(1,knight.id(),1,helm,3);db.equip(1,knight.id(),1,armor,4);
            var costumes=db.equip(1,knight.id(),1,shield,15);
            check(costumes.equipment()[0]==0&&costumes.equipment()[1]==0&&costumes.equipment()[2]==0&&costumes.equipment()[3]==helm&&costumes.equipment()[4]==armor&&costumes.equipment()[15]==shield,"ordinary gear can move into native costume slots");
            var model=StarterCatalog.equipped(knight,costumes,ItemCatalog.starter());
            check(model.arguments().containsKey("Helmet")&&model.arguments().containsKey("Armor")&&model.arguments().containsKey("Shield"),"ordinary costume gear remains visible");
            check(costumes.progress().equals(updated.progress()),"equipment writes retain rank and prestige");
            db.deleteKnight("Alice",knight.name(),1,initial.worldId());
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM metadata WHERE key LIKE 'knight.progress.%'")){r.next();return r.getInt(1);}})==0,"knight deletion cleans its progress metadata");
            System.out.println("PASS: persisted rank/prestige, starter sprite grant, idempotency, revisions, ownership, rollback, picker rank and ordinary costume equipment");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void refuse(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid progress update accepted");}catch(IOException expected){}}
    private interface Action{void run()throws Exception;}
}
