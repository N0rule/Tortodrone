package org.tortodrone.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.support.TestWire;

/** Independent structural checks for the verified session wire subset. */
public final class BootstrapTest {
    private static final String PREFIX = "com.threerings.presents.net.";

    public static void main(String[] args) throws Exception {
        HandshakeCodec incoming = new HandshakeCodec();
        byte[] ping = request(-1, "PingRequest", (short) 3, null);
        var parsed = incoming.sessionRequest(ping);
        check(parsed.type().equals("PingRequest") && parsed.messageId() == 3, "ping request");
        var reused = incoming.sessionRequest(request(1, null, (short) 4, null));
        check(reused.type().equals("PingRequest") && reused.messageId() == 4, "reused incoming mapping");
        var subscription = incoming.sessionRequest(request(-2, "SubscribeRequest", (short) 5, 123));
        check(subscription.objectId() == 123 && subscription.type().equals("SubscribeRequest"), "object subscription");
        expectFailure(() -> incoming.sessionRequest(request(2, null, (short) 6, 0)));
        expectFailure(() -> new HandshakeCodec().sessionRequest(Arrays.copyOf(ping, ping.length + 8)));
        byte[] truncated = request(-1, "SubscribeRequest", (short) 1, 123);
        expectFailure(() -> new HandshakeCodec().sessionRequest(Arrays.copyOf(truncated, truncated.length - 1)));
        expectFailure(() -> new HandshakeCodec().sessionRequest(request(1, null, (short) 1, null)));
        expectFailure(() -> new HandshakeCodec().sessionRequest(request(-2, "PingRequest", (short) 1, null)));
        expectFailure(() -> new HandshakeCodec().sessionRequest(request(-1, "UnimplementedRequest", (short) 1, null)));
        var unsubscribe = incoming.sessionRequest(request(-3, "UnsubscribeRequest", (short) 7, 123));
        check(unsubscribe.objectId() == 123 && unsubscribe.type().equals("UnsubscribeRequest"), "unsubscribe request");
        var logoff = incoming.sessionRequest(request(-4, "LogoffRequest", (short) 8, null));
        check(logoff.type().equals("LogoffRequest"), "logoff request");

        SessionCodec outgoing = new SessionCodec();
        TestWire wire = new TestWire();
        check(wire.bootstrap(input(outgoing.bootstrap(101, 201, "192.0.2.10", 47624)), 47624) == 201, "bootstrap identity");
        long received = System.currentTimeMillis() - 10;
        DataInputStream pong = input(outgoing.pong((short) 9, received));
        check(wire.type(pong).equals(PREFIX + "PongResponse"), "first pong mapping");
        check(pong.readShort() == 9, "pong correlation");
        long packed = pong.readLong();
        check(packed >= received && pong.readInt() == packed - received && pong.available() == 0, "pong time and processing delay");
        pong = input(outgoing.pong((short) 10, received));
        check(wire.type(pong).equals(PREFIX + "PongResponse") && pong.readShort() == 10, "repeated pong mapping");
        DataInputStream failure = input(outgoing.subscriptionFailure((short) 11, 201, "unavailable"));
        check(wire.type(failure).equals(PREFIX + "FailureResponse"), "failure mapping");
        check(failure.readShort() == 11 && failure.readInt() == 201 && failure.readBoolean()
            && failure.readUTF().equals("unavailable") && failure.available() == 0, "subscription failure fields");
        DataInputStream unsub = input(outgoing.unsubscribe((short) 12, 201));
        check(wire.type(unsub).equals(PREFIX + "UnsubscribeResponse"), "unsubscribe response mapping");
        check(unsub.readShort() == 12 && unsub.readInt() == 201 && unsub.available() == 0, "unsubscribe response fields");
        var player = wire.snapshot(input(outgoing.player((short) 13, 201, 1, "test-player")), (short) 13, 201);
        check(player.get("username").equals("test-player") && player.get("playerId").equals(1)
            && player.get("availableKnights").equals(java.util.List.of()) && player.get("queuePosition").equals(0), "empty player roster");
        var server = wire.snapshot(input(outgoing.server((short) 14)), (short) 14, 1);
        check(server.get("towns").equals(java.util.List.of()) && server.get("spamwords").equals(java.util.List.of()), "server schema");
        var interiorServer=wire.snapshot(input(outgoing.server((short) 17,true)),(short) 17,1);
        var gates=(java.util.List<?>)interiorServer.get("gates");
        var tutorial=org.tortodrone.world.SceneRegistry.system().tutorial();
        check(gates.size()==(tutorial==null?1:2),"solo interior render transport");
        var gate=(java.util.Map<?,?>)gates.getFirst();
        check(gate.get("_gateId").equals(0)&&gate.get("_wheels").equals(java.util.List.of())
            &&gate.get("_destId").equals(0),"interior transport exposes no dungeon floors or destination");
        if(tutorial!=null) {
            var tutorialGate=(java.util.Map<?,?>)gates.get(1);
            check(tutorialGate.get("_gateId").equals(org.tortodrone.world.TutorialRoute.GATE)
                &&((java.util.List<?>)tutorialGate.get("_wheels")).size()==1,"tutorial transport gate");
        }
        var config = wire.snapshot(input(outgoing.configuration((short) 15)), (short) 15, 2);
        check(config.get("dconfigService") == null && config.get("removed").equals(java.util.List.of()), "configuration schema");
        player = wire.snapshot(input(outgoing.player((short) 16, 201, 1, "test-player", java.util.Map.of("test-receiver", (short) 0))), (short) 16, 201);
        check(player.get("receivers").equals(java.util.List.of(java.util.Map.of("code", "test-receiver", "id", (short) 0))), "zero-based receiver snapshot");
        // Role and inspection changes must keep the native notification HUD usable.
        for (boolean gm : new boolean[]{true, false}) for (boolean hidden : new boolean[]{true, false}) {
            DataInputStream flags = input(outgoing.playerFlags(201, gm, hidden));
            check(wire.type(flags).equals(PREFIX + "EventNotification") && flags.readShort() == -1,
                "player flags event envelope");
            check(wire.type(flags).equals(TestWire.DOB + "CompoundEvent") && flags.readInt() == 201
                && wire.type(flags).equals("com.threerings.util.StreamableArrayList") && flags.readInt() == 2,
                "atomic flags and token update");
            check(wire.type(flags).equals(TestWire.DOB + "AttributeChangedEvent") && flags.readInt() == 201
                && wire.string(flags).equals("flags"), "player flags attribute");
            check(wire.object(flags).equals(32 | (gm ? 256 : 0) | (hidden ? 1024 : 0)),
                "notification HUD flag survives role/privacy changes without granting another role");
            check(wire.type(flags).equals(TestWire.DOB + "AttributeChangedEvent") && flags.readInt() == 201
                && wire.string(flags).equals("tokens") && wire.object(flags).equals(gm ? 8 : 0)
                && flags.available() == 0, "support token stays independent of notification HUD");
        }
        expectFailure(() -> new SessionCodec().bootstrap(0, 1, "127.0.0.1", 47624));
        System.out.println("PASS: bootstrap services, complete player/server/configuration schemas, persistent mappings, ping/pong, malformed messages");
    }

    private static DataInputStream input(byte[] frame) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(HandshakeCodec.readFrame(new ByteArrayInputStream(frame))));
    }

    private static byte[] request(int type, String name, short id, Integer objectId) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeShort(type);
        if (type < 0) out.writeUTF(PREFIX + name);
        out.writeShort(id);
        if (objectId != null) out.writeInt(objectId);
        return body.toByteArray();
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected invalid message rejection");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
