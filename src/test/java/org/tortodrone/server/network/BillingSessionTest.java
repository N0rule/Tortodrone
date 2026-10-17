package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.util.*;
import org.tortodrone.protocol.*;
import org.tortodrone.support.TestWire;
import org.tortodrone.world.SceneRegistry;
import static org.tortodrone.support.TestWire.check;

/** Inert billing must preserve callback shapes without requiring a selected knight. */
public final class BillingSessionTest {
    public static void main(String[] args)throws Exception {
        var bytes=new ByteArrayOutputStream();
        var socket=new Socket(){@Override public OutputStream getOutputStream(){return bytes;}};
        var codec=new SessionCodec();
        var replies=new ArrayList<Integer>();
        try(var output=new SessionOutput(socket);var world=new WorldSession(socket,null,output,codec,1001,1,m->{},SceneRegistry.system())){
            // Decoder explicitly permits a zero-element invocation array.
            var writer=new TestWire.Writer();
            var decoded=new HandshakeCodec().sessionRequest(writer.body(out->{
                writer.message(out,"ForwardEventRequest",1);writer.type(out,TestWire.DOB+"InvocationRequestEvent");
                out.writeInt(3);out.writeInt(SessionCodec.BILLING_SERVICE);out.writeByte(99);out.writeBoolean(true);out.writeInt(0);
            }));
            check(world.billingInvocation((SessionDecoder.Invocation)decoded.event()),"empty decoded call handled");
            for(int method:new int[]{1,2,3,6,7,8,99}){
                check(world.billingInvocation(call(method,List.of())),"empty calls handled");
                check(world.billingInvocation(call(method,List.of("missing listener"))),"missing listener ignored");
                check(world.billingInvocation(call(method,List.of(listener("Listener",(short)90)))),"unsupported listener ignored");
                var result=listener("ResultMarshaller",(short)(10+replies.size()));
                List<Object> nativeArgs=switch(method){
                    case 1->List.of("",0,false,result);
                    case 2->List.of("","",0,0,"","",0,false,result);
                    case 3->List.of("en",result);
                    case 6->List.of(0,"",listener("ConfirmMarshaller",result.requestId()));
                    case 7->List.of(0,"",result);
                    case 8->List.of(0L,false,listener("ConfirmMarshaller",result.requestId()));
                    default->List.of(result);
                };
                world.billingInvocation(call(method,nativeArgs));replies.add(method==1||method==2?1:0);
                // Wrong count and wrong listener shape still receive a native failure.
                world.billingInvocation(call(method,List.of(listener("ConfirmMarshaller",(short)(10+replies.size())))));replies.add(0);
                world.billingInvocation(call(method,List.of("wrong",false,listener("ResultMarshaller",(short)(10+replies.size())))));replies.add(0);
            }
            check(!world.billingInvocation(new SessionDecoder.Invocation(999,SessionCodec.BILLING_SERVICE,3,List.of())),"foreign target not consumed");
            output.send(()->codec.invocationConfirmed(1001,(short)100));
        }
        var wire=new TestWire();var input=new ByteArrayInputStream(bytes.toByteArray());
        for(int i=0;i<replies.size();i++){
            var in=wire.frame(input);header(wire,in,(short)(10+i),replies.get(i));
            check(in.readBoolean()&&in.readInt()==1,"one callback argument");
            Object value=wire.object(in);
            check(replies.get(i)==1?value.equals(-1):value.equals("~Sorry, but P2W features are disabled on this server."),"billing refused without success");
            TestWire.end(in);
        }
        var tail=wire.frame(input);header(wire,tail,(short)100,1);check(!tail.readBoolean(),"subsequent output survives");TestWire.end(tail);
        check(input.available()==0,"no replies to unaddressable calls");
        System.out.println("PASS: empty/unknown/malformed billing calls, native methods 1/2/3/6/7/8, listener correlation and session output survival");
    }
    private static SessionDecoder.Listener listener(String type,short id){return new SessionDecoder.Listener(TestWire.INV+type,id);}
    private static SessionDecoder.Invocation call(int method,List<Object> args){return new SessionDecoder.Invocation(3,SessionCodec.BILLING_SERVICE,method,args);}
    private static void header(TestWire wire,DataInputStream in,short id,int code)throws IOException{
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"notification");
        check(wire.type(in).equals(TestWire.DOB+"InvocationResponseEvent")&&in.readInt()==1001&&in.readShort()==id&&in.readUnsignedByte()==code,"addressed native callback");
    }
}
