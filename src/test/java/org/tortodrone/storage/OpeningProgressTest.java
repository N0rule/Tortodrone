package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** First-run creation/checkpoints use production storage, without simulating a successful fight. */
public final class OpeningProgressTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-opening-");
        try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
            accounts.create("OpeningOwner","opening-password".toCharArray());
            var db=new Database(home,false);var store=new CharacterStore(db,1,"OpeningOwner");
            var old=store.create(knight("Existing-Knight"));var original=store.worldProfile(old.name(),1);
            store.lastTownScene(old.id(),1,2);
            check(original.progress().openingStage()==-1&&original.progress().progressFlags()==4,"unmarked existing/imported knights keep their completed intro");
            refused(()->store.createForOpening(knight("Bad-Owner"),-1));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_opening BEFORE INSERT ON metadata WHEN NEW.key GLOB 'world.*.opening-stage' BEGIN SELECT RAISE(ABORT,'injected checkpoint failure'); END");}return null;});
            refused(()->store.createForOpening(knight("Fresh-Knight"),1));
            check(store.roster().size()==1&&store.worldProfile(old.name(),1).equals(original),"failed checkpoint creation rolls back roster, inventory and ID allocation");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_opening");}return null;});
            var fresh=store.createForOpening(knight("Fresh-Knight"),1);var before=store.worldProfile(fresh.name(),1);
            check(fresh.id()==2&&before.worldId()==original.worldId()+1&&before.items().size()==5,"atomic fresh creation has exactly five intended starters and no burned IDs");
            check(before.progress().crashSiteIncomplete()&&!before.progress().tutorialComplete()&&before.progress().progressFlags()==0,"fresh knight has neither tutorial nor Haven completion");
            var reopened=new CharacterStore(new Database(home,false),1,"OpeningOwner");
            check(reopened.worldProfile(fresh.name(),1).equals(before),"interrupted opening survives reconnect without inventory or progress mutation");
            refused(()->db.completeTutorial(1,fresh.id(),2,before.worldId(),before.revision()));
            String key="world."+before.worldId()+".opening-stage";
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON metadata WHEN NEW.key='"+key+"' BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");}return null;});
            refused(()->store.completeTutorial(fresh.id(),1,before));
            check(store.worldProfile(fresh.name(),1).equals(before)&&store.lastTownScene(fresh.id(),1)==0,"failed completion cannot publish Camp, receipt or checkpoint");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_checkpoint");}return null;});
            var after=store.completeTutorial(fresh.id(),1,before);
            check(after.progress().openingStage()==1&&after.progress().tutorialComplete()&&after.progress().progressFlags()==0&&store.lastTownScene(fresh.id(),1)==217,"real tutorial settlement advances only to Camp, without inventing Haven access");
            check(after.items().equals(before.items())&&after.progress().rank()==1&&after.progress().subrank()==1&&after.progress().prestige()==0,"no fixed tutorial reward or rank is invented");
            check(store.completeTutorial(fresh.id(),1,before).equals(after),"replayed settlement grants nothing twice");
            check(store.worldProfile(old.name(),1).equals(original)&&store.lastTownScene(old.id(),1)==2,"new onboarding never resets an existing knight or saved location");
            var state=db.rosterState(1);
            refused(()->db.saveRoster(1,state.knights(),state.next(),1,state.revision(),old.id()));
            check(store.worldProfile(old.name(),1).equals(original),"existing identity cannot be relabeled as a new opening knight");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: authenticated atomic first-run creation, rollback, grandfathering, durable Crash Site/Camp checkpoints, exact starters and duplicate settlement");
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Operation{void run()throws Exception;}
    private static void refused(Operation op)throws Exception{try{op.run();throw new AssertionError("Invalid opening operation accepted");}catch(IOException expected){}}
}
