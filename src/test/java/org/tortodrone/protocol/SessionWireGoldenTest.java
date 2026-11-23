package org.tortodrone.protocol;

import static org.tortodrone.support.TestWire.check;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.RosterEntry;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.SceneChat;

/**
 * Locks a synthetic cross-feature downstream stream to an exact SHA-256.
 *
 * <p>No client capture is used. The fixture exercises bootstrap, object, roster, social, party,
 * town, travel, inventory and scene frames through one connection so that class/intern table
 * ordering is covered end to end. Any feature split must keep the concatenated bytes identical.
 */
public final class SessionWireGoldenTest {
    public static void main(String[] args) throws Exception {
        ItemCatalog catalog = ItemCatalog.starter();
        SessionCodec codec = new SessionCodec(catalog);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(stream);

        out.write(codec.bootstrap(11, 7, "127.0.0.1", 47624));
        out.write(codec.player((short) 1, 7, 7, "Ann"));
        out.write(codec.server((short) 1));
        out.write(codec.configuration((short) 1));
        out.write(codec.missionObject((short) 1));
        out.write(codec.loveTest((short) 1));
        out.write(codec.completedMissions(7, List.of()));
        out.write(codec.emptyListResult(7, (short) 3));
        out.write(codec.listResult(7, (short) 3, List.of("first", "second")));
        out.write(codec.gameMaster(7, true));
        out.write(codec.playerFlags(7, true, false, true));
        out.write(codec.lookingForGroup(7, true));
        out.write(codec.awayMessage(7, "gone"));
        out.write(codec.tellSucceeded(7, (short) 4, "not here"));
        out.write(codec.chatFeedback(9, "too fast"));
        out.write(codec.systemNotice(9, "hello"));
        out.write(codec.chat(9, new SceneChat.Message("Ann", "hi", (byte) 0)));
        out.write(codec.partyOid(7, 3));
        out.write(codec.chatChannel((short) 2, 7, 6));
        out.write(codec.readyRoomLocation(7));
        out.write(codec.forceDestination(7, (short) 5, 10, 20));
        out.write(codec.readyRoomMove(7, (short) 5));
        out.write(codec.subscriptionFailure((short) 5, 7, "no"));
        out.write(codec.invocationFailure(7, (short) 5, "busy"));
        out.write(codec.invocationConfirmed(7, (short) 5));
        out.write(codec.invocationResult(7, (short) 5, "ok"));
        out.write(codec.quickbar(7, new long[] {1, 2, 3, 4}));
        out.write(codec.knightName(7, "Ann"));
        out.write(codec.equipPolicy(7, true));
        out.write(codec.friends(7, List.of(), List.of(new SessionCodec.FriendView(1, "Bob", 2, "Haven", 3, 4, 5))));
        Knight knight = new Knight(7, "Ann", "helm", "armor", "helmAcc", "armorAcc", "costume", 3, 4, 5, 1000L);
        out.write(codec.rosterChanged(
                7, List.of(new RosterEntry(knight, new StarterCatalog.Model("Player/Test", Map.of("k", "v")), 1))));
        out.write(codec.sceneDelta(
                7,
                9,
                0,
                (short) 0,
                0,
                1234,
                (short) 0,
                List.of(),
                List.of(),
                new int[0],
                Map.of(),
                false,
                List.of(),
                List.of()));
        var template = SessionCodec.sceneDeltaTemplate(
                catalog, 9, 1234, List.of(), List.of(), new int[0], Map.of(), false, List.of(), List.of());
        byte[] materialized = codec.materialize(template, 7, 0, (short) 0, 0, (short) 0);
        check(materialized != null, "Scene template materialized after the direct frame registered its symbols");
        out.write(materialized);

        byte[] bytes = stream.toByteArray();
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String expected = "b15cbffe653e52765f8485db975568355156d5d2fdbc51f5440626d731045fc0";
        if (!expected.equals(digest))
            throw new AssertionError("Cross-feature stream changed; bytes=" + bytes.length + " sha256=" + digest);
        System.out.println("PASS: synthetic cross-feature downstream stream is byte-identical (" + bytes.length
                + " bytes, sha256=" + digest + ")");
    }
}
