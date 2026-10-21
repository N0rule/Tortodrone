import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.social.data.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;

/** Read-only stock marshaller and friend-presence oracle; no sockets or preferences. */
public final class SocialCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent request;
        @Override public void a(int oid,int code,int method,Object[] args,Transport transport){request=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var dispatch=new Dispatch();var service=new SocialMarshaller();service.a(3,12,dispatch);
        service.p(new com.threerings.util.Name("Bob"),(com.threerings.presents.client.A.a)null);check(method(dispatch)==2,"native join is method 2");
        service.o(new com.threerings.util.Name("Bob"),(com.threerings.presents.client.A.a)null);check(method(dispatch)==6,"native removal is method 6");
        var scenes=new SceneRegistry(SceneCatalog.load(Path.of("content/maps")));var scene=scenes.entry();
        var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        append(bytes,codec.sceneLocation(1002,scene));
        append(bytes,codec.friends(1002,List.of(),List.of(new SessionCodec.FriendView(2,"Bob",257,"~Haven",0,scene.map().zone(),scene.oid()+1))));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();var player=new PlayerObject();
        apply(in,player);apply(in,player);
        if(player.instanceId!=scene.oid()||player.zoneId!=scene.map().zone())throw new AssertionError("native current Town instance/zone");
        var friend=player.friends.iterator().next();var where=(Whereabouts.Online)friend.whereabouts;
        if(where.instanceId!=scene.oid()+1||where.zoneId!=scene.map().zone()||!where.a(player,new ServerObject(),System.currentTimeMillis(),false))throw new AssertionError("native friend Join UI is enabled");
        if(in.available()!=0)throw new AssertionError("trailing fields");
        System.out.println("PASS: stock Social join/remove IDs, friend whereabouts and native joinability predicate");
    }
    private static int method(Dispatch dispatch)throws Exception {
        var bytes=new ByteArrayOutputStream();var writer=new com.threerings.io.w(bytes);writer.bf(new ForwardEventRequest(dispatch.request));writer.flush();
        var decoded=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
        if(decoded.service()!=12||!decoded.arguments().getFirst().equals("Bob"))throw new AssertionError("native social arguments");return decoded.method();
    }
    private static void apply(com.threerings.io.v in,DObject player)throws Exception{var event=(CompoundEvent)((EventNotification)in.qe()).yj();for(var child:event.xE())child.b(player);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
