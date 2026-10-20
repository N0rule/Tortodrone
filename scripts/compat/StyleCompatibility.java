import java.io.*;
import java.util.*;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.*;
import org.tortodrone.character.Knight;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.StyleStore;

/** Stock reader and marshaller check; no network, client launch or preferences. */
public final class StyleCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent request;
        @Override public void a(int oid,int code,int method,Object[] args,Transport transport){request=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var dispatch=new Dispatch();var service=new KnightMarshaller();service.a(3,1,dispatch);
        service.a((Object)ProjectXCodes.EyeType.ALMOND,(com.threerings.presents.client.A.a)null);
        var request=new ByteArrayOutputStream();var writer=new com.threerings.io.w(request);
        writer.bf(new ForwardEventRequest(dispatch.request));writer.flush();
        var decoded=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(request.toByteArray()).event();
        check(decoded.service()==1&&decoded.method()==1&&decoded.arguments().getFirst().equals(new SessionDecoder.EnumValue("com.threerings.projectx.data.ProjectXCodes$EyeType",1)),"native applyStyle request");
        var bytes=new ByteArrayOutputStream();append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        var knight=new Knight(1,"Fixture","","",null,null,"Blue",8,3,1,0);
        var eyes=java.util.stream.IntStream.range(0,47).boxed().toList();
        append(bytes,new SessionCodec().styles(1001,knight,new StyleStore.Saved(List.of(264,277),eyes,List.of(0,2,3,4))));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var event=(CompoundEvent)((EventNotification)in.qe()).yj();var player=new PlayerObject();
        for(var child:event.xE())child.b(player);
        check(player.color==264&&player.eye==ProjectXCodes.EyeType.ALMOND&&player.height==ProjectXCodes.KnightHeight.TALL_1,"native appearance attributes");
        check(Arrays.equals(player.savedColors,new int[]{264,277})&&Arrays.equals(player.savedEyes,ProjectXCodes.EyeType.values())&&player.savedHeights.length==4,"native saved-style arrays including all 47 eye bits");
        check(in.available()==0,"no trailing bytes");
        System.out.println("PASS: stock applyStyle marshaller, appearance attributes and saved color/eye/height arrays");
    }
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
