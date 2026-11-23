package org.tortodrone.core.party;

import java.util.List;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneStatus;

/** Party events and read views shared by the live registry and the wire codec. */
public final class PartyView {
    public static final int CAPACITY = 8;

    public sealed interface Event permits Refresh, Invitation, Chat {}

    public record Refresh() implements Event {}

    public record Invitation(int partyOid, List<String> names, int inviter, boolean add) implements Event {}

    public record Chat(int partyOid, SceneChat.Message message) implements Event {}

    public record Participant(
            int playerOid,
            int worldId,
            int order,
            Knight knight,
            WorldProfile profile,
            SceneHealth.State health,
            SceneStatus.State status,
            float heat) {
        public Participant(
                int playerOid,
                int worldId,
                int order,
                Knight knight,
                WorldProfile profile,
                SceneHealth.State health,
                SceneStatus.State status) {
            this(playerOid, worldId, order, knight, profile, health, status, 0);
        }

        public Participant {
            if (!Float.isFinite(heat) || heat < 0 || heat > 1_000_000)
                throw new IllegalArgumentException("Invalid floor heat");
        }
    }

    public record View(
            int oid,
            long revision,
            List<Participant> members,
            boolean automatic,
            boolean inviteOnly,
            boolean allowFriends,
            boolean allowGuild) {
        public View {
            members = List.copyOf(members);
        }
    }

    private PartyView() {}
}
