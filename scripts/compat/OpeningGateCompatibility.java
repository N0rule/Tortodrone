package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.ServerObject;
import com.threerings.projectx.dungeon.data.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Original-map native protocol/entry acceptance from an explicit post-Camp fixture.
 * Does not claim to play Generator encounters or settle its final exit. */
public final class OpeningGateCompatibility {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("GAME BASE RAW337 RAW218");
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var content=ContentManager.load(Path.of(args[1]));var base=content.createScenes();
        var maps=new TreeMap<Integer,SceneCatalog.MapScene>();for(var map:base.catalog().scenes())maps.put(map.id(),map);
        maps.put(337,SceneCatalog.loadTemplate(337,Path.of(args[2])));maps.put(218,SceneCatalog.loadTemplate(218,Path.of(args[3])));
        var registry=new SceneRegistry(SceneCatalog.of(maps,base.catalog().entry().id()));registry.town(base.town());registry.weapons(base.weapons());registry.equipment(base.equipment());registry.fires(base.fires());registry.petFires(content.petFires());SceneRegistry.install(registry);
        var gate=registry.openingGate(OpeningMission.ANCIENT_GENERATOR);check(gate!=null,"original Generator, powered Camp, entrances and native elevator required");
        var home=Files.createTempDirectory("tortodrone-opening-gate-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("GateOwner",protection,false),peerAccount=db.create("GatePeer",protection,false);
            var owner=new CharacterStore(db,account,"GateOwner",content.items());var peer=new CharacterStore(db,peerAccount,"GatePeer",content.items());
            var knight=owner.createForOpening(knight("Gate-Owner"),1);var other=peer.createForOpening(knight("Gate-Peer"),1);
            var initial=owner.worldProfile(knight.name(),1);
            try(var output=new SessionOutput(new RecordingSocket());var world=new WorldSession(new RecordingSocket(),owner,output,new SessionCodec(content.items()),1010,1,message->{},registry)){
                world.select(knight.name(),RECEIVERS,true);world.townInvocation(request(2,"ancient_generator",0,true,false,false,1));
                check(field(world,"portalMove")==null&&owner.worldProfile(knight.name(),1).equals(initial),"fresh tutorial-incomplete knight cannot skip directly into the mission");
            }
            var checkpoint=checkpoint(owner,knight,registry);var peerCheckpoint=checkpoint(peer,other,registry);
            protocol(registry,owner,knight,checkpoint,content.items(),gate);
            try(var output=new SessionOutput(new RecordingSocket());var interrupted=new WorldSession(new RecordingSocket(),owner,output,new SessionCodec(content.items()),1011,1,message->{},registry)){
                interrupted.select(knight.name(),RECEIVERS,true);interrupted.townInvocation(request(2,"ancient_generator",0,true,false,false,2));
                check(field(interrupted,"portalMove")!=null&&active(registry,337)==1,"native start prepares one owned original floor without attaching a phantom player");
            }
            check(active(registry,337)==0&&owner.worldProfile(knight.name(),1).equals(checkpoint),"disconnect during forced movement frees the instance and grants no progression");
            var socket=new RecordingSocket();var peerSocket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var peerOutput=new SessionOutput(peerSocket);
                var world=new WorldSession(socket,owner,output,new SessionCodec(content.items()),1012,1,message->{},registry);
                var peerWorld=new WorldSession(peerSocket,peer,peerOutput,new SessionCodec(content.items()),1013,1,message->{},registry)){
                try{peerWorld.select(knight.name(),RECEIVERS,true);throw new AssertionError("Foreign knight selected");}catch(CharacterStore.Refused|IOException expected){}
                world.select(knight.name(),RECEIVERS,true);peerWorld.select(other.name(),RECEIVERS,true);
                int camp=registry.destination(217,checkpoint.worldId()).oid();check(registry.destination(217,checkpoint.worldId()).simulation().playerCount()==0,"Ready Room still has no Camp pawn");
                for(var invalid:List.of(request(2,"arbitrary",0,true,false,false,3),request(2,"ancient_generator",3,true,false,false,4),request(2,"ancient_generator",0,false,false,false,5),request(2,"ancient_generator",0,true,true,false,6))){world.townInvocation(invalid);check(field(world,"portalMove")==null,"invalid/ineligible/unsupported party intent changes no route");}
                world.townInvocation(request(7,"ancient_generator",0,true,false,false,7));check(field(world,"portalMove")==null&&active(registry,337)==0,"native Result preflight has no instance or inventory mutation");
                var start=request(2,"ancient_generator",0,true,false,false,8);
                var one=CompletableFuture.runAsync(()->invoke(world,start));var two=CompletableFuture.runAsync(()->invoke(world,start));one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);
                check(active(registry,337)==1&&owner.worldProfile(knight.name(),1).equals(checkpoint),"concurrent duplicate native starts allocate once and pay nothing");
                enter(world,registry,checkpoint.worldId());var scene=(SceneRegistry.Instance)field(world,"scene");
                check(scene.opening()!=null&&scene.map()==gate.source()&&scene.simulation().playerCount()==1&&!world.ownsScene(camp),"stock move/party subscription attaches the authorized mission instance once");
                var pawn=scene.simulation().snapshot().players().getFirst();check(pawn.id()==checkpoint.worldId()&&pawn.x()==.5f&&pawn.y()==.5f,"real original entrance 82, no direct position mutation");
                var member=(PartyRegistry.Member)field(world,"partyMember");int party=registry.parties().view(member).oid();
                world.interiorPartyInvocation(new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(true)));
                check(owner.worldProfile(knight.name(),1).equals(checkpoint)&&field(world,"floorReport")==null,"premature report cannot complete a mission, grant rewards or bypass encounters");
                peerWorld.townInvocation(request(2,"ancient_generator",0,true,false,false,9));enter(peerWorld,registry,peerCheckpoint.worldId());var peerScene=(SceneRegistry.Instance)field(peerWorld,"scene");
                check(peerScene!=scene&&peerScene.oid()!=scene.oid()&&peerScene.simulation().playerCount()==1&&active(registry,337)==2,"two accounts own separate native mission parties and simulations");
                check(!peerWorld.interiorPartyInvocation(new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(true))),"foreign party cannot submit a report");
                check(owner.worldProfile(knight.name(),1).equals(checkpoint)&&peer.worldProfile(other.name(),1).equals(peerCheckpoint),"entry and unauthorized reports grant no progression/rewards");
            }
            check(active(registry,337)==0,"all private floor actors/subscriptions are released after disconnect");
            try(var output=new SessionOutput(new RecordingSocket());var reconnect=new WorldSession(new RecordingSocket(),owner,output,new SessionCodec(content.items()),1014,1,message->{},registry)){
                reconnect.select(knight.name(),RECEIVERS,true);check((boolean)field(reconnect,"readyRoom")&&((SceneRegistry.Instance)field(reconnect,"scene")).map().id()==217,"interrupted mission reconnect returns to the saved Camp through Ready Room");
            }
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("PASS: original 337/218 native mission gate/party/report serialization; production mission preflight/start, Ready Room departure, exact entrance, ownership, duplicate/concurrent requests, premature reports, interrupted travel, reconnect and cleanup");
        System.out.println("POST-CAMP FIXTURE ONLY: Generator combat/traversal, payout/travel and complete fresh-knight-to-Haven acceptance are not established by this check.");
    }
    private static void protocol(SceneRegistry registry,CharacterStore owner,Knight knight,WorldProfile profile,ItemCatalog items,OpeningGate gate)throws Exception {
        var parties=registry.parties();
        try(var member=parties.join(1009,knight,profile,event->{})){
            var scene=registry.startOpening(gate,profile.worldId(),member,"EASY");
            try{
                var codec=new SessionCodec(items);var bytes=new ByteArrayOutputStream();append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"fixture","GateOwner"));
                append(bytes,codec.server((short)3,true));append(bytes,codec.party((short)4,parties.view(member),scene));append(bytes,codec.openingFloorReport(parties.view(member).oid()));
                var reader=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));reader.qe();reader.qe();
                var server=(ServerObject)((ObjectResponse<?>)reader.qe()).yk();GateSummary nativeGate=null;for(var candidate:server.gates)if((int)field(candidate,"_gateId")==398)nativeGate=candidate;
                check(nativeGate!=null,"native server advertises the available Generator mission gate");
                check(nativeGate.getClass()==MissionGateSummary.class&&field(nativeGate,"_name").equals("%mission:m.ancient_generator")&&(int)field(nativeGate,"_flags")==8&&(int)field(nativeGate,"_ownerKnightId")==0,"current native mission gate shape, including owner field absent from older archive classes");
                var wheels=(GateSummary.Wheel[])field(nativeGate,"_wheels");check(wheels.length==2&&((GateSummary.Wedge[])field(wheels[1],"_wedges")).length==1,"both captured gate wheels retained");
                var nativeParty=(MissionPartyObject)((ObjectResponse<?>)reader.qe()).yk();
                check(nativeParty.missionId.equals("ancient_generator")&&nativeParty.gateId==398&&nativeParty.floor==1&&nativeParty.depth==1&&nativeParty.levelType==com.threerings.projectx.dungeon.data.DungeonCodes.LevelType.LEVEL&&nativeParty.floorType==LevelPartyObject.FloorType.NORMAL&&nativeParty.difficulty.name().equals("EASY")&&nativeParty.memberLimit==1&&nativeParty.inviteOnly&&nativeParty.wedgeIndices.length==2,"native mission party identity, floor/depth/difficulty and solo settings");
                var compound=(com.threerings.presents.dobj.CompoundEvent)((EventNotification)reader.qe()).yj();for(var event:compound.xE())event.b(nativeParty);
                check(nativeParty.playFanfare&&nativeParty.showLoot&&!nativeParty.showElevatorChoice&&nativeParty.pendingFloorDepth==-1&&nativeParty.pendingFloorType==LevelPartyObject.FloorType.NORMAL&&nativeParty.pendingFloorName==null&&reader.available()==0,"ordered native terminal report fields and exact stream alignment");
            }finally{registry.release(scene,profile.worldId());}
        }
    }
    private static WorldProfile checkpoint(CharacterStore store,Knight knight,SceneRegistry registry)throws Exception {
        var p=store.completeTutorial(knight.id(),1,store.worldProfile(knight.name(),1),0,registry.town());
        p=store.knightStory(knight.id(),1,p,List.of(new KnightMemory("talkedRhendonRC",true)),List.of()).profile();
        return store.openingMissions(registry.town()).complete(store.accountId(),knight.id(),1,p,OpeningMission.SECURING_CAMP,null).profile();
    }
    private static void enter(WorldSession world,SceneRegistry registry,int id)throws Exception {
        var map=registry.catalog().scene(337);
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),337,1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)31))));
        world.subscribeInteriorParty((short)32);world.subscribe((short)33);world.entered();
    }
    private static SessionDecoder.Invocation request(int method,String id,int band,boolean solo,boolean friends,boolean guild,int request){return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.MISSION_SERVICE,method,List.of(id,new SessionDecoder.EnumValue("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty",band),solo,friends,guild,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$"+(method==7?"ResultMarshaller":"ConfirmMarshaller"),(short)request)));}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static long active(SceneRegistry registry,int template){return registry.statuses().stream().filter(s->s.templateId()==template).count();}
    private static void invoke(WorldSession world,SessionDecoder.Invocation request){try{world.townInvocation(request);}catch(IOException e){throw new CompletionException(e);}}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static Object field(Object object,String name)throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    private static final class RecordingSocket extends Socket {private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();@Override public OutputStream getOutputStream(){return bytes;}}
}
