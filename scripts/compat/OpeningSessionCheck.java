package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Real exported-map session/ownership checks, not a gameplay completion simulator. */
public final class OpeningSessionCheck {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of(args[0]));var scenes=content.createScenes();SceneRegistry.install(scenes);
        if(scenes.tutorial()==null)throw new AssertionError("Verified original 398/217 imports required");
        Path home=Files.createTempDirectory("tortodrone-opening-sessions-");
        try(var accounts=new AccountStore(home,false,content.items())){
            accounts.create("OpeningOwner","opening-password".toCharArray());accounts.create("OpeningPeer","opening-password".toCharArray());
            var db=new Database(home,false);var owner=new CharacterStore(db,1,"OpeningOwner",content.items());var peer=new CharacterStore(db,2,"OpeningPeer",content.items());
            var fresh=owner.createForOpening(knight("Opening-Knight"),1);var other=peer.createForOpening(knight("Opening-Peer"),1);var old=owner.create(knight("Existing-Knight"));
            var before=owner.worldProfile(fresh.name(),1);SceneRegistry.Instance prior=null;
            for(int reconnect=0;reconnect<2;reconnect++){
                try(var p=new Player(owner,scenes,1001);var q=new Player(peer,scenes,1002)){
                    p.world.select(fresh.name(),RECEIVERS,true);q.world.select(other.name(),RECEIVERS,true);
                    var a=scenes.destination(398,before.worldId());var b=scenes.destination(398,peer.worldProfile(other.name(),1).worldId());
                    check(a!=b&&p.world.ownsScene(a.oid())&&q.world.ownsScene(b.oid()),"new/interrupt selections bypass Ready Room into independent authenticated tutorial floors");
                    check(a.map().spawnX()==-21&&a.map().spawnY()==-69.5f&&a.simulation().snapshot().players().size()==1,"original entrance and one unique pawn");
                    p.world.subscribe((short)1);p.world.entered();q.world.subscribe((short)2);q.world.entered();
                    move(p.world,scenes.entry().map());check(p.world.ownsScene(a.oid()),"unsolicited Haven travel cannot bypass an incomplete tutorial");
                    p.world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(ReadyRoom.ZONE,ReadyRoom.SCENE,1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)7))));
                    check(p.world.ownsScene(a.oid()),"Ready Room cannot be used to skip the unfinished tutorial");
                    try(var duplicate=new Player(owner,scenes,1003)){refused(()->duplicate.world.select(fresh.name(),RECEIVERS,true));}
                    try(var foreign=new Player(peer,scenes,1004)){refused(()->foreign.world.select(fresh.name(),RECEIVERS,true));}
                    check(owner.worldProfile(fresh.name(),1).equals(before)&&owner.lastTownScene(fresh.id(),1)==0,"enter/reconnect cannot award completion or overwrite permanent locations");prior=a;
                }
                check(prior.simulation().playerCount()==0&&!WorldSession.active(1,fresh.id()),"disconnect releases pawn and authenticated ownership");
            }
            // Isolate completed-selection routing; the graphical test supplies real gameplay settlement.
            var completed=owner.completeTutorial(fresh.id(),1,before);
            check(completed.progress().openingStage()==1,"durable Camp checkpoint is distinct from Haven");
            try(var p=new Player(owner,scenes,1005)){
                p.world.select(fresh.name(),RECEIVERS,true);var camp=scenes.destination(217,before.worldId());
                check(!p.world.ownsScene(camp.oid()),"completed selection first stages in native Ready Room");
                p.world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));move(p.world,camp.map());p.world.subscribe((short)3);p.world.entered();
                check(p.world.ownsScene(camp.oid())&&camp.simulation().snapshot().players().size()==1,"Return to Last reaches original initial Camp with exactly one pawn");
                move(p.world,scenes.entry().map());check(p.world.ownsScene(camp.oid()),"a forged Haven zone request cannot skip Camp missions");
            }
            try(var p=new Player(owner,scenes,1006)){
                p.world.select(old.name(),RECEIVERS,true);check(!p.world.ownsScene(scenes.entry().oid()),"grandfathered knight retains native Ready Room behavior");
            }
            System.out.println("PASS: original-scene new/interrupted selection, private instances, ownership, no Haven bypass, disconnect cleanup, Camp checkpoint and Ready Room return (session test; gameplay settlement tested separately)");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static void move(WorldSession world,SceneCatalog.MapScene map)throws IOException{world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)6))));}
    private interface Operation{void run()throws Exception;}
    private static void refused(Operation op)throws Exception{try{op.run();throw new AssertionError("Unauthorized selection accepted");}catch(IOException|CharacterStore.Refused expected){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Player implements AutoCloseable {
        final Socket socket=new Socket(){private final OutputStream out=new ByteArrayOutputStream();@Override public OutputStream getOutputStream(){return out;}};
        final SessionOutput output;final WorldSession world;
        Player(CharacterStore characters,SceneRegistry scenes,int oid)throws IOException{output=new SessionOutput(socket);world=new WorldSession(socket,characters,output,new SessionCodec(characters.catalog()),oid,1,m->{},scenes);}
        public void close()throws IOException{world.close();output.close();socket.close();}
    }
}
