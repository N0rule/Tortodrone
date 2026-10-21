package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

public final class SlashCommandsSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-slash-commands-");
        try {
            var content=ContentManager.load(Path.of("."));var scenes=content.createScenes();var db=new Database(home,true);
            try(var gm=person(db,content.items(),scenes,"Master",true);var player=person(db,content.items(),scenes,"Player",false);var peer=person(db,content.items(),scenes,"Other",false)) {
                // Scene chat shares service 12 with the friend list and is addressed at the scene
                // object, not the global invocation object; it must not be rejected as a friend call.
                int havenOid=scenes.entry().oid();
                var sceneChat=new SessionDecoder.Invocation(havenOid,SceneChat.SERVICE,1,List.of("scene-marker",(byte)0));
                check(!gm.world.friendInvocation(sceneChat),"scene chat addressed at the scene is not a friend-service request");
                check(!gm.world.partyInvocation(sceneChat)&&!gm.world.interiorPartyInvocation(sceneChat)
                    &&!gm.world.exchangeInvocation(sceneChat)&&!gm.world.auctionInvocation(sceneChat)
                    &&!gm.world.townInvocation(sceneChat)&&!gm.world.conversationInvocation(sceneChat)
                    &&!gm.world.tradeInvocation(sceneChat),"no other handler claims a scene-addressed chat invocation");
                gm.world.speak(sceneChat);peer.socket.awaitText("scene-marker");
                gm.world.adminPeople(admin(13,"Player"));gm.socket.awaitText("; knight=");
                player.world.adminPeople(admin(21,"Other"));player.socket.awaitText("Game Master permission required");check(!peer.socket.isClosed(),"ordinary account cannot kick another player");
                player.world.away(new SessionDecoder.Invocation(3,3,1,List.of("Away-marker")));
                peer.world.tell(new SessionDecoder.Invocation(3,3,3,List.of("Player","private-marker",new SessionDecoder.Listener("com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller",(short)5))));
                peer.socket.awaitText("Away-marker");player.socket.awaitText("private-marker");
                player.world.away(new SessionDecoder.Invocation(3,3,1,Collections.singletonList(null)));
                player.world.invocation(new SessionDecoder.Invocation(3,9,10,List.of("First",confirm())));
                check(player.chars.worldProfile("Player",1).town().loadouts().size()==1,"native save-loadout dispatch persists the named snapshot");
                player.world.invocation(new SessionDecoder.Invocation(3,9,4,List.of("First",confirm())));
                check(player.world.invocation(new SessionDecoder.Invocation(3,1,15,List.of("First",true,confirm()))),"Knight service owns native PvP loadout method 15");
                check(player.chars.worldProfile("Player",1).town().loadouts().getFirst().pvp(),"PvP designation changes through the authenticated session");
                int general=scenes.channelOid(scenes.catalog().entry().zone(),false),trade=scenes.channelOid(scenes.catalog().entry().zone(),true);
                gm.world.subscribeChannel((short)20,general);peer.world.subscribeChannel((short)21,general);player.world.subscribeChannel((short)22,trade);
                check(gm.world.channelSpeak(speak(general,"zone-marker",(byte)0)),"native zone speak dispatched");peer.socket.awaitText("zone-marker");
                player.drain();check(!player.socket.contains("zone-marker"),"trade-only subscriber cannot receive general chat");
                check(player.world.channelSpeak(speak(trade,"trade-marker",(byte)0)),"native trade speak dispatched");player.socket.awaitText("trade-marker");
                peer.world.channelSpeak(speak(general,"spoof-marker",(byte)4));peer.socket.awaitText("Unsupported scene chat mode");check(!gm.socket.contains("spoof-marker"),"channel sender cannot spoof a GM broadcast mode");
                peer.world.unsubscribeChannel(general);
                try{peer.world.channelSpeak(speak(general,"after-unsubscribe",(byte)0));throw new AssertionError("Unsubscribed channel sender accepted");}catch(SessionDecoder.Rejected expected){}
                try{peer.world.subscribeChannel((short)23,Integer.MAX_VALUE);throw new AssertionError("Foreign channel subscription accepted");}catch(IOException expected){}
                var bazaar=scenes.catalog().scenes().stream().filter(m->m.town()&&m.id()!=scenes.catalog().entry().id()).findFirst().orElseThrow();
                gm.world.adminPeople(admin(28,Integer.toString(bazaar.id())));move(gm,bazaar);
                gm.world.adminPeople(admin(27,"Player"));move(gm,scenes.catalog().entry());
                gm.world.adminPeople(admin(28,Integer.toString(bazaar.id())));move(gm,bazaar);
                Thread.sleep(1100); // Shared five-command burst intentionally covers both chat and GM calls.
                gm.world.adminPeople(admin(50,"Player"));move(player,bazaar);
                check(player.world.ownsScene(scenes.destination(bazaar.id(),2).oid()),"summon completes normal owned-scene travel");
                Thread.sleep(1100);
                gm.world.adminPeople(admin(21,"Other"));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!peer.socket.isClosed()&&System.nanoTime()<until)Thread.sleep(10);
                check(peer.socket.isClosed()&&!gm.socket.isClosed()&&!player.socket.isClosed(),"kick ends only the named live connection");
                db.password("Master",new Passwords.Protection(new byte[16],new byte[32]),1);
                try{gm.world.adminPeople(admin(21,"Player"));}catch(IOException revoked){}check(!player.socket.isClosed(),"stale authenticated GM cannot kick after password revocation");
            }
            try(var gm=person(db,content.items(),scenes,"Staff",true);var target=person(db,content.items(),scenes,"Target",false)) {
                gm.world.adminPeople(new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,24,List.of(Integer.MAX_VALUE,Integer.MAX_VALUE,confirm())));gm.socket.awaitText("Destination is blocked");
                gm.world.adminPeople(admin(30,"haven"));move(gm,scenes.catalog().entry());
                check(target.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("training",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)7)))),"native training travel dispatched");
                var training=scenes.catalog().scene(294);move(target,training);
                var announcedParty=WorldSession.class.getDeclaredField("announcedPartyOid");announcedParty.setAccessible(true);
                check(announcedParty.getInt(target.world)!=0,"Training retains the party object required by its HUD");
                target.world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));move(target,scenes.catalog().entry());
                check(announcedParty.getInt(target.world)==0,"returning to town clears the solo mission party so /training does not ask to abandon it");
                target.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("training",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(target,training);
                gm.world.adminPeople(admin(27,"Target"));move(gm,training);
                var sceneField=WorldSession.class.getDeclaredField("scene");sceneField.setAccessible(true);
                check(sceneField.get(gm.world)==sceneField.get(target.world),"GM travel uses ordinary public Training assignment");
                gm.world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));move(gm,scenes.catalog().entry());
                target.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("lab",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(target,scenes.catalog().scene(30893));
                Thread.sleep(1100);
                gm.world.adminPeople(admin(27,"Target"));gm.socket.awaitText("private instance belongs");check(gm.world.ownsScene(scenes.entry().oid()),"GM travel cannot enter another knight's private Laboratory instance");
                try{gm.world.adminPeople(new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,13,List.of("Target",new SessionDecoder.Listener("bad",(short)8))));throw new AssertionError("Malformed admin listener accepted");}catch(SessionDecoder.Rejected expected){}
                check(!target.socket.isClosed(),"malformed listener does not mutate peer state");
                // /sgo 1 targets Haven (the knight's current scene). The stock client refuses
                // same-scene moves, so the server must fail without tearing down the live scene.
                gm.world.adminPeople(admin(28,Integer.toString(scenes.catalog().entry().id())));
                gm.socket.awaitText("You are already in that scene");
                check(gm.world.ownsScene(scenes.entry().oid()),"same-scene /sgo keeps the current scene subscribed");
                check(gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("haven",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)11)))),"same-scene town destination request handled");
                gm.drain();
                check(gm.world.ownsScene(scenes.entry().oid()),"same-scene travel leaves the current scene intact");
                var pending=WorldSession.class.getDeclaredField("portalMove");pending.setAccessible(true);
                check(pending.get(gm.world)==null,"same-scene destination never starts a loading transition");
                Thread.sleep(1100);
                int moveX=0,moveY=0;boolean found=false;
                for(int x=-5;x<=5&&!found;x++)for(int y=-12;y<=-4;y++)if(scenes.entry().simulation().placement(x,y)){moveX=x;moveY=y;found=true;break;}
                check(found,"fixture has a validated integer destination");
                gm.world.adminPeople(new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,24,List.of(moveX,moveY,confirm())));gm.drain();
                var own=scenes.entry().simulation().snapshot().players().stream().filter(a->a.name().equals("Staff")).findFirst().orElseThrow();
                check(own.x()==moveX&&own.y()==moveY&&pending.get(gm.world)==null,"/move relocates the existing pawn with no forced scene; actual="+own.x()+","+own.y()+" expected="+moveX+","+moveY);
                gm.world.adminPeople(new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,24,List.of(Integer.MIN_VALUE,0,confirm())));gm.socket.awaitText("Destination is blocked");
                check(gm.world.ownsScene(scenes.entry().oid())&&pending.get(gm.world)==null,"invalid coordinates leave the scene ready");
                // KnightMarshaller 11 is the stock /haven travel, sent with no arguments or listener.
                var secondTown=scenes.catalog().scenes().stream().filter(m->m.town()&&m.id()!=scenes.catalog().entry().id()).findFirst().orElseThrow();
                gm.world.adminPeople(admin(28,Integer.toString(secondTown.id())));move(gm,secondTown);
                check(!gm.world.ownsScene(scenes.entry().oid()),"GM travel reaches the second town before /haven");
                check(gm.world.invocation(new SessionDecoder.Invocation(3,1,11,List.of())),"native /haven command dispatched");
                move(gm,scenes.catalog().entry());
                check(gm.world.ownsScene(scenes.entry().oid()),"native /haven travel returns the knight to Haven");
                var result=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)12);
                var currentScene=WorldSession.class.getDeclaredField("scene");currentScene.setAccessible(true);
                var currentMember=WorldSession.class.getDeclaredField("membership");currentMember.setAccessible(true);
                for(int mapId:new int[]{1,2}){
                    if(mapId!=1){gm.world.adminPeople(admin(28,Integer.toString(mapId)));move(gm,scenes.catalog().scene(mapId));}
                    var place=(SceneRegistry.Instance)currentScene.get(gm.world);
                    var machines=place.map().content().entries().stream().filter(e->e.spec().config().name().equals("Shop/Haven/Alchemy")).toList();
                    check(machines.size()==2,"fixture has two general alchemy machines");
                    var first=machines.get(0);var second=machines.get(1);
                    float x=first.x()+(float)Math.cos(first.rotation())*3,y=first.y()+(float)Math.sin(first.rotation())*3;
                    check(((SceneSimulation.Membership)currentMember.get(gm.world)).teleport(x,y,0),"fixture approaches an authored alchemy machine");
                    for(var expected:List.of(first,second,first)){
                        gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("move_alchemy_machine",result)));gm.drain();
                        var atMachine=place.simulation().snapshot().players().stream().filter(a->a.name().equals("Staff")).findFirst().orElseThrow();
                        check(currentScene.get(gm.world)==place&&pending.get(gm.world)==null&&Math.hypot(atMachine.x()-expected.x(),atMachine.y()-expected.y())<3,
                            "/alchemy starts at the nearest machine, cycles through the others and stays in the current instance");
                    }
                }
                var bazaar=scenes.catalog().scenes().stream().filter(m->m.content().stats().containsKey("destination.move_bechamel.x")).findFirst().orElseThrow();
                gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("move_bechamel",result)));gm.drain();
                gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("move_bechamel",result)));gm.drain();
                check(pending.get(gm.world)==null&&gm.world.ownsScene(scenes.destination(bazaar.id(),1).oid()),"captured named command relocates within the existing scene");
                gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("move_auction",result)));move(gm,scenes.catalog().entry());
                var auctionPending=WorldSession.class.getDeclaredField("auctionAfterArrival");auctionPending.setAccessible(true);
                check(!auctionPending.getBoolean(gm.world)&&pending.get(gm.world)==null,"auction opening completes after the native cross-scene callbacks");
                gm.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("move_auction",result)));gm.drain();
                var atAuction=scenes.entry().simulation().snapshot().players().stream().filter(a->a.name().equals("Staff")).findFirst().orElseThrow();
                check(atAuction.x()==1.5f&&atAuction.y()==12.5f&&pending.get(gm.world)==null,"/ah uses the authored destination and opens in-scene without a loading transition");
            }
            System.out.println("PASS: scene-chat/friend-service routing, same-scene travel guards, native /haven, native away/back, loadouts, separate zone/trade channels, GM who/kick/go/summon, readiness, authorization, revocation and private-instance isolation");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static Person person(Database db,ItemCatalog items,SceneRegistry scenes,String name,boolean gm)throws Exception {
        int id=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),gm);var chars=new CharacterStore(db,id,name,items);
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,chars,output,new SessionCodec(items),1200+id,1,m->{},scenes,()->{var identity=db.identity(name);return identity!=null&&identity.gameMaster()&&identity.authRevision()==1;});
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();var person=new Person(world,socket,output,chars);person.drain();return person;
    }
    private static void move(Person person,SceneCatalog.MapScene map)throws Exception {
        person.world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),map.version(),new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)9))));
        if(!map.town())person.world.subscribeInteriorParty((short)11);
        person.world.subscribe((short)10);person.world.entered();person.drain();
    }
    private static SessionDecoder.Listener confirm(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)3);}
    private static SessionDecoder.Invocation admin(int method,String name){return new SessionDecoder.Invocation(3,SessionCodec.ADMIN_SERVICE,method,List.of(name,confirm()));}
    private static SessionDecoder.Invocation speak(int oid,String text,byte mode){return new SessionDecoder.Invocation(oid,SceneChat.SERVICE,1,List.of(text,mode));}
    private record Person(WorldSession world,RecordingSocket socket,SessionOutput output,CharacterStore chars)implements AutoCloseable {
        public void close(){world.close();output.close();}
        void drain()throws Exception {byte[] marker={0};output.send(()->marker);for(;;){var frame=socket.frames.poll(5,TimeUnit.SECONDS);check(frame!=null,"session output drains");if(frame==marker)return;}}
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int v){throw new AssertionError();}public void write(byte[] bytes){frames.add(bytes);}};}
        void awaitText(String text)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&new String(frame,StandardCharsets.ISO_8859_1).contains(text))return;}throw new AssertionError("Missing response: "+text);}
        boolean contains(String text){return frames.stream().anyMatch(b->new String(b,StandardCharsets.ISO_8859_1).contains(text));}
    }
}
