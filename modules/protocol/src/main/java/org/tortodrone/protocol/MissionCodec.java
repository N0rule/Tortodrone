package org.tortodrone.protocol;

import static org.tortodrone.protocol.SessionCodec.*;
import static org.tortodrone.protocol.WireWriter.*;

import java.io.DataOutputStream;
import java.io.IOException;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;

/** Mission-owned downstream frames; shares the connection symbol tables. */
public final class MissionCodec extends WireWriter {
    public MissionCodec(WireContext context, ItemCatalog catalog) {
        super(context, catalog);
    }

    public byte[] completedMissions(
            int oid, java.util.Collection<org.tortodrone.core.character.OpeningCompletion> completions)
            throws IOException {
        if (completions.size() > 4096) throw new IOException("Too many mission receipts");
        return frame(out -> {
            eventHeader(out, "AttributeChangedEvent", oid);
            string(out, "completedMissions");
            type(out, WireTypes.DSET);
            out.writeInt(completions.size());
            for (var receipt : completions.stream()
                    .sorted(java.util.Comparator.comparing(org.tortodrone.core.character.OpeningCompletion::missionId))
                    .toList()) missionCompletion(out, receipt);
        });
    }

    private void missionCompletion(DataOutputStream out, org.tortodrone.core.character.OpeningCompletion receipt)
            throws IOException {
        type(out, "com.threerings.projectx.mission.data.MissionCompletion");
        string(out, receipt.missionId());
        out.writeLong(receipt.completed());
        if (receipt.difficulty() == null) value(out, null);
        else {
            type(out, WireTypes.DIFFICULTY);
            out.writeByte(java.util.List.of("EASY", "MEDIUM", "HARD").indexOf(receipt.difficulty()));
        }
    }

    public byte[] missionSettlement(
            int oid,
            WorldProfile before,
            WorldProfile after,
            org.tortodrone.core.character.OpeningCompletion receipt,
            java.util.List<org.tortodrone.core.mission.MissionCatalog.Grant> rewards,
            int prestige,
            boolean immediate,
            long created)
            throws IOException {
        int total = rewards.stream()
                .mapToInt(org.tortodrone.core.mission.MissionCatalog.Grant::count)
                .sum();
        return frame(out -> {
            eventHeader(out, "CompoundEvent", oid);
            type(out, WireTypes.ARRAY_LIST);
            out.writeInt(6);
            rank(out, oid, after);
            attribute(out, oid, "prestige", o -> value(o, after.progress().prestige()));
            attribute(out, oid, "progressFlags", o -> value(o, after.progress().progressFlags()));
            partyEntry(out, oid, "completedMissions", "EntryAddedEvent");
            missionCompletion(out, receipt);
            attribute(out, oid, "sceneDialogCreated", o -> value(o, created));
            attribute(out, oid, "sceneDialog", o -> {
                type(o, "com.threerings.projectx.mission.data.MissionCompleteDialogInfo");
                o.writeShort(0);
                o.writeShort(0);
                o.writeFloat(0);
                o.writeShort(0);
                o.writeShort(0);
                string(o, receipt.missionId());
                o.writeBoolean(true);
                o.writeInt(total);
                for (var reward : rewards) for (int i = 0; i < reward.count(); i++) value(o, grantModel(reward));
                o.writeInt(prestige);
                o.writeBoolean(immediate);
                type(o, "com.threerings.projectx.mission.data.RankPair");
                o.writeInt(before.progress().rank());
                o.writeInt(before.progress().subrank());
                o.writeBoolean(true);
                string(
                        o,
                        "ui/icon/difficulty/"
                                + (receipt.difficulty() == null
                                        ? "hard"
                                        : receipt.difficulty().toLowerCase(java.util.Locale.ROOT))
                                + ".png");
            });
        });
    }
}
