package org.tortodrone.support;

import java.io.*;
import java.net.Socket;

/** The stock billing service must answer purchase-tab calls politely instead of closing the
 *  session: a shape the server does not model gets an addressed failure, and a valid native
 *  shape reaches the handler. The session stays alive throughout. */
public final class BillingScenario {
    public static void run(Socket peer, TestWire wire, int oid) throws Exception {
        TestWire.Writer writer = new TestWire.Writer(TestWire.NET + "SecureRequest", TestWire.NET + "PublicKeyCredentials",
            "[Ljava.lang.String;", TestWire.NET + "AESAuthRequest");
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 42); out.writeInt(oid); });
        wire.snapshot(wire.frame(peer.getInputStream()), (short) 42, oid);
        // The decoder permits empty arrays; unsupported calls must not close the session.
        billing(writer,peer,99,0,(short)50,ignored->{});
        billing(writer,peer,3,0,(short)51,ignored->{});
        // Native getBillingInfo is refused through the client's failed-load path.
        billing(writer, peer, 3, 2, (short) 52, listeners -> {
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("en");
            writer.type(listeners, TestWire.INV + "ResultMarshaller"); listeners.writeShort(53);
        });
        confirmation(peer, wire, oid, (short) 53, DISABLED);
        // An unmodeled shape (three arguments with a Confirm listener) must fail politely, not
        // reject the protocol, and the session survives everything.
        billing(writer, peer, 3, 3, (short) 60, listeners -> {
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("en");
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("USD");
            writer.type(listeners, TestWire.INV + "ConfirmMarshaller"); listeners.writeShort(61);
        });
        confirmation(peer, wire, oid, (short) 61, DISABLED);
        // Current stock micro-transaction response is method 8, not the archive's method 7.
        billing(writer,peer,8,3,(short)64,out->{
            writer.type(out,"java.lang.Long");out.writeLong(0);
            writer.type(out,"java.lang.Boolean");out.writeBoolean(false);
            writer.type(out,TestWire.INV+"ConfirmMarshaller");out.writeShort(65);
        });
        confirmation(peer,wire,oid,(short)65,DISABLED);
        billing(writer,peer,1,4,(short)66,out->{
            writer.type(out,"java.lang.String");out.writeUTF("");
            writer.type(out,"java.lang.Integer");out.writeInt(0);
            writer.type(out,"java.lang.Boolean");out.writeBoolean(false);
            writer.type(out,TestWire.INV+"ResultMarshaller");out.writeShort(67);
        });
        billingResult(peer,wire,oid,(short)67,-1);
        billing(writer,peer,7,3,(short)68,out->{
            writer.type(out,"java.lang.Integer");out.writeInt(0);
            writer.type(out,"java.lang.String");out.writeUTF("");
            writer.type(out,TestWire.INV+"ResultMarshaller");out.writeShort(69);
        });
        confirmation(peer,wire,oid,(short)69,DISABLED);
        writer.send(peer.getOutputStream(), out -> writer.message(out, "PingRequest", 62));
        var pong = wire.frame(peer.getInputStream());
        TestWire.check(wire.type(pong).equals(TestWire.NET + "PongResponse") && pong.readShort() == 62,
            "session survives billing shape mismatches");
        pong.readLong(); pong.readInt();
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 63));
    }

    private static final String DISABLED = "Sorry, but P2W features are disabled on this server.";

    @FunctionalInterface private interface Charges { void apply(DataOutputStream listeners) throws IOException; }

    private static void billing(TestWire.Writer writer, Socket peer, int method, int count, short callback,
            Charges charges) throws IOException {
        writer.send(peer.getOutputStream(), out -> {
            writer.message(out, "ForwardEventRequest", callback);
            writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3);
            out.writeInt(org.tortodrone.protocol.SessionCodec.BILLING_SERVICE);
            out.writeByte(method); out.writeBoolean(true); out.writeInt(count);
            charges.apply(out);
        });
    }

    private static void confirmation(Socket peer, TestWire wire, int oid, short callback, String reason) throws IOException {
        var in = wire.frame(peer.getInputStream());
        TestWire.check(wire.type(in).equals(TestWire.NET + "EventNotification") && in.readShort() == -1, "unsolicited event");
        TestWire.check(wire.type(in).equals(TestWire.DOB + "InvocationResponseEvent") && in.readInt() == oid, "response target");
        TestWire.check(in.readShort() == callback && in.readByte() == 0, "polite billing failure");
        TestWire.check(in.readBoolean() && in.readInt() == 1 && wire.object(in).equals("~" + reason), "billing refusal reason");
        TestWire.end(in);
    }

    private static void billingResult(Socket peer, TestWire wire, int oid, short callback, int value) throws IOException {
        var in = wire.frame(peer.getInputStream());
        TestWire.check(wire.type(in).equals(TestWire.NET + "EventNotification") && in.readShort() == -1, "unsolicited event");
        TestWire.check(wire.type(in).equals(TestWire.DOB + "InvocationResponseEvent") && in.readInt() == oid, "response target");
        TestWire.check(in.readShort() == callback && in.readByte() == 1, "polite billing result");
        TestWire.check(in.readBoolean() && in.readInt() == 1, "result argument count");
        TestWire.check(wire.object(in).equals(value), "billing error code");
        TestWire.end(in);
    }
}
