package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Production session/dialogue/SQLite acceptance from explicit post-floor checkpoints.
 * Camp 218 uses synthetic geometry; this does not exercise either mission floor. */
public final class OpeningComlinkSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var base=content.createScenes();var haven=base.catalog().scene(1);
        var camp=new SceneCatalog.MapScene(218,16777222,"%town:m.fixture","Fixture powered Camp",haven.image(),"town",1,haven.spawnX(),haven.spawnY(),haven.spawnRotation(),1,haven.collision(),haven.layout(),ScenePortals.EMPTY,SceneContent.empty());
        var maps=new TreeMap<Integer,SceneCatalog.MapScene>();for(var map:base.catalog().scenes())maps.put(map.id(),map);maps.put(218,camp);
        var registry=new SceneRegistry(SceneCatalog.of(maps,1));registry.town(content.town());
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-comlinks-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int ownerId=db.create("ComlinkOwner",protection,false),peerId=db.create("ComlinkPeer",protection,false);
            var owner=new CharacterStore(db,ownerId,"ComlinkOwner",content.items());var peer=new CharacterStore(db,peerId,"ComlinkPeer",content.items());
            var foreign=peer.createForOpening(knight("Foreign-Comlink"),1);var untouched=peer.worldProfile(foreign.name(),1);
            for(var mission:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN)){
                var knight=owner.createForOpening(knight(mission==OpeningMission.FIRST_CONTACT?"Comlink-Camp":"Comlink-Haven"),1);
                var checkpoint=checkpoint(db,owner,knight,registry,mission);var socket=new RecordingSocket();
                try(var interruptedOutput=new SessionOutput(new RecordingSocket());var interrupted=new WorldSession(new RecordingSocket(),owner,interruptedOutput,new SessionCodec(),1201+mission.ordinal(),1,message->{},registry)){
                    interrupted.select(knight.name(),RECEIVERS,true);enter(interrupted,registry,mission);await(()->node(interrupted)!=null);
                    int oid=registry.destination(OpeningComlinks.scene(mission),checkpoint.worldId()).oid();interrupted.conversationInvocation(response(oid,0,90));
                }
                check(owner.worldProfile(knight.name(),1).equals(checkpoint),"disconnect during an unfinished comlink grants nothing and keeps the checkpoint");
                try(var output=new SessionOutput(socket);var world=new WorldSession(socket,owner,output,new SessionCodec(),1001+mission.ordinal(),1,message->{},registry)){
                    refuse(()->world.select(foreign.name(),RECEIVERS,true));
                    world.select(knight.name(),RECEIVERS,true);world.townInvocation(start(mission.id(),10));
                    check(node(world)==null,"Ready Room attempt cannot create a phantom scene dialog or complete a mission");
                    enter(world,registry,mission);int oid=registry.destination(OpeningComlinks.scene(mission),checkpoint.worldId()).oid();
                    await(()->OpeningComlinks.get(mission).initial().equals(node(world)));
                    world.townInvocation(start("arbitrary_mission",11));check(node(world).equals(OpeningComlinks.get(mission).initial()),"unknown mission never replaces the arrival comlink with arbitrary content");
                    world.townInvocation(start(mission==OpeningMission.FIRST_CONTACT?OpeningMission.WELCOME_HAVEN.id():OpeningMission.FIRST_CONTACT.id(),12));check(node(world).equals(OpeningComlinks.get(mission).initial()),"wrong location/ineligible mission refused");
                    world.townInvocation(start(mission.id(),13));check(node(world).equals(OpeningComlinks.get(mission).initial()),"native mission attempt opens the actual first node");
                    world.townInvocation(start(mission.id(),13));check(owner.worldProfile(knight.name(),1).equals(checkpoint),"duplicate opener grants nothing");
                    check(!world.conversationInvocation(response(oid+1,0,14)),"foreign scene cannot answer the owner's panel");
                    world.conversationInvocation(response(oid,99,14));check(node(world).equals(OpeningComlinks.get(mission).initial()),"invalid choice preserves the node");
                    var first=response(oid,0,15);world.conversationInvocation(first);String second=node(world);world.conversationInvocation(first);check(node(world).equals(second),"retried response cannot answer another panel");
                    for(int panel=2;panel<5;panel++)world.conversationInvocation(response(oid,0,20+panel));
                    if(mission==OpeningMission.WELCOME_HAVEN){
                        world.conversationInvocation(response(oid,0,25));check(node(world)==null&&owner.worldProfile(knight.name(),1).equals(checkpoint),"native first choice at Welcome 5a closes without completing or paying");
                        world.townInvocation(start(mission.id(),26));for(int panel=1;panel<5;panel++)world.conversationInvocation(response(oid,0,50+panel));
                    }
                    world.conversationInvocation(response(oid,mission==OpeningMission.WELCOME_HAVEN?1:0,27));
                    check(owner.worldProfile(knight.name(),1).equals(checkpoint),"all nonterminal panels leave inventory/rank/receipts unchanged");
                    String receipt="world."+checkpoint.worldId()+".mission."+mission.id();
                    db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_comlink BEFORE INSERT ON metadata WHEN NEW.key='"+receipt+"' BEGIN SELECT RAISE(ABORT,'injected terminal failure'); END");}return null;});
                    var terminal=response(oid,0,30);world.conversationInvocation(terminal);
                    check(owner.worldProfile(knight.name(),1).equals(checkpoint)&&node(world)!=null,"failed transaction preserves the final panel and rolls back every reward/progression write");
                    db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_comlink");}return null;});
                    world.conversationInvocation(terminal);var settled=owner.worldProfile(knight.name(),1);
                    check(settled.revision()==checkpoint.revision()+1&&settled.progress().prestige()==checkpoint.progress().prestige()+mission.prestige()&&node(world)==null,"proper final answer commits once and replaces the comlink with completion presentation");
                    for(var reward:mission.rewards())check(count(settled,reward.config())-count(checkpoint,reward.config())==reward.count(),"exact sourced reward quantity");
                    world.conversationInvocation(terminal);world.townInvocation(start(mission.id(),31));check(owner.worldProfile(knight.name(),1).equals(settled)&&node(world)==null,"duplicate terminal and completed-card attempt cannot pay again");
                    await(()->socket.contains("com.threerings.projectx.mission.data.MissionCompleteDialogInfo")&&socket.contains(mission.id()));
                }
                var persisted=owner.worldProfile(knight.name(),1);var reopened=new CharacterStore(new Database(home,false),ownerId,"ComlinkOwner",content.items());
                try(var output=new SessionOutput(new RecordingSocket());var world=new WorldSession(new RecordingSocket(),reopened,output,new SessionCodec(),1101+mission.ordinal(),1,message->{},registry)){
                    world.select(knight.name(),RECEIVERS,true);enter(world,registry,mission);world.townInvocation(start(mission.id(),40));check(node(world)==null&&reopened.worldProfile(knight.name(),1).equals(persisted),"reconnect keeps completion and inventory; cannot restart a paid card");
                }
                check(registry.destination(OpeningComlinks.scene(mission),checkpoint.worldId()).simulation().playerCount()==0,"disconnect releases every owned pawn");
            }
            check(peer.worldProfile(foreign.name(),1).equals(untouched),"foreign account inventory and progress unaffected");
        }finally{SceneRegistry.install(previous);try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        System.out.println("PASS: native First Contact B and Welcome to Haven attempts, all captured panels, ownership, eligibility, invalid/duplicate responses, rollback/retry, exact rewards, completion publication and Ready Room reconnect (post-floor checkpoints; synthetic Camp geometry)");
    }
    private static WorldProfile checkpoint(Database db,CharacterStore owner,Knight knight,SceneRegistry registry,OpeningMission target)throws Exception {
        // Legacy route fixture, not the current Crash Site -> Haven settlement.
        var profile=owner.worldProfile(knight.name(),1);
        int world=profile.worldId();
        db.write(c->{try(var s=c.prepareStatement("UPDATE metadata SET value=1 WHERE key=?")){s.setString(1,"world."+world+".opening-stage");s.executeUpdate();}return null;});
        profile=owner.worldProfile(knight.name(),1);
        profile=owner.knightStory(knight.id(),1,profile,List.of(new KnightMemory("talkedRhendonRC",true)),List.of()).profile();
        for(var mission:OpeningMission.values()){if(mission==target)break;profile=owner.openingMissions(registry.town()).complete(owner.accountId(),knight.id(),1,profile,mission,mission.gate()?"EASY":null).profile();}
        return profile;
    }
    private static void enter(WorldSession world,SceneRegistry registry,OpeningMission mission)throws Exception {
        var map=registry.catalog().scene(OpeningComlinks.scene(mission));world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));world.subscribe((short)2);world.entered();
    }
    private static int count(WorldProfile profile,String config){return profile.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static SessionDecoder.Invocation start(String id,int request){return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.MISSION_SERVICE,3,List.of(id,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)request)));}
    private static SessionDecoder.Invocation response(int oid,int choice,int request){return new SessionDecoder.Invocation(oid,SceneConversation.SERVICE,1,List.of(choice,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)request)));}
    private static String node(WorldSession world)throws Exception{synchronized(world){var field=WorldSession.class.getDeclaredField("conversationNode");field.setAccessible(true);return (String)field.get(world);}}
    private static void refuse(Operation op)throws Exception{try{op.run();throw new AssertionError("Unauthorized selection accepted");}catch(IOException|CharacterStore.Refused expected){}}
    private interface Operation{void run()throws Exception;}
    private interface Check{boolean ok()throws Exception;}
    private static void await(Check check)throws Exception{long end=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<end){if(check.ok())return;Thread.sleep(10);}throw new AssertionError("Timed out awaiting comlink state/publication");}
    private static final class RecordingSocket extends Socket {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        @Override public OutputStream getOutputStream(){return bytes;}
        boolean contains(String text){return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1).contains(text);}
    }
}
