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
        // Native BillingMarshaller method 3 is exactly [String, Result]: reaching the handler
        // (instead of a protocol rejection) proves the argument-shape gate parses the native call.
        billing(writer, peer, 3, 2, (short) 52, listeners -> {
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("credits");
            writer.type(listeners, TestWire.INV + "ResultMarshaller"); listeners.writeShort(53);
        });
        confirmation(peer, wire, oid, (short) 53, "Select a knight first");
        // An unmodeled shape (three arguments with a Confirm listener) must fail politely.
        billing(writer, peer, 3, 3, (short) 54, listeners -> {
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("credits");
            writer.type(listeners, "java.lang.String"); listeners.writeUTF("USD");
            writer.type(listeners, TestWire.INV + "ConfirmMarshaller"); listeners.writeShort(55);
        });
        confirmation(peer, wire, oid, (short) 55, "Billing is not available");
        // The session survives both calls: the server still answers pings.
        writer.send(peer.getOutputStream(), out -> writer.message(out, "PingRequest", 56));
        var pong = wire.frame(peer.getInputStream());
        TestWire.check(wire.type(pong).equals(TestWire.NET + "PongResponse") && pong.readShort() == 56,
            "session survives billing shape mismatches");
        pong.readLong(); pong.readInt();
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 57));
    }

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
}
