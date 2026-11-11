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
import org.tortodrone.support.ContentFixture;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Real exit sensor/session/SQLite handoff. Server-side test placement skips combat;
 * this is not a full gameplay or graphical acceptance test. */
public final class TutorialTravelSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var registry=content.createScenes();
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-tutorial-travel-");
        try {
            var db=new Database(home,true);int account=db.create("TravelOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"TravelOwner",content.items());
            var knight=characters.createForOpening(new Knight(0,"Travel-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
            var before=characters.worldProfile(knight.name(),1);var route=registry.tutorial();
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1601,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);move(world,route.source());world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
                int party=registry.parties().view(world.partyMemberSnapshot()).oid();
                world.interiorPartyInvocation(report(party));
                check(characters.worldProfile(knight.name(),1).equals(before),"premature report cannot skip Crash Site");
                var member=(SceneSimulation.Membership)field(world,"membership");
                var exit=route.source().content().entries().stream().filter(e->e.key().equals(Integer.toString(TutorialRoute.EXIT))).findFirst().orElseThrow();
                check(member.teleport(exit.x(),exit.y(),0),"server-only fixture placement at original exit");
                await(()->field(world,"floorReport")!=null);
                synchronized(world) {
                    var pending=(FloorReport)field(world,"floorReport");
                    check(route.exit(pending.request())&&pending.valid(),"report belongs to the actual authored exit and current membership");
                    String location="world."+before.worldId()+".last-town-scene";
                    db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_haven BEFORE INSERT ON metadata WHEN NEW.key='"+location+"' BEGIN SELECT RAISE(ABORT,'injected Haven failure'); END");}return null;});
                    try {world.interiorPartyInvocation(report(party));throw new AssertionError("Failed settlement accepted");}catch(IOException expected){}
                    check(characters.worldProfile(knight.name(),1).equals(before)&&characters.lastTownScene(knight.id(),1)==0&&field(world,"portalMove")==null&&pending.valid(),"failed Haven write rolls back progress and publishes no travel; report remains retryable");
                    db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_haven");}return null;});
                    var finish=WorldSession.class.getDeclaredMethod("finishTutorialFloor",int.class);finish.setAccessible(true);
                    check((boolean)finish.invoke(world,SceneClock.now()),"same ready report retries through production settlement and handoff");
                    check(field(world,"portalMove")!=null&&!pending.valid(),"successful commit retires the report before publishing movement");
                }
                var after=characters.worldProfile(knight.name(),1);
                check(after.progress().openingStage()==3&&after.progress().progressFlags()==4&&characters.lastTownScene(knight.id(),1)==1,"direct Haven checkpoint and town access committed");
                check(after.items().equals(before.items())&&after.progress().rank()==1&&after.progress().prestige()==0,"skipped Camp missions grant no fabricated rewards or rank");
                move(world,route.destination());world.subscribe((short)4);world.entered();
                var haven=registry.destination(1,before.worldId());
                var pawn=haven.simulation().snapshot().players().stream().filter(p->p.id()==before.worldId()).findFirst().orElseThrow();
                check(world.ownsScene(haven.oid())&&pawn.x()==route.entrance().x()&&pawn.y()==route.entrance().y(),"forced move handshake attaches at Haven's authored spawn");
                world.interiorPartyInvocation(report(party));
                check(characters.worldProfile(knight.name(),1).equals(after),"late report does not settle twice");
            }
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1602,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);
                check(registry.destination(1,before.worldId()).simulation().playerCount()==0,"reconnect stages in Ready Room without a world pawn");
                world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));move(world,route.destination());world.subscribe((short)5);world.entered();
                check(world.ownsScene(registry.destination(1,before.worldId()).oid()),"Return to Game resumes Haven rather than Camp or Crash Site");
            }
            check(registry.statuses().stream().noneMatch(s->s.templateId()==398)&&registry.destination(1,before.worldId()).simulation().playerCount()==0,"disconnect releases private floor and Haven pawn");
            System.out.println("PASS: real Crash Site exit sensor -> floor report -> atomic Haven handoff, injected rollback/retry, authored spawn, replay protection and Ready Room reconnect (combat skipped by fixture placement)");
        } finally {SceneRegistry.install(previous);ContentFixture.delete(home);}
    }
    private static Object field(WorldSession world,String name)throws Exception {synchronized(world){var f=WorldSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);}}
    private static SessionDecoder.Invocation report(int party){return new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(true));}
    private static void move(WorldSession world,SceneCatalog.MapScene map)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));}
    private interface Check{boolean ok()throws Exception;}
    private static void await(Check check)throws Exception {long end=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<end){if(check.ok())return;Thread.sleep(10);}throw new AssertionError("Exit sensor did not open a floor report");}
    private static final class RecordingSocket extends Socket {@Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}}
}
