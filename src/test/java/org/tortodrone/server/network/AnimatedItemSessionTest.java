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

/** Persistent fireworks/fireflies use must commit once before starting the scene animation. */
public final class AnimatedItemSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-animated-items-");
        try {
            var content=ContentManager.load(Path.of("."));var items=content.items();
            var definitions=items.entries().stream().filter(i->AnimatedItems.supports(i.config())).toList();
            check(definitions.size()==82,"81 playable fireworks and one firefly jar; no authoring base");
            check(!AnimatedItems.supports("Usable/Fireworks/Parts/Firework"),"internal firework remains unavailable");
            var db=new Database(home,true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Firework-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            for(var definition:definitions)db.grantItems("Alice",knight.name(),-1,-1,definition,2,-1,-1);
            var before=db.worldProfile(account,1,1);var held=before.items().stream().filter(i->AnimatedItems.supports(i.config())).findFirst().orElseThrow();
            var store=characters.town(content.town());
            refused(()->store.animatedItem(account,1,999,before.revision(),held.id()));
            refused(()->store.animatedItem(account,1,1,before.revision()-1,held.id()));
            refused(()->store.animatedItem(account,1,1,before.revision(),Long.MAX_VALUE));
            int stranger=db.create("Bob",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,stranger,"Bob",items).create(new Knight(0,"Stranger-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var other=db.worldProfile(stranger,1,1);refused(()->store.animatedItem(stranger,1,1,other.revision(),held.id()));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_animated BEFORE UPDATE OF state_revision ON world_characters BEGIN SELECT RAISE(ABORT,'test'); END");}return null;});
            refused(()->store.animatedItem(account,1,1,before.revision(),held.id()));
            check(db.worldProfile(account,1,1).equals(before),"failed auth/revision/ownership and late write roll back consumption");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_animated");}return null;});
            var scenes=content.createScenes();var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                var field=WorldSession.class.getDeclaredField("membership");field.setAccessible(true);var member=(SceneSimulation.Membership)field.get(world);
                short request=20;
                for(var definition:definitions){
                    member.resetInput();var current=db.worldProfile(account,1,1);
                    var item=current.items().stream().filter(i->i.config().equals(definition.config())).findFirst().orElseThrow();
                    var use=use(item.id(),request++);check(world.invocation(use),"native Use dispatch");
                    var after=db.worldProfile(account,1,1);
                    check(after.items().stream().filter(i->i.config().equals(definition.config())).mapToInt(WorldProfile.Item::count).sum()==1,"one accepted use consumes one item");
                    check(scenes.entry().simulation().snapshot().players().getFirst().activity()==TownMovement.ANIMATED_ITEM,"dedicated native animation starts");
                    world.invocation(use);check(db.worldProfile(account,1,1).equals(after),"request replay cannot consume again");
                    world.invocation(use(item.id(),request++));check(db.worldProfile(account,1,1).equals(after),"busy use cannot consume again");
                    var movement=new TownMovement();movement.resetInput(1000);
                    check(movement.animatedItem(definition.config(),1000),"idle use starts");
                    movement.advance(1000+AnimatedItems.duration(definition.config())-1,1000);
                    check(movement.activity()==267,"animation persists to native deadline");
                    movement.advance(1000+AnimatedItems.duration(definition.config()),1000);
                    check(movement.activity()==0&&movement.activityData()==null,"animation clears at native deadline");
                }
            }
            System.out.println("PASS: 82 playable animated items, single consumption, auth/revision/rollback, replay/busy handling and native durations");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static SessionDecoder.Invocation use(long id,short request){return new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",request)));}
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
