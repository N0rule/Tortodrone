package org.tortodrone.protocol;

import static org.tortodrone.support.TestWire.check;

import java.util.List;
import org.tortodrone.support.TestWire;

/** GuildObject and roster entries in the client's declared field order. */
public final class GuildWireTest {
    public static void main(String[] args) throws Exception {
        var guild = new org.tortodrone.core.guild.Guild(
                42,
                "Oracion",
                1_324_425_600_000L,
                "All hail",
                false,
                250,
                17_700,
                1_792_519_460_460L,
                false,
                100,
                null,
                null,
                0,
                0);
        var view = new org.tortodrone.core.guild.View(
                guild,
                List.of(
                        new org.tortodrone.core.guild.Member(
                                2074963, "Adlmarchus", (byte) 4, 0, 1_789_084_800_000L, 0, 0),
                        new org.tortodrone.core.guild.Member(
                                14066812, "Aklesitz", (byte) 1, -2147483647, 1_783_555_200_000L, 0, 0)),
                List.of());
        var wire = new TestWire();
        var codec = new SessionCodec();
        var in = wire.frame(codec.guild((short) 7, view));
        check(wire.type(in).equals(TestWire.NET + "ObjectResponse"), "guild arrives as an object response");
        check(in.readShort() == 7, "guild response keeps the subscribe message id");
        check(wire.type(in).equals("com.threerings.projectx.guild.data.GuildObject"), "guild object class");
        check(in.readInt() == SessionCodec.guildOid(42), "guild object id");
        check(
                wire.type(in).equals("com.threerings.projectx.guild.data.GuildPeerMarshaller")
                        && in.readInt() == SessionCodec.guildOid(42)
                        && in.readInt() == SessionCodec.GUILD_PEER_SERVICE,
                "guild peer marshaller");
        check(
                wire.type(in).equals("com.threerings.crowd.chat.data.SpeakMarshaller")
                        && in.readInt() == SessionCodec.guildOid(42)
                        && in.readInt() == SessionCodec.GUILD_CHAT_SERVICE,
                "guild chat marshaller");
        check(in.readInt() == 42, "guild id");
        check(
                wire.type(in).equals("com.threerings.util.Name")
                        && wire.string(in).equals("Oracion"),
                "guild name");
        check(in.readLong() == 1_324_425_600_000L, "formation time");
        check(wire.type(in).equals("com.threerings.presents.dobj.DSet") && in.readInt() == 2, "roster size");
        check(wire.type(in).equals("com.threerings.projectx.guild.data.GuildMemberEntry"), "member entry class");
        check(
                wire.type(in).equals("com.threerings.util.Name")
                        && wire.string(in).equals("Adlmarchus"),
                "member name streams first");
        check(in.readInt() == 2074963, "member knight id");
        check(wire.type(in).equals("com.threerings.projectx.social.data.Whereabouts$Offline"), "offline whereabouts");
        in.readLong();
        check(in.readByte() == 4, "member rank");
        check(in.readInt() == 0, "member command order");
        check(in.readLong() == 1_789_084_800_000L, "member join time");
        check(wire.type(in).equals("com.threerings.projectx.guild.data.GuildMemberEntry"), "second member");
        check(
                wire.type(in).equals("com.threerings.util.Name")
                        && wire.string(in).equals("Aklesitz"),
                "second member name");
        in.readInt();
        wire.type(in);
        in.readLong();
        check(in.readByte() == 1, "officer rank");
        check(in.readInt() == -2147483647, "officer seniority");
        in.readLong();
        check(in.readInt() == 0 && in.readInt() == 0, "no hall or design scene is invented");
        check(wire.string(in).equals("All hail"), "message of the day");
        check(!in.readBoolean(), "hall closed");
        check(in.readInt() == 250, "well energy");
        check(in.readInt() == 17_700, "upkeep cost");
        check(in.readLong() == 1_792_519_460_460L, "upkeep time");
        check(!in.readBoolean(), "not in default");
        check(wire.type(in).equals("java.util.HashSet") && in.readInt() == 0, "no purchased guild attributes");
        check(in.readInt() == 100, "member limit");
        check(in.available() == 0, "the guild object ends where the client's fields do");

        var changes = wire.frame(codec.guildChanges(
                view,
                new org.tortodrone.core.guild.View(guild, List.of(view.members().getFirst()), List.of())));
        check(wire.type(changes).equals(TestWire.NET + "EventNotification"), "guild delta envelope");
        check(
                changes.readShort() == -1
                        && wire.type(changes).equals(TestWire.DOB + "CompoundEvent")
                        && changes.readInt() == SessionCodec.guildOid(42),
                "guild delta compound");
        check(wire.type(changes).equals("com.threerings.util.StreamableArrayList"), "guild delta list");
        int count = changes.readInt();
        check(count >= 1, "a removed member emits at least one event");

        var expansionWire = new TestWire();
        var expansion = expansionWire.frame(
                new SessionCodec().expansionDialog(9, 42, List.of(0, 1), false, 0.25f, 1_700_000_000_000L));
        check(
                expansionWire.type(expansion).equals(TestWire.NET + "EventNotification")
                        && expansion.readShort() == -1
                        && expansionWire.type(expansion).equals(TestWire.DOB + "CompoundEvent")
                        && expansion.readInt() == 9,
                "expansion window envelope");
        check(
                expansionWire.type(expansion).equals("com.threerings.util.StreamableArrayList")
                        && expansion.readInt() == 2,
                "expansion window replaces the scene dialog");
        check(
                expansionWire.type(expansion).equals(TestWire.DOB + "AttributeChangedEvent")
                        && expansion.readInt() == 9
                        && expansionWire.string(expansion).equals("sceneDialogCreated")
                        && expansionWire.type(expansion).equals("java.lang.Long")
                        && expansion.readLong() == 1_700_000_000_000L,
                "expansion window creation stamp");
        check(
                expansionWire.type(expansion).equals(TestWire.DOB + "AttributeChangedEvent")
                        && expansion.readInt() == 9
                        && expansionWire.string(expansion).equals("sceneDialog"),
                "expansion window is the scene dialog");
        check(
                expansionWire.type(expansion).equals("com.threerings.projectx.guild.data.ExpansionDialogInfo"),
                "expansion window class");
        expansion.readShort();
        expansion.readShort();
        expansion.readFloat();
        expansion.readShort();
        expansion.readShort();
        check(expansion.readShort() == 0 && expansion.readShort() == 0, "null portrait model and animation");
        check(
                expansionWire.string(expansion) == null && expansionWire.string(expansion) == null,
                "null portrait name and title");
        check(expansion.readInt() == 42, "expansion window echoes the guild");
        check(
                expansionWire.type(expansion).equals("java.util.HashSet") && expansion.readInt() == 2,
                "installed expansion set");
        check(
                expansionWire.type(expansion).equals("com.threerings.projectx.guild.data.HallExpansion")
                        && expansion.readByte() == 0,
                "expansion ordinal streams first");
        check(
                expansionWire.type(expansion).equals("com.threerings.projectx.guild.data.HallExpansion")
                        && expansion.readByte() == 1,
                "expansion ordinal streams second");
        check(!expansion.readBoolean(), "not in default");
        check(expansion.readFloat() == 0.25f && expansion.available() == 0, "remaining upkeep share");
        // The record page answers the invocation with a GuildLogPage argument. It has no town
        // result branch, so the guild codec must encode it or the downstream stream fails.
        // The record page cannot travel through the town result writer: it has no branch for a
        // Page and would fail the downstream stream, which is why the handler uses the guild codec.
        boolean townWriterRejects = false;
        try {
            new SessionCodec()
                    .townResult(
                            SessionCodec.guildOid(42),
                            (short) 12,
                            new org.tortodrone.core.guild.Page(0, List.of()),
                            null);
        } catch (java.io.IOException expected) {
            townWriterRejects = true;
        }
        check(townWriterRejects, "the town result writer cannot encode a guild record page");

        var recordCodec = new SessionCodec();
        var recordWire = new TestWire();
        var record = recordWire.frame(recordCodec.guildLog(
                (short) 12,
                SessionCodec.guildOid(42),
                new org.tortodrone.core.guild.Page(
                        7,
                        List.of(new org.tortodrone.core.guild.LogEntry(
                                9L, 9, "Adlmarchus", 1_789_084_800_000L, List.of("~500"))))));
        check(
                recordWire.type(record).equals(TestWire.NET + "EventNotification")
                        && record.readShort() == -1
                        && recordWire.type(record).equals(TestWire.DOB + "InvocationResponseEvent"),
                "record page arrives as an invocation response");
        check(
                record.readInt() == SessionCodec.guildOid(42) && record.readShort() == 12,
                "record page echoes the guild object and the request");
        check(record.readByte() == 1 && record.readBoolean(), "record page success flags");
        check(record.readInt() == 1, "record page result count");
        check(recordWire.type(record).equals("com.threerings.projectx.guild.data.GuildLogPage"), "record page class");
        check(record.readInt() == 7, "record page reports the filter total, not the page size");
        check(recordWire.type(record).equals("java.util.ArrayList") && record.readInt() == 1, "record page entry list");
        check(recordWire.type(record).equals("com.threerings.projectx.guild.data.GuildLogEntry"), "record entry class");
        check(record.readLong() == 9L, "record entry id");
        check(
                recordWire.type(record).equals("com.threerings.projectx.guild.data.GuildLogEntry$Type")
                        && record.readByte() == 9,
                "record entry type ordinal");
        check(TestWire.string(record).equals("Adlmarchus"), "record entry knight");
        check(record.readLong() == 1_789_084_800_000L, "record entry time");
        check(
                recordWire.type(record).equals("[Ljava.lang.String;") && record.readInt() == 1,
                "record entry argument array");
        // The argument array declares its presence-bitmap length in shorts, then writes that many
        // bitmap bytes, as the client's String[] marshaller does.
        check(record.readShort() == 1, "record entry argument bitmap length");
        check(record.readByte() == 1, "record entry argument presence bit");
        check(record.readUTF().equals("~500") && record.available() == 0, "record entry argument value");

        System.out.println(
                "PASS: guild object, roster entries, removal delta, expansion window and record page follow the client's field order");
    }
}
