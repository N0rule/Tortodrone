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
            }
            try(var player=new Player(owner,knight.id(),scenes,1002)){
                player.world.select(knight.name(),RECEIVERS,true);await(()->player.socket.objectivePublished);
                check(player.socket.contains("talkedGreta2"),"Ready Room reconnect restores both durable story attributes");
            }
            check(peer.worldProfile(other.name(),1).equals(untouched)&&peer.knightObjectives(other.id(),1).isEmpty(),"peer identity/progression unchanged");
            check(scenes.destination(217,before.worldId()).simulation().playerCount()==0,"disconnect and Ready Room leave no Camp occupants");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: original Camp 217/v373 -> legal movement -> Greta interaction -> all three authored panels -> atomic KNIGHT memory/objective -> duplicate/invalid response protection -> Ready Room reconnect; no position/story mutation in success path (tutorial checkpoint setup only)");
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
        final ByteArrayOutputStream frames=new ByteArrayOutputStream();final CharacterStore characters;final int knight;volatile boolean objectivePublished;
        RecordingSocket(CharacterStore characters,int knight){this.characters=characters;this.knight=knight;}
        synchronized boolean contains(String value){return frames.toString(StandardCharsets.ISO_8859_1).contains(value);}
        @Override public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError("Whole frames required");}
            public void write(byte[] bytes)throws IOException{String text=new String(bytes,StandardCharsets.ISO_8859_1);
                if(text.contains("findRhendon")){check(characters.knightObjectives(knight,1).containsKey("findRhendon")&&characters.knightMemories(knight,1).contains("talkedGreta2"),"story publication follows commit");objectivePublished=true;}
                synchronized(RecordingSocket.this){frames.write(bytes);}
            }};
        }
    }
}
