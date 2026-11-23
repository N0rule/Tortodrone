package org.tortodrone.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/**
 * Pins each per-feature downstream codec directly, without the {@link SessionCodec} facade.
 *
 * <p>Every codec is constructed with a fresh {@link WireContext} so its class and string-intern
 * tables start from the five handshake mappings alone. The SHA-256 digests therefore pin each
 * feature's own frame bytes, including its symbol registration order; the structural reads check
 * the same frame against the client's declared field order. All fixtures are synthetic.
 */
public final class SessionCodecFeaturesTest {
    private static final ItemCatalog CATALOG = ItemCatalog.starter();

    public static void main(String[] args) throws Exception {
        bootstrap();
        social();
        mission();
        knight();
        trade();
        town();
        party();
        scene();
        guild();
        handshake();
        System.out.println("PASS: every per-feature codec pins its own frame bytes and field order");
    }

    private static void bootstrap() throws Exception {
        byte[] frame = new BootstrapCodec(new WireContext(), CATALOG)
            .bootstrap(11, 7, "127.0.0.1", 47624);
        pin("BootstrapCodec.bootstrap", frame, 1497,
            "ec82ba4f9939ac09971c5a3960d35d1b65f64be371833b5fe78797bbe29a8d91");
        TestWire wire = new TestWire();
        check(wire.bootstrap(wire.frame(frame), 47624, "127.0.0.1") == 7,
            "bootstrap identity, services and endpoint");
    }

    private static void social() throws Exception {
        byte[] frame = new SocialCodec(new WireContext(), CATALOG)
            .listResult(7, (short) 3, List.of("first", "second"));
        pin("SocialCodec.listResult", frame, 207,
            "eb21d49ff994343b20e2fd26aca18bb41a050fdba48a783837d050aaa6b990e5");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "InvocationResponseEvent", 7);
        check(in.readShort() == 3 && in.readByte() == 1 && in.readBoolean() && in.readInt() == 1,
            "list result callback and single result");
        check(wire.type(in).equals(WireTypes.ARRAY_LIST) && in.readInt() == 2, "list result size");
        check(wire.type(in).equals("java.lang.String") && in.readUTF().equals("first"), "first list value");
        check(wire.type(in).equals("java.lang.String") && in.readUTF().equals("second"), "second list value");
        TestWire.end(in);
    }

    private static void mission() throws Exception {
        byte[] frame = new MissionCodec(new WireContext(), CATALOG).completedMissions(7,
            List.of(new OpeningCompletion("m.synthetic_mission", 1_700_000_000_000L, "HARD")));
        pin("MissionCodec.completedMissions", frame, 327,
            "3c927d617e6b06d5e7edcad0fc1499ef69611f6109162e90bf4073ba1fb3025c");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "AttributeChangedEvent", 7);
        check(wire.string(in).equals("completedMissions"), "completion attribute");
        check(wire.type(in).equals(WireTypes.DSET) && in.readInt() == 1, "completion set");
        check(wire.type(in).equals("com.threerings.projectx.mission.data.MissionCompletion")
            && wire.string(in).equals("m.synthetic_mission"), "completion identity");
        check(in.readLong() == 1_700_000_000_000L, "completion stamp");
        check(wire.type(in).equals(WireTypes.DIFFICULTY) && in.readByte() == 2, "hard difficulty ordinal");
        TestWire.end(in);
    }

    private static void knight() throws Exception {
        byte[] frame = new KnightCodec(new WireContext(), CATALOG).knightName(7, "Ann");
        pin("KnightCodec.knightName", frame, 130,
            "c725444b49d4c7dd6645deef53f699f6c3fb9078c8249ee9c232c763fc910069");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "AttributeChangedEvent", 7);
        check(wire.string(in).equals("knight"), "knight name attribute");
        check(wire.type(in).equals(WireTypes.NAME) && wire.string(in).equals("Ann"), "knight name value");
        TestWire.end(in);
    }

    private static void trade() throws Exception {
        byte[] frame = new TradeCodec(new WireContext(), CATALOG)
            .tradeNotification(7, "Bob", true);
        pin("TradeCodec.tradeNotification", frame, 196,
            "c6f76c040318d135f66f7d62ca25fb04f52c19b502e85c45e555a70ab3e52e89");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "EntryAddedEvent", 7);
        check(wire.string(in).equals("notifications"), "notification field");
        check(wire.type(in).equals("com.threerings.projectx.social.data.Notification$TradeRequest"),
            "trade request notification");
        check(wire.type(in).equals(WireTypes.NAME) && wire.string(in).equals("Bob"), "trade sender");
        TestWire.end(in);
    }

    private static void town() throws Exception {
        byte[] frame = new TownCodec(new WireContext(), CATALOG).itemAcquireDelay(7, 250);
        pin("TownCodec.itemAcquireDelay", frame, 157,
            "a10553efd3b6fcf991f87eb7eb660ab0b2113d851085f44fa989ca6e068d6917");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "AttributeChangedEvent", 7);
        check(wire.string(in).equals("itemAcquireDelay"), "acquisition delay attribute");
        check(wire.type(in).equals("java.lang.Integer") && in.readInt() == 250, "delay value");
        TestWire.end(in);
    }

    private static void party() throws Exception {
        byte[] frame = new PartyCodec(new WireContext(), CATALOG).partyOid(7, 3);
        pin("PartyCodec.partyOid", frame, 149,
            "5968973e5ce507392f8b483f0bf4d2814436bfaef93ceb57e1242e285961857d");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "AttributeChangedEvent", 7);
        check(wire.string(in).equals("partyOid"), "party identity attribute");
        check(wire.type(in).equals("java.lang.Integer") && in.readInt() == 3, "party identity value");
        TestWire.end(in);
    }

    private static void scene() throws Exception {
        byte[] frame = new SceneCodec(new WireContext(), CATALOG)
            .forceDestination(7, (short) 5, 10, 20);
        pin("SceneCodec.forceDestination", frame, 158,
            "e7a5b9341a95fc3c9ab6726d5d4c48e4cb7b9664238232ac4ca824d0e69b4547");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "InvocationNotificationEvent", 7);
        check(in.readShort() == 5 && in.readByte() == 1 && in.readBoolean() && in.readInt() == 2,
            "forced destination callback");
        check(wire.type(in).equals("java.lang.Integer") && in.readInt() == 10, "forced zone");
        check(wire.type(in).equals("java.lang.Integer") && in.readInt() == 20, "forced scene");
        TestWire.end(in);
    }

    private static void guild() throws Exception {
        byte[] frame = new GuildCodec(new WireContext(), CATALOG).guildCleared(7);
        pin("GuildCodec.guildCleared", frame, 284,
            "2a268edd48581936938ed471dcdad6094f8d20ba83f441b28abcd3a5bbd62065");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(frame);
        eventEnvelope(wire, in, "CompoundEvent", 7);
        check(wire.type(in).equals(WireTypes.ARRAY_LIST) && in.readInt() == 3, "guild clear events");
        clearedAttribute(wire, in, "guild", false);
        clearedAttribute(wire, in, "guildId", true);
        clearedAttribute(wire, in, "guildOid", true);
        TestWire.end(in);
    }

    private static void clearedAttribute(TestWire wire, DataInputStream in, String field,
            boolean integer) throws IOException {
        check(wire.type(in).equals(TestWire.DOB + "AttributeChangedEvent") && in.readInt() == 7,
            "guild clear event identity");
        check(wire.string(in).equals(field), "guild clear field " + field);
        if (integer) check(wire.type(in).equals("java.lang.Integer") && in.readInt() == 0,
            "guild clear integer " + field);
        else check(in.readShort() == 0, "null guild name");
    }

    private static void handshake() throws Exception {
        byte[] response = HandshakeCodec.response((short) 3, "0123456789abcdef0123456789abcdef");
        pin("HandshakeCodec.response", response, 139,
            "190f64551fcc6debef235cdbbabe8eb27308f839adfde1d5ed599e2824f9c85b");
        TestWire wire = new TestWire();
        DataInputStream in = wire.frame(response);
        check(in.readShort() == -1 && in.readUTF().equals(TestWire.NET + "SecureResponse"),
            "secure response class");
        check(in.readShort() == 3, "secure response correlation");
        check(in.readShort() == -2 && in.readUTF().equals(TestWire.NET + "AuthResponseData"),
            "secure response data class");
        check(in.readInt() == 0 && in.readBoolean()
            && in.readUTF().equals("0123456789abcdef0123456789abcdef"), "masked secret payload");
        TestWire.end(in);

        byte[] authentication = HandshakeCodec.authenticationResponse((short) 4, true, "session-1", "Ann");
        pin("HandshakeCodec.authenticationResponse", authentication, 168,
            "ceb97d5d1b8591b4f6bcc955c2dea477e6e1a5075fe677b077d1bfb6709c99e6");
        in = new TestWire().frame(authentication);
        check(in.readShort() == -3 && in.readUTF().equals(TestWire.NET + "AuthResponse"),
            "authentication response class");
        check(in.readShort() == 4 && in.readShort() == -4
            && in.readUTF().equals("com.threerings.projectx.data.ProjectXAuthResponseData"),
            "authentication response data class");
        check(in.readInt() == 0 && in.readBoolean() && in.readUTF().equals("success"),
            "authentication result");
        check(!in.readBoolean(), "installation identity is not echoed");
        check(in.readBoolean() && in.readUTF().equals("session-1"), "accepted session");
        check(in.readShort() == -5 && in.readUTF().equals(WireTypes.NAME)
            && in.readBoolean() && in.readUTF().equals("Ann"), "authenticated username");
        TestWire.end(in);
    }

    private static void eventEnvelope(TestWire wire, DataInputStream in, String kind, int oid)
            throws IOException {
        check(wire.type(in).equals(TestWire.NET + "EventNotification") && in.readShort() == -1,
            "event notification envelope");
        check(wire.type(in).equals(TestWire.DOB + kind) && in.readInt() == oid,
            "event class and object identity");
    }

    private static void pin(String feature, byte[] frame, int length, String sha) throws Exception {
        check(frame.length == length,
            feature + " frame length changed: bytes=" + frame.length + " expected=" + length);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame));
        check(digest.equals(sha),
            feature + " frame bytes changed: bytes=" + frame.length + " sha256=" + digest);
    }
}
