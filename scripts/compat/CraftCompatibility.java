import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.io.v;
import com.threerings.presents.dobj.*;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.net.ForwardEventRequest;

import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.craft.data.CraftMarshaller;
import com.threerings.presents.client.A;
import com.threerings.projectx.data.ProjectXSceneMarshaller;
import org.tortodrone.character.KnightProgress;
import org.tortodrone.protocol.*;
import org.tortodrone.town.TownCatalog;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.character.WorldProfile;

/** Current installed marshallers are the oracle: craft invocation, dialog close and reply wire layouts. */
public final class CraftCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent event;
        @Override public void a(int oid,int code,int method,Object[] args,com.threerings.presents.net.Transport transport){event=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        // The pet alchemy machine is a catalog crafter: method one carries the machine catalog index.
        var dispatch=new Dispatch();
        var craft=new CraftMarshaller();craft.a(10,101,dispatch);craft.a(3,new long[]{22L,44L},true,(A.c)null);
        var invocation=decode(dispatch.event);check("method",invocation.method(),1);check("service",invocation.service(),101);check("oid",invocation.oid(),10);
        check("index",invocation.arguments().get(0),Integer.valueOf(3));
        check("items type",invocation.arguments().get(1) instanceof long[] ids && ids.length==2 && ids[0]==22L && ids[1]==44L,true);
        check("upgrade",invocation.arguments().get(2),Boolean.TRUE);
        listener(invocation.arguments().get(3),true);
        // Plain crafters send the learned recipe identifier instead.
        craft.a(77L,new long[]{88L},false,(A.c)null);
        invocation=decode(dispatch.event);check("method",invocation.method(),2);
        check("learned id",invocation.arguments().get(0),Long.valueOf(77L));
        listener(invocation.arguments().get(3),true);
        // An ingredient-less craft request is still well formed.
        craft.a(3,new long[0],false,(A.c)null);
        invocation=decode(dispatch.event);check("empty items",((long[])invocation.arguments().get(1)).length==0,true);
        // Dialog closes ride the scene service as [created].
        var scene=new ProjectXSceneMarshaller();scene.a(10,6,dispatch);scene.R(123456L);
        invocation=decode(dispatch.event);check("service",invocation.service(),6);check("method",invocation.method(),1);
        check("close created",invocation.arguments().getFirst(),Long.valueOf(123456L));
        check("close listener count",invocation.arguments().size(),1);
        // Our winner/loser replies must satisfy the stock craft result listener contract,
        // read through a normal session stream (the handshake primes the stock class table).
        var bytes=new ByteArrayOutputStream();
        var codec=new SessionCodec();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        var item=new WorldProfile.Item(20,WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,null,false,true,false,1,false,2,.4f,List.of());
        var profile=new WorldProfile(42,1,1,1000,100,1,List.of(item),KnightProgress.RECRUIT);
        append(bytes,codec.townResult(10,(short)9,527L,profile));
        append(bytes,codec.invocationFailure(10,(short)10,"Missing recipe ingredients"));
        append(bytes,codec.invocationConfirmed(10,(short)11));
        var in=new v(new ByteArrayInputStream(bytes.toByteArray()));
        in.qe();in.qe(); // handshake and authentication responses prime the stock reader
        var winner=(InvocationResponseEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        check("success method",winner.yc(),1);check("success request",winner.yf(),9);
        check("success payload",winner.yd()[0],Long.valueOf(527L));
        var loser=(InvocationResponseEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        check("failure method",loser.yc(),0);check("failure request",loser.yf(),10);
        check("failure reason",loser.yd()[0] instanceof String text&&text.length()>1,true);
        var confirmed=(InvocationResponseEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        check("confirm method",confirmed.yc(),1);check("confirm args",confirmed.yd(),null);
        if(in.available()!=0)throw new AssertionError("Trailing craft reply wire bytes");
        // The alchemy machines' catalogs must resolve server-side with matchable native ingredients.
        var rules=TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json"));
        var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));
        for(String actor:List.of("Shop/Haven/Alchemy, Battle Sprites","scene.1.6837.39","scene.30893.207.4")){
            var station=rules.station(actor);
            if(station==null)throw new AssertionError("Missing station: "+actor);
            for(String text:station.recipes()){
                var recipe=rules.recipe(text);
                if(recipe==null)throw new AssertionError("Station catalog references unavailable recipe: "+text);
                check("recipe output item",recipe.item().isEmpty()||items.find(recipe.item())!=null,true);
                for(var ingredient:recipe.ingredients()){
                    if(ingredient.kind().equals("SpriteFoodMaterial")||ingredient.kind().equals("ItemGroup"))continue;
                    if(ingredient.item()==null||ingredient.item().isEmpty()||items.find(ingredient.item())==null)
                        throw new AssertionError("Recipe "+text+" ingredient does not resolve: "+ingredient.item());
                }
            }
        }
        System.out.println("PASS: native craft invocations, dialog close and callback replies match the stock layouts");
    }
    private static SessionDecoder.Invocation decode(InvocationRequestEvent event)throws Exception{
        var bytes=new ByteArrayOutputStream();var nativeOut=new com.threerings.io.w(bytes);nativeOut.bf(new ForwardEventRequest(event));nativeOut.flush();
        return (SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
    }
    private static void listener(Object value,boolean result)throws Exception{
        if(!(value instanceof org.tortodrone.protocol.SessionDecoder.Listener listener))throw new AssertionError("Missing result listener");
        String expected="com.threerings.presents.data.InvocationMarshaller$"+(result?"ResultMarshaller":"ConfirmMarshaller");
        if(!listener.type().equals(expected))throw new AssertionError("Listener type differs: "+listener.type()+" != "+expected);
    }
    private static void check(String name,Object actual,Object expected){
        if(!Objects.equals(actual,expected))throw new AssertionError(name+" differs: "+actual+" != "+expected);
    }
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
}
