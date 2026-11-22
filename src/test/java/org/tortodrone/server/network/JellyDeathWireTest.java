package org.tortodrone.server.network;
import org.tortodrone.core.world.SceneClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.InputBits;

/** The mob death VFX wire, end to end: a full Crash Site session fights its jelly
 *  wave until a knight's sword kills one, then the recorded downstream bytes are
 *  walked frame by frame to the scene deltas the stock client would receive. The
 *  dying jelly has to ride those deltas with its dying activity (2), its killed
 *  stamp in activityStarted and its delayed destruction stamp - the three fields
 *  the client's delayed-time ActorHistory uses to play the dying animation and
 *  fire the actor config's destructionTransient death effect before removal.
 *  A second death is then forced while the session's delivery thread is stalled
 *  mid-publication, when ordinary frames coalesce into one pending snapshot: the
 *  dying record must still be delivered ahead of the eventual removal, as native's
 *  uncoalesced per-tick stream always delivers it. */
public final class JellyDeathWireTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);

    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var registry=org.tortodrone.world.SceneRegistry.create(content);
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-jelly-death-");
        try {
            var db=new Database(home,true);int account=db.create("DeathOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"DeathOwner",content.items());
            var knight=characters.createForOpening(new Knight(0,"Death-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
            var route=registry.tutorial();
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1601,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);move(world,route.source());world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
                var member=(SceneSimulation.Membership)field(world,"membership");
                var simulation=((SceneRegistry.Instance)field(world,"scene")).simulation();
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
                boolean placed=false;
                for(float[] spot:new float[][]{{-2f,-55f},{-6f,-55f},{0f,-52f},{-4f,-57f},{-1f,-50f}})
                    if(member.teleport(spot[0],spot[1],0)){placed=true;break;}
                check(placed,"knight moved into the jelly wave arena");
                // Fight like a live client until a jelly dies through the whole
                // pipeline: the wave monster's destroyed stamp carries the death.
                long end=System.nanoTime()+150_000_000_000L;
                int deathStamp=0;int killed=0;
                long lastFrame=0;
                while(System.nanoTime()<end) {
                    Thread.sleep(20);
                    var failure=simulation.failure();
                    if(failure!=null){failure.printStackTrace();throw new AssertionError("Scene simulation crashed during the jelly fight: "+failure);}
                    SceneSimulation.Snapshot snapshot;synchronized(simulation){snapshot=simulation.snapshot();}
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
                    for(var actor:snapshot.actors())
                        if(actor.entity()!=null&&"Monster".equals(actor.entity().kind())&&actor.entity().destroyed()!=Integer.MAX_VALUE) {
                            deathStamp=actor.entity().destroyed();killed=actor.activityStarted();break;
                        }
                    if(deathStamp!=0)break;
                }
                check(deathStamp!=0,"the knight's sword killed a jelly through the session pipeline");
                check(deathStamp-killed==100,"the jelly's destruction waits its authored Instant death time ("+(deathStamp-killed)+" ms)");
                // Let the imminent removal and the remaining dying publications flush.
                Thread.sleep(1000);
                var dying=socket.dyingMonsterDeltas();
                if(dying.isEmpty())socket.printWireDiagnostics(killed,deathStamp);
                check(!dying.isEmpty(),"dying monster deltas reached the session wire ("+dying.size()+" frames)");
                for(var entry:dying) {
                    int frameStamp=entry[0],destroyed=entry[1],activity=entry[2],started=entry[3];
                    check(activity==2,"every dying wire frame carries the dying activity");
                    check(destroyed-started==100,"every dying wire frame preserves the death-time window");
                    check(frameStamp<=destroyed,"no dying frame is published after the destroyed stamp itself");
                }
                var first=dying.getFirst();
                System.out.println("PASS: full-session jelly death streams dying activity 2 from stamp "
                    +first[3]+" to destroyed "+first[1]+" across "+dying.size()+" wire frames");

                // Act two - the same death while the delivery thread is stalled. Holding
                // the output encoding lock parks the delivery thread mid-publication, so
                // every later snapshot coalesces into one pending publication - the exact
                // state a slow downstream reader produces. With only coalescing, a death
                // inside the stall is delivered as a straight alive-to-removed jump and
                // the client never sees the dying record.
                var deliveryThread=world.deliveryThread();
                var entitiesField=SceneSimulation.class.getDeclaredField("entities");entitiesField.setAccessible(true);
                int kill;
                var encoding=output.encodingLockForTest();
                encoding.lock();
                try {
                    // Park the delivery thread inside a publication. Ticks, captures,
                    // offers and the damage path all run without these monitors.
                    long parked=System.nanoTime()+20_000_000_000L;
                    while(!blockedInPublish(deliveryThread)&&System.nanoTime()<parked)Thread.sleep(50);
                    check(blockedInPublish(deliveryThread),"the delivery thread is stalled mid-publication ("+deliveryThread.getState()+")");
                    int monster=-1,killer=0;
                    synchronized(simulation){
                        var entities=(SceneEntities)entitiesField.get(simulation);
                        var live=simulation.snapshot();
                        for(var player:live.players())killer=player.id();
                        // Wave jellies are the sword-proven damageable target from act one.
                        var candidates=entities.actors().stream()
                            .filter(a->a.entity()!=null&&"Monster".equals(a.entity().kind())&&a.entity().destroyed()==Integer.MAX_VALUE).toList();
                        for(var actor:candidates){monster=actor.id();if(actor.config()!=null&&actor.config().name().contains("Jelly"))break;}
                        final int chosen=monster;
                        var target=candidates.stream().filter(a->a.id()==chosen).findFirst().orElse(null);
                        check(target!=null,"a live monster remains for the stalled-delivery death");
                        kill=SceneClock.now()+100;
                        entities.damage(region(normal(500,true)),target.x(),target.y(),0,kill,killer);
                        var slain=entities.actors().stream().filter(a->a.id()==chosen).findFirst().orElse(null);
                        check(slain!=null&&slain.activity()==2&&slain.entity().destroyed()==kill+100,
                            "the stalled kill lands through the production damage path (got "
                                +(slain==null?"removed":slain.activity()+"/"+slain.entity().destroyed())+", expected 2/"+(kill+100)+")");
                    }
                    Thread.sleep(2000); // past the death window, the grace and the removal tick, with the queue drained
                    int frozenAt=socket.wireLength();
                    Thread.sleep(500);
                    check(socket.wireLength()==frozenAt,"the downstream stays frozen across the stalled death");
                } finally { encoding.unlock(); }
                Thread.sleep(3000); // let the drained publications deliver after the stall
                var stalled=socket.dyingMonsterDeltas();
                check(stalled.stream().anyMatch(d->d[3]==kill&&d[2]==2),
                    "the stalled death still delivers its dying record after the drain ("+stalled.size()+" dying frames total)");
                System.out.println("PASS: a death during a delivery stall delivers its dying record after the stall clears");
            }
        } finally {SceneRegistry.install(previous);ContentFixture.delete(home);}
    }
    private static Object field(WorldSession world,String name)throws Exception {synchronized(world){var f=WorldSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);}}
    /** True when the delivery virtual thread is parked inside a publication's downstream send. */
    private static boolean blockedInPublish(Thread delivery){
        if(delivery==null)return false;
        var state=delivery.getState();
        if(state!=Thread.State.BLOCKED&&state!=Thread.State.WAITING)return false;
        boolean inSession=false,inOutput=false;
        for(var frame:delivery.getStackTrace()){
            if(frame.getClassName().endsWith("WorldSession")&&frame.getMethodName().equals("publish"))inSession=true;
            if(frame.getClassName().endsWith("SessionOutput")&&(frame.getMethodName().equals("encodeAndQueue")
                    ||frame.getMethodName().equals("send")||frame.getMethodName().equals("sendOptional")))inOutput=true;
        }
        return inSession&&inOutput;
    }
    private static void move(WorldSession world,org.tortodrone.core.world.MapScene map)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),0,new SessionDecoder.Listener("com.threeerings.whirled.zone.data.ZoneMoveMarshaller$ZoneMoveMarshaller",(short)1))));}
    private static FireCatalog.DataValue normal(float amount,boolean major){
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",amount,"shouldReact",true,"majorReact",major));
    }
    private static StarterCatalog.Model region(FireCatalog.DataValue damage){
        return new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",1f)),"damage",damage));
    }

    /** Downstream frames are length-prefixed; scene delta frames start with the
     *  interned EventNotification and SceneDeltaEvent type codes. The jelly's
     *  active-Monster ActorDelta mask is a fixed three bytes (0x53BDE), followed by
     *  destroyed, translation, rotation, flags, direction, activity and
     *  activityStarted, so each death frame is read straight off the wire. */
    private static final class RecordingSocket extends Socket {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int wireLength(){synchronized(bytes){return bytes.size();}}
        @Override public OutputStream getOutputStream(){
            return new OutputStream(){
                @Override public void write(int b){synchronized(bytes){bytes.write(b);}}
                @Override public void write(byte[] buffer,int offset,int length){synchronized(bytes){bytes.write(buffer,offset,length);}}
            };
        }
        /** Dying monster deltas per scene-delta frame: {timestamp, destroyed, activity, activityStarted}. */
        java.util.List<int[]> dyingMonsterDeltas(){
            byte[] all;synchronized(bytes){all=bytes.toByteArray();}
            int sceneCode=typeCode(all,"com.threerings.tudey.dobj.SceneDeltaEvent");
            if(sceneCode<0)return List.of();
            byte[] marker={(byte)0xDE,(byte)0x3B,0x05};
            var found=new ArrayList<int[]>();
            int at=0;
            while(at+4<=all.length){
                int len=intAt(all,at);
                if(len<4||at+len>all.length)break; // a frame still being written
                int body=at+4;at+=len;
                // A scene delta body: type(EventNotification) short, -1 short, then the scene delta type short.
                if(body+24>all.length)continue;
                if(intAt(all,body+2)>>>16!=0xFFFF||(intAt(all,body+4)>>>16)!=sceneCode)continue;
                int frameStamp=intAt(all,body+24);
                int frameEnd=at;
                for(int scan=body+30;scan+marker.length+36<=frameEnd;scan++){
                    if(all[scan]!=marker[0]||all[scan+1]!=marker[1]||all[scan+2]!=marker[2])continue;
                    // Values follow ascending bit order: destroyed(4), translation
                    // vector(2+4+4), rotation(4), flags(4), direction(4), activity(4),
                    // activityStarted(4) - so activity sits 26 bytes past the values.
                    int values=scan+3;
                    int destroyed=intAt(all,values);
                    int activity=intAt(all,values+26);
                    int started=intAt(all,values+30);
                    if(activity==2&&destroyed!=Integer.MAX_VALUE&&started>0)found.add(new int[]{frameStamp,destroyed,activity,started});
                    scan+=3; // consume the matched mask
                }
            }
            return found;
        }
        /** The interned stream code of a type name, read from its first-frame
         *  registration: writeShort(-code) then writeUTF(name). */
        private static int typeCode(byte[] all,String name){
            byte[] named=name.getBytes(StandardCharsets.US_ASCII);
            int first=indexOf(all,named);
            if(first<4)return -1;
            int declared=(short)((all[first-4]&255)<<8|(all[first-3]&255));
            if(declared>=0)return -1; // the registration writes the negative code, then the UTF length, then the name
            return -declared;
        }
        private static int indexOf(byte[] all,byte[] pattern){
            outer: for(int at=0;at<=all.length-pattern.length;at++){
                for(int i=0;i<pattern.length;i++)if(all[at+i]!=pattern[i])continue outer;
                return at;
            }
            return -1;
        }
        private static int intAt(byte[] s,int at){
            return (s[at]&255)<<24|(s[at+1]&255)<<16|(s[at+2]&255)<<8|(s[at+3]&255);
        }
        /** Local, namespace-only diagnostics for a missing death frame. */
        void printWireDiagnostics(int killed,int destroyedStamp){
            byte[] all;synchronized(bytes){all=bytes.toByteArray();}
            int sceneCode=typeCode(all,"com.threerings.tudey.dobj.SceneDeltaEvent");
            System.out.println("DIAG wire bytes="+all.length+" sceneCode="+sceneCode
                +" killed="+killed+" destroyed="+destroyedStamp);
            if(sceneCode<0)return;
            byte[] marker={(byte)0xDE,(byte)0x3B,0x05};
            int frames=0,hits=0;var activities=new HashMap<Integer,Integer>();
            int at=0;
            while(at+4<=all.length){
                int len=intAt(all,at);
                if(len<4||at+len>all.length)break;
                int body=at+4;at+=len;
                if(body+24>all.length)continue;
                if(intAt(all,body+2)>>>16!=0xFFFF||(intAt(all,body+4)>>>16)!=sceneCode)continue;
                frames++;
                for(int scan=body+30;scan+marker.length+36<=at;scan++){
                    if(all[scan]!=marker[0]||all[scan+1]!=marker[1]||all[scan+2]!=marker[2])continue;
                    int values=scan+3;
                    int destroyed=intAt(all,values);
                    int activity=intAt(all,values+26);
                    int started=intAt(all,values+30);
                    activities.merge(activity,1,Integer::sum);
                    if(destroyed!=Integer.MAX_VALUE)
                        System.out.println("DIAG monster delta in frame "+intAt(all,body+24)+": destroyed="+destroyed+" activity="
                            +activity+" started="+started);
                    hits++;scan+=3;
                }
            }
            System.out.println("DIAG scene frames="+frames+" monster deltas="+hits+" activities="+activities);
        }
    }
}
