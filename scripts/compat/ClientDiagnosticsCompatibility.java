import java.io.*;
import java.util.*;
import com.threerings.presents.dobj.InvocationRequestEvent;
import com.threerings.presents.net.ForwardEventRequest;
import com.threerings.projectx.data.KnightMarshaller;
import org.tortodrone.protocol.*;

/** Read-only stock marshaller oracle for the advisory hardware report. */
public final class ClientDiagnosticsCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent event;
        @Override public void a(int oid,int service,int method,Object[] args,com.threerings.presents.net.Transport transport){event=new InvocationRequestEvent(oid,service,method,args);}
    }
    public static void main(String[] args)throws Exception {
        var dispatch=new Dispatch();var knight=new KnightMarshaller();knight.a(3,1,dispatch);
        knight.a("Fixture OS","1.0","Fixture CPU","25","amd64","1920x1080","1280x720",false,1.25f);
        var bytes=new ByteArrayOutputStream();var out=new com.threerings.io.w(bytes);out.bf(new ForwardEventRequest(dispatch.event));out.flush();
        var report=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
        if(report.oid()!=3||report.service()!=1||report.method()!=10||!report.arguments().equals(List.of("Fixture OS","1.0","Fixture CPU","25","amd64","1920x1080","1280x720",false,1.25f)))throw new AssertionError("Stock hardware report layout differs");
        knight.a(null,null,null,null,null,null,null,true,0f);
        bytes=new ByteArrayOutputStream();out=new com.threerings.io.w(bytes);out.bf(new ForwardEventRequest(dispatch.event));out.flush();
        report=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
        if(!report.arguments().equals(Arrays.asList(null,null,null,null,null,null,null,true,0f)))throw new AssertionError("Nullable stock hardware properties differ");
        System.out.println("PASS: unchanged stock KnightMarshaller 10, seven nullable strings, fullscreen boolean and UI scale float decode exactly");
    }
}
