package org.tortodrone.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;

/** The one owner of "staff letter to {@code town_mail} row" semantics: sender negation,
 *  literal/tainted text, SYSTEM mail type and gift materialisation. Campaigns, the welcome
 *  letter and one-off sends all write through here inside the caller's transaction. */
final class StaffMail {
    private final ItemCatalog items;

    StaffMail(ItemCatalog items) {
        this.items = Objects.requireNonNull(items);
    }

    /** Materialise gift templates for one delivery. A removed or no-longer-grantable item is
     *  skipped so a content change cannot block the knight's login or the rest of the letter. */
    List<WorldProfile.Item> materialise(List<MailCampaignStore.Gift> gifts) throws IOException {
        var result = new ArrayList<WorldProfile.Item>(gifts.size());
        for (var gift : gifts) {
            var entry = items.find(gift.config());
            if (entry == null || !entry.grantable()) continue;
            result.add(new WorldProfile.Item(0, entry.slot(), entry.config(), null, 0, null,
                gift.bound(), entry.bindOnEquip(), false, gift.count(), entry.permanent(), 0, 0f,
                List.of(), List.of()));
        }
        return result;
    }

    /** Insert one staff letter inside the caller's transaction and return its row id. */
    long deliver(Connection c, int world, long sent, String subject, String body, int crowns,
            int energy, List<WorldProfile.Item> attachments, int sender, String senderLabel)
            throws SQLException, IOException {
        try (var s = c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,sender_label,mail_type) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id")) {
            s.setInt(1, world); s.setLong(2, sent);
            s.setString(3, mailText(subject, senderLabel)); s.setString(4, mailText(body, senderLabel));
            s.setInt(5, crowns); s.setInt(6, energy);
            s.setString(7, attachments.isEmpty() ? null : TownMail.encodeItems(attachments));
            s.setInt(8, -sender); s.setString(9, senderLabel);
            s.setInt(10, mailType(senderLabel));
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    /** System senders use a tainted literal; a custom name uses an ANNOUNCE letter with plain
     *  text, because the native reader only translates the subject and body when the sender is
     *  a stock SystemSender. */
    private static String mailText(String text, String senderLabel) {
        return senderLabel == null ? "%uplink:~" + text : text;
    }

    private static int mailType(String senderLabel) {
        // Both are SYSTEM mail so the inbox lists them; a custom name makes the
        // native reader skip translation and show the plain text and literal sender.
        return 3;
    }
}
