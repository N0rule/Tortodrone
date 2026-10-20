package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.*;
import org.tortodrone.town.TownCatalog;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Disposable native party chat and Spark request path; no graphical client or owner data. */
public final class TrainingSessionTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-training-session-");
        try{
            var db=new Database(home,true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Training-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            db.grantItems("Alice",knight.name(),account,-1,items.require("Rarity/Spark of Life"),2,-1,-1);
            db.currency("Alice",knight.name(),account,-1,Currency.ENERGY,100);
            var scenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
            scenes.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                world.subscribe((short)1);world.entered();
                var portal=scenes.entry().map().portals().triggers().stream().filter(p->p.destination()==294).findFirst().orElseThrow();
                var begin=WorldSession.class.getDeclaredMethod("beginPortal",ScenePortals.Trigger.class);begin.setAccessible(true);
                synchronized(world){begin.invoke(world,portal);}
                world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(scenes.catalog().scene(294).zone(),294,0,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)3))));
                var partyField=WorldSession.class.getDeclaredField("partyMember");partyField.setAccessible(true);var partyMember=(PartyRegistry.Member)partyField.get(world);
                var place=scenes.destination(294,characters.worldProfile(knight.name(),1).worldId(),partyMember);int party=scenes.parties().view(partyMember).oid();
                world.subscribeInteriorParty((short)4);world.subscribe((short)5);world.entered();
                var chat=new SessionDecoder.Invocation(party,SceneChat.PARTY_SERVICE,1,List.of("training-chat-fixture",(byte)0));
                check(!world.interiorPartyInvocation(chat),"party chat is routed to its SpeakMarshaller instead of the revive service");
                check(!world.conversationInvocation(chat),"party chat is not an NPC conversation response despite sharing service 21");
                world.speak(chat);socket.await("training-chat-fixture");
                try{world.speak(new SessionDecoder.Invocation(party+1,SceneChat.PARTY_SERVICE,1,chat.arguments()));throw new AssertionError("Foreign party chat accepted");}catch(IOException expected){}
                var before=characters.worldProfile(knight.name(),1);world.interiorPartyInvocation(revive(party,knight.name(),-1));socket.await("m.no_revive");
                check(characters.worldProfile(knight.name(),1).equals(before),"a living player cannot spend Sparks");
                var memberField=WorldSession.class.getDeclaredField("membership");memberField.setAccessible(true);var member=(SceneSimulation.Membership)memberField.get(world);
                var healthField=member.getClass().getDeclaredField("health");healthField.setAccessible(true);var health=(SceneHealth)healthField.get(member);
                synchronized(place.simulation()){
                    // Exercise damage publication after the separately tested arrival window.
                    var arrivalProtection=SceneHealth.class.getDeclaredField("invulnerableUntil");arrivalProtection.setAccessible(true);arrivalProtection.setInt(health,0);
                    int stamp=SceneClock.now();
                    health.hit(new FireCatalog.DataValue("Damage$Unblockable",Map.of("amount",40f,"flags",1)),stamp);
                    var freeze=place.map().content().statuses().get("Freeze");
                    health.condition(new StarterCatalog.Model("Freeze",Map.of("Chance",1f,"Power",100,"Duration",20000)),freeze.model("data").arguments().get("data"),stamp);
                }
                var sentHealth=WorldSession.class.getDeclaredField("publishedHealth");sentHealth.setAccessible(true);
                var sentStatus=WorldSession.class.getDeclaredField("publishedStatus");sentStatus.setAccessible(true);
                boolean published=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline){synchronized(world){
                    var hp=(SceneHealth.State)sentHealth.get(world);var status=(SceneStatus.State)sentStatus.get(world);
                    if(hp!=null&&hp.current()<hp.maximum()&&status!=null&&!status.conditions().isEmpty()){published=true;break;}}
                    Thread.sleep(20);
                }
                check(published,"entered Training session publishes changed HP and non-empty HUD statuses");
                synchronized(place.simulation()){
                    int stamp=SceneClock.now();health.hit(hit(),stamp);health.advance(stamp+5000,5000,false);
                    var immunity=SceneHealth.class.getDeclaredField("invulnerableUntil");immunity.setAccessible(true);immunity.setInt(health,0);
                    health.hit(hit(),SceneClock.now());
                    check(health.canSparkRevive(),"fixture exhausted the one emergency revive");
                }
                world.interiorPartyInvocation(revive(party,"Another-Knight",-1));socket.await("m.no_revive");
                check(characters.worldProfile(knight.name(),1).equals(before),"foreign revive target cannot consume owned currency or Sparks");
                world.interiorPartyInvocation(revive(party,knight.name(),-1));socket.awaitSuccess();
                var after=characters.worldProfile(knight.name(),1);
                check(member.health().current()==member.health().maximum()&&sparks(after)==sparks(before)-1&&after.energy()==before.energy(),"native Spark request restores HP and consumes one owned Spark after commit");
                world.interiorPartyInvocation(revive(party,knight.name(),-1));socket.await("m.no_revive");
                check(characters.worldProfile(knight.name(),1).equals(after),"repeated live revive request cannot consume a second Spark");
                synchronized(place.simulation()){health.hit(hit(),SceneClock.now()+3000);}
                world.interiorPartyInvocation(revive(party,knight.name(),49));socket.await("m.costupdate");
                check(characters.worldProfile(knight.name(),1).equals(after),"under-approved energy revive rolls back");
                world.interiorPartyInvocation(revive(party,knight.name(),50));socket.awaitSuccess();
                check(member.health().current()>0&&characters.worldProfile(knight.name(),1).energy()==after.energy()-50,"native buy-and-use request restores HP and charges 50 energy");
            }
            System.out.println("PASS: Training party chat/ownership, native Spark requests, duplicate/foreign/alive refusal and paid revive");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static int sparks(WorldProfile p){return p.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")).mapToInt(WorldProfile.Item::count).sum();}
    private static Object hit(){return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Unblockable",List.of(new FireCatalog.FieldValue("amount","float",10000f),new FireCatalog.FieldValue("flags","int",1)));}
    private static SessionDecoder.Invocation revive(int party,String name,int price){return new SessionDecoder.Invocation(party,20,4,List.of(name,price,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)20)));}
    private static final class RecordingSocket extends Socket{
        final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError("Expected complete frame");}public void write(byte[] bytes){frames.add(bytes.clone());}};}
        void await(String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){byte[] frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&new String(frame,StandardCharsets.ISO_8859_1).contains(text))return;}throw new AssertionError("Missing party response: "+text);}
        void awaitSuccess()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){byte[] frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;int n=frame.length;if(n>=4&&frame[n-4]==0&&frame[n-3]==20&&frame[n-2]==1&&frame[n-1]==0)return;}throw new AssertionError("Missing revive confirmation");}
    }
}
