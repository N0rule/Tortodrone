package org.tortodrone.protocol;

import java.io.DataInputStream;
import java.util.Map;
import org.tortodrone.core.character.AchievementState;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.NativeAchievements;
import org.tortodrone.core.character.NativeStats;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Exact stock wire shape for achievement entries, stat entries and the unlock event. */
public final class AchievementWireTest {
    public static void main(String[] args) throws Exception {
        var codec = new SessionCodec(ItemCatalog.starter());
        var wire = new TestWire();
        var knight = new Knight(7, "Ann", "helm", "armor", "helmAcc", "armorAcc", "costume", 3, 4, 5, 1000L);
        var empty = new WorldProfile(10, 1, 7, 0, 0, 1, java.util.List.of());
        var state = new AchievementState(
            Map.of(NativeAchievements.ordinal("ITEMS_CRAFTED_2"), 456L,
                NativeAchievements.ordinal("REACHED_RESCUE_CAMP"), 123L),
            Map.of(NativeStats.ITEMS_CRAFTED, 10));
        var after = empty.withAchievements(state);

        byte[] frame = codec.achievementChanges(7, empty, after);
        check(frame != null, "a new unlock produces an event");
        DataInputStream in = wire.frame(frame);
        check(wire.type(in).equals(TestWire.NET + "EventNotification"), "event notification");
        check(in.readShort() == -1, "event correlation");
        check(wire.type(in).equals(TestWire.DOB + "CompoundEvent") && in.readInt() == 7, "compound event identity");
        check(wire.type(in).equals(org.tortodrone.core.WireTypes.ARRAY_LIST) && in.readInt() == 3, "two entries and one stat change");
        String[] expected = {"REACHED_RESCUE_CAMP", "ITEMS_CRAFTED_2"};
        long[] stamps = {123L, 456L};
        for (int i = 0; i < expected.length; i++) {
            check(wire.type(in).equals(TestWire.DOB + "EntryAddedEvent") && in.readInt() == 7, "achievement entry event");
            check(TestWire.string(in).equals("achievements"), "achievement field name");
            check(wire.type(in).equals("com.threerings.projectx.data.Achievement"), "stock achievement class");
            check(wire.type(in).equals("com.threerings.projectx.data.Achievement$Type"), "stock achievement key type");
            check(in.readUnsignedByte() == NativeAchievements.ordinal(expected[i]), "client enum ordinal identity");
            check(in.readLong() == stamps[i], "unlock timestamp");
        }
        check(wire.type(in).equals(TestWire.DOB + "AttributeChangedEvent") && in.readInt() == 7, "stats attribute event");
        check(TestWire.string(in).equals("stats"), "stats field name");
        check(wire.type(in).equals("com.threerings.stats.data.StatSet") && in.readInt() == 1, "stat set with one counter");
        check(wire.type(in).equals("com.threerings.stats.data.IntStat"), "stock IntStat entry");
        check(wire.type(in).equals("com.threerings.projectx.data.StatType"), "stock stat key type");
        check(in.readUnsignedByte() == NativeStats.ITEMS_CRAFTED, "stat enum ordinal identity");
        check(!in.readBoolean() && in.readByte() == 0, "stat is clean server state");
        check(in.readInt() == 10, "stat value");
        TestWire.end(in);

        check(codec.achievementChanges(7, after, after) == null, "no event when nothing changed");
        System.out.println("PASS: achievement DSet entries, StatSet entries and unlock compound event");
    }
}
