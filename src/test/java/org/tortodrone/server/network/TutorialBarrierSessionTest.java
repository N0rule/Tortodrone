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

/** Real Crash Site breakable-barrier combat: first wall of the authored exit corridor.
 * Verified against the stock flow: movement stops at the barrier, sword attacks destroy
 * the haven blocks and open the corridor. */
public final class TutorialBarrierSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    /** The authored breakable wall sealing the eastern exit corridor (x -14.5..-8.5, y -64.5..-59.5). */
    private static final float WALL_MIN_X=-15.25f,WALL_MAX_X=-8.0f,WALL_MIN_Y=-65.25f,WALL_MAX_Y=-58.75f;
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var registry=content.createScenes();
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-tutorial-barrier-");
        try {
            var db=new Database(home,true);int account=db.create("BarrierOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"BarrierOwner",content.items());
            var knight=characters.createForOpening(new Knight(0,"Barrier-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
            var route=registry.tutorial();var worldId=characters.worldProfile(knight.name(),1).worldId();
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1603,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);move(world,route.source());world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
                var member=(SceneSimulation.Membership)field(world,"membership");
                var simulation=((SceneRegistry.Instance)field(world,"scene")).simulation();
                var wall=simulation.snapshot().actors().stream()
                    .filter(a->a.config().name().contains("Haven")&&inWall(a.x(),a.y())).toList();
                check(wall.size()>=10,"authored breakable barrier present: "+wall.size()+" haven blocks");
                // The stuck observation from the graphical run: pressed against the wall, west side.
                check(member.teleport(-15.9f,-63.4f,facing(-15.9f,-63.4f)),"fixture placement at the authored barrier");
                walk(member,facing(-15.9f,-63.4f),3_000);
                var pressed=simulation.snapshot().players().stream().filter(a->a.id()==worldId).findFirst().orElseThrow();
                check(pressed.x()>-15.5f&&pressed.x()<-14.0f,"barrier stops ordinary movement at the authored wall (knight at "+pressed.x()+","+pressed.y()+")");
                check(simulation.snapshot().actors().stream().filter(a->inWall(a.x(),a.y())&&a.config().name().contains("Haven")).count()==wall.size(),"no barrier block changes without attacks");
                // Stock tutorial behavior: slash the breakable haven blocks to open the corridor.
                long deadline=System.nanoTime()+30_000_000_000L;
                float crossed=Float.NEGATIVE_INFINITY;long after=wall.size();
                while(System.nanoTime()<deadline) {
                    attack(member,-15.9f,-63.4f,-14.3f,-62.4f,2_000);
                    after=simulation.snapshot().actors().stream().filter(a->inWall(a.x(),a.y())&&a.config().name().contains("Haven")).count();
                    // Which blocks each burst fells depends on input-frame timing, so keep
                    // slashing and walking east like a stock player until the knight threads
                    // through the opened gap.
                    walk(member,0f,4_000);
                    var stepped=simulation.snapshot().players().stream().filter(a->a.id()==worldId).findFirst().orElseThrow();
                    crossed=Math.max(crossed,stepped.x());
                    if(stepped.x()>-13)break;
                }
                check(after<wall.size(),"sword attacks destroyed breakable haven blocks: "+wall.size()+" -> "+after);
                check(after<=wall.size()-3,"at least the eastern wall section clears so the corridor opens");
                check(crossed>-13,"cleared barrier is walkable through the authored corridor (knight reached "+crossed+")");
                var passed=simulation.snapshot().players().stream().filter(a->a.id()==worldId).findFirst().orElseThrow();
                check(passed.x()>-13,"cleared barrier is walkable through the authored corridor (knight at "+passed.x()+","+passed.y()+")");
                check(characters.worldProfile(knight.name(),1).progress().crashSiteIncomplete(),"barrier combat alone does not finish the tutorial");
            }
            System.out.println("PASS: Crash Site breakable barrier blocks movement until sword attacks destroy the haven blocks and the corridor opens");
        } finally {SceneRegistry.install(previous);ContentFixture.delete(home);}
    }
    private static boolean inWall(float x,float y){return x>=WALL_MIN_X&&x<=WALL_MAX_X&&y>=WALL_MIN_Y&&y<=WALL_MAX_Y;}
    private static float facing(float x,float y){return (float)Math.atan2(-63.4f-y,-15.9f-x);}
    private static float facing(float x,float y,float tx,float ty){return (float)Math.atan2(ty-y,tx-x);}
    /** Hold movement intent toward the facing without attacking. */
    private static void walk(SceneSimulation.Membership member,float facing,long millis)throws Exception {
        long end=System.nanoTime()+millis*1_000_000L;
        long changed=0;boolean moving=false;
        while(System.nanoTime()<end) {
            int stamp=SceneClock.now();
            boolean press=(stamp%1250)<900;
            if(press!=moving){moving=press;changed=stamp;}
            member.input(stamp,facing,facing,moving?InputBits.MOVING:0,(int)changed,SceneClock.now(),System.nanoTime()/1_000_000L);
            Thread.sleep(100);
        }
    }
    /** Alternate stock movement and attack presses the way the client controller latches them. */
    private static void attack(SceneSimulation.Membership member,float x,float y,float targetX,float targetY,long millis)throws Exception {
        long end=System.nanoTime()+millis*1_000_000L;
        long changed=0;int flags=InputBits.MOVING;
        while(System.nanoTime()<end) {
            int stamp=SceneClock.now();
            boolean press=(stamp%1250)<150;
            int next=press?InputBits.ACTION|InputBits.MOVING:InputBits.MOVING;
            if(press&&flags!=(InputBits.ACTION|InputBits.MOVING))changed=stamp;
            flags=next;
            member.input(stamp,facing(x,y,targetX,targetY),facing(x,y,targetX,targetY),flags,(int)changed,SceneClock.now(),System.nanoTime()/1_000_000L);
            Thread.sleep(100);
        }
    }
    private static Object field(WorldSession world,String name)throws Exception {synchronized(world){var f=WorldSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);}}
    private static void move(WorldSession world,SceneCatalog.MapScene map)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));}
    private static final class RecordingSocket extends Socket {@Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}}
}
