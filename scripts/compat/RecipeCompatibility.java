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
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.PlayerObject;

/** Feed real inventory Use publications into the installed stock reader and DSet implementation. */
public final class RecipeCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path home=Files.createTempDirectory("tortodrone-native-recipe-");
        try{
            var db=new Database(home,false);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=org.tortodrone.resources.ContentManager.load(Path.of(".")).items();var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Recipe-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            String recipe="Recipe/Armor/Padded Armor Recipe";db.grantItems("Alice",knight.name(),-1,-1,items.require(recipe),2,-1,-1);
            var scenes=new SceneRegistry(SceneCatalog.load(Path.of("content/maps")));scenes.town(org.tortodrone.resources.ContentManager.load(Path.of(".")).town());
            var bytes=new ByteArrayOutputStream();bytes.write(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));bytes.write(HandshakeCodec.authenticationResponse((short)2,true,"fixture","Fixture"));
            var socket=new Socket(){@Override public OutputStream getOutputStream(){return bytes;}};var codec=new SessionCodec(items);
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,codec,1001,1,m->{},scenes)){
                output.send(()->codec.player((short)3,1001,1,"Fixture"));
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                long id=db.worldProfile(account,knight.id(),1).items().stream().filter(i->i.config().equals(recipe)).findFirst().orElseThrow().id();
                var confirm=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7);
                var use=new SessionDecoder.Invocation(3,9,14,Arrays.asList(id,null,confirm));world.townInvocation(use);world.townInvocation(use);
            }
            var framed=new ByteArrayInputStream(bytes.toByteArray());var body=new ByteArrayOutputStream();byte[] frame;
            while((frame=HandshakeCodec.readFrame(framed))!=null)body.write(frame);
            var in=new com.threerings.io.v(new ByteArrayInputStream(body.toByteArray()));PlayerObject player=null;int added=0;
            while(in.available()>0){
                Object message=in.qe();
                if(message instanceof DEvent)throw new AssertionError("Raw DEvent lacks EventNotification envelope");
                if(message instanceof ObjectResponse response&&response.yk() instanceof PlayerObject p)player=p;
                if(message instanceof EventNotification notice&&notice.yj().xH()==1001&&player!=null){
                    var event=notice.yj();
                    if(event instanceof CompoundEvent compound){for(var child:compound.xE())child.b(player);}
                    else if(event instanceof EntryAddedEvent<?> entry&&entry.getName().equals("recipes")){
                        if(player.recipes.size()!=0||!entry.b(player))throw new AssertionError("Recipe was replaced before its EntryAddedEvent");added++;
                    }else if(!(event instanceof InvocationResponseEvent))event.b(player);
                }
            }
            if(player==null||player.recipes.size()!=1||added!=1)throw new AssertionError("Use/replay did not publish exactly one native recipe addition");
            System.out.println("PASS: real recipe Use/replay publications have native envelopes, apply one recipe entry and preserve event order");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
