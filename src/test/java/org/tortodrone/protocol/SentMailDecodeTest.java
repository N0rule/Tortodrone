package org.tortodrone.protocol;

import java.io.*;
import static org.tortodrone.support.TestWire.check;

/** The stock Uplink.deliver request is a streamable with field-specific encodings. */
public final class SentMailDecodeTest {
    private static final String NET="com.threerings.presents.net.",DOB="com.threerings.presents.dobj.";
    private static final String INV="com.threerings.presents.data.InvocationMarshaller$";

    public static void main(String[] args)throws Exception {
        var sent=(SessionDecoder.SentMail)invocation("Bob-Knight",Long.valueOf(42),2,25,10).arguments().getFirst();
        check(sent.recipient().equals("Bob-Knight")&&sent.subject().equals("parcel")&&sent.body().equals("for you"),
            "Name, subject and body stream as a field, not a dynamic value");
        check(sent.items().equals(java.util.Map.of(42L,2))&&sent.crowns()==25&&sent.energy()==10,
            "a Multiset attachment keeps item ids, units, crowns and energy");
        var empty=(SessionDecoder.SentMail)invocation("Bob-Knight",null,0,0,0).arguments().getFirst();
        check(empty.items().isEmpty()&&empty.crowns()==0&&empty.energy()==0,"an absent multiset is an empty attachment");
        refuse(()->invocation("Bob-Knight",Integer.valueOf(42),1,0,0));
        refuse(()->invocation("Bob-Knight",Long.valueOf(42),0,0,0));
        System.out.println("PASS: native SentMail recipient, multiset attachments and counts decode without desync");
    }

    private static SessionDecoder.Invocation invocation(String recipient,Object item,int units,int crowns,int energy)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeUTF(NET+"ForwardEventRequest");out.writeShort(1);
        out.writeUTF(DOB+"InvocationRequestEvent");out.writeInt(10);out.writeInt(10);out.writeByte(5);
        out.writeBoolean(true);out.writeInt(2);
        out.writeUTF("com.threerings.projectx.uplink.data.SentMail");
        out.writeUTF("com.threerings.util.Name");out.writeBoolean(true);out.writeUTF(recipient);
        out.writeBoolean(true);out.writeUTF("parcel");
        out.writeBoolean(true);out.writeUTF("for you");
        out.writeBoolean(item!=null);
        if(item instanceof Long id){out.writeInt(1);out.writeUTF("java.lang.Long");out.writeLong(id);out.writeInt(units);}
        else if(item instanceof Integer id){out.writeInt(1);out.writeUTF("java.lang.Integer");out.writeInt(id);out.writeInt(units);}
        out.writeInt(crowns);out.writeInt(energy);
        out.writeUTF(INV+"ConfirmMarshaller");out.writeShort(1);
        return (SessionDecoder.Invocation)new SessionDecoder(in->in.readUTF()).decode(bytes.toByteArray()).getFirst().event();
    }
    private static void refuse(Action action)throws Exception {
        try{action.run();throw new AssertionError("Invalid SentMail accepted");}
        catch(SessionDecoder.Rejected expected){}
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
