package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Lightweight storage/session coverage; no external server or graphical client. */
public final class ConfettiSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-confetti-");
        try{
            var content=ContentManager.load(Path.of("."));var items=content.items();
            var definitions=items.entries().stream().filter(i->Confetti.supports(i.config())).toList();
            check(definitions.size()==14,"all stock confetti variants recognized");
            var db=new Database(home,true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Confetti-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            for(var definition:definitions)db.grantItems("Alice",knight.name(),-1,-1,definition,2,-1,-1);
            var store=characters.town(content.town());var initial=db.worldProfile(account,knight.id(),1);
            long id=initial.items().stream().filter(i->i.config().equals("Usable/Confetti, Dark Harvest")).findFirst().orElseThrow().id();
            refused(()->store.confetti(account,knight.id(),99,initial.revision(),id));
            refused(()->store.confetti(account,knight.id(),1,initial.revision()-1,id));
            int stranger=db.create("Bob",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,stranger,"Bob",items).create(new Knight(0,"Other-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var other=db.worldProfile(stranger,1,1);refused(()->store.confetti(stranger,1,1,other.revision(),id));
            check(db.worldProfile(account,knight.id(),1).equals(initial),"auth, revision and foreign ownership failures preserve inventory");
            db.write(c->{c.createStatement().execute("CREATE TRIGGER reject_confetti BEFORE UPDATE OF state_revision ON world_characters BEGIN SELECT RAISE(ABORT,'injected commit failure'); END");return null;});
            refused(()->store.confetti(account,knight.id(),1,initial.revision(),id));
            check(db.worldProfile(account,knight.id(),1).equals(initial),"late failure rolls back stack consumption and revision");
            db.write(c->{c.createStatement().execute("DROP TRIGGER reject_confetti");return null;});
            var scenes=content.createScenes();
            var frames=new java.util.concurrent.LinkedBlockingQueue<byte[]>();
            var socket=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError();}public void write(byte[] frame){frames.add(frame);}};}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                var field=WorldSession.class.getDeclaredField("membership");field.setAccessible(true);var member=(SceneSimulation.Membership)field.get(world);
                short request=10;
                for(var definition:definitions){
                    member.resetInput();var before=db.worldProfile(account,knight.id(),1);
                    long held=before.items().stream().filter(i->i.config().equals(definition.config())).findFirst().orElseThrow().id();
                    var invocation=use(held,request++);
                    check(world.invocation(invocation),"native Use dispatched");var after=db.worldProfile(account,knight.id(),1);
                    check(after.items().stream().filter(i->i.id()==held).findFirst().orElseThrow().count()==1,"each variant consumes exactly one from its owned stack");
                    var actor=scenes.entry().simulation().snapshot().players().getFirst();
                    check(actor.activity()==267&&actor.sceneKnight().activityData().equals(new StarterCatalog.Model(definition.config(),Map.of())),"native item reference is scene-owned animation data");
                    world.invocation(invocation);check(db.worldProfile(account,knight.id(),1).equals(after),"receipt replay cannot consume again");
                    world.invocation(use(held,request++));check(db.worldProfile(account,knight.id(),1).equals(after),"busy refusal preserves remaining confetti");
                    var movement=new TownMovement(scenes.entry().map().collision(),scenes.entry().map().spawnX(),scenes.entry().map().spawnY());
                    movement.resetInput(1000);check(movement.confetti(definition.config(),1000),"confetti starts in an idle scene");
                    int duration=Confetti.duration(definition.config(),1000);movement.advance(1000+duration-1,1000+duration-1);
                    check(movement.activity()==267,"animation persists to its native deadline");
                    movement.advance(1000+duration,1000+duration);check(movement.activity()==0&&movement.activityData()==null,"native duration clears activity and data");
                }
                member.resetInput();world.invocation(use(id,request++));
                var exhausted=db.worldProfile(account,knight.id(),1);check(exhausted.items().stream().noneMatch(i->i.id()==id),"last use removes the empty stack");
                world.invocation(use(id,request++));check(db.worldProfile(account,knight.id(),1).equals(exhausted),"missing inventory item cannot be used");
                try{world.invocation(new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",request++))));throw new AssertionError("malformed listener accepted");}catch(SessionDecoder.Rejected expected){ }
                var entities=new SceneEntities(scenes.entry().map().content(),scenes.entry().map().collision(),scenes.entry().map().staticCollision());
                var auctionArea=scenes.entry().map().content().entries().stream().filter(e->e.key().equals("3733")&&e.spec().text("town-kind","").equals("OpenAuction")).findFirst().orElseThrow();
                check(auctionArea.rotation()==0&&auctionArea.spec().contains(1,18.5f,0,1,14,.4f),"native unrotated transformed capsule covers the public counter");
                check(auctionArea.spec().contains(1,18.5f,0,6,14,.4f)&&!auctionArea.spec().contains(1,18.5f,0,7,14,.4f)
                    &&!auctionArea.spec().contains(1,18.5f,0,1,18,.4f),"capsule endpoints and transformed bounds exclude positions outside the station");
                var prompt=entities.interaction(.45199376f,14.619257f,1.28f);
                check(prompt!=null&&entities.actor(prompt.actorId()).entity().key().equals("3733"),"auction building interaction reaches the captured public counter position");
                var source=entities.actor(prompt.actorId());
                var open=WorldSession.class.getDeclaredMethod("openTownDialog",org.tortodrone.town.TownCatalog.Station.class,ActorState.class);open.setAccessible(true);
                open.invoke(world,scenes.town().station("scene.1.placed.3733"),source);
                var dialog=WorldSession.class.getDeclaredField("townDialog");dialog.setAccessible(true);check(dialog.get(world)==null,"auction opens through a controller action without installing a scene modal");
                var search=new SessionDecoder.AuctionSearch(true,null,null,0,true,false);
                check(world.auctionInvocation(new SessionDecoder.Invocation(3,15,5,List.of(search,0,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",request++)))),"native featured auction search is reachable after opening");
                byte[] marker={0};output.send(()->marker);for(;;){var frame=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(frame!=null,"session output drains");if(frame==marker)break;}
            }
            System.out.println("PASS: all 14 confetti variants, owned single consumption, auth/revision/rollback, replay/busy/missing/listener rejection, native durations and reachable Auction House entry/search");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static SessionDecoder.Invocation use(long id,short request){return new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",request)));}
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception {try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){ }}
}
