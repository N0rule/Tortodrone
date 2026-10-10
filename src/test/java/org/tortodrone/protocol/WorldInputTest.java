package org.tortodrone.protocol;

import java.io.*;
import static org.tortodrone.support.TestWire.check;

/** Independent field writer: no client classes or server encoder needed. */
public final class WorldInputTest {
    private static final String NET="com.threerings.presents.net.";
    public static void main(String[] args) throws Exception {
        var input=(SessionDecoder.Input)decode(1,1.5f,0,2).event();
        check(input.oid()==10 && input.acknowledge()==50 && input.frames().size()==1
            && input.frames().get(0).rotation()==1.5f, "real input frame schema");
        check(((SessionDecoder.Input)decode(0,0,0,0).event()).frames().isEmpty(), "empty ACK batches accepted");
        check(((SessionDecoder.Input)decodeExtended("com.threerings.projectx.data.ProjectXInputFrame").event()).frames().size()==1, "stock gameplay input extension");
        var dungeon=(SessionDecoder.Input)decodeExtended("com.threerings.projectx.dungeon.data.DungeonInputFrame").event();
        check(dungeon.frames().size()==1&&dungeon.frames().getFirst().rotation()==1.5f,
            "stock dungeon input has the inherited five-field schema");
        refuse(()->decode(33,0,0,2));
        refuse(()->decode(1,Float.NaN,0,2));
        refuse(()->decode(1,0,Float.POSITIVE_INFINITY,2));
        refuse(()->decode(-1,0,0,0));
        check(throttleAck(0).type().equals("ThrottleUpdatedMessage"),"native no-argument throttle ACK");
        refuse(()->throttleAck(100));
        System.out.println("PASS: bounded town input decoder, empty acknowledgements and finite angles");
    }
    private static HandshakeCodec.SessionRequest throttleAck(int value)throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeUTF(NET+"ThrottleUpdatedMessage");out.writeShort(1);out.writeInt(value);
        return new SessionDecoder(in->in.readUTF()).decode(bytes.toByteArray()).getFirst();
    }
    private static HandshakeCodec.SessionRequest decode(int count,float rotation,float direction,int flags) throws Exception {
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeUTF(NET+"ForwardEventRequest"); out.writeShort(1);
        out.writeUTF("com.threerings.tudey.data.InputEnqueueEvent");
        out.writeInt(10); out.writeInt(50); out.writeInt(100);
        out.writeUTF("[Lcom.threerings.tudey.data.InputFrame;"); out.writeInt(count);
        for(int i=0;i<count;i++) {
            out.writeUTF("com.threerings.tudey.data.InputFrame");
            out.writeInt(100+i); out.writeFloat(rotation); out.writeFloat(direction); out.writeInt(flags);
        }
        return new SessionDecoder(in -> in.readUTF()).decode(bytes.toByteArray()).get(0);
    }
    private static HandshakeCodec.SessionRequest decodeExtended(String frameType) throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeUTF(NET+"ForwardEventRequest");out.writeShort(1);
        out.writeUTF("com.threerings.tudey.data.InputEnqueueEvent");
        out.writeInt(10);out.writeInt(0);out.writeInt(100);
        out.writeUTF("[Lcom.threerings.tudey.data.InputFrame;");out.writeInt(1);
        out.writeUTF(frameType);
        out.writeInt(100);out.writeFloat(1.5f);out.writeFloat(0);out.writeInt(2);out.writeInt(0);
        return new SessionDecoder(in -> in.readUTF()).decode(bytes.toByteArray()).get(0);
    }
    private static void refuse(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch(SessionDecoder.Rejected expected) { }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
