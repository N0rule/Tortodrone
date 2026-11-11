package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Settlement/storage only. Direct terminal calls here do not establish playable floors. */
public final class OpeningMissionStoreTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));Path home=Files.createTempDirectory("tortodrone-opening-missions-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("MissionOwner",protection,false),foreign=db.create("MissionPeer",protection,false);
            var owner=new CharacterStore(db,account,"MissionOwner",content.items());var peer=new CharacterStore(db,foreign,"MissionPeer",content.items());
            var knight=owner.createForOpening(knight("Mission-Knight"),1);var other=peer.createForOpening(knight("Mission-Peer"),1);
            var store=owner.openingMissions(content.town());var incomplete=owner.worldProfile(knight.name(),1);
            refused(()->store.complete(account,knight.id(),1,incomplete,OpeningMission.SECURING_CAMP,null));
            // Explicit legacy Camp checkpoint: new tutorial completions now skip this route.
            db.write(c->{try(var s=c.prepareStatement("UPDATE metadata SET value=1 WHERE key=?")){s.setString(1,"world."+incomplete.worldId()+".opening-stage");s.executeUpdate();}return null;});
            var camp=owner.worldProfile(knight.name(),1);var peerBefore=peer.worldProfile(other.name(),1);
            for(long auth:new long[]{-1,0,2})refused(()->store.complete(account,knight.id(),auth,camp,OpeningMission.SECURING_CAMP,null));
            refused(()->store.complete(foreign,other.id(),1,camp,OpeningMission.SECURING_CAMP,null));
            refused(()->store.complete(account,knight.id(),1,camp,OpeningMission.ANCIENT_GENERATOR,"EASY"));
            refused(()->store.complete(account,knight.id(),1,camp,OpeningMission.SECURING_CAMP,null));
            var story=owner.knightStory(knight.id(),1,camp,List.of(new KnightMemory("talkedRhendonRC",true),new KnightMemory("talkto_rhendon_icon",true)),List.of(new KnightObjective("findRhendon","m.find_rhendon")));
            var before=story.profile();String receipt="world."+before.worldId()+".mission.securing_camp";
            refused(()->store.complete(account,knight.id(),1,camp,OpeningMission.SECURING_CAMP,null));
            refused(()->store.complete(account,knight.id(),1,before,OpeningMission.SECURING_CAMP,"EASY"));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_completion BEFORE INSERT ON metadata WHEN NEW.key='"+receipt+"' BEGIN SELECT RAISE(ABORT,'reject after rewards'); END");}return null;});
            refused(()->store.complete(account,knight.id(),1,before,OpeningMission.SECURING_CAMP,null));
            check(owner.worldProfile(knight.name(),1).equals(before)&&store.load(account,knight.id(),1).isEmpty()&&owner.knightObjectives(knight.id(),1).equals(story.objectives())&&owner.knightMemories(knight.id(),1).equals(story.memories()),"receipt failure rolls back inventory, allocation counters, story effects, rank and prestige");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_completion");}return null;});
            var gate=new CountDownLatch(1);var results=new LinkedBlockingQueue<OpeningMissionStore.Settlement>();var failures=new LinkedBlockingQueue<Throwable>();
            Runnable settle=()->{try{gate.await();results.add(store.complete(account,knight.id(),1,before,OpeningMission.SECURING_CAMP,null));}catch(Throwable e){failures.add(e);}};
            var a=Thread.ofVirtual().start(settle);var b=Thread.ofVirtual().start(settle);gate.countDown();a.join();b.join();
            check(failures.isEmpty()&&results.size()==2&&results.stream().filter(OpeningMissionStore.Settlement::first).count()==1,"concurrent terminal requests pay once and share one durable receipt");
            var first=owner.worldProfile(knight.name(),1);
            check(first.revision()==before.revision()+1&&first.progress().rankPair().equals("1-2")&&first.progress().prestige()==10&&first.progress().openingStage()==1,"Securing Camp promotes only on committed terminal");
            check(owner.knightObjectives(knight.id(),1).isEmpty()&&!owner.knightMemories(knight.id(),1).contains("talkto_rhendon_icon"),"authored terminal clears icon memory and findRhendon objective atomically");
            check(count(first,"Rarity/Fire Crystal/Cracked")==15&&count(first,"Rarity/Spark of Life")==5&&first.items().stream().filter(i->!i.starter()).allMatch(WorldProfile.Item::bound),"exact native locked rewards");
            WorldProfile current=first;int prestige=10;
            for(var mission:List.of(OpeningMission.ANCIENT_GENERATOR,OpeningMission.FIRST_CONTACT,OpeningMission.CROSSING_CHASM,OpeningMission.WELCOME_HAVEN)){
                if(mission==OpeningMission.ANCIENT_GENERATOR){
                    var expected=current;
                    for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,1_000_001})refused(()->store.complete(account,knight.id(),1,expected,mission,"EASY",invalid));
                    String gateReceipt="world."+expected.worldId()+".mission."+mission.id();
                    db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_floor_receipt BEFORE INSERT ON metadata WHEN NEW.key='"+gateReceipt+"' BEGIN SELECT RAISE(ABORT,'reject after heat and rewards'); END");}return null;});
                    refused(()->store.complete(account,knight.id(),1,expected,mission,"EASY",25));
                    check(owner.worldProfile(knight.name(),1).equals(expected),"failed gate receipt rolls back heat, rewards and progression together");
                    db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_floor_receipt");}return null;});
                }
                OpeningMissionStore.Settlement settled;
                if(mission==OpeningMission.ANCIENT_GENERATOR){
                    var expected=current;var heatStart=new CountDownLatch(1);var heatResults=new LinkedBlockingQueue<OpeningMissionStore.Settlement>();var heatErrors=new LinkedBlockingQueue<Throwable>();
                    Runnable finishFloor=()->{try{heatStart.await();heatResults.add(store.complete(account,knight.id(),1,expected,mission,"EASY",25));}catch(Throwable failure){heatErrors.add(failure);}};
                    var one=Thread.ofVirtual().start(finishFloor);var two=Thread.ofVirtual().start(finishFloor);heatStart.countDown();one.join();two.join();
                    check(heatErrors.isEmpty()&&heatResults.size()==2&&heatResults.stream().filter(OpeningMissionStore.Settlement::first).count()==1,"concurrent gate settlement grants floor heat and rewards once");
                    settled=heatResults.stream().filter(OpeningMissionStore.Settlement::first).findFirst().orElseThrow();
                }else settled=store.complete(account,knight.id(),1,current,mission,mission.gate()?"EASY":null);
                prestige+=mission.prestige();
                check(settled.first()&&settled.profile().progress().prestige()==prestige&&settled.profile().revision()==current.revision()+1,"each verified definition pays once in route order");current=settled.profile();
                if(mission==OpeningMission.ANCIENT_GENERATOR){
                    var expected=current;
                    check(current.items().stream().filter(i->i.starter()&&i.equippedSlot()!=null&&Set.of(0,1,2,6,7).contains(i.equippedSlot())).allMatch(i->i.heatProgress()>0),"collected floor heat reaches the carried equipment");
                    check(current.items().stream().filter(i->i.config().equals("Gear/Armor/Vitasuit")).allMatch(i->i.heatProgress()==0),"new mission reward does not absorb the previous floor's heat");
                    check(!store.complete(account,knight.id(),1,expected,mission,"HARD",100).first()&&owner.worldProfile(knight.name(),1).equals(expected),"receipt prevents repeated heat or difficulty upgrades");
                }
            }
            var finalState=current;var receipts=store.load(account,knight.id(),1);
            check(receipts.size()==5&&current.progress().rankPair().equals("2-1")&&current.progress().openingStage()==3&&owner.lastTownScene(knight.id(),1)==1,"all opening storage transitions preserve the Haven checkpoint");
            for(var mission:OpeningMission.values())check(!store.complete(account,knight.id(),1,before,mission,mission.gate()?"HARD":null).first(),"stale retries of a recorded terminal cannot upgrade difficulty or pay twice");
            check(owner.worldProfile(knight.name(),1).equals(finalState)&&store.load(account,knight.id(),1).equals(receipts),"replay does not modify later inventory, rank, checkpoint or original difficulty/time");
            var reopened=new CharacterStore(new Database(home,false),account,"MissionOwner",content.items());
            check(reopened.openingMissions(content.town()).load(account,knight.id(),1).equals(receipts)&&reopened.worldProfile(knight.name(),1).equals(finalState),"receipts and reward state survive reopening SQLite");
            check(peer.worldProfile(other.name(),1).equals(peerBefore),"foreign knight unaffected");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: opening settlement auth/ownership, prerequisites/revisions, exact locked rewards, native rank/prestige/checkpoints, injected rollback, concurrent receipts, replay and reopen (storage only; floors not exercised)");
    }
    private static int count(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Action{void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid mission settlement accepted");}catch(IOException expected){}}
}
