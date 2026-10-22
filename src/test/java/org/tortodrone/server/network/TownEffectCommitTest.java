package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.town.TownCatalog;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** A source connection lost after commit cannot suppress the effect for scene observers. */
public final class TownEffectCommitTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-town-effect-");
        try {
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var registry=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));registry.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            for(boolean learn:List.of(true,false)){
                var db=new Database(home.resolve(learn?"recipe":"energy"),true);int account=db.create("Learner",new Passwords.Protection(new byte[16],new byte[32]),false);
                var characters=new CharacterStore(db,account,"Learner",items);var knight=characters.create(new Knight(0,"Learner-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
                String recipe="Recipe/Armor/Padded Armor Recipe";db.grantItems("Learner",knight.name(),-1,-1,items.require(recipe),1,-1,-1);db.setCurrency("Learner",knight.name(),account,-1,org.tortodrone.character.Currency.ENERGY,1_000_000);
                var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
                try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1201,1,m->{},registry)){
                    synchronized(world){
                        world.select(knight.name(),Map.of());world.subscribe((short)1);world.entered();
                        var before=db.worldProfile(account,knight.id(),1);
                        var listener=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7);
                        SessionDecoder.Invocation use;
                        if(learn){long id=before.items().stream().filter(i->i.config().equals(recipe)).findFirst().orElseThrow().id();use=new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,listener));}
                        else {
                            var result=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8);
                            world.townInvocation(new SessionDecoder.Invocation(3,16,4,List.of(result)));
                            var offer=characters.depot(registry.town()).catalog().offers().stream().filter(o->o.enabled()&&o.good().energy()>0&&o.good().limit()==0&&items.find(o.good().item()).grantable()).findFirst().orElseThrow();
                            use=new SessionDecoder.Invocation(3,16,10,List.of(offer.id(),offer.good().energy(),listener));
                        }
                        var scene=WorldSession.class.getDeclaredField("scene");scene.setAccessible(true);var simulation=((SceneRegistry.Instance)scene.get(world)).simulation();
                        synchronized(simulation){
                            var baseline=simulation.snapshot();output.close();
                            try{world.townInvocation(use);throw new AssertionError("Expected source notification failure");}catch(IOException expected){ }
                            var after=db.worldProfile(account,knight.id(),1);
                            check(learn?after.town().recipes().size()==1:after.energy()<before.energy(),"operation commits before notification");
                            var effects=simulation.snapshot().effectsSince(baseline);
                            check(effects.size()==1&&effects.getFirst().config().name().equals(learn?"Player/Learn Recipe":"Energy Spent")&&effects.getFirst().ownerOid()==0,"source disconnect leaves one shared effect for observers");
                        }
                    }
                }
            }
            System.out.println("PASS: committed recipe/Energy effects survive failed source notification without rollback or replay");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
}
