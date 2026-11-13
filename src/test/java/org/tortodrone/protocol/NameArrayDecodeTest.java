package org.tortodrone.protocol;

import java.io.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Party suggestions moved from a streamed ArrayList to a Name[] on client build 20261110044614. */
public final class NameArrayDecodeTest {
    private static final String NET="com.threerings.presents.net.",DOB="com.threerings.presents.dobj.";
    private static final String INV="com.threerings.presents.data.InvocationMarshaller$";
    private static final String ARRAY="[Lcom.threerings.util.Name;";

    public static void main(String[] args)throws Exception {
        var invocation=invocation(2,"Alpha","Beta");
        var arguments=invocation.arguments();
        check(arguments.size()==3&&arguments.get(0).equals(Integer.valueOf(2)),
            "the suggestion level decodes before the name array");
        check(arguments.get(1) instanceof List<?> names
                &&names.size()==2&&names.get(0).equals("Alpha")&&names.get(1).equals("Beta"),
            "a Name[] streams its length and per-element class records like a list");
        check(arguments.get(2) instanceof SessionDecoder.Listener listener
                &&listener.type().equals(INV+"ResultMarshaller")&&listener.requestId()==1,
            "the result listener follows the array without desync");
        check(invocation(2).arguments().get(1) instanceof List<?> empty&&empty.isEmpty(),
            "an empty Name[] decodes to an empty name list");
        refuse(()->oversized(200)); // array length above the shared collection cap
        refuse(()->foreignElement("com.threerings.util.Message")); // element type outside the value switch
        System.out.println("PASS: stock party-suggestion Name[] arrays decode as name lists");
    }

    private static SessionDecoder.Invocation invocation(int level,String... names)throws Exception {
        return wire(level,0,null,names);
    }
    private static SessionDecoder.Invocation oversized(int length)throws Exception {
        return wire(2,length,null);
    }
    private static SessionDecoder.Invocation foreignElement(String badElementType)throws Exception {
        return wire(2,0,badElementType,"Alpha");
    }
    private static SessionDecoder.Invocation wire(int level,int oversize,String badElementType,String... names)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeUTF(NET+"ForwardEventRequest");out.writeShort(1);
        out.writeUTF(DOB+"InvocationRequestEvent");out.writeInt(10);out.writeInt(10);out.writeByte(2);
        out.writeBoolean(true);out.writeInt(3);
        out.writeUTF("java.lang.Integer");out.writeInt(level);
        out.writeUTF(ARRAY);out.writeInt(oversize>0?oversize:names.length);
        for(String name:names) {
            out.writeUTF(badElementType!=null?badElementType:"com.threerings.util.Name");
            out.writeBoolean(true);out.writeUTF(name);
        }
        out.writeUTF(INV+"ResultMarshaller");out.writeShort(1);
        return (SessionDecoder.Invocation)new SessionDecoder(in->in.readUTF()).decode(bytes.toByteArray()).getFirst().event();
    }
    private static void refuse(Action action)throws Exception {
        try{action.run();throw new AssertionError("Invalid name array accepted");}
        catch(SessionDecoder.Rejected expected){}
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
