package org.tortodrone.server.network;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.resources.MapYaml;
import org.tortodrone.storage.Database;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Crash Site stateful cameras and pulse cables spawn inert the native way: the authored
 * "Startup State: Off" choice has the placeable's Startup handler signal each DumbSwitch to
 * state 1 at its creation tick (archive actor.xml; live wire session-2026-09-10 has 217's
 * Off beacons arriving at state 1 entered at their creation tick), and Switch.setState keeps
 * the START flag exactly in state 0. DumbSwitch signals wrap like DumbSwitchLogic. Verified
 * against the stock flow: no priority-100 camera bounds the tutorial, so the opening comlink
 * keeps the player camera. */
public final class StatefulCameraSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        checkStartupStates();
        var content=ContentManager.load(Path.of("."));var registry=content.createScenes();
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-stateful-camera-");
        try {
            var db=new Database(home,true);int account=db.create("CameraOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"CameraOwner",content.items());
            var knight=characters.createForOpening(new Knight(0,"Camera-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
            var route=registry.tutorial();
            try(var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1603,1,m->{},registry)) {
                world.select(knight.name(),RECEIVERS,true);move(world,route.source());
                world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
                var simulation=((SceneRegistry.Instance)field(world,"scene")).simulation();
                // The three authored cameras spawn in state 1 (extent 0.01, inert model) with the
                // native START flag cleared; stateEntered stays their creation tick.
                var cameras=simulation.snapshot().actors().stream().filter(a->a.config().name().equals("Environment/Stateful Camera")).toList();
                check(cameras.size()==3,"three authored stateful cameras present: "+cameras.size());
                for(var camera:cameras){
                    check(camera.entity().state()==1,"camera "+camera.id()+" starts in native state 1, not "+camera.entity().state());
                    check((camera.flags()&2)==0,"camera "+camera.id()+" clears START outside state 0: flags "+camera.flags());
                    check(camera.entity().stateEntered()==camera.created(),"camera "+camera.id()+" enters its startup state at its creation tick");
                }
                var cables=simulation.snapshot().actors().stream().filter(a->a.config().name().equals("Static/Stateful Props/Cables and Wires/Hanging Cable, Pulse")).toList();
                check(cables.size()==4,"four pulse cables present: "+cables.size());
                for(var cable:cables){
                    check(cable.entity().state()==1,"pulse cable "+cable.id()+" starts in native state 1, not "+cable.entity().state());
                    check((cable.flags()&2)==0,"pulse cable "+cable.id()+" clears START outside state 0: flags "+cable.flags());
                }
                // DumbSwitchLogic signal semantics on the tagged tutorial camera: next wraps
                // 1 -> 0 and re-arms START, repeated state names are change-guarded, unknown
                // names are swallowed.
                var entities=entityField(simulation);
                var signal=SceneEntities.class.getDeclaredMethod("signal",String.class,String.class,int.class,String.class);
                signal.setAccessible(true);
                signal(simulation,signal,entities,"rhendonCam","next");
                int cameraId=tutorialCamera(cameras);
                var rhendon=simulation.snapshot().actors().stream().filter(a->a.id()==cameraId).findFirst().orElseThrow();
                check(rhendon.entity().state()==0&&(rhendon.flags()&2)!=0,"signal next wraps the Off camera back to state 0 and re-arms START");
                int entered=rhendon.entity().stateEntered();
                signal(simulation,signal,entities,"rhendonCam","0");
                rhendon=simulation.snapshot().actors().stream().filter(a->a.id()==cameraId).findFirst().orElseThrow();
                check(rhendon.entity().state()==0&&rhendon.entity().stateEntered()==entered,"numeric repeat of the current state is change-guarded");
                signal(simulation,signal,entities,"rhendonCam","1");
                rhendon=simulation.snapshot().actors().stream().filter(a->a.id()==cameraId).findFirst().orElseThrow();
                check(rhendon.entity().state()==1&&(rhendon.flags()&2)==0,"numeric signal 1 returns the camera to native state 1 and clears START");
                signal(simulation,signal,entities,"rhendonCam","not-a-state");
                rhendon=simulation.snapshot().actors().stream().filter(a->a.id()==cameraId).findFirst().orElseThrow();
                check(rhendon.entity().state()==1,"non-numeric signal names are swallowed");
                var others=simulation.snapshot().actors().stream().filter(a->a.config().name().equals("Environment/Stateful Camera")&&a.id()!=cameraId).toList();
                check(others.stream().allMatch(a->a.entity().state()==1),"tagged signals touch only the matching camera");
            }
            System.out.println("PASS: stateful cameras and pulse cables spawn at native Startup State 1 with START cleared, and DumbSwitch signals wrap and re-flag like DumbSwitchLogic");
        } finally {SceneRegistry.install(previous);ContentFixture.delete(home);}
    }

    /** The remaining shipped Startup State families prepare startup-state/switch-states fields. */
    private static void checkStartupStates()throws IOException {
        var content=ContentManager.load(Path.of("."));
        for(var folder:MapYaml.folders(Path.of("content/maps")).entrySet()) {
            var prepared=MapYaml.prepare(folder.getValue(),Path.of("content/shared"),content.items());
            int beacons=0,borders=0;
            for(String id:prepared.content().getProperty("entries","").split(",")) {
                String config=prepared.content().getProperty("entry."+id+".config","");
                String states=prepared.content().getProperty("entry."+id+".switch-states","");
                String startup=prepared.content().getProperty("entry."+id+".startup-state","");
                if(config.equals("Static/Stateful Props/Beacons, Floor/Recolor, On-Off")){
                    beacons++;
                    check(states.equals("2")&&startup.equals("1"),"rescue camp beacon "+id+" prepares native startup state 1: "+states+"/"+startup);
                }else if(config.startsWith("Dynamic/Misc/Border/Stateful/")){
                    borders++;
                    if(!states.isBlank())check(states.equals("2")&&startup.isBlank(),"bazaar border "+id+" prepares only its native state count: "+states+"/"+startup);
                }
            }
            if(folder.getValue().getFileName().toString().equals("rescue_camp"))check(beacons==14,"fourteen rescue camp beacons: "+beacons);
            if(folder.getValue().getFileName().toString().equals("bazaar"))check(borders==5,"five bazaar stateful borders: "+borders);
        }
    }

    /** The authored tutorial camera is the rhendonCam-tagged one; runtime keys are source keys. */
    private static int tutorialCamera(List<org.tortodrone.world.ActorState> cameras){
        return cameras.stream().filter(a->a.entity()!=null&&a.entity().key().equals("2959")).findFirst().orElseThrow().id();
    }
    private static Object entityField(SceneSimulation simulation)throws Exception {synchronized(simulation){var f=SceneSimulation.class.getDeclaredField("entities");f.setAccessible(true);return f.get(simulation);}}
    private static void signal(SceneSimulation simulation,Method signal,Object entities,String tag,String name)throws Exception {synchronized(simulation){signal.invoke(entities,tag,name,SceneClock.now(),"test");}}
    private static Object field(WorldSession world,String name)throws Exception {synchronized(world){var f=WorldSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(world);}}
    private static void move(WorldSession world,SceneCatalog.MapScene map)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));}
    private static final class RecordingSocket extends Socket {@Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}}
}
