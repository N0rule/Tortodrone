import java.io.*;
import java.util.*;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.InvocationRequestEvent;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.presents.net.*;
import org.tortodrone.protocol.*;

/** Read-only installed-client check: native billing request IDs/types and refusal frames. */
public final class BillingCompatibility {
    public static void main(String[] args)throws Exception {
        var m=new Capture();
        m.a("",0,false,null);check(m,1,String.class,Integer.class,Boolean.class,InvocationMarshaller.ResultMarshaller.class);
        m.a("","",0,0,"","",0,false,null);check(m,2,String.class,String.class,Integer.class,Integer.class,String.class,String.class,Integer.class,Boolean.class,InvocationMarshaller.ResultMarshaller.class);
        m.b("en",null);check(m,3,String.class,InvocationMarshaller.ResultMarshaller.class);
        m.c(0,"",null);check(m,6,Integer.class,String.class,InvocationMarshaller.ConfirmMarshaller.class);
        m.a(0,"",null);check(m,7,Integer.class,String.class,InvocationMarshaller.ResultMarshaller.class);
        m.a(0L,false,null);check(m,8,Long.class,Boolean.class,InvocationMarshaller.ConfirmMarshaller.class);
        var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"fixture","Fixture"));
        var codec=new SessionCodec();append(bytes,codec.invocationFailure(1001,(short)7,"Billing disabled"));
        append(bytes,codec.townResult(1001,(short)8,-1,null));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var failure=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        var result=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        if(!Arrays.equals(failure.yd(),new Object[]{"~Billing disabled"})||!Arrays.equals(result.yd(),new Object[]{-1})||in.available()!=0)throw new AssertionError("Native refusal layout differs");
        if(com.threerings.projectx.billing.data.BillingResponseCode.ERROR.getCode()!=-1)throw new AssertionError("Native error code changed");
        System.out.println("PASS: installed BillingMarshaller methods 1/2/3/6/7/8, native upstream decoding and disabled billing response layouts");
    }
    private static final class Capture extends com.threerings.projectx.billing.data.BillingMarshaller {
        int method;Object[] arguments;
        @Override protected void c(int id,Object[] args){method=id;arguments=args;}
    }
    private static void check(Capture m,int method,Class<?>...types)throws Exception {
        if(m.method!=method||m.arguments.length!=types.length)throw new AssertionError("Native method ID/count differs: "+method);
        for(int i=0;i<types.length;i++)if(!types[i].isInstance(m.arguments[i]))throw new AssertionError("Native argument differs: "+method+"/"+i);
        var bytes=new ByteArrayOutputStream();var out=new com.threerings.io.w(bytes);
        out.bf(new ForwardEventRequest(new InvocationRequestEvent(3,SessionCodec.BILLING_SERVICE,m.method,m.arguments)));out.flush();
        var decoded=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
        if(decoded.method()!=method||decoded.arguments().size()!=types.length||!(decoded.arguments().getLast() instanceof SessionDecoder.Listener))throw new AssertionError("Native request rejected");
    }
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
}
