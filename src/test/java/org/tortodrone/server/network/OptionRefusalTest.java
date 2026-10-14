package org.tortodrone.server.network;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Unsupported stock options must answer their listener without losing the session. */
public final class OptionRefusalTest {
    private static final String LOOT = "com.threerings.projectx.data.LootReportLevel";
    private static final String DIFFICULTY = "com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty";

    public static void main(String[] args) throws Exception {
        // Production startup loads content before accepting clients. Keep this
        // fixture's socket deadline scoped to RPC delivery, not first-use map loading.
        org.tortodrone.world.SceneRegistry.system();
        for (String type : new String[]{LOOT, DIFFICULTY}) {
            for (int ordinal : new int[]{3, 255}) {
                var writer = new TestWire.Writer();
                byte[] body = writer.body(out -> option(writer, out, type, ordinal, (short) 7));
                try {
                    new HandshakeCodec().sessionRequest(body);
                    throw new AssertionError("Out-of-range option enum accepted");
                } catch (org.tortodrone.protocol.SessionDecoder.Rejected expected) {
                    check(expected.getMessage().equals("Invalid enum ordinal"), "bounded option enum");
                }
            }
        }
        try (var workers = Executors.newSingleThreadExecutor();
             var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var completed = workers.submit(() -> {
                try (var socket = listener.accept()) {
                    ClientSession.run(socket, new HandshakeCodec(), 1, "127.0.0.1",
                        listener.getLocalPort(), ignored -> {});
                }
                return null;
            });
            try (var socket = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                socket.setSoTimeout(3000);
                var wire = new TestWire();
                int playerOid = wire.bootstrap(wire.frame(socket.getInputStream()), listener.getLocalPort());
                var writer = new TestWire.Writer();
                short request = 10;
                for (String type : new String[]{LOOT, DIFFICULTY}) {
                    for (int ordinal = 0; ordinal < 3; ordinal++) {
                        short id = request++; int value = ordinal;
                        writer.send(socket.getOutputStream(), out -> option(writer, out, type, value, id));
                        var response = wire.frame(socket.getInputStream());
                        check(wire.type(response).equals(TestWire.NET + "EventNotification")
                            && response.readShort() == -1, "refusal notification");
                        check(wire.type(response).equals(TestWire.DOB + "InvocationResponseEvent")
                            && response.readInt() == playerOid && response.readShort() == id
                            && response.readUnsignedByte() == 0, "correlated failure callback");
                        check(response.readBoolean() && response.readInt() == 1
                            && wire.object(response).equals("~Server operation not implemented yet")
                            && response.available() == 0, "unsupported option refused");
                        writer.send(socket.getOutputStream(), out -> writer.message(out, "PingRequest", id));
                        var pong = wire.frame(socket.getInputStream());
                        check(wire.type(pong).equals(TestWire.NET + "PongResponse")
                            && pong.readShort() == id, "session and class mappings survive refusal");
                        pong.readLong(); pong.readInt(); check(pong.available() == 0, "complete pong");
                    }
                }
                writer.send(socket.getOutputStream(), out -> writer.message(out, "LogoffRequest", 30));
                check(socket.getInputStream().read() == -1, "clean logoff after option refusals");
            }
            completed.get(5, TimeUnit.SECONDS);
        }
        System.out.println("PASS: loot/party option enum bounds, correlated refusals, persistent mappings, heartbeat and logoff");
    }

    private static void option(TestWire.Writer writer, DataOutputStream out, String type,
            int ordinal, short request) throws IOException {
        writer.message(out, "ForwardEventRequest", request);
        writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3);
        boolean loot = type.equals(LOOT);
        out.writeInt(loot ? 1 : 12); out.writeByte(loot ? 17 : 4);
        out.writeBoolean(true); out.writeInt(loot ? 3 : 4);
        if (loot) {
            writer.type(out, "com.threerings.projectx.data.Pref"); out.writeByte(1);
        } else {
            writer.type(out, "java.lang.Boolean"); out.writeBoolean(false);
        }
        writer.type(out, type); out.writeByte(ordinal);
        if (!loot) { writer.type(out, "java.lang.Boolean"); out.writeBoolean(true); }
        writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(request);
    }
}
