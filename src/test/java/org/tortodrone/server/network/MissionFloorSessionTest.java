package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.wire.EnumValue;
import org.tortodrone.core.world.MapScene;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.SceneSimulation;
import static org.tortodrone.support.TestWire.check;

/** Shadow Beast runs its authored floors through native elevators and settles once. */
public final class MissionFloorSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);

    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));
        var registry=SceneRegistry.create(content);
        var previous=SceneRegistry.system();SceneRegistry.install(registry);
        Path home=Files.createTempDirectory("tortodrone-mission-floor-");
        try{
            var db=new Database(home,true);
            int account=db.create("FloorOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"FloorOwner",content.items());
            var knight=characters.create(new Knight(0,"Floor-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var seeded=characters.worldProfile(knight.name(),1);
            int world=seeded.worldId();
            db.write(c->{try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,32) ON CONFLICT(key) DO UPDATE SET value=32")){
                s.setString(1,"knight.progress."+world+".rank");s.executeUpdate();}return null;});
            var before=characters.worldProfile(knight.name(),1);
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var session=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1701,1,message->{},registry)){
                session.select(knight.name(),RECEIVERS);
                enter(session,registry,registry.catalog().scene(1));
                session.townInvocation(gateStart("shadow_beast",1,0));
                var lobby=portalDestination(session);
                check(lobby!=null&&lobby.map().id()==351,"gate start opens the authored lobby");
                enter(session,registry,lobby.map());
                var run=scene(session).run();
                check(run!=null&&run.floor()==0,"the live run starts on the lobby floor");
                int party=party(session);

                advance(session,party,0.5f,17.5f);
                check(portalDestination(session).run().floor()==1,"the lobby elevator advances to the first level");
                check(portalMapId(session)==380,"the next floor is the authored Gloaming Wildwoods Path");
                check(portalInstance(session)!=scene(session).oid(),"the advanced floor owns a distinct scene instance");
                var intermediate=characters.worldProfile(knight.name(),1);
                check(intermediate.equals(before),"an intermediate floor grants no settlement; diff="+diff(before,intermediate));
                enterNext(session,registry);

                advance(session,party,138.5f,51.5f);
                check(portalDestination(session).run().floor()==2,"the first level advances to the second");
                check(portalMapId(session)==382,"the second floor is the authored Gloaming Wildwoods Ruins");
                check(characters.worldProfile(knight.name(),1).equals(before),"the second intermediate floor grants no settlement");
                enterNext(session,registry);

                advance(session,party,50.5f,104.5f);
                check(portalDestination(session).run().floor()==3,"the second level advances to the final floor");
                check(portalMapId(session)==384,"the final floor is the authored Snarbolax arena");
                check(characters.worldProfile(knight.name(),1).equals(before),"the final report is not yet paid before the vote");
                enterNext(session,registry);

                advance(session,party,17.5f,59.5f);
                var after=characters.worldProfile(knight.name(),1);
                check(portalMapId(session)==1,"the final elevator returns to Haven");
                check(after.progress().prestige()>before.progress().prestige(),"the final floor settles the mission once");
                check(characters.openingMissions(registry.town(),registry.missions()).load(account,knight.id(),1)
                    .containsKey("shadow_beast"),"the settlement stores one Shadow Beast receipt");
                check(socket.text().contains("com.threerings.projectx.mission.data.MissionCompleteDialogInfo"),
                    "the client receives the mission completion dialog");
                session.interiorPartyInvocation(report(party,true));
                check(characters.worldProfile(knight.name(),1).equals(after),"a late report cannot settle the mission twice");
            }
            System.out.println("PASS: Shadow Beast runs lobby -> Path -> Ruins -> Snarbolax through native elevators and settles exactly once");
        }finally{SceneRegistry.install(previous);try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }

    /** Fixture placement on the authored elevator, then the native report/vote and drop. */
    private static void advance(WorldSession world,int party,float x,float y)throws Exception {
        if(!member(world).teleport(x,y,0)){
            var map=scene(world).map();
            var triggers=map.portals().triggers().stream().filter(t->t.intersects(x,y)).count();
            throw new AssertionError("server-only fixture placement on the floor elevator failed at "+x+","+y
                +" walkable="+map.collision().walkable(x,y)+" triggers="+triggers);
        }
        try{
            await(()->field(world,"floorReport")!=null,15_000);
        }catch(AssertionError timeout){
            var membership=member(world);
            var movement=(org.tortodrone.world.TownMovement)field(membership,"movement");
            var actors=scene(world).simulation().snapshot().actors();
            var elevator=actors.stream().filter(a->a.entity()!=null&&a.entity().kind().equals("EntryState")).toList();
            throw new AssertionError("floor report never opened at "+x+","+y
                +" elevatorEntry="+field(membership,"elevatorEntry")
                +" elevatorMembers="+field(membership,"elevatorMembers")
                +" trip="+field(membership,"elevatorTrip")
                +" activeAdvance="+field(membership,"activeFloorAdvance")
                +" pendingAdvance="+field(membership,"pendingFloorAdvance")
                +" tripAge="+(field(membership,"elevatorTrip")==null?null:SceneClock.now()-((org.tortodrone.world.SceneEntities.ElevatorTrip)field(membership,"elevatorTrip")).started())
                +" ticks="+scene(world).simulation().tickMetrics().ticks()
                +" scheduled="+org.tortodrone.world.SceneScheduler.shared().running(scene(world).oid())
                +" sceneFailure="+scene(world).simulation().failure()
                +" activity="+movement.activity()+" transit="+movement.inTransit()
                +" pawn="+movement.x()+","+movement.y()
                +" elevators="+elevator.stream().map(a->a.entity().key()+":state="+a.entity().state()+"@"+a.x()+","+a.y()).toList()
                +" pawns="+actors.stream().map(a->a.id()+"@"+a.x()+","+a.y()).toList(),timeout);
        }
        synchronized(world){
            @SuppressWarnings("unchecked") var report=(org.tortodrone.world.FloorReport)field(world,"floorReport");
            check(report.valid()&&report.request().source().entity()!=null,"the live elevator report belongs to the authored exit");
            check(report.request().source().entity().key().equals(scene(world).run().exitKey()),"the report exit key matches the floor");
        }
        world.interiorPartyInvocation(report(party,true));
        await(()->field(world,"portalMove")!=null,20_000);
    }

    private static void enterNext(WorldSession world,SceneRegistry registry)throws Exception {
        var destination=portalDestination(world);
        check(destination!=null,"the dropped elevator names its destination");
        enter(world,registry,destination.map());
    }

    private static int party(WorldSession world)throws Exception {
        var member=(org.tortodrone.world.PartyRegistry.Member)field(world,"partyMember");
        return world.sceneRegistry().parties().view(member).oid();
    }

    private static SceneSimulation.Membership member(WorldSession world)throws Exception {
        return (SceneSimulation.Membership)field(world,"membership");
    }

    private static void enter(WorldSession world,SceneRegistry registry,MapScene map)throws Exception {
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),map.version(),
            new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));
        world.subscribeInteriorParty((short)2);world.subscribe((short)3);world.entered();
    }
    private static SessionDecoder.Invocation gateStart(String mission,int request,int difficulty){
        return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.MISSION_SERVICE,2,
            List.of(mission,new EnumValue("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty",difficulty),
                true,false,false,new SessionDecoder.Listener(
                    "com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)request)));
    }
    private static SessionDecoder.Invocation report(int party,boolean descend){
        return new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(descend));
    }
    private static int portalMapId(WorldSession world)throws Exception{return portalDestination(world).map().id();}
    private static int portalInstance(WorldSession world)throws Exception{return portalDestination(world).oid();}
    private static SceneRegistry.Instance portalDestination(WorldSession world)throws Exception{
        synchronized(world){var field=WorldSession.class.getDeclaredField("portalMove");field.setAccessible(true);
            Object move=field.get(world);if(move==null)return null;
            return (SceneRegistry.Instance)move.getClass().getDeclaredMethod("destination").invoke(move);}
    }
    private static SceneRegistry.Instance scene(WorldSession world)throws Exception{
        synchronized(world){var field=WorldSession.class.getDeclaredField("scene");field.setAccessible(true);
            return (SceneRegistry.Instance)field.get(world);}
    }
    private static Object field(Object target,String name)throws Exception{
        synchronized(target){var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    }
    private static String diff(org.tortodrone.core.character.WorldProfile a,org.tortodrone.core.character.WorldProfile b){
        var out=new StringBuilder();
        if(a.revision()!=b.revision())out.append("revision ").append(a.revision()).append("->").append(b.revision()).append(' ');
        if(!a.progress().equals(b.progress()))out.append("progress ").append(a.progress()).append("->").append(b.progress()).append(' ');
        if(!a.items().equals(b.items()))out.append("items ").append(a.items().size()).append("->").append(b.items().size()).append(' ');
        if(!a.town().equals(b.town()))out.append("town changed ");
        if(a.crowns()!=b.crowns())out.append("crowns ").append(a.crowns()).append("->").append(b.crowns()).append(' ');
        if(a.energy()!=b.energy())out.append("energy ").append(a.energy()).append("->").append(b.energy()).append(' ');
        return out.length()==0?"none":out.toString();
    }
    private interface Check{boolean ok()throws Exception;}
    private static void await(Check check,long millis)throws Exception{
        long end=System.nanoTime()+millis*1_000_000L;
        while(System.nanoTime()<end){if(check.ok())return;Thread.sleep(10);}
        throw new AssertionError("Timed out waiting for the native mission floor");
    }
    private static final class RecordingSocket extends Socket {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        @Override public OutputStream getOutputStream(){return bytes;}
        String text(){return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);}
    }
}
