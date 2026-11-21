package org.tortodrone.server.network;

import java.io.IOException;
import java.io.OutputStream;
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
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;

/** Full-session crash site fight regression: an entered knight arms the Crash Site
 *  jelly wave and trades blows with it like a live client - walking input frames and
 *  proto sword swings against jelly lunges. Both damage directions must land through
 *  the session pipeline (weapon impulse on the monster, melee hit on the knight) and
 *  the scene simulation must keep ticking; a stored tick failure fails the test with
 *  its stack trace. This reproduces the frozen-scene regression where the shared
 *  combat table's injected impulse level was read with an integer parser. */
public final class JellyMeleeSceneTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var registry=content.createScenes();
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-jelly-melee-");
        try {
            var db=new Database(home,true);int account=db.create("JellyOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"JellyOwner",content.items());
            var knight=characters.createForOpening(new Knight(0,"Jelly-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
            var route=registry.tutorial();
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1601,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);move(world,route.source());world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
                var member=(SceneSimulation.Membership)field(world,"membership");
                var simulation=((SceneRegistry.Instance)field(world,"scene")).simulation();
                // Wave 1a is armed by the player sensor at the arena entrance (spawner 492).
                check(member.teleport(-1.5f,-69f,0),"knight enters the wave sensor");
                long waveEnd=System.nanoTime()+10_000_000_000L;boolean jellySeen=false;
                while(System.nanoTime()<waveEnd&&!jellySeen) {
                    Thread.sleep(250);
                    var failure=simulation.failure();
                    if(failure!=null){failure.printStackTrace();throw new AssertionError("Scene simulation crashed before jelly contact: "+failure);}
                    SceneSimulation.Snapshot snapshot;synchronized(simulation){snapshot=simulation.snapshot();}
                    for(var actor:snapshot.actors())
                        if(actor.entity()!=null&&"Monster".equals(actor.entity().kind())&&actor.entity().destroyed()==Integer.MAX_VALUE)jellySeen=true;
                }
                check(jellySeen,"the jelly wave spawned");
                // Stand among the spawned wave so its perception picks the knight up; the
                // spawner tiles themselves are occupied, so take the nearest open approach.
                boolean placed=false;
                for(float[] spot:new float[][]{{-2f,-55f},{-6f,-55f},{0f,-52f},{-4f,-57f},{-1f,-50f}})
                    if(member.teleport(spot[0],spot[1],0)){placed=true;break;}
                check(placed,"knight moved into the jelly wave arena");
                // Phase one - stand still like the reported AFK reproduction until a jelly
                // lunge lands through the whole session pipeline.
                float lowest=Float.MAX_VALUE;
                var combat=new ArrayList<SceneCombatEvent>();
                SceneSimulation.Snapshot previousSnapshot=null;
                final int[] pawnId={0};
                long afkEnd=System.nanoTime()+150_000_000_000L;
                while(System.nanoTime()<afkEnd) {
                    Thread.sleep(100);
                    var failure=simulation.failure();
                    if(failure!=null){failure.printStackTrace();throw new AssertionError("Scene simulation crashed during the AFK phase: "+failure);}
                    SceneSimulation.Snapshot snapshot;synchronized(simulation){snapshot=simulation.snapshot();}
                    combat.addAll(snapshot.combatEventsSince(previousSnapshot));previousSnapshot=snapshot;
                    var player=snapshot.players().stream().filter(p->p.entity()==null&&p.pet()==null).findFirst().orElse(null);
                    if(player!=null) {
                        pawnId[0]=player.id();
                        var state=snapshot.health(player.id());
                        if(state!=null)lowest=Math.min(lowest,state.current());
                    }
                    if(lowest<200)break;
                }
                check(lowest<200,"jelly melee damaged the AFK knight through the full session pipeline");
                // Phase two - fight like a live client: walk at the nearest jelly with the
                // proto sword swinging. The sword's weapon impulse on the monster is the
                // former frozen-scene path.
                long end=System.nanoTime()+150_000_000_000L;
                boolean monsterDamaged=false;
                var firstHealth=new HashMap<Integer,Float>();
                long lastFrame=0;
                while(System.nanoTime()<end) {
                    Thread.sleep(20);
                    var failure=simulation.failure();
                    if(failure!=null){failure.printStackTrace();throw new AssertionError("Scene simulation crashed during the jelly fight: "+failure);}
                    SceneSimulation.Snapshot snapshot;synchronized(simulation){snapshot=simulation.snapshot();}
                    combat.addAll(snapshot.combatEventsSince(previousSnapshot));previousSnapshot=snapshot;
                    if(System.nanoTime()-lastFrame>100_000_000L) {
                        lastFrame=System.nanoTime();
                        var pawn=snapshot.players().stream().filter(p->p.entity()==null&&p.pet()==null).findFirst().orElse(null);
                        var target=snapshot.actors().stream()
                            .filter(a->a.entity()!=null&&"Monster".equals(a.entity().kind())&&a.entity().destroyed()==Integer.MAX_VALUE)
                            .min(Comparator.comparingDouble(a->pawn==null?0:Math.hypot(a.x()-pawn.x(),a.y()-pawn.y()))).orElse(null);
                        if(pawn!=null&&target!=null) {
                            float heading=(float)Math.atan2(target.y()-pawn.y(),target.x()-pawn.x());
                            member.input(SceneClock.now(),heading,heading,InputBits.MOVING|InputBits.ACTION,SceneClock.now(),SceneClock.now(),System.nanoTime()/1_000_000L);
                        }
                    }
                    var alive=new HashSet<Integer>();
                    for(var actor:snapshot.actors())
                        if(actor.entity()!=null&&"Monster".equals(actor.entity().kind())&&actor.entity().destroyed()==Integer.MAX_VALUE) {
                            alive.add(actor.id());
                            firstHealth.putIfAbsent(actor.id(),actor.entity().health());
                            if(actor.entity().health()<firstHealth.get(actor.id()))monsterDamaged=true;
                        }
                    // A monster vanishing from the wave it spawned in took a killing blow.
                    for(var known:new ArrayList<>(firstHealth.keySet()))
                        if(firstHealth.get(known)>0&&!alive.contains(known))monsterDamaged=true;
                    var player=snapshot.players().stream().filter(p->p.entity()==null&&p.pet()==null).findFirst().orElse(null);
                    if(player!=null) {
                        var state=snapshot.health(player.id());
                        if(state!=null)lowest=Math.min(lowest,state.current());
                    }
                    if(monsterDamaged)break;
                }
                check(monsterDamaged,"the knight's sword landed a weapon impulse on the wave");
                // Combat feedback rides the Feedback/Damage scene effect from the fight's own
                // snapshots: the knight's own posts carry packed type breakdowns and a literal
                // zero source, and his sword hits on monsters carry the dealt amount with his
                // pawn as the source (the client reserves the numbers for the attacker's client).
                var profile=(WorldProfile)field(world,"profile");
                check(combat.stream().anyMatch(e->e.actor()==pawnId[0]&&e.source()==0&&e.amount()!=0),"the knight's lost hit carries a packed breakdown and a literal zero source");
                check(combat.stream().anyMatch(e->e.source()==profile.worldId()&&e.amount()>0),"the knight's sword hit is attributed to his pawn with a plain dealt amount");
                check(combat.stream().allMatch(e->Math.abs(e.x())<1000f&&Math.abs(e.y())<1000f),"every feedback hit records a scene position");
                // Fights continue on later ticks (impulse travel, death, pickups): keep
                // the scene alive past the observed hits.
                long settle=System.nanoTime()+10_000_000_000L;
                while(System.nanoTime()<settle) {
                    Thread.sleep(250);
                    var failure=simulation.failure();
                    if(failure!=null){failure.printStackTrace();throw new AssertionError("Scene simulation crashed after the jelly fight: "+failure);}
                }
                check(simulation.failure()==null,"scene still ticking after the jelly fight");
                System.out.println("PASS: full-session jelly fight keeps the scene simulation alive (knight health "+lowest+"/200)");
            }
        } finally {SceneRegistry.install(previous);ContentFixture.delete(home);}
    }
    private static Object field(WorldSession world,String name)throws Exception {synchronized(world){var f=WorldSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);}}
    private static void move(WorldSession world,SceneCatalog.MapScene map)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));}
    private static final class RecordingSocket extends Socket {
        @Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
    }
}
