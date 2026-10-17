package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.town.TownCatalog;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Live scene publications must stop before the post-rename output drain closes admission. */
public final class RenameDrainTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-rename-drain-");
        try{
            var db=new Database(home,false);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Drain-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            db.grantItems("Alice",knight.name(),-1,-1,items.require("Upgrade/Rename Knight"),1,-1,-1);
            var scenes=new SceneRegistry(SceneCatalog.load(Path.of("content/packs/vanilla/maps")));scenes.town(TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json")));
            var socket=new SlowSocket();var codec=new SessionCodec(items);
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,codec,1001,1,m->{},scenes);var worker=Executors.newSingleThreadExecutor()){
                try{
                    world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                    socket.block=true;check(socket.blocked.await(3,TimeUnit.SECONDS),"live writer reached slow peer");
                    var handler=ClientSession.class.getDeclaredMethod("handleEvent",SessionOutput.class,SessionCodec.class,SessionDecoder.Event.class,int.class,Map.class,SessionLog.class,CharacterStore.class,boolean.class,long.class,WorldSession.class);handler.setAccessible(true);
                    var request=new SessionDecoder.Invocation(3,1,8,List.of("Renamed-Knight",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7)));
                    var renamed=worker.submit(()->{handler.invoke(null,output,codec,request,1001,new HashMap<String,Short>(),(SessionLog)m->{},characters,true,1L,world);return null;});
                    var closed=SessionOutput.class.getDeclaredField("closed");closed.setAccessible(true);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean draining=false;
                    while(System.nanoTime()<deadline){synchronized(output){draining=closed.getBoolean(output);}if(draining)break;Thread.sleep(1);}
                    check(draining,"rename entered output drain");
                    check(!socket.ended.await(250,TimeUnit.MILLISECONDS),"scene publisher must not close socket while rename confirmation is queued");
                    socket.released.countDown();renamed.get(3,TimeUnit.SECONDS);
                    check(socket.isClosed()&&socket.frames>1,"rename drains output then disconnects");
                    check(characters.roster().getFirst().name().equals("Renamed-Knight"),"rename remains committed");
                }finally{socket.released.countDown();socket.close();}
            }
            System.out.println("PASS: successful rename stops live publications before draining a slow peer and disconnecting");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static final class SlowSocket extends Socket {
        final CountDownLatch blocked=new CountDownLatch(1),released=new CountDownLatch(1),ended=new CountDownLatch(1);
        volatile boolean block;volatile int frames;
        @Override public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
            @Override public void write(byte[] bytes)throws IOException{
                if(block){blocked.countDown();try{if(!released.await(5,TimeUnit.SECONDS))throw new IOException("fixture slow peer");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
                if(isClosed())throw new IOException("fixture socket closed");frames++;
            }
        };}
        @Override public void close()throws IOException{super.close();ended.countDown();released.countDown();}
    }
}
