package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Three disposable authenticated sessions exercise actual invitation, travel, publication and exit paths. */
public class CooperativeTrainingTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-coop-");
        try{
            var db=new Database(home,true);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var scenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
            scenes.weapons(WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv")));
            scenes.fires(FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json")));
            try(var a=new Player(db,items,scenes,"Alice",1001);var b=new Player(db,items,scenes,"Bob",1002);var c=new Player(db,items,scenes,"Carol",1003)){
                int party=a.party().oid();b.socket.frames.clear();a.operation(2,b.name);b.socket.await("Notification$PartyInvite");
                b.world.partyInvocation(new SessionDecoder.Invocation(3,12,5,List.of("PARTY_INV:"+party,1,listener())));
                b.world.subscribeInteriorParty((short)5);await(()->a.publishedMembers()==2);
                check(a.party().oid()==b.party().oid(),"native accept joins the inviter's stable party");
                a.travel(294);b.travel(294);c.travel(294);
                var shared=a.place();check(shared==b.place()&&shared!=c.place(),"party shares one Training simulation while an unrelated knight stays private");
                check(shared.simulation().snapshot().players().size()==2&&!c.world.ownsScene(shared.oid())&&!c.world.ownsInteriorParty(party),"scene and party object IDs do not authorize an outsider");
                int stamp=SceneClock.now();float start=shared.simulation().snapshot().players().stream().filter(p->p.id()==a.member().worldId).findFirst().orElseThrow().y();
                a.world.input(new SessionDecoder.Input(shared.oid(),0,stamp,List.of(new SessionDecoder.InputFrame(stamp,(float)Math.PI/2,(float)Math.PI/2,1))));
                await(()->shared.simulation().snapshot().players().stream().anyMatch(p->p.id()==a.member().worldId&&p.y()>start+.05));
                c.socket.frames.clear();a.socket.frames.clear();b.socket.frames.clear();
                a.world.speak(new SessionDecoder.Invocation(party,SceneChat.PARTY_SERVICE,1,List.of("cooperative-private-chat",(byte)0)));
                a.socket.await("cooperative-private-chat");b.socket.await("cooperative-private-chat");check(!c.socket.contains("cooperative-private-chat"),"party chat is private to authorized members");
                synchronized(shared.simulation()){
                    var health=(SceneHealth)field(a.membership(),"health");health.hit(new FireCatalog.DataValue("Damage$Unblockable",Map.of("amount",30f,"flags",1)),SceneClock.now());
                    var entities=(SceneEntities)field(shared.simulation(),"entities");var cube=entities.actors().stream().filter(p->p.entity().kind().equals("Monster")).findFirst().orElseThrow();
                    entities.damage(new StarterCatalog.Model("DamageRegion",Map.of("damage",new FireCatalog.DataValue("Damage$Normal",Map.of("amount",100000f,"flags",2)))),cube.x(),cube.y(),0,SceneClock.now(),a.member().worldId);
                    check(b.place().simulation().snapshot().actors().stream().anyMatch(p->p.id()==cube.id()&&p.entity().health()==0),"both sessions observe the same defeated enemy");
                }
                await(()->b.peerHurt(a.member().playerOid));check(b.membership().health().current()==b.membership().health().maximum(),"peer damage leaves the other knight's health independent");
                db.grantItems("Alice",a.name,-1,-1,items.require("Rarity/Spark of Life"),2,-1,-1);
                synchronized(shared.simulation()){
                    var down=(SceneHealth)field(b.membership(),"health");int now=SceneClock.now();
                    down.hit(new FireCatalog.DataValue("Damage$Unblockable",Map.of("amount",100000f,"flags",1)),now);
                    down.revive(now); // Spend the single emergency charge before the qualifying Spark death.
                    down.hit(new FireCatalog.DataValue("Damage$Unblockable",Map.of("amount",100000f,"flags",1)),now+2000);
                }
                var beforeSpark=db.worldProfile(1,1,1);
                var revive=new SessionDecoder.Invocation(party,20,4,List.of(b.name,-1,listener()));
                check(!c.world.interiorPartyInvocation(revive),"unrelated party cannot invoke the victim's revive service");
                a.world.interiorPartyInvocation(new SessionDecoder.Invocation(party,20,4,List.of("Missing-Knight",-1,listener())));
                check(db.worldProfile(1,1,1).equals(beforeSpark),"invalid revive target preserves the payer's Spark");
                a.world.interiorPartyInvocation(revive);var afterSpark=db.worldProfile(1,1,1);
                check(b.membership().health().current()==b.membership().health().maximum()&&b.membership().health().reviveFlags()==0,"cooperative revive restores the target without replenishing Emergency Revive");
                check(afterSpark.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")).mapToInt(WorldProfile.Item::count).sum()==1,"the living caller pays exactly one Spark");
                a.world.interiorPartyInvocation(revive);check(db.worldProfile(1,1,1).equals(afterSpark),"duplicate revive cannot consume a second Spark");
                b.operation(3,null);b.world.subscribeInteriorParty((short)6);b.travel(1);
                await(()->shared.simulation().playerCount()==1&&a.publishedMembers()==1);
                check(a.place()==shared&&a.membership().health().current()>0,"leaving returns only that member and preserves the remaining simulation");
                a.travel(1);a.operation(2,b.name);b.world.partyInvocation(new SessionDecoder.Invocation(3,12,5,List.of("PARTY_INV:"+party,1,listener())));b.world.subscribeInteriorParty((short)7);
                a.travel(294);b.travel(294);var kickedRoom=a.place();a.operation(1,b.name);
                await(()->{synchronized(b.world){return field(b.world,"portalMove")!=null;}});
                b.world.subscribeInteriorParty((short)8);b.travel(1);
                check(a.place()==kickedRoom&&kickedRoom.simulation().playerCount()==1,"leader removal safely returns only the removed member");
                a.travel(1);a.operation(2,b.name);b.world.partyInvocation(new SessionDecoder.Invocation(3,12,5,List.of("PARTY_INV:"+party,1,listener())));b.world.subscribeInteriorParty((short)9);
                a.travel(294);b.travel(294);var retained=b.place();a.close();
                await(()->retained.simulation().playerCount()==1&&b.publishedMembers()==1);
                check(b.party().members().getFirst().playerOid()==1002,"leader disconnect transfers leadership without replacing the survivor's party");
                b.operation(3,null);b.world.subscribeInteriorParty((short)10);b.travel(1);
                await(()->scenes.statuses().stream().noneMatch(s->s.instanceId()==retained.oid()));
            }
            System.out.println("PASS: native invite/accept, co-op travel/movement/enemy state/health/chat, outsider isolation, independent exit, leader disconnect and cleanup");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static SessionDecoder.Listener listener(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)20);}
    private static Object field(Object object,String name){try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception failure){throw new AssertionError(failure);}}
    private static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){if(ready.getAsBoolean())return;Thread.sleep(20);}throw new AssertionError("Cooperative publication did not arrive");}
    private static final class Player implements AutoCloseable {
        final WorldSession world;final SessionOutput output;final RecordingSocket socket=new RecordingSocket();final SceneRegistry scenes;final String name;
        Player(Database db,ItemCatalog items,SceneRegistry scenes,String user,int oid)throws Exception{
            this.scenes=scenes;int account=db.create(user,new Passwords.Protection(new byte[16],new byte[32]),false);var characters=new CharacterStore(db,account,user,items);name="Party-"+user;
            characters.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            output=new SessionOutput(socket);world=new WorldSession(socket,characters,output,new SessionCodec(items),oid,1,m->{},scenes);
            world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribeInteriorParty((short)1);world.subscribe((short)2);world.entered();
        }
        PartyRegistry.Member member(){return (PartyRegistry.Member)field(world,"partyMember");}
        PartyRegistry.View party(){try{return scenes.parties().view(member());}catch(IOException failure){throw new AssertionError(failure);}}
        SceneRegistry.Instance place(){synchronized(world){return (SceneRegistry.Instance)field(world,"scene");}}
        SceneSimulation.Membership membership(){synchronized(world){return (SceneSimulation.Membership)field(world,"membership");}}
        int publishedMembers(){synchronized(world){var view=(PartyRegistry.View)field(world,"publishedParty");return view==null?0:view.members().size();}}
        boolean peerHurt(int oid){synchronized(world){var view=(PartyRegistry.View)field(world,"publishedParty");return view!=null&&view.members().stream().anyMatch(m->m.playerOid()==oid&&m.health().current()<m.health().maximum());}}
        void operation(int method,String target)throws Exception{world.partyInvocation(new SessionDecoder.Invocation(party().oid(),20,method,target==null?List.of(listener()):List.of(target,listener())));}
        void travel(int template)throws Exception{
            synchronized(world){
                if(field(world,"portalMove")==null){var trigger=place().map().portals().triggers().stream().filter(p->p.destination()==template).findFirst().orElseThrow();var begin=WorldSession.class.getDeclaredMethod("beginPortal",ScenePortals.Trigger.class);begin.setAccessible(true);begin.invoke(world,trigger);}
                world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(scenes.catalog().scene(template).zone(),template,0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)3))));
                world.subscribe((short)4);world.entered();
            }
        }
        public void close(){world.close();output.close();}
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError("Expected complete frame");}public void write(byte[] bytes){frames.add(bytes.clone());}};}
        boolean contains(String text){return frames.stream().anyMatch(f->new String(f,StandardCharsets.ISO_8859_1).contains(text));}
        void await(String text)throws Exception{CooperativeTrainingTest.await(()->contains(text));}
    }
}
