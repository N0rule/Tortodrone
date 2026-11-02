package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Original Camp content through real movement, interaction, conversation, session and SQLite.
 * Starts at a completed-tutorial checkpoint; does not claim to play scene 398. */
public final class CampStoryCompatibility {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of(args[0]));var scenes=content.createScenes();SceneRegistry.install(scenes);
        var route=scenes.tutorial();check(route!=null,"original 398/217 content required");
        var map=route.camp();check(map.id()==217&&map.version()==373,"pinned initial Camp revision");
        var binding=map.content().conversations().get("Character/NPC/Rescue Camp/Greta 2");
        check(binding!=null&&binding.nodes().size()==3,"native three-panel Greta graph retained");
        Path home=Files.createTempDirectory("tortodrone-camp-story-");
        try(var accounts=new AccountStore(home,false,content.items())){
            accounts.create("CampOwner","camp-password".toCharArray());accounts.create("CampPeer","camp-password".toCharArray());
            var db=new Database(home,false);var owner=new CharacterStore(db,1,"CampOwner",content.items());
            var peer=new CharacterStore(db,2,"CampPeer",content.items());
            var knight=owner.createForOpening(knight("Camp-Knight"),1);var other=peer.createForOpening(knight("Camp-Peer"),1);
            // Checkpoint setup only. Success path below never mutates position or story state.
            var before=owner.completeTutorial(knight.id(),1,owner.worldProfile(knight.name(),1));
            var untouched=peer.worldProfile(other.name(),1);
            try(var player=new Player(owner,knight.id(),scenes,1001)){
                player.world.select(knight.name(),RECEIVERS,true);
                player.world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                player.world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)1))));
                player.world.subscribe((short)2);player.world.entered();
                var place=scenes.destination(217,before.worldId());
                var greta=place.simulation().snapshot().actors().stream().filter(a->a.config().name().equals(binding.actor())).findFirst().orElseThrow();
                walk(player.world,place,before.worldId(),greta.x(),greta.y()-1.15f);
                var pawn=pawn(place,before.worldId());float face=(float)Math.atan2(greta.y()-pawn.y(),greta.x()-pawn.x());
                input(player.world,place,face,0);Thread.sleep(150);input(player.world,place,face,4);
                await(()->player.socket.contains("m.rc_greet2_1a"));input(player.world,place,face,0);
                var first=response(place,0,(short)10);check(player.world.conversationInvocation(first),"native conversation service accepts owned interaction");
                await(()->player.socket.contains("m.rc_greet2_2a"));
                player.world.conversationInvocation(first);check(!player.socket.contains("m.rc_greet2_3a"),"duplicate request cannot advance a second panel");
                player.world.conversationInvocation(response(place,9,(short)11));check(!player.socket.contains("m.rc_greet2_3a"),"invalid response cannot advance");
                player.world.conversationInvocation(response(place,0,(short)12));await(()->player.socket.contains("m.rc_greet2_3a"));
                check(owner.knightObjectives(knight.id(),1).isEmpty()&&!owner.knightMemories(knight.id(),1).contains("talkedGreta2"),"opening/reading panels alone grants nothing");
                var terminal=response(place,0,(short)13);player.world.conversationInvocation(terminal);
                await(()->owner.knightObjectives(knight.id(),1).containsKey("findRhendon"));
                await(()->player.socket.objectivePublished);
                var settled=owner.worldProfile(knight.name(),1);var objective=owner.knightObjectives(knight.id(),1).get("findRhendon");
                check(objective.index()==0&&objective.text().equals("m.find_rhendon")&&owner.knightMemories(knight.id(),1).contains("talkedGreta2"),"authored final action commits memory and first native objective");
                player.world.conversationInvocation(terminal);Thread.sleep(150);
                check(owner.worldProfile(knight.name(),1).equals(settled),"duplicate terminal has no extra revision/reward");
                check(settled.items().equals(before.items())&&settled.progress().equals(before.progress()),"Greta cannot fabricate mission rewards or rank");
                player.world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.MISSION_SERVICE,3,List.of("arbitrary_mission",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)19))));
                check(owner.worldProfile(knight.name(),1).equals(settled)&&player.socket.contains("m.internal_error"),"unsupported native mission attempt fails through its listener without arbitrary completion");
                var rhendon=place.simulation().snapshot().actors().stream().filter(a->a.config().name().equals("Character/NPC/Rescue Camp/Rhendon, Rescue Camp")).findFirst().orElseThrow();
                walk(player.world,place,before.worldId(),rhendon.x(),rhendon.y()-1.15f);
                pawn=pawn(place,before.worldId());face=(float)Math.atan2(rhendon.y()-pawn.y(),rhendon.x()-pawn.x());
                input(player.world,place,face,0);Thread.sleep(150);input(player.world,place,face,4);
                await(()->player.socket.contains("m.securing_camp_1a"));input(player.world,place,face,0);
                await(()->owner.knightMemories(knight.id(),1).contains("talkedRhendonRC"));
                check(owner.worldProfile(knight.name(),1).progress().rankPair().equals("1-1")&&owner.openingMissions(scenes.town()).load(1,knight.id(),1).isEmpty(),"Rhendon opener remembers the briefing without completing it");
                player.world.conversationInvocation(response(place,0,(short)20));await(()->player.socket.contains("m.securing_camp_2a"));
                player.world.conversationInvocation(response(place,0,(short)21));await(()->player.socket.contains("m.securing_camp_3a"));
                var briefing=owner.worldProfile(knight.name(),1);
                String receipt="world."+before.worldId()+".mission.securing_camp";
                db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_mission BEFORE INSERT ON metadata WHEN NEW.key='"+receipt+"' BEGIN SELECT RAISE(ABORT,'injected terminal failure'); END");}return null;});
                player.world.conversationInvocation(response(place,0,(short)22));
                check(owner.worldProfile(knight.name(),1).equals(briefing)&&!player.socket.missionPublished&&owner.knightObjectives(knight.id(),1).containsKey("findRhendon"),"terminal rollback keeps briefing, objective, inventory and rank consistent without publishing completion");
                db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_mission");}return null;});
                var finalResponse=response(place,0,(short)23);player.world.conversationInvocation(finalResponse);await(()->player.socket.missionPublished);
                var completed=owner.worldProfile(knight.name(),1);
                check(completed.progress().rankPair().equals("1-2")&&completed.progress().prestige()==10&&owner.knightObjectives(knight.id(),1).isEmpty(),"only the correct terminal clears objective and grants native rank/prestige");
                player.world.conversationInvocation(finalResponse);Thread.sleep(150);check(owner.worldProfile(knight.name(),1).equals(completed),"duplicate terminal never pays twice");
                var dialogField=WorldSession.class.getDeclaredField("missionDialogCreated");dialogField.setAccessible(true);long dialog=dialogField.getLong(player.world);
                player.world.townInvocation(new SessionDecoder.Invocation(place.oid(),6,1,List.of(0L)));
                check(dialog>0&&dialogField.getLong(player.world)==dialog,"a stale conversation close cannot dismiss the new completion window");
                player.world.townInvocation(new SessionDecoder.Invocation(place.oid(),6,1,List.of(dialog)));
                check(dialogField.getLong(player.world)==0,"the exact current completion close succeeds");
                Thread.sleep(1800);input(player.world,place,face,4);await(()->player.socket.contains("m.securing_camp_4a"));input(player.world,place,face,0);
                player.world.conversationInvocation(response(place,0,(short)24));Thread.sleep(150);
                check(owner.worldProfile(knight.name(),1).equals(completed),"completed Rhendon follow-up closes without rewards or repeated revision");
            }
            try(var player=new Player(owner,knight.id(),scenes,1002)){
                player.world.select(knight.name(),RECEIVERS,true);await(()->player.socket.missionPublished);
                check(player.socket.contains("talkedGreta2")&&owner.knightObjectives(knight.id(),1).isEmpty(),"Ready Room reconnect restores completed briefing with objective cleared");
            }
            check(peer.worldProfile(other.name(),1).equals(untouched)&&peer.knightObjectives(other.id(),1).isEmpty(),"peer identity/progression unchanged");
            check(scenes.destination(217,before.worldId()).simulation().playerCount()==0,"disconnect and Ready Room leave no Camp occupants");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: original Camp 217/v373 -> legal movement -> Greta -> objective -> Rhendon three-panel briefing -> rollback/retry -> native mission completion, exact rewards/rank/prestige -> duplicate protection -> Ready Room reconnect (tutorial checkpoint setup only; no success-path position/story mutation)");
    }
    private static void walk(WorldSession world,SceneRegistry.Instance place,int id,float x,float y)throws Exception {
        var start=pawn(place,id);var simulation=place.simulation();
        var path=ScenePathing.route(start.x(),start.y(),x,y,(sx,sy,tx,ty)->{
            int n=Math.max(1,(int)Math.ceil(Math.hypot(tx-sx,ty-sy)/.1));
            for(int i=1;i<=n;i++)if(!simulation.placement(sx+(tx-sx)*i/n,sy+(ty-sy)*i/n))return false;return true;
        });check(!path.isEmpty(),"native collision route from entrance to Greta");long until=System.nanoTime()+20_000_000_000L;
        for(var point:path){while(true){var p=pawn(place,id);if(Math.hypot(point.x()-p.x(),point.y()-p.y())<.13)break;
            if(System.nanoTime()>until)throw new AssertionError("Camp walk blocked at "+p.x()+","+p.y()+" toward "+point);
            input(world,place,(float)Math.atan2(point.y()-p.y(),point.x()-p.x()),1);Thread.sleep(25);
        }}input(world,place,0,0);
    }
    private static ActorState pawn(SceneRegistry.Instance place,int id){return place.simulation().snapshot().players().stream().filter(p->p.id()==id).findFirst().orElseThrow();}
    private static void input(WorldSession world,SceneRegistry.Instance place,float face,int flags)throws IOException {int t=SceneClock.now();world.input(new SessionDecoder.Input(place.oid(),0,t,List.of(new SessionDecoder.InputFrame(t,face,face,flags,t))));}
    private static SessionDecoder.Invocation response(SceneRegistry.Instance place,int choice,short request){return new SessionDecoder.Invocation(place.oid(),SceneConversation.SERVICE,1,List.of(choice,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",request)));}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Check{boolean ok()throws Exception;}
    private static void await(Check check)throws Exception{long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){if(check.ok())return;Thread.sleep(10);}throw new AssertionError("Timed out waiting for native Camp story state");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Player implements AutoCloseable {
        final RecordingSocket socket;final SessionOutput output;final WorldSession world;
        Player(CharacterStore characters,int knight,SceneRegistry scenes,int oid)throws IOException{socket=new RecordingSocket(characters,knight);output=new SessionOutput(socket);world=new WorldSession(socket,characters,output,new SessionCodec(characters.catalog()),oid,1,m->{},scenes);}
        public void close()throws IOException{world.close();output.close();socket.close();}
    }
    private static final class RecordingSocket extends Socket {
        final ByteArrayOutputStream frames=new ByteArrayOutputStream();final CharacterStore characters;final int knight;volatile boolean objectivePublished,missionPublished;
        RecordingSocket(CharacterStore characters,int knight){this.characters=characters;this.knight=knight;}
        synchronized boolean contains(String value){return frames.toString(StandardCharsets.ISO_8859_1).contains(value);}
        @Override public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError("Whole frames required");}
            public void write(byte[] bytes)throws IOException{String text=new String(bytes,StandardCharsets.ISO_8859_1);
                if(text.contains("findRhendon")){check(characters.knightObjectives(knight,1).containsKey("findRhendon")&&characters.knightMemories(knight,1).contains("talkedGreta2"),"story publication follows commit");objectivePublished=true;}
                if(text.contains("com.threerings.projectx.mission.data.MissionCompletion")){
                    WorldProfile p;try{p=characters.worldProfile("Camp-Knight",1);}catch(CharacterStore.Refused failure){throw new IOException(failure);}check(p.progress().rankPair().equals("1-2")&&p.progress().prestige()==10&&characters.knightObjectives(knight,1).isEmpty(),"mission presentation follows rank/objective commit");
                    check(p.items().stream().filter(i->i.config().equals("Rarity/Fire Crystal/Cracked")).mapToInt(WorldProfile.Item::count).sum()==15&&p.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")).mapToInt(WorldProfile.Item::count).sum()==5,"rewards committed before native mission publication");missionPublished=true;
                }
                synchronized(RecordingSocket.this){frames.write(bytes);}
            }};
        }
    }
}
