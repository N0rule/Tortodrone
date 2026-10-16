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

/** Native inventory Use dispatch and dialog ownership, on a disposable authenticated session. */
public final class TownServicesSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-town-services-");
        try{
            var db=new Database(home,true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Service-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            String recipe="Recipe/Armor/Padded Armor Recipe";
            db.grantItems("Alice",knight.name(),-1,-1,items.require(recipe),2,-1,-1);
            db.grantItems("Alice",knight.name(),-1,-1,items.require("Upgrade/Rename Knight"),1,-1,-1);
            var scenes=new SceneRegistry(SceneCatalog.load(Path.of("content/packs/vanilla/maps")));
            scenes.town(TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json")));
            var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                long id=db.worldProfile(account,knight.id(),1).items().stream().filter(i->i.config().equals(recipe)).findFirst().orElseThrow().id();
                var confirm=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7);
                check(!world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(id,confirm))),"deleteItem cannot accidentally learn a recipe");
                var use=new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,confirm));
                check(world.townInvocation(use),"stock inventory Use reaches the recipe handler");
                var learned=db.worldProfile(account,knight.id(),1);
                check(learned.town().recipes().size()==1&&learned.items().stream().filter(i->i.config().equals(recipe)).mapToInt(WorldProfile.Item::count).sum()==1,"native Use consumes one recipe and publishes the learned book");
                world.townInvocation(use);check(db.worldProfile(account,knight.id(),1).equals(learned),"duplicate Use preserves recipes, currency, revision and remaining item");
                check(world.townInvocation(new SessionDecoder.Invocation(3,1,14,List.of(confirm))),"native rename eligibility callback is implemented");
                world.townInvocation(new SessionDecoder.Invocation(3,1,8,List.of("Renamed-Knight",confirm)));
                check(characters.roster().getFirst().name().equals("Renamed-Knight"),"native rename updates the connected character store");
                // Depot replacement and late close must not erase a newer dialog.
                var open=WorldSession.class.getDeclaredMethod("openTownDialog",TownCatalog.Station.class,ActorState.class);open.setAccessible(true);
                var station=scenes.town().station("Shop/Haven/Exchange");open.invoke(world,station,null);
                var created=WorldSession.class.getDeclaredField("townCreated");created.setAccessible(true);long first=created.getLong(world);
                open.invoke(world,station,null);long second=created.getLong(world);check(second>first,"replacement dialog has a fresh ownership token");
                var place=WorldSession.class.getDeclaredField("scene");place.setAccessible(true);int oid=((SceneRegistry.Instance)place.get(world)).oid();
                check(world.townInvocation(new SessionDecoder.Invocation(oid,6,1,List.of(first))),"stale dialog close is handled without dispatching an unrelated operation");
                var dialog=WorldSession.class.getDeclaredField("townDialog");dialog.setAccessible(true);check(dialog.get(world)!=null,"stale close preserves the newer depot");
                world.townInvocation(new SessionDecoder.Invocation(oid,6,1,List.of(second)));check(dialog.get(world)==null,"matching close releases the native modal dialog");
                check(world.townInvocation(new SessionDecoder.Invocation(oid,6,1,List.of(second))),"repeated close is idempotently acknowledged");
            }
            System.out.println("PASS: native recipe Use, duplicate rollback, rename dispatch and Depot dialog ownership/close lifecycle");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
