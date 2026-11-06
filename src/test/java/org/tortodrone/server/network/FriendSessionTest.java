package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Actual native social IDs, authenticated friend travel and private/transition isolation. */
public final class FriendSessionTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-friend-session-");
        try{
            var content=ContentManager.load(Path.of("."));var scenes=content.createScenes();var db=new Database(root,false);
            try(var alice=person(db,content.items(),scenes,"Alice");var bob=person(db,content.items(),scenes,"Bob");var other=person(db,content.items(),scenes,"Other")){
                check(alice.world.feedInvocation(new SessionDecoder.Invocation(3,11,1,List.of(true,0L,result()))),"current feed request is answered");
                check(alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,2,List.of(1,List.of(),result()))),"current party-suggestion request is answered");
                check(alice.world.eventLogInvocation(new SessionDecoder.Invocation(3,SessionCodec.EVENT_LOG_SERVICE,2,List.of())),"current event-log handle is accepted");
                alice.world.friendInvocation(joinRpc("Bob"));alice.socket.await("m.internal_error");
                check(alice.chars.friends().list(1,1,1).isEmpty(),"join cannot create or remove a friendship");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,1,List.of("Bob","Hello","Friend request",confirm())));
                var mail=bob.chars.worldProfile("Bob",1).town().mail().getFirst();
                bob.chars.auction(content.town()).mail(2,1,1,2,List.of(mail.id()));
                check(alice.chars.friends().list(1,1,1).size()==1,"native request and acceptance create mutual friendship");
                var town=scenes.catalog().scenes().stream().filter(m->m.town()&&m.id()!=scenes.catalog().entry().id()).findFirst().orElseThrow();
                bob.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of(destination(town),new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));
                alice.world.friendInvocation(joinRpc("Bob"));alice.socket.await("changing scenes");
                move(bob,town);
                var presence=WorldSession.friendPresence(db.file());int bobWorld=bob.chars.worldProfile("Bob",1).worldId();
                check(presence.get(bobWorld).instance()>0&&presence.get(bobWorld).zone()==town.zone(),"Town presence includes joinable native instance and zone");
                alice.world.friendInvocation(joinRpc("Bob"));
                var pending=WorldSession.class.getDeclaredField("portalMove");pending.setAccessible(true);
                check(pending.get(alice.world)!=null,"friend join starts native forced travel; queued="+alice.socket.frames);
                check(alice.chars.friends().list(1,1,1).size()==1,"method 2 joins without unfriending");move(alice,town);
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"friend travel reaches the exact Town instance");
                alice.world.friendInvocation(joinRpc("Bob"));
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"same-scene join keeps the scene active");
                // Native method 4: PlayerObject.Nq() is the authorization; friendship is not.
                move(alice,scenes.catalog().entry());
                String secret="Bob,"+String.format("%016x",bob.world.externalSecret());
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of("Bob,"+String.format("%016x",bob.world.externalSecret()^1),false,confirm())));
                String refusedSecret=alice.socket.await("%social:m.friend_unjoinable");
                check(!refusedSecret.contains("~%social:"),"qualified social failures keep their bundle prefix");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of("Bob,not-hex",false,confirm())));alice.socket.await("m.internal_error");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of("Alice,"+String.format("%016x",alice.world.externalSecret()),false,confirm())));alice.socket.await("m.friend_self");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of(secret,false,confirm())));
                check(pending.get(alice.world)!=null,"secret join starts native forced travel");
                move(alice,town);
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"secret join reaches the exact Town instance without a friendship edge");
                // Native Join follows a friend into the shared Training party room and advertises it.
                var training=scenes.catalog().scene(294);
                bob.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("training",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));
                move(bob,training);
                var trainingPresence=WorldSession.friendPresence(db.file()).get(bobWorld);
                check(trainingPresence.instance()>0,"shared Training room publishes the instance that enables native Join");
                alice.world.friendInvocation(joinRpc("Bob"));
                check(pending.get(alice.world)!=null,"friend join starts native forced travel into Training");
                move(alice,training);
                check(alice.world.ownsScene(trainingPresence.instance()),"friend join reaches the exact Training party instance");
                check(WorldSession.friendPresence(db.file()).get(alice.chars.worldProfile("Alice",1).worldId()).instance()==trainingPresence.instance(),"both friends publish the same Training presence");
                // Native method 6: only the toggle is published; matching itself is not simulated.
                var difficulty=new SessionDecoder.EnumValue("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty",1);
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,6,List.of(true,difficulty,confirm())));alice.socket.await("lfg");
                check(alice.world.lookingForGroup(),"party-finder toggle publishes its native lfg attribute");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,6,List.of(false,difficulty,confirm())));alice.socket.await("lfg");
                check(!alice.world.lookingForGroup(),"party-finder toggle clears the native lfg attribute");
                other.world.friendInvocation(joinRpc("Bob"));other.socket.await("m.internal_error");
                bob.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("lab",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(bob,scenes.catalog().scene(30893));
                alice.world.friendInvocation(joinRpc("Bob"));alice.socket.await("friend_unjoinable");
                check(WorldSession.friendPresence(db.file()).get(bobWorld).instance()==0,"private interior is published as unjoinable");
                bob.world.close();alice.world.friendInvocation(joinRpc("Bob"));alice.socket.await("friend_unjoinable");
                alice.world.friendInvocation(rpc(8,"Bob"));
                check(alice.chars.friends().list(1,1,1).isEmpty()&&bob.chars.friends().list(2,1,1).isEmpty(),"native method 8 removes both friendship edges");
                db.password("Other",new Passwords.Protection(new byte[16],new byte[32]),3);
                other.world.friendInvocation(rpc(8,"Alice"));other.socket.await("reconnect");
            }
            // Explicit open/private party: the leader controls friend Join while in Training.
            try(var lead=person(db,content.items(),scenes,"Lead");var mate=person(db,content.items(),scenes,"Mate");var spare=person(db,content.items(),scenes,"Spare")){
                befriend(spare,mate,"Mate",content.town());
                int leadParty=scenes.parties().view(partyMember(lead.world)).oid();
                lead.world.partyInvocation(new SessionDecoder.Invocation(leadParty,20,2,List.of("Mate",confirm())));
                mate.world.partyInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,12,5,List.of("PARTY_INV:"+leadParty,1,confirm())));
                check(scenes.parties().view(partyMember(mate.world)).oid()==leadParty,"explicit party formation shares the native party identity");
                var room=scenes.catalog().scene(294);
                lead.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("training",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(lead,room);
                mate.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("training",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(mate,room);
                int mateWorld=mate.chars.worldProfile("Mate",1).worldId();
                int mateScene=WorldSession.friendPresence(db.file()).get(mateWorld).instance();
                check(mateScene==((SceneRegistry.Instance)field(lead.world,"scene")).oid(),"party members enter one Training simulation");
                check(mateScene>0,"an open Training party publishes the joinable instance");
                lead.world.interiorPartyInvocation(new SessionDecoder.Invocation(leadParty,SessionCodec.LEVEL_PARTY_SERVICE,6,List.of(true,confirm())));mate.socket.await("inviteOnly");
                await(()->WorldSession.friendPresence(db.file()).get(mateWorld).instance()==0);
                spare.world.friendInvocation(joinRpc("Mate"));spare.socket.await("friend_unjoinable");
                check(scenes.parties().view(partyMember(lead.world)).inviteOnly(),"the stock party panel writes inviteOnly through LevelPartyMarshaller");
                lead.world.interiorPartyInvocation(new SessionDecoder.Invocation(leadParty,SessionCodec.LEVEL_PARTY_SERVICE,6,List.of(false,confirm())));
                await(()->WorldSession.friendPresence(db.file()).get(mateWorld).instance()==mateScene);
                spare.world.friendInvocation(joinRpc("Mate"));
                check(field(spare.world,"portalMove")!=null,"friend join starts native forced travel into an open explicit party");
                move(spare,room);
                check(spare.world.ownsScene(mateScene)&&scenes.parties().view(partyMember(spare.world)).oid()==leadParty,"friend join reaches the explicit party instance and seat");
                lead.world.interiorPartyInvocation(new SessionDecoder.Invocation(leadParty,SessionCodec.LEVEL_PARTY_SERVICE,6,List.of(true,confirm())));
                await(()->WorldSession.friendPresence(db.file()).get(mateWorld).instance()==0);
            }
            System.out.println("PASS: native friend request/join/secret join/looking-for-group/remove, Town and Training instance presence/travel, open/private party controls, stranger/offline/private/transition/auth guards");
        }finally{org.tortodrone.support.ContentFixture.delete(root);}
    }
    private static String destination(SceneCatalog.MapScene map){return map.name().substring("%town:m.".length());}
    private static SessionDecoder.Listener confirm(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)5);}
    private static SessionDecoder.Listener result(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)6);}
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static PartyRegistry.Member partyMember(WorldSession world)throws Exception{return (PartyRegistry.Member)field(world,"partyMember");}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(ready.getAsBoolean())return;Thread.sleep(20);}throw new AssertionError("Party presence did not refresh");}
    private static void befriend(Person requester,Person target,String targetName,org.tortodrone.town.TownCatalog town)throws Exception{
        requester.world.friendInvocation(new SessionDecoder.Invocation(3,12,1,List.of(targetName,"Hello","Friend request",confirm())));
        var profile=target.chars.worldProfile(targetName,1);var mail=profile.town().mail().getFirst();
        target.chars.auction(town).mail(profile.accountId(),profile.knightId(),1,2,List.of(mail.id()));
    }
    private static SessionDecoder.Invocation rpc(int method,String name){return new SessionDecoder.Invocation(3,12,method,List.of(name,confirm()));}
    private static SessionDecoder.Invocation joinRpc(String name){return rpc(3,name);}
    private static Person person(Database db,ItemCatalog items,SceneRegistry scenes,String name)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);var chars=new CharacterStore(db,account,name,items);
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,chars,output,new SessionCodec(items),1200+account,1,m->{},scenes);
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();return new Person(world,socket,output,chars);
    }
    private static void move(Person person,SceneCatalog.MapScene map)throws Exception {
        person.world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),map.version(),new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)9))));
        if(!map.town())person.world.subscribeInteriorParty((short)11);
        person.world.subscribe((short)10);person.world.entered();
    }
    private record Person(WorldSession world,RecordingSocket socket,SessionOutput output,CharacterStore chars)implements AutoCloseable{
        public void close(){world.close();output.close();}
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError();}public void write(byte[] bytes){frames.add(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1));}};}
        String await(String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){String frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return frame;}throw new AssertionError("Missing response: "+text);}
    }
}
