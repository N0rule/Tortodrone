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
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Service-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            String recipe="Recipe/Armor/Padded Armor Recipe";
            db.grantItems("Alice",knight.name(),-1,-1,items.require(recipe),2,-1,-1);
            db.grantItems("Alice",knight.name(),-1,-1,items.require("Upgrade/Rename Knight"),1,-1,-1);
            var scenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
            scenes.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            var frames=new java.util.concurrent.LinkedBlockingQueue<byte[]>();
            var socket=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){
                public void write(int value){throw new AssertionError("Expected complete downstream frames");}
                public void write(byte[] frame){frames.add(frame);}
            };}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));drain(output,frames);
                world.subscribe((short)1);
                var firstFrame=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(firstFrame!=null,"initial Town subscription publishes before readiness");
                try(var in=new DataInputStream(new ByteArrayInputStream(firstFrame))){
                    in.readInt();check(in.readShort()<0&&in.readUTF().equals("com.threerings.presents.net.UpdateThrottleMessage"),"fresh Haven configures the shared native TCP queue before its scene snapshot");
                    check(in.readShort()==-1&&in.readInt()==SessionCodec.TCP_MESSAGES_PER_SECOND&&in.read()==-1,"bounded native TCP throttle payload");
                }
                drain(output,frames);world.unsubscribe();world.subscribe((short)2);
                var repeated=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(repeated!=null,"resubscription publishes a scene");
                check(!new String(repeated,java.nio.charset.StandardCharsets.ISO_8859_1).contains("UpdateThrottleMessage"),"resubscription retains the connection throttle");
                world.entered();
                long id=db.worldProfile(account,knight.id(),1).items().stream().filter(i->i.config().equals(recipe)).findFirst().orElseThrow().id();
                var confirm=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7);
                check(world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(id,confirm))),"stock Trash dispatches to the deletion handler");
                var trashed=db.worldProfile(account,knight.id(),1);
                long trashedItem=id;
                check(trashed.town().recipes().isEmpty()&&trashed.items().stream().noneMatch(i->i.id()==trashedItem),"Trash deletes the whole owned item and never touches recipes");
                db.grantItems("Alice",knight.name(),-1,-1,items.require(recipe),1,-1,-1);
                id=db.worldProfile(account,knight.id(),1).items().stream().filter(i->i.config().equals(recipe)).findFirst().orElseThrow().id();
                var use=new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,confirm));
                check(world.townInvocation(use),"stock inventory Use reaches the recipe handler");
                var learned=db.worldProfile(account,knight.id(),1);
                check(learned.town().recipes().size()==1&&learned.items().stream().filter(i->i.config().equals(recipe)).mapToInt(WorldProfile.Item::count).sum()==1,"native Use consumes one recipe and publishes the learned book");
                world.townInvocation(use);check(db.worldProfile(account,knight.id(),1).equals(learned),"duplicate Use preserves recipes, currency, revision and remaining item");
                var temporaryField=WorldSession.class.getDeclaredField("temporaryItems");temporaryField.setAccessible(true);
                @SuppressWarnings("unchecked") var temporary=(Map<Long,WorldProfile.Item>)temporaryField.get(world);
                temporary.put(-1L,new WorldProfile.Item(-1,WorldProfile.Slot.OTHER,"Gear/Dungeon/Vitapod/Health +01",null,0,0,false,false,false,1,false));
                temporary.put(-2L,new WorldProfile.Item(-2,WorldProfile.Slot.OTHER,recipe,null,0,null,false,false,false,1,true));
                temporary.put(-3L,new WorldProfile.Item(-3,WorldProfile.Slot.OTHER,recipe,null,0,null,false,false,false,3,false));
                world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(-1L,confirm)));
                world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(-2L,confirm)));
                check(temporary.containsKey(-1L)&&temporary.containsKey(-2L),"temporary equipped and permanent items cannot be trashed");
                world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(-3L,confirm)));
                world.townInvocation(new SessionDecoder.Invocation(3,9,1,List.of(-3L,confirm)));
                check(!temporary.containsKey(-3L)&&db.worldProfile(account,knight.id(),1).equals(learned),"temporary stack Trash and replay leave persistent state unchanged");
                temporary.clear();
                check(world.townInvocation(new SessionDecoder.Invocation(3,1,2,List.of(confirm))),"native rename eligibility callback is implemented");
                world.townInvocation(new SessionDecoder.Invocation(3,1,8,List.of("Renamed-Knight",confirm)));
                check(characters.roster().getFirst().name().equals("Renamed-Knight"),"native rename updates the connected character store");
                check(world.takeRenameDisconnect(),"rename ends the native session");
                var store=new org.tortodrone.storage.TownStore(db,items,scenes.town());
                try{store.canRename(account,knight.id(),1);check(false,"renaming inside thirty days is refused");}
                catch(IOException failure){check(failure.getMessage().equals("m.rename_too_soon"),"thirty-day rename clock is the official refusal");}
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
    private static void drain(SessionOutput output,java.util.concurrent.BlockingQueue<byte[]> frames)throws Exception {
        byte[] marker=new byte[]{0};output.send(()->marker);
        for(;;){byte[] frame=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(frame!=null,"queued output drains");if(frame==marker)return;}
    }
}
