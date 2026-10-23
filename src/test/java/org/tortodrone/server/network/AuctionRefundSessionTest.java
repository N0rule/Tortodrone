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
import org.tortodrone.storage.*;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Actual native bid entry point, deployment-scoped presence and observer publication after commit. */
public final class AuctionRefundSessionTest {
    private record Client(int account,String name,LinkedBlockingQueue<byte[]> frames,SessionOutput output,WorldSession world) implements AutoCloseable {
        public void close(){world.close();output.close();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-outbid-session-");
        try {
            var content=ContentManager.load(Path.of("."));var db=new Database(root,true);
            var scenes=content.createScenes();SceneRegistry.install(scenes);
            var house=new AuctionHouse(db,content.items(),content.town());
            var listing=house.createFeatured("Material/Gel Drop",1,100,0,0,1);
            int aw;
            try(var alice=client(db,content.items(),scenes,"Alice",1001);var bob=client(db,content.items(),scenes,"Bobby",1002)) {
                aw=db.worldProfile(alice.account(),1,1).worldId();
                check(WorldSession.auctionPresence(db.file()).keySet().contains(aw),"selected authenticated knight is online for this deployment");
                check(WorldSession.auctionPresence(root.resolve("other.db")).isEmpty(),"same native IDs in another deployment cannot select direct refunds");
                var listener=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)7);
                alice.world().auctionInvocation(new SessionDecoder.Invocation(3,15,1,List.of(listing.id(),100,listener)));
                bob.world().auctionInvocation(new SessionDecoder.Invocation(3,15,1,List.of(listing.id(),110,listener)));
                var refunded=db.worldProfile(alice.account(),1,1);
                check(refunded.crowns()==10000&&refunded.town().mail().isEmpty()&&refunded.town().outbids().size()==1,"native bid commits online credit and indicator without mail");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean notice=false,indicator=false;
                while(System.nanoTime()<deadline&&(!notice||!indicator)) {
                    byte[] frame=alice.frames().poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;
                    String raw=new String(frame,StandardCharsets.ISO_8859_1);
                    notice|=raw.contains("m.note_outbid|")&&raw.contains("|~100")&&raw.contains("auction");
                    indicator|=raw.contains("outbid");
                }
                check(notice&&indicator,"connected original bidder receives native outbid flag and localized system notice through the poller");
                alice.world().auctionInvocation(new SessionDecoder.Invocation(3,15,4,List.of(listener)));
                check(db.worldProfile(alice.account(),1,1).town().outbids().isEmpty(),"My Bids native call acknowledges durable indicator");
            }
            WorldStatePoller.awaitStopped();check(!WorldSession.auctionPresence(db.file()).containsKey(aw),"closed connection leaves presence");
            System.out.println("PASS: native online outbid credit, deployment presence, poller publication, notification and My Bids acknowledgement");
        }finally{WorldStatePoller.awaitStopped();ContentFixture.delete(root);}
    }
    private static Client client(Database db,ItemCatalog items,SceneRegistry scenes,String name,int oid)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);
        var characters=new CharacterStore(db,account,name,items);String knight=name+"-Knight";
        characters.create(new Knight(0,knight,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
        db.setCurrency(name,knight,account,-1,org.tortodrone.character.Currency.CROWNS,10000);
        var frames=new LinkedBlockingQueue<byte[]>();
        var socket=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError("Complete frames required");}public void write(byte[] bytes){frames.add(bytes);}};}};
        var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),oid,1,m->{},scenes);
        try{world.select(knight,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();return new Client(account,name,frames,output,world);}
        catch(Exception failure){world.close();output.close();throw failure;}
    }
}
