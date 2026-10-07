package org.tortodrone.server.network;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.support.TestWire;

/** Untrusted post-login frames and object ownership, independent of game files. */
public final class SessionTest {
    public static void main(String[] args) throws Exception {
        TestWire.Writer writer = new TestWire.Writer();
        byte[] creation = writer.body(out -> {
            writer.message(out, "ForwardEventRequest", 7);
            writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3); out.writeInt(1); out.writeByte(5);
            out.writeBoolean(true); out.writeInt(5); out.writeShort(0);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$KnightHeight"); out.writeByte(2);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$EyeType"); out.writeByte(46);
            writer.type(out, "java.util.List"); out.writeInt(0);
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(0);
        });
        var parsed = new HandshakeCodec().sessionRequest(creation);
        var invocation = (SessionDecoder.Invocation) parsed.event();
        TestWire.check(invocation.method() == 5 && invocation.arguments().get(0) == null
            && invocation.arguments().get(1).equals(new SessionDecoder.EnumValue("com.threerings.projectx.data.ProjectXCodes$KnightHeight", 2))
            && invocation.arguments().get(3).equals(List.of())
            && ((SessionDecoder.Listener) invocation.arguments().get(4)).requestId() == 0, "character creation value subset");
        rejected(out -> { newMessage(out, "CompoundUpstreamMessage"); out.writeBoolean(true); out.writeInt(129); });
        rejected(out -> { newMessage(out, "CompoundUpstreamMessage"); out.writeBoolean(true); out.writeInt(-1); });
        rejected(out -> { newMessage(out, "CompoundUpstreamMessage"); out.writeByte(2); });
        rejected(out -> newMessage(out, "ForwardEventRequest"));
        rejected(out -> {
            TestWire.Writer nested = new TestWire.Writer();
            for (int i = 0; i < 10; i++) {
                nested.message(out, "CompoundUpstreamMessage", i); out.writeBoolean(true); out.writeInt(1);
            }
            nested.message(out, "PingRequest", 11);
        });
        rejected(out -> {
            newMessage(out, "ForwardEventRequest"); out.writeShort(-2); out.writeUTF(TestWire.DOB + "InvocationRequestEvent");
            out.writeInt(3); out.writeInt(1); out.writeByte(5); out.writeBoolean(true); out.writeInt(1);
            out.writeShort(-3); out.writeUTF("com.threerings.projectx.data.ProjectXCodes$EyeType"); out.writeByte(47);
        });
        rejected(out -> {
            newMessage(out, "ForwardEventRequest"); out.writeShort(-2); out.writeUTF(TestWire.DOB + "InvocationRequestEvent");
            out.writeInt(3); out.writeInt(1); out.writeByte(5); out.writeBoolean(true); out.writeInt(1);
            out.writeShort(-3); out.writeUTF("java.lang.Runtime"); // No reflective class loading.
        });
        ownership(1002, "receivers", "Unauthorized object mutation");
        ownership(1001, "crowns", "Unauthorized object mutation");
        duplicateReceivers();
        System.out.println("PASS: bounded events/batches/collections/enums, null arguments, foreign object/field mutations, duplicate receivers");
    }

    private static void ownership(int oid, String field, String reason) throws Exception {
        sessionRejected((writer, out) -> {
            writer.message(out, "ForwardEventRequest", 1);
            writer.type(out, TestWire.DOB + "AttributeChangedEvent"); out.writeInt(oid);
            out.writeBoolean(true); out.writeUTF(field); out.writeShort(0);
        }, reason);
    }

    private static void duplicateReceivers() throws Exception {
        sessionRejected((writer, out) -> {
            writer.message(out, "ForwardEventRequest", 1);
            writer.type(out, TestWire.DOB + "CompoundEvent"); out.writeInt(1001);
            writer.type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(2);
            for (String code : List.of("receiver-a", "receiver-b")) {
                writer.type(out, TestWire.DOB + "EntryAddedEvent"); out.writeInt(1001);
                out.writeBoolean(true); out.writeUTF("receivers");
                writer.type(out, "com.threerings.presents.client.InvocationReceiver$Registration");
                out.writeBoolean(true); out.writeUTF(code); out.writeShort(0);
            }
        }, "Duplicate receiver ID");
    }

    private static void sessionRejected(Request operation, String reason) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<String> result = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    ClientSession.run(socket, new HandshakeCodec(), 1, "127.0.0.1", listener.getLocalPort(), ignored -> {});
                    return "Unexpected accepted session";
                } catch (SessionDecoder.Rejected rejected) { return rejected.getMessage(); }
            });
            try (Socket socket = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                socket.setSoTimeout(3000);
                TestWire wire = new TestWire(); wire.bootstrap(wire.frame(socket.getInputStream()), socket.getPort());
                TestWire.Writer writer = new TestWire.Writer();
                writer.send(socket.getOutputStream(), out -> operation.write(writer, out));
                TestWire.check(socket.getInputStream().read() == -1, "invalid mutation disconnect");
            }
            TestWire.check(result.get(3, TimeUnit.SECONDS).equals(reason), "session ownership rejection reason");
        } finally { executor.shutdownNow(); }
    }

    private static void newMessage(DataOutputStream out, String type) throws IOException {
        out.writeShort(-1); out.writeUTF(TestWire.NET + type); out.writeShort(1);
    }
    private static void rejected(TestWire.Encoder encoder) throws Exception {
        byte[] body = new TestWire.Writer().body(encoder);
        try { new HandshakeCodec().sessionRequests(body); }
        catch (IOException expected) { return; }
        throw new AssertionError("Invalid session frame accepted");
    }
    @FunctionalInterface private interface Request { void write(TestWire.Writer writer, DataOutputStream out) throws IOException; }
}
