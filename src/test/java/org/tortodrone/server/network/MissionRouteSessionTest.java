package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.mission.MissionRun;
import org.tortodrone.core.wire.EnumValue;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.SceneRegistry;
import static org.tortodrone.support.TestWire.check;

/** Imported mission routes start through the native gate call and keep their authored floors. */
public final class MissionRouteSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);

    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));
        var registry=SceneRegistry.create(content);
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-mission-route-");
        try{
            var db=new Database(home,true);
            int account=db.create("MissionOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"MissionOwner",content.items());
            var knight=characters.create(new Knight(0,"Mission-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            int world=characters.worldProfile(knight.name(),1).worldId();
            db.write(c->{try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,32) ON CONFLICT(key) DO UPDATE SET value=32")){
                s.setString(1,"knight.progress."+world+".rank");s.executeUpdate();}return null;});
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var session=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1501,1,message->{},registry)){
                session.select(knight.name(),RECEIVERS);
                enter(session,registry,1);
                check(!socket.text().contains("o.shadow_beast"),"the town HUD carries no mission objective");
                check(registry.missionRoute("shadow_beast",world,1,"MEDIUM")!=null,"the bundled Shadow Beast route is playable");
                session.townInvocation(gateStart("shadow_beast",1,0));
                var destination=portalDestination(session);
                check(destination!=null&&destination.map().id()==351,"gate start forces the authored lobby scene");
                enter(session,registry,351);
                check(sceneId(session)==351,"the mission lobby becomes the owned scene");
                MissionRun run=sceneRun(session);
                check(run!=null&&run.floors().size()==4&&run.floors().getFirst().scene().id()==351,
                    "the live scene keeps the authored four-floor mission route");
                check(scene(session).missionGateId()==run.gateId()&&scene(session).missionFloor()==0,
                    "the party view exposes the mission gate and current floor");
                check(session.sceneEntered()&&session.sceneSubscribed(),"the lobby accepts native readiness");
                check(socket.text().contains("o.shadow_beast"),"the mission bar publishes the mission's own o.<id> objective");
                session.townInvocation(gateStart("shadow_beast",2,0));
                check(sceneId(session)==351,"a repeated gate start keeps the current mission scene");
            }
        }finally{SceneRegistry.install(previous);try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        System.out.println("PASS: imported Shadow Beast gate start forces the authored lobby, keeps four floors and stays in the current mission scene on repeat");
    }

    private static void enter(WorldSession world,SceneRegistry registry,int sceneId)throws Exception {
        var map=registry.catalog().scene(sceneId);
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),map.version(),
            new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));
        world.subscribe((short)2);world.entered();
    }
    private static SessionDecoder.Invocation gateStart(String mission,int request,int difficulty){
        return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.MISSION_SERVICE,2,
            List.of(mission,new EnumValue("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty",difficulty),
                true,false,false,new SessionDecoder.Listener(
                    "com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)request)));
    }
    private static int sceneId(WorldSession world)throws Exception{return scene(world).map().id();}
    private static SceneRegistry.Instance portalDestination(WorldSession world)throws Exception{
        synchronized(world){var field=WorldSession.class.getDeclaredField("portalMove");field.setAccessible(true);
            Object move=field.get(world);if(move==null)return null;
            return (SceneRegistry.Instance)move.getClass().getDeclaredMethod("destination").invoke(move);}
    }
    private static MissionRun sceneRun(WorldSession world)throws Exception{return scene(world).run();}
    private static SceneRegistry.Instance scene(WorldSession world)throws Exception{
        synchronized(world){var field=WorldSession.class.getDeclaredField("scene");field.setAccessible(true);
            return (SceneRegistry.Instance)field.get(world);}
    }
    private static final class RecordingSocket extends Socket {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        @Override public OutputStream getOutputStream(){return bytes;}
        String text(){return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);}
    }
}
