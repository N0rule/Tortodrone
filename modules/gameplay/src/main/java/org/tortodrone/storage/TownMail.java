package org.tortodrone.storage;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.*;
import org.tortodrone.core.town.AuctionState;

/** Durable inbox, uplink letters, attachments and retention, atomically protected by
 *  SQLite's write lock. Auction settlement delivers its result as a letter here rather
 *  than owning the inbox itself. */
public final class TownMail {
    private final Database database;
    private final ItemCatalog items;
    private final TownStore town;
    private final MailCampaignStore campaigns;

    TownMail(Database database, ItemCatalog items, TownStore town, MailCampaignStore campaigns) {
        this.database = database;
        this.items = items;
        this.town = town;
        this.campaigns = campaigns;
    }
    /** ServerObject mail fees from the archive: a letter costs 25, any attachment adds 75. */
    public static final int MAIL_FEE = org.tortodrone.core.economy.Fees.MAIL_FEE,
            MAIL_ATTACH_FEE = org.tortodrone.core.economy.Fees.MAIL_ATTACH_FEE;
    /** Stock mail retention: every inbox letter is deleted 30 days after it was sent. */
    static final long MAIL_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private static final int MAIL_PURGE_BATCH = 500;
    private static final String RETURNED_MAIL_SENDER = "Postmaster";
    private static final int MAX_MAIL_ATTACHMENTS = 14;

    /** Uplink RPC body inside the auction owner's transaction; the caller has already
     *  settled expired listings and reloads the profile after this returns. */
    Object mail(Connection c, WorldProfile p, int method, List<Object> args) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        purgeWorldMail(c, p.worldId(), now);
        campaigns.deliver(c, p.worldId());
        Object result = null;
        if (method == 5) deliver(c, p, (org.tortodrone.core.wire.SentMail) args.getFirst());
        else if (method == 6) {
            long[] ids = (long[]) args.getFirst();
            var messages = loadMail(c, p.worldId());
            result = ids == null
                    ? messages
                    : messages.stream()
                            .filter(m -> java.util.stream.LongStream.of(ids).anyMatch(id -> id == m.id()))
                            .toList();
        } else if (method == 11) {
            long[] ids = (long[]) args.getFirst();
            for (long id : ids)
                try (var s = c.prepareStatement("UPDATE town_mail SET is_read=1 WHERE id=? AND world_id=?")) {
                    s.setLong(1, id);
                    s.setInt(2, p.worldId());
                    s.executeUpdate();
                }
        } else {
            long id = (Long) args.getFirst();
            var mail = loadMail(c, p.worldId()).stream()
                    .filter(m -> m.id() == id)
                    .findFirst()
                    .orElseThrow(() -> new IOException("Unknown owned mail"));
            if (method == 1 || method == 2) claim(c, p, mail);
            if (method == 1 || method == 4) {
                if (method == 4 && !mail.claimed() && !mail.friendRequest() && hasAttachment(mail))
                    throw new IOException("Accept the attachment before deleting this mail");
                try (var s = c.prepareStatement("DELETE FROM town_mail WHERE id=? AND world_id=?")) {
                    s.setLong(1, id);
                    s.setInt(2, p.worldId());
                    s.executeUpdate();
                }
                FriendStore.deletedMail(c, id);
            }
        }
        TownLedger.touch(c, p.worldId());
        return result;
    }

    private static boolean hasAttachment(AuctionState.Mail mail) {
        return mail.guildInvite() || !mail.items().isEmpty() || mail.crowns() > 0 || mail.energy() > 0;
    }

    private void claim(Connection c, WorldProfile p, AuctionState.Mail mail) throws SQLException, IOException {
        if (mail.claimed()) throw new IOException("%uplink:b.accepted");
        if (mail.friendRequest()) FriendStore.accept(c, p.worldId(), mail);
        if (mail.guildInvite()) {
            String name;
            try (var s = c.prepareStatement(
                    "SELECT k.name FROM world_characters w JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE w.world_id=?")) {
                s.setInt(1, p.worldId());
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new IOException("m.internal_error");
                    name = r.getString(1);
                }
            }
            GuildStore.acceptInvite(c, p.worldId(), mail.guildInviteId(), mail.guildInviteName(), name);
        }
        TownLedger.credit(c, p.worldId(), mail.crowns(), mail.energy());
        long now = System.currentTimeMillis();
        for (var item : mail.items()) if (item != null && !item.expired(now)) grant(c, p, item);
        try (var s = c.prepareStatement(
                "UPDATE town_mail SET claimed=1,is_read=1 WHERE id=? AND world_id=? AND claimed=0")) {
            s.setLong(1, mail.id());
            s.setInt(2, p.worldId());
            if (s.executeUpdate() != 1) throw new IOException("Attachment changed");
        }
    }

    private void grant(Connection c, WorldProfile p, WorldProfile.Item i) throws SQLException, IOException {
        long created = town.create(c, p, i.config(), i.count(), i.bound(), i.level(), i.heatProgress(), i.variants());
        InventoryExpiry.save(c, created, i.expiresAt());
        try (var s = c.prepareStatement(
                "UPDATE inventory_items SET accessory=?,accessories=? WHERE item_id=? AND world_id=?")) {
            s.setString(1, i.accessory());
            s.setString(2, Json.stringify(i.additionalAccessories()));
            s.setLong(3, created);
            s.setInt(4, p.worldId());
            s.executeUpdate();
        }
        GiftStore.save(c, created, i.gift());
    }

    private record Recipient(int world, boolean gameMaster) {}

    private static Recipient recipient(Connection c, String name) throws SQLException, IOException {
        if (CharacterStore.nameError(name) != null) throw new IOException("m.unknown_knight");
        try (var s = c.prepareStatement(
                "SELECT w.world_id,a.game_master FROM world_characters w JOIN accounts a ON a.id=w.account_id JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE k.name_key=?")) {
            s.setString(1, name.toLowerCase(Locale.ROOT));
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IOException("m.unknown_knight");
                return new Recipient(r.getInt(1), r.getBoolean(2));
            }
        }
    }
    /** Uplink method 5: validate text, fee and item ownership, then escrow everything in one write. */
    private void deliver(Connection c, WorldProfile p, org.tortodrone.core.wire.SentMail sent)
            throws SQLException, IOException {
        if (sent == null
                || sent.subject() == null
                || sent.body() == null
                || sent.subject().length() > 50
                || sent.body().length() > 8096
                || sent.crowns() < 0
                || sent.energy() < 0) throw new IOException("Invalid mail text or attachment");
        long units = 0;
        for (var count : sent.items().values()) {
            if (count == null || count < 1) throw new IOException("Invalid mail attachment");
            units += count;
        }
        if (units > MAX_MAIL_ATTACHMENTS) throw new IOException("A mail can carry at most 14 item units");
        var target = recipient(c, sent.recipient());
        if (target.gameMaster) {
            // Stock first-market behaviour: a GM badge bounces the letter back without charging.
            try (var s = c.prepareStatement(
                    "INSERT INTO town_mail(world_id,sent,subject,body,sender,mail_type) VALUES(?,?,?,?,?,3)")) {
                s.setInt(1, p.worldId());
                s.setLong(2, System.currentTimeMillis());
                s.setString(3, "%uplink:" + compose("s.bounced", sent.recipient(), sent.subject()));
                s.setString(4, "%uplink:" + compose("m.bounced", sent.body()));
                s.setInt(5, -2);
                s.executeUpdate();
            }
            TownLedger.touch(c, p.worldId());
            return;
        }
        boolean attached = !sent.items().isEmpty() || sent.crowns() > 0 || sent.energy() > 0;
        long cost = (long) (MAIL_FEE + (attached ? MAIL_ATTACH_FEE : 0)) + sent.crowns();
        if (cost > Integer.MAX_VALUE) throw new Database.Refused("m.nsf_crowns");
        if (p.crowns() < cost) throw new Database.Refused("m.nsf_crowns");
        if (p.energy() < sent.energy()) throw new Database.Refused("m.nsf_energy");
        TradeTransfer.validate(p, sent.items(), 0, 0, items);
        var held = new ArrayList<WorldProfile.Item>();
        for (var entry : sent.items().entrySet()) {
            var item = TownLedger.owned(p, entry.getKey());
            int count = entry.getValue();
            held.add(new WorldProfile.Item(
                            item.id(),
                            item.slot(),
                            item.config(),
                            item.accessory(),
                            item.heat(),
                            null,
                            item.bound(),
                            item.bindOnEquip(),
                            false,
                            count,
                            item.permanent(),
                            item.level(),
                            item.heatProgress(),
                            item.variants(),
                            item.additionalAccessories())
                    .withGift(item.gift())
                    .withExpiration(item.expiresAt()));
        }
        TownLedger.pay(c, p, (int) cost, sent.energy());
        for (var entry : sent.items().entrySet()) TownLedger.consume(c, p, entry.getKey(), entry.getValue());
        try (var s = c.prepareStatement(
                "INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,?,?,4)")) {
            s.setInt(1, target.world());
            s.setLong(2, System.currentTimeMillis());
            s.setString(3, sent.subject());
            s.setString(4, sent.body());
            s.setInt(5, sent.crowns());
            s.setInt(6, sent.energy());
            s.setString(7, held.isEmpty() ? null : encodeItems(held));
            s.setInt(8, p.worldId());
            s.executeUpdate();
        }
        TownLedger.touch(c, target.world());
    }

    /** One bounded retention batch for the scheduler and storage regressions. */
    int expire(Connection c, long now) throws SQLException, IOException {
        return purgeExpiredMail(c, now, MAIL_PURGE_BATCH);
    }

    private int purgeExpiredMail(Connection c, long now, int limit) throws SQLException, IOException {
        var rows = new ArrayList<ExpiredMail>(limit);
        try (var s = c.prepareStatement(
                "SELECT id,world_id,subject,body,crowns,energy,attachments,sender,mail_type,claimed FROM town_mail WHERE sent<=? ORDER BY id LIMIT ?")) {
            s.setLong(1, now - MAIL_RETENTION_MS);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) rows.add(expired(r));
            }
        }
        return purge(c, rows, now);
    }

    private int purgeWorldMail(Connection c, int world, long now) throws SQLException, IOException {
        var rows = new ArrayList<ExpiredMail>();
        try (var s = c.prepareStatement(
                "SELECT id,world_id,subject,body,crowns,energy,attachments,sender,mail_type,claimed FROM town_mail WHERE world_id=? AND sent<=? ORDER BY id")) {
            s.setInt(1, world);
            s.setLong(2, now - MAIL_RETENTION_MS);
            try (var r = s.executeQuery()) {
                while (r.next()) rows.add(expired(r));
            }
        }
        return purge(c, rows, now);
    }

    private static ExpiredMail expired(ResultSet r) throws SQLException {
        return new ExpiredMail(
                r.getLong("id"),
                r.getInt("world_id"),
                r.getString("subject"),
                r.getString("body"),
                r.getInt("crowns"),
                r.getInt("energy"),
                r.getString("attachments"),
                r.getInt("sender"),
                r.getInt("mail_type"),
                r.getBoolean("claimed"));
    }
    /** Unaccepted player attachments go back to the sender; every other letter is deleted. */
    private int purge(Connection c, List<ExpiredMail> rows, long now) throws SQLException, IOException {
        var touched = new HashSet<Integer>();
        for (var row : rows) {
            if (returnable(c, row)) {
                try (var s = c.prepareStatement(
                        "INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,sender_label,mail_type) VALUES(?,?,?,?,?,?,?,?,?,3)")) {
                    s.setInt(1, row.sender());
                    s.setLong(2, now);
                    s.setString(3, row.subject());
                    s.setString(4, row.body());
                    s.setInt(5, row.crowns());
                    s.setInt(6, row.energy());
                    s.setString(7, row.attachments());
                    s.setInt(8, -2);
                    s.setString(9, RETURNED_MAIL_SENDER);
                    s.executeUpdate();
                }
                touched.add(row.sender());
            }
            try (var s = c.prepareStatement("DELETE FROM town_mail WHERE id=? AND world_id=?")) {
                s.setLong(1, row.id());
                s.setInt(2, row.world());
                if (s.executeUpdate() != 1) throw new IOException("Mail changed");
            }
            FriendStore.deletedMail(c, row.id());
            touched.add(row.world());
        }
        for (int world : touched) TownLedger.touch(c, world);
        return rows.size();
    }

    private static boolean returnable(Connection c, ExpiredMail mail) throws SQLException {
        if (mail.type() != 4 || mail.claimed() || mail.sender() <= 0) return false;
        if (mail.crowns() <= 0
                && mail.energy() <= 0
                && (mail.attachments() == null || mail.attachments().isBlank())) return false;
        if (!worldExists(c, mail.sender())) return false;
        if (mail.attachments() != null && !mail.attachments().isBlank())
            try {
                decodeItems(mail.attachments());
            } catch (IOException corrupt) {
                return false;
            }
        return true;
    }

    private static boolean worldExists(Connection c, int world) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM world_characters WHERE world_id=?")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private record ExpiredMail(
            long id,
            int world,
            String subject,
            String body,
            int crowns,
            int energy,
            String attachments,
            int sender,
            int type,
            boolean claimed) {}

    public record Delivery(long id, int recipient, int crowns, boolean item, boolean claimed) {}

    public List<Delivery> deliveries(long auction) throws IOException {
        return database.read(c -> {
            var result = new ArrayList<Delivery>();
            try (var s = c.prepareStatement(
                    "SELECT id,world_id,crowns,item IS NOT NULL,claimed FROM town_mail WHERE auction=? ORDER BY id")) {
                s.setLong(1, auction);
                try (var r = s.executeQuery()) {
                    while (r.next())
                        result.add(
                                new Delivery(r.getLong(1), r.getInt(2), r.getInt(3), r.getBoolean(4), r.getBoolean(5)));
                }
            }
            return List.copyOf(result);
        });
    }

    String display(WorldProfile.Item item) {
        var definition = items.find(item.config());
        return definition == null
                ? "~" + item.config().substring(item.config().lastIndexOf('/') + 1)
                : "%item:" + definition.nameKey();
    }
    /** One SYSTEM-type uplink letter from a native SystemSender, inside the caller's transaction;
     * the caller owns the knight's single revision increment. */
    static void systemMail(
            Connection c, int world, int senderOrdinal, String subject, String body, List<WorldProfile.Item> items)
            throws SQLException, IOException {
        if (senderOrdinal < 1) throw new IOException("Invalid system sender");
        try (var s = c.prepareStatement(
                "INSERT INTO town_mail(world_id,sent,subject,body,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,3)")) {
            s.setInt(1, world);
            s.setLong(2, System.currentTimeMillis());
            s.setString(3, "%uplink:" + subject);
            s.setString(4, "%uplink:" + body);
            s.setString(5, items.isEmpty() ? null : encodeItems(items));
            s.setInt(6, -senderOrdinal);
            s.executeUpdate();
        }
    }

    static String composeMessage(String key, String... args) {
        return compose(key, args);
    }

    static String compose(String key, String... args) {
        var result = new StringBuilder(key);
        for (String arg : args)
            result.append('|').append(arg.replace("\\", "\\\\").replace("|", "\\!"));
        return result.toString();
    }
    /** One settlement letter inside the caller's transaction, tagged with its auction. */
    static void letter(
            Connection c, long auction, int world, String subject, String body, int crowns, WorldProfile.Item item)
            throws SQLException, IOException {
        try (var s = c.prepareStatement(
                "INSERT INTO town_mail(world_id,sent,subject,body,crowns,item,auction) VALUES(?,?,?,?,?,?,?)")) {
            s.setInt(1, world);
            s.setLong(2, System.currentTimeMillis());
            s.setString(3, "%uplink:" + subject);
            s.setString(4, "%uplink:" + body);
            s.setInt(5, crowns);
            s.setString(6, item == null ? null : encode(item));
            s.setLong(7, auction);
            s.executeUpdate();
        }
        TownLedger.touch(c, world);
    }

    static List<AuctionState.Mail> loadMail(Connection c, int world) throws SQLException, IOException {
        var messages = new ArrayList<AuctionState.Mail>();
        try (var s = c.prepareStatement(
                "SELECT m.*,r.value AS friend_world,k.name AS friend_name,COALESCE(m.sender_label,s.name) AS sender_name FROM town_mail m LEFT JOIN metadata r ON r.key='social.request.'||m.id LEFT JOIN world_characters fw ON fw.world_id=r.value LEFT JOIN knights k ON k.account_id=fw.account_id AND k.id=fw.knight_id LEFT JOIN world_characters sw ON sw.world_id=m.sender LEFT JOIN knights s ON s.account_id=sw.account_id AND s.id=sw.knight_id WHERE m.world_id=? ORDER BY m.sent DESC,m.id DESC LIMIT 500")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var held = new ArrayList<WorldProfile.Item>();
                    if (r.getString("item") != null) held.add(decode(r.getString("item")));
                    held.addAll(decodeItems(r.getString("attachments")));
                    messages.add(new AuctionState.Mail(
                            r.getLong("id"),
                            world,
                            r.getLong("sent"),
                            r.getString("subject"),
                            r.getString("body"),
                            r.getInt("crowns"),
                            r.getInt("energy"),
                            held,
                            r.getBoolean("claimed"),
                            r.getBoolean("is_read"),
                            r.getInt("friend_world"),
                            r.getString("friend_name"),
                            r.getInt("sender"),
                            r.getInt("mail_type"),
                            r.getString("sender_name"),
                            r.getLong("guild_invite_id"),
                            r.getString("guild_invite_name")));
                }
            }
        }
        return List.copyOf(messages);
    }

    static String encodeItems(List<WorldProfile.Item> items) throws IOException {
        var encoded = new ArrayList<String>(items.size());
        for (var item : items) encoded.add(encode(item));
        return Json.stringify(encoded);
    }

    @SuppressWarnings("unchecked")
    private static List<WorldProfile.Item> decodeItems(String json) throws IOException {
        if (json == null) return List.of();
        try {
            var encoded = (List<Object>) Json.parse(json);
            var items = new ArrayList<WorldProfile.Item>(encoded.size());
            for (var value : encoded) {
                if (!(value instanceof String text)) throw new IOException("Invalid mail attachment");
                items.add(decode(text));
            }
            return List.copyOf(items);
        } catch (RuntimeException bad) {
            throw new IOException("Invalid mail attachment", bad);
        }
    }

    static String encode(WorldProfile.Item i) throws IOException {
        var m = new TreeMap<String, Object>();
        m.put("id", i.id());
        m.put("expiresAt", i.expiresAt());
        m.put("gift", i.gift() == null ? null : GiftStore.encode(i.gift()));
        m.put("config", i.config());
        m.put("slot", i.slot().name());
        m.put("accessory", i.accessory());
        m.put("heat", i.heat());
        m.put("count", i.count());
        m.put("bound", i.bound());
        m.put("bindOnEquip", i.bindOnEquip());
        m.put("permanent", i.permanent());
        m.put("level", i.level());
        m.put("progress", i.heatProgress());
        m.put("accessories", i.additionalAccessories());
        m.put("variants", i.variants().stream().map(StarterCatalog.Model::name).toList());
        return Json.stringify(m);
    }

    @SuppressWarnings("unchecked")
    static WorldProfile.Item decode(String json) throws IOException {
        try {
            var m = (Map<String, Object>) Json.parse(json);
            return new WorldProfile.Item(
                            ((Number) m.getOrDefault("id", 0)).longValue(),
                            WorldProfile.Slot.valueOf((String) m.get("slot")),
                            (String) m.get("config"),
                            (String) m.get("accessory"),
                            ((Number) m.get("heat")).intValue(),
                            null,
                            (Boolean) m.get("bound"),
                            (Boolean) m.get("bindOnEquip"),
                            false,
                            ((Number) m.get("count")).intValue(),
                            (Boolean) m.get("permanent"),
                            ((Number) m.get("level")).intValue(),
                            ((Number) m.get("progress")).floatValue(),
                            ((List<String>) m.get("variants"))
                                    .stream()
                                            .map(v -> new StarterCatalog.Model(v, Map.of()))
                                            .toList(),
                            (List<String>) m.getOrDefault("accessories", List.of()))
                    .withGift(m.get("gift") == null ? null : GiftStore.decode((String) m.get("gift")))
                    .withExpiration(m.get("expiresAt") == null ? null : ((Number) m.get("expiresAt")).longValue());
        } catch (RuntimeException bad) {
            throw new IOException("Invalid escrow item", bad);
        }
    }
}
