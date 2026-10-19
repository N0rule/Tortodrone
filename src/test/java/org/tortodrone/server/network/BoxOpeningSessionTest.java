package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.loot.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Duplicate native listener tokens versus distinct consecutive Arsenal opens. */
public final class BoxOpeningSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-box-session-");
        try {
            var snapshot=ContentManager.load(Path.of("."));var items=snapshot.items();BoxFixture.write(home.resolve("content"));
            var db=new Database(home.resolve("data"),true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Box-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            db.grantItems("Alice",knight.name(),-1,-1,items.require("lucky_prize_box"),2,-1,-1);
            db.grantItems("Alice",knight.name(),-1,-1,items.require("silver_key"),2,-1,-1);
            var scenes=snapshot.createScenes();scenes.boxes(BoxCatalog.load(home.resolve("content"),items));
            var frames=new java.util.concurrent.LinkedBlockingQueue<byte[]>();
            var socket=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){public void write(int v){throw new AssertionError();}public void write(byte[] frame){frames.add(frame);}};}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));drain(output,frames);world.subscribe((short)1);world.entered();drain(output,frames);
                var initial=db.worldProfile(account,1,1);var ids=initial.items().stream().filter(i->i.config().equals("Upgrade/Prize Box/Lucky Prize Box")).map(WorldProfile.Item::id).toList();
                var result=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)12);
                var request=new SessionDecoder.Invocation(3,9,12,List.of(ids.getFirst(),-1,result));
                check(world.townInvocation(request),"native method 12 dispatched");var opened=db.worldProfile(account,1,1);drain(output,frames);
                check(count(opened,"Weapon/Sword/Calibur")==2&&count(opened,"Ticket/Keys/Silver Key")==2,"keyless native operation grants real rewards and preserves keys");
                world.townInvocation(request);check(db.worldProfile(account,1,1).equals(opened),"same native invocation receipt prevents replay");
                world.townInvocation(new SessionDecoder.Invocation(3,9,12,List.of(ids.get(1),-1,result)));check(db.worldProfile(account,1,1).equals(opened),"reused request ID with different box is rejected");
                world.townInvocation(new SessionDecoder.Invocation(3,9,12,List.of(ids.get(1),-1,new SessionDecoder.Listener(result.type(),(short)13))));
                var twice=db.worldProfile(account,1,1);check(count(twice,"Weapon/Sword/Calibur")==4,"fresh listener opens another box legitimately");
                try{world.townInvocation(new SessionDecoder.Invocation(3,9,12,List.of(ids.getFirst(),-1,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)14))));throw new AssertionError("Expected malformed callback refusal");}catch(SessionDecoder.Rejected expected){ }
                check(db.worldProfile(account,1,1).equals(twice),"malformed callback never mutates inventory");
                world.close();world.townInvocation(new SessionDecoder.Invocation(3,9,12,List.of(ids.getFirst(),-1,result)));check(db.worldProfile(account,1,1).equals(twice),"closed session cannot open");
            }
            System.out.println("PASS: native opening dispatch, keyless key preservation, listener replay/mismatch, consecutive opens, malformed callback and closed session rejection");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static int count(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
    private static void drain(SessionOutput output,java.util.concurrent.BlockingQueue<byte[]> frames)throws Exception {
        byte[] marker={0};output.send(()->marker);for(;;){byte[] frame=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(frame!=null,"output drains");if(frame==marker)return;}
    }
}
