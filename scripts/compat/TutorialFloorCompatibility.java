package org.tortodrone.server.network;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.ServerObject;
import com.threerings.projectx.dungeon.data.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Original-map protocol oracle and premature-report/session rejection checks.
 * This does not pretend to drive the opening encounters or a complete knight playthrough. */
public final class TutorialFloorCompatibility {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: TutorialFloorCompatibility GAME DISPOSABLE_DEPLOYMENT_WITH_ORIGINAL_MAPS");
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var registry=ContentManager.load(Path.of(args[1])).createScenes();SceneRegistry.install(registry);
        var route=registry.tutorial();check(route!=null,"exact original scene pair and entrance must be available");
        memoryWire();
        check(route.source().version()==TutorialRoute.sourceVersion(route.source().layout())&&route.camp().version()==TutorialRoute.campVersion(route.camp().layout())&&route.entrance().id()==2017,"pinned source revisions and native Camp entrance");
        check(!route.camp().staticCollision().isEmpty(),"Camp collision is retained; rail primitives have a separate stock collision regression");
        Path home=Files.createTempDirectory("tortodrone-tutorial-floor-oracle-");
        try {
            var db=new Database(home,true);int account=db.create("FloorOwner",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"FloorOwner");
            var knight=characters.create(new Knight(0,"Floor-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var profile=characters.worldProfile(knight.name(),1);
            var parties=registry.parties();
            try(var member=parties.join(1555,knight,profile,event->{})){
                var scene=registry.destination(398,profile.worldId(),member);check(scene.tutorial()==route,"private instance owns the verified route");
                var codec=new SessionCodec();var wire=new ByteArrayOutputStream();
                append(wire,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
                append(wire,HandshakeCodec.authenticationResponse((short)2,true,"fixture","FloorOwner"));
                append(wire,codec.server((short)3,true));
                append(wire,codec.party((short)4,parties.view(member),scene));
                var initialView=parties.view(member);parties.setInviteOnly(member,false);parties.setAllowFriends(member,true);parties.setAllowGuild(member,true);
                var openView=parties.view(member);append(wire,codec.partyChanges(initialView,openView));
                parties.enterTutorial(member);append(wire,codec.partyChanges(openView,parties.view(member)));
                append(wire,codec.finalFloorReport(parties.view(member).oid()));
                append(wire,codec.party((short)5,parties.view(member),scene,true));
                var camp=registry.destination(217,profile.worldId(),member);
                append(wire,codec.sceneMove(1555,(short)6,true,camp,registry.catalog()));
                var reader=new com.threerings.io.v(new ByteArrayInputStream(wire.toByteArray()));reader.qe();reader.qe();
                var server=(ServerObject)((ObjectResponse<?>)reader.qe()).yk();
                GateSummary gate=null;for(var candidate:server.gates)if((int)field(candidate,"_gateId")==397)gate=candidate;
                check(gate!=null,"tutorial gate exists in native bootstrap");
                check(field(gate,"_name").equals("m.tutorial_b")&&field(gate,"_icon").equals("queen")&&(int)field(gate,"_flags")==3&&(int)field(gate,"_tokens")==9,"captured tutorial gate identity and flags");
                Object[] colors=(Object[])field(gate,"_colorizations");check(colors.length==2&&(int)field(colors[0],"colorization")==544&&(int)field(colors[1],"colorization")==799,"captured ordered gate colors");
                var wheels=(GateSummary.Wheel[])field(gate,"_wheels");check(wheels.length==1&&wheels[0] instanceof GateSummary.Constant,"native stationary single-wheel gate");
                var lengths=(float[])field(wheels[0],"_lengths");check(lengths.length==1&&lengths[0]==(float)(2*Math.PI)&&wheels[0].aD(0)==0,"one native full-circle wedge with valid index lookup");
                boolean campPresent=false;for(var town:server.towns)if(town.defaultSceneId==217)campPresent=true;
                check(campPresent,"Camp town is present in the bootstrap");
                var party=(LevelPartyObject)((ObjectResponse<?>)reader.qe()).yk();
                checkParty(party,false);
                for(var child:((CompoundEvent)((EventNotification)reader.qe()).yj()).xE())child.b(party);
                check(!party.inviteOnly&&party.allowFriends&&party.allowGuild,"boxed native party-setting attribute updates");
                for(var child:((CompoundEvent)((EventNotification)reader.qe()).yj()).xE())child.b(party);
                checkParty(party,false);
                var compound=(CompoundEvent)((EventNotification)reader.qe()).yj();
                check(compound.xE().size()==4,"exact final-floor transaction size");
                String[] fields={"pendingFloorType","pendingFloorDepth","pendingFloorName","returnFloor"};
                for(int i=0;i<fields.length;i++){
                    var event=(AttributeChangedEvent)compound.xE().get(i);check(event.getName().equals(fields[i]),"native report event order "+fields[i]);event.b(party);
                    if(i==2)check(party.pendingFloorType==LevelPartyObject.FloorType.NORMAL&&party.pendingFloorDepth==-1&&event.getValue()==null,"name event opens the report with already-current type/depth, including null name");
                }
                checkParty(party,true);
                checkParty((LevelPartyObject)((ObjectResponse<?>)reader.qe()).yk(),true);
                var move=(InvocationResponseEvent)((EventNotification)reader.qe()).yj();
                check(Arrays.stream(move.yd()).anyMatch(v->v instanceof com.threerings.projectx.town.data.TownSceneConfig),"native Camp town scene config");
                var model=Arrays.stream(move.yd()).filter(v->v instanceof com.threerings.tudey.data.TudeySceneModel).map(v->(com.threerings.tudey.data.TudeySceneModel)v).findFirst().orElseThrow();
                check(model.sceneId==217&&model.version==route.camp().version()&&reader.available()==0,"native Camp callback carries the original scene/version without trailing bytes");
                registry.release(scene,profile.worldId());
            }
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1556,1,m->{},registry)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                move(world,route.source().zone(),398);world.subscribeInteriorParty((short)10);world.subscribe((short)11);world.entered();
                int party=parties.view(world.partyMemberSnapshot()).oid();
                check(world.interiorPartyInvocation(new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(true))),"native reportComplete recognized without listener");
                world.interiorPartyInvocation(new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(false)));
                check(characters.lastTownScene(knight.id(),1)==0&&characters.worldProfile(knight.name(),1).revision()==profile.revision(),"premature/repeated report cannot settle or save Camp");
                check(!world.interiorPartyInvocation(new SessionDecoder.Invocation(party+1,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(true))),"foreign party cannot report completion");
                try{world.interiorPartyInvocation(new SessionDecoder.Invocation(party,SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(1)));throw new AssertionError("Wrong native report type accepted");}catch(SessionDecoder.Rejected expected){}
                move(world,route.camp().zone(),217);
                var source=registry.destination(398,profile.worldId(),world.partyMemberSnapshot());
                check(world.ownsScene(source.oid())&&characters.lastTownScene(knight.id(),1)==0,"direct Camp travel cannot substitute for the original exit");
            }
            check(registry.statuses().stream().noneMatch(s->s.templateId()==398),"disconnect releases private tutorial actors, instance and subscription");
            // Independent persisted-return fixture, not a substitute for gameplay completion.
            characters.completeTutorial(knight.id(),1,characters.worldProfile(knight.name(),1));
            var returning=new RecordingSocket();
            try(var output=new SessionOutput(returning);var world=new WorldSession(returning,characters,output,new SessionCodec(),1557,1,m->{},registry)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);
                var camp=registry.destination(217,profile.worldId());check(camp.simulation().playerCount()==0,"Ready Room reconnect does not create a Camp pawn");
                world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));move(world,route.camp().zone(),217);
                world.subscribe((short)15);world.entered();
                var own=camp.simulation().snapshot().players().stream().filter(p->p.id()==profile.worldId()).findFirst().orElseThrow();
                check(world.ownsScene(camp.oid())&&camp.simulation().playerCount()==1&&own.x()==.5f&&own.y()==-.5f,"saved Camp return attaches exactly one pawn at the original entrance transform");
                check(characters.lastTownScene(knight.id(),1)==217,"Camp entry acknowledges the persisted destination");
            }
            check(registry.destination(217,profile.worldId()).simulation().playerCount()==0,"Camp reconnect fixture leaves no pawn or subscription");
            gmPreview(db,registry);
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS: original 398/217 route; stock-reader tutorial GateSummary/LevelPartyObject, ordered final-floor report and original Camp callback; production session rejects premature/repeated/foreign/malformed completion and direct Camp travel; separate saved-Camp Ready Room return fixture attaches one pawn and cleans up");
        System.out.println("NOT VERIFIED: full fresh-knight gameplay, real boss AI/retaliation, successful session handoff after combat, graphical tutorial/Camp rendering");
    }
    /** Native /sgo 398 test entry: no position or progression mutation. */
    private static void gmPreview(Database db,SceneRegistry registry)throws Exception {
        int account=db.create("PreviewOwner",new Passwords.Protection(new byte[16],new byte[32]),true);
        var characters=new CharacterStore(db,account,"PreviewOwner");
        var knight=characters.create(new Knight(0,"Preview-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        int worldId=characters.worldProfile(knight.name(),1).worldId();
        var socket=new RecordingSocket();
        try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1558,1,m->{},registry,
                ()->{var identity=db.identity("PreviewOwner");return identity!=null&&identity.gameMaster()&&identity.authRevision()==1;})){
            world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)20);world.entered();
            var listener=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)21);
            check(world.adminPeople(new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,28,List.of("398",listener))),"native /sgo request accepted");
            move(world,registry.tutorial().source().zone(),398);world.subscribeInteriorParty((short)22);world.subscribe((short)23);world.entered();
            var scene=registry.destination(398,worldId,world.partyMemberSnapshot());
            var pawn=scene.simulation().snapshot().players().stream().filter(p->p.id()==worldId).findFirst().orElseThrow();
            check(world.ownsScene(scene.oid())&&scene.simulation().playerCount()==1&&pawn.x()==-21&&pawn.y()==-69.5f,"GM scene command enters original private tutorial at its authored spawn");
            check(characters.lastTownScene(knight.id(),1)!=217,"GM entry does not award tutorial completion or Camp arrival");
        }
        check(registry.statuses().stream().noneMatch(s->s.templateId()==398),"GM preview disconnect releases its private instance");
        System.out.println("PASS: authenticated native /sgo 398 -> zone/scene handshake -> one private pawn at entrance 2659; no completion grant; disconnect cleanup");
    }
    private static void checkParty(LevelPartyObject p,boolean pending){
        check(p.gateId==397&&p.memberLimit==1&&p.depth==0&&p.floor==0&&p.difficulty.name().equals("MEDIUM")&&p.floorType==LevelPartyObject.FloorType.TUTORIAL&&p.inviteOnly&&!p.allowFriends&&!p.allowGuild&&!p.restricted&&!p.hasPickups&&!p.playFanfare&&!p.showLoot&&!p.showElevatorChoice,"native tutorial party shape and no fabricated reward presentation");
        check(p.pendingFloorName==null&&p.pendingFloorDepth==(pending?-1:0)&&p.pendingFloorType==(pending?LevelPartyObject.FloorType.NORMAL:LevelPartyObject.FloorType.NO_DEPTH),"native pending final-floor state");
    }
    private static void memoryWire()throws Exception {
        var codec=new SessionCodec();var wire=new ByteArrayOutputStream();
        append(wire,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(wire,HandshakeCodec.authenticationResponse((short)2,true,"fixture","MemoryOwner"));
        append(wire,codec.player((short)3,1555,1,"MemoryOwner"));
        append(wire,codec.knightMemories(1555,Set.of("talkto_rhendon_icon")));
        append(wire,codec.knightMemoryChanges(1555,Set.of("talkto_rhendon_icon"),Set.of("talkto_rhendon_icon","fixture_other")));
        append(wire,codec.knightMemoryChanges(1555,Set.of("talkto_rhendon_icon","fixture_other"),Set.of("fixture_other")));
        append(wire,codec.knightMemoryChanges(1555,Set.of("fixture_other"),Set.of()));
        var in=new com.threerings.io.v(new ByteArrayInputStream(wire.toByteArray()));in.qe();in.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((ObjectResponse<?>)in.qe()).yk();
        ((EventNotification)in.qe()).yj().b(player);
        var memory=player.memories.f("talkto_rhendon_icon");
        check(player.memories.size()==1&&memory!=null&&memory.persistence==com.threerings.projectx.config.Persistence.KNIGHT&&memory.lj().equals("talkto_rhendon_icon"),"stock Memory persistence/identity and native DSet snapshot");
        for(var child:((CompoundEvent)((EventNotification)in.qe()).yj()).xE())child.b(player);
        check(player.memories.size()==2&&player.memories.f("fixture_other")!=null,"native EntryAddedEvent informs memory observers");
        for(var child:((CompoundEvent)((EventNotification)in.qe()).yj()).xE())child.b(player);
        check(player.memories.size()==1&&player.memories.f("talkto_rhendon_icon")==null,"native erase removes the named memory without changing its peer");
        for(var child:((CompoundEvent)((EventNotification)in.qe()).yj()).xE())child.b(player);
        check(player.memories.isEmpty()&&in.available()==0,"native clear and stream alignment");
        System.out.println("PASS: stock reader accepts KNIGHT Memory DSet snapshot and native add/remove observer events");
    }
    private static Object field(Object object,String name)throws Exception {for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void move(WorldSession world,int zone,int scene)throws IOException {world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(zone,scene,0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)12))));}
    private static final class RecordingSocket extends Socket {private final OutputStream sink=OutputStream.nullOutputStream();@Override public OutputStream getOutputStream(){return sink;}}
}
