package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.resources.Json;

/**
 * Administrative Uplink mail campaigns.
 *
 * <p>A campaign is one letter (literal subject/body, optional crowns, energy and item gifts) with
 * a start/end window. Each knight receives it at most once while the window is open, and knights
 * created after the campaign was saved receive it too: delivery happens lazily in the same
 * transaction that first serves mail (or selects the knight). The delivery marker and the mail row
 * commit together, so retries, reconnects and concurrent sessions cannot duplicate a gift.
 */
public final class MailCampaignStore {
    public static final int MAX_SUBJECT = 50;
    public static final int MAX_BODY = 8096;
    public static final int MAX_UNITS = 14;
    /** SystemSender ordinals that are meaningful for staff mail. */
    public static final List<Integer> SENDERS = List.of(4, 5, 6);

    /** One gift template; the item is materialised per delivery so expiry is relative to it. */
    public record Gift(String config, int count, boolean bound, int expireDays) { }

    public record Campaign(long id, long created, long updated, long starts, long ends,
            boolean enabled, int sender, String subject, String body, int crowns, int energy,
            List<Gift> gifts, int delivered) {
        public Campaign {
            gifts = List.copyOf(gifts);
        }
    }

    /** The optional once-per-knight welcome letter for knights created after it is enabled. */
    public record Welcome(boolean enabled, long enabledAt, long disabledAt, int sender, String subject,
            String body, long updated) { }

    private final Database database;
    private final ItemCatalog items;

    public MailCampaignStore(Database database, ItemCatalog items) {
        this.database = Objects.requireNonNull(database);
        this.items = Objects.requireNonNull(items);
    }

    /** Fresh databases and the explicit 16 to 17 upgrade share one idempotent creation. */
    static void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS mail_campaigns("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "created INTEGER NOT NULL,updated INTEGER NOT NULL,"
                + "starts INTEGER NOT NULL,ends INTEGER NOT NULL,"
                + "enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN(0,1)),"
                + "sender INTEGER NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,"
                + "crowns INTEGER NOT NULL DEFAULT 0 CHECK(crowns>=0),"
                + "energy INTEGER NOT NULL DEFAULT 0 CHECK(energy>=0),"
                + "gifts TEXT NOT NULL,CHECK(ends>starts))");
            s.execute("CREATE TABLE IF NOT EXISTS mail_campaign_deliveries("
                + "campaign INTEGER NOT NULL REFERENCES mail_campaigns(id) ON DELETE CASCADE,"
                + "world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,"
                + "sent INTEGER NOT NULL,PRIMARY KEY(campaign,world_id))");
            s.execute("CREATE TABLE IF NOT EXISTS mail_welcome("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN(0,1)),"
                + "enabled_at INTEGER NOT NULL DEFAULT 0,"
                + "disabled_at INTEGER NOT NULL DEFAULT 0,"
                + "sender INTEGER NOT NULL DEFAULT 6,subject TEXT NOT NULL DEFAULT '',"
                + "body TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS mail_welcome_deliveries("
                + "world_id INTEGER PRIMARY KEY REFERENCES world_characters(world_id) ON DELETE CASCADE,"
                + "sent INTEGER NOT NULL)");
        }
    }

    public List<Campaign> list() throws IOException {
        return database.read(c -> {
            var delivered = new HashMap<Long, Integer>();
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT campaign,COUNT(*) FROM mail_campaign_deliveries GROUP BY campaign")) {
                while (r.next()) delivered.put(r.getLong(1), r.getInt(2));
            }
            var campaigns = new ArrayList<Campaign>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM mail_campaigns ORDER BY id DESC")) {
                while (r.next()) {
                    campaigns.add(new Campaign(r.getLong("id"), r.getLong("created"), r.getLong("updated"),
                        r.getLong("starts"), r.getLong("ends"), r.getBoolean("enabled"), r.getInt("sender"),
                        r.getString("subject"), r.getString("body"), r.getInt("crowns"), r.getInt("energy"),
                        decodeGifts(r.getString("gifts")), delivered.getOrDefault(r.getLong("id"), 0)));
                }
            }
            return List.copyOf(campaigns);
        });
    }

    /** Creates ({@code id == 0}) or updates a campaign. {@code revision} is the previous updated stamp. */
    public Campaign save(long id, long revision, long starts, long ends, int sender, String subject,
            String body, int crowns, int energy, List<Gift> gifts) throws IOException {
        String cleanSubject = validateText(subject, MAX_SUBJECT, false, "Subject");
        String cleanBody = validateText(body, MAX_BODY, true, "Body");
        validateWindow(starts, ends);
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        if (crowns < 0 || energy < 0) throw new IOException("Crowns and energy cannot be negative");
        var merged = normalizeGifts(gifts);
        return database.write(c -> {
            long now = System.currentTimeMillis();
            if (id == 0) {
                if (revision != 0) throw new IOException("This mailing changed; reopen it");
                try (var s = c.prepareStatement("INSERT INTO mail_campaigns(created,updated,starts,ends,sender,subject,body,crowns,energy,gifts) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id")) {
                    s.setLong(1, now); s.setLong(2, now); s.setLong(3, starts); s.setLong(4, ends);
                    s.setInt(5, sender); s.setString(6, cleanSubject); s.setString(7, cleanBody);
                    s.setInt(8, crowns); s.setInt(9, energy); s.setString(10, encodeGifts(merged));
                    try (var r = s.executeQuery()) {
                        r.next();
                        return find(c, r.getLong(1));
                    }
                }
            }
            try (var s = c.prepareStatement("UPDATE mail_campaigns SET updated=?,starts=?,ends=?,sender=?,subject=?,body=?,crowns=?,energy=?,gifts=? WHERE id=? AND updated=? AND enabled=1")) {
                s.setLong(1, now); s.setLong(2, starts); s.setLong(3, ends); s.setInt(4, sender);
                s.setString(5, cleanSubject); s.setString(6, cleanBody); s.setInt(7, crowns); s.setInt(8, energy);
                s.setString(9, encodeGifts(merged)); s.setLong(10, id); s.setLong(11, revision);
                if (s.executeUpdate() != 1) throw new IOException("This mailing changed or was cancelled; reopen it");
            }
            return find(c, id);
        });
    }

    /** Stops future deliveries; mail already in an inbox stays there. */
    public Campaign cancel(long id, long revision) throws IOException {
        return database.write(c -> {
            try (var s = c.prepareStatement("UPDATE mail_campaigns SET enabled=0,updated=? WHERE id=? AND updated=? AND enabled=1")) {
                s.setLong(1, System.currentTimeMillis()); s.setLong(2, id); s.setLong(3, revision);
                if (s.executeUpdate() != 1) throw new IOException("This mailing changed or was already cancelled; reopen it");
            }
            return find(c, id);
        });
    }

    /** Delivers every currently active campaign once; called on selection before mail is served. */
    public boolean deliver(int world) throws IOException {
        long now = System.currentTimeMillis();
        boolean active = database.read(c -> {
            try (var s = c.prepareStatement("SELECT 1 FROM mail_campaigns WHERE enabled=1 AND starts<=? AND ends>? LIMIT 1")) {
                s.setLong(1, now); s.setLong(2, now);
                try (var r = s.executeQuery()) { return r.next(); }
            }
        });
        return active && database.write(c -> deliver(c, world));
    }

    /** Delivery inside a caller-owned transaction (the mail invocation). */
    boolean deliver(Connection c, int world) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        var active = new ArrayList<Campaign>();
        try (var s = c.prepareStatement("SELECT * FROM mail_campaigns WHERE enabled=1 AND starts<=? AND ends>? ORDER BY id")) {
            s.setLong(1, now); s.setLong(2, now);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    active.add(new Campaign(r.getLong("id"), r.getLong("created"), r.getLong("updated"),
                        r.getLong("starts"), r.getLong("ends"), true, r.getInt("sender"),
                        r.getString("subject"), r.getString("body"), r.getInt("crowns"), r.getInt("energy"),
                        decodeGifts(r.getString("gifts")), 0));
                }
            }
        }
        boolean delivered = false;
        for (var campaign : active) {
            try (var s = c.prepareStatement("INSERT INTO mail_campaign_deliveries(campaign,world_id,sent) VALUES(?,?,?) ON CONFLICT DO NOTHING")) {
                s.setLong(1, campaign.id()); s.setInt(2, world); s.setLong(3, now);
                s.executeUpdate();
            }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT changes()")) {
                r.next();
                if (r.getInt(1) == 0) continue;
            }
            var gifts = materialise(campaign, now);
            try (var s = c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,?,?,3)")) {
                s.setInt(1, world); s.setLong(2, now);
                s.setString(3, "%uplink:~" + campaign.subject());
                s.setString(4, "%uplink:~" + campaign.body());
                s.setInt(5, campaign.crowns()); s.setInt(6, campaign.energy());
                s.setString(7, gifts.isEmpty() ? null : AuctionHouse.encodeItems(gifts));
                s.setInt(8, -campaign.sender());
                s.executeUpdate();
            }
            delivered = true;
        }
        return delivered;
    }

    public Welcome welcome() throws IOException {
        return database.read(c -> readWelcome(c));
    }

    /** Saves the welcome letter. Enabling starts a new eligibility clock; edits keep it. */
    public Welcome saveWelcome(boolean enabled, int sender, String subject, String body) throws IOException {
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        // A knight created during an earlier enabled period can still receive the letter
        // after it is switched off, so keep usable text once it has ever been enabled.
        boolean textRequired = enabled || welcome().enabledAt() > 0;
        String cleanSubject = textRequired ? validateText(subject, MAX_SUBJECT, false, "Subject") : nullToEmpty(subject).strip();
        String cleanBody = textRequired ? validateText(body, MAX_BODY, true, "Body") : nullToEmpty(body).strip();
        if (cleanSubject.length() > MAX_SUBJECT || cleanBody.length() > MAX_BODY)
            throw new IOException("Welcome mail is too long");
        return database.write(c -> {
            long now = System.currentTimeMillis();
            var previous = readWelcome(c);
            long enabledAt = enabled ? previous.enabled() ? previous.enabledAt() : now : previous.enabledAt();
            long disabledAt = enabled ? 0 : previous.enabled() ? now : previous.disabledAt();
            try (var s = c.prepareStatement("INSERT INTO mail_welcome(id,enabled,enabled_at,disabled_at,sender,subject,body,updated) VALUES(1,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,enabled_at=excluded.enabled_at,disabled_at=excluded.disabled_at,sender=excluded.sender,subject=excluded.subject,body=excluded.body,updated=excluded.updated")) {
                s.setBoolean(1, enabled); s.setLong(2, enabledAt); s.setLong(3, disabledAt);
                s.setInt(4, sender); s.setString(5, cleanSubject); s.setString(6, cleanBody);
                s.setLong(7, now);
                s.executeUpdate();
            }
            return readWelcome(c);
        });
    }

    /** One-off staff mail to a single knight, online or offline; the inbox row is the delivery. */
    public long sendTo(int world, int sender, String subject, String body, int crowns, int energy,
            List<Gift> gifts) throws IOException {
        String cleanSubject = validateText(subject, MAX_SUBJECT, false, "Subject");
        String cleanBody = validateText(body, MAX_BODY, true, "Body");
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        if (crowns < 0 || energy < 0) throw new IOException("Crowns and energy cannot be negative");
        var merged = normalizeGifts(gifts);
        return database.write(c -> {
            try (var s = c.prepareStatement("SELECT 1 FROM world_characters WHERE world_id=?")) {
                s.setInt(1, world);
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new IOException("Unknown knight");
                }
            }
            long now = System.currentTimeMillis();
            var mail = materialise(merged, now);
            try (var s = c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,?,?,3) RETURNING id")) {
                s.setInt(1, world); s.setLong(2, now);
                s.setString(3, "%uplink:~" + cleanSubject); s.setString(4, "%uplink:~" + cleanBody);
                s.setInt(5, crowns); s.setInt(6, energy);
                s.setString(7, mail.isEmpty() ? null : AuctionHouse.encodeItems(mail));
                s.setInt(8, -sender);
                try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
            }
        });
    }

    /** Delivers the welcome letter once to a knight created after it was enabled. */
    public boolean deliverWelcome(int world, long createdAt) throws IOException {
        var letter = welcome();
        if (letter.enabledAt() == 0 || createdAt < letter.enabledAt()) return false;
        if (!letter.enabled() && letter.disabledAt() > 0 && createdAt >= letter.disabledAt()) return false;
        return database.write(c -> welcome(c, world, createdAt));
    }

    private boolean welcome(Connection c, int world, long createdAt) throws SQLException, IOException {
        var letter = readWelcome(c);
        // Eligibility follows the enabled period the knight was created in, even if the
        // welcome letter has since been disabled before the knight's first visit.
        if (letter.enabledAt() == 0 || createdAt < letter.enabledAt()) return false;
        if (!letter.enabled() && letter.disabledAt() > 0 && createdAt >= letter.disabledAt()) return false;
        try (var s = c.prepareStatement("INSERT INTO mail_welcome_deliveries(world_id,sent) VALUES(?,?) ON CONFLICT DO NOTHING")) {
            s.setInt(1, world); s.setLong(2, System.currentTimeMillis()); s.executeUpdate();
        }
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT changes()")) {
            r.next();
            if (r.getInt(1) == 0) return false;
        }
        long now = System.currentTimeMillis();
        try (var s = c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,sender,mail_type) VALUES(?,?,?,?,?,3)")) {
            s.setInt(1, world); s.setLong(2, now);
            s.setString(3, "%uplink:~" + letter.subject()); s.setString(4, "%uplink:~" + letter.body());
            s.setInt(5, -letter.sender());
            s.executeUpdate();
        }
        return true;
    }

    private static Welcome readWelcome(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM mail_welcome WHERE id=1")) {
            if (!r.next()) return new Welcome(false, 0, 0, 6, "", "", 0);
            return new Welcome(r.getBoolean("enabled"), r.getLong("enabled_at"),
                r.getLong("disabled_at"), r.getInt("sender"), r.getString("subject"),
                r.getString("body"), r.getLong("updated"));
        }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private List<WorldProfile.Item> materialise(List<Gift> gifts, long now) throws IOException {
        var result = new ArrayList<WorldProfile.Item>(gifts.size());
        for (var gift : gifts) {
            var entry = items.find(gift.config());
            if (entry == null || !entry.grantable()) continue;
            var item = new WorldProfile.Item(0, entry.slot(), entry.config(), null, 0, null,
                gift.bound(), entry.bindOnEquip(), false, gift.count(), entry.permanent(), 0, 0f,
                List.of(), List.of());
            if (gift.expireDays() > 0) item = item.withExpiration(now + gift.expireDays() * 86_400_000L);
            result.add(item);
        }
        return result;
    }

    private List<WorldProfile.Item> materialise(Campaign campaign, long now) throws IOException {
        // Content can be replaced between saving a campaign and a later delivery. A removed
        // or no-longer-grantable item must not block the knight's login or the rest of the
        // letter; the other gifts and text still arrive.
        return materialise(campaign.gifts(), now);
    }

    private static Campaign find(Connection c, long id) throws SQLException, IOException {
        try (var s = c.prepareStatement("SELECT * FROM mail_campaigns WHERE id=?")) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IOException("Unknown mailing");
                int delivered;
                try (var d = c.prepareStatement("SELECT COUNT(*) FROM mail_campaign_deliveries WHERE campaign=?")) {
                    d.setLong(1, id);
                    try (var count = d.executeQuery()) { count.next(); delivered = count.getInt(1); }
                }
                return new Campaign(r.getLong("id"), r.getLong("created"), r.getLong("updated"),
                    r.getLong("starts"), r.getLong("ends"), r.getBoolean("enabled"), r.getInt("sender"),
                    r.getString("subject"), r.getString("body"), r.getInt("crowns"), r.getInt("energy"),
                    decodeGifts(r.getString("gifts")), delivered);
            }
        }
    }

    private static void validateWindow(long starts, long ends) throws IOException {
        if (starts < 0 || ends < 0) throw new IOException("Choose a valid delivery window");
        if (ends <= starts) throw new IOException("The mailing must end after it starts");
        if (ends - starts > 10L * 366 * 86_400_000L) throw new IOException("The delivery window is too long");
    }

    private static String validateText(String value, int limit, boolean multiline, String label) throws IOException {
        if (value == null) throw new IOException(label + " is required");
        String text = value.strip();
        if (text.isEmpty()) throw new IOException(label + " is required");
        if (text.length() > limit) throw new IOException(label + " must be at most " + limit + " characters");
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\n' || multiline && ch == '\t') continue;
            if (Character.isISOControl(ch)) throw new IOException(label + " contains unsupported control characters");
        }
        return text;
    }

    /** Merges identical gifts and enforces the client's fourteen-unit attachment budget. */
    private List<Gift> normalizeGifts(List<Gift> gifts) throws IOException {
        if (gifts == null || gifts.size() > MAX_UNITS) throw new IOException("Add at most " + MAX_UNITS + " gift stacks");
        var merged = new LinkedHashMap<String, Gift>();
        int units = 0;
        for (var gift : gifts) {
            if (gift == null || gift.config() == null || gift.config().isBlank()) throw new IOException("Choose a gift item");
            if (gift.count() < 1 || gift.count() > 999) throw new IOException("Gift quantities must be between 1 and 999");
            if (gift.expireDays() < 0 || gift.expireDays() > 3650) throw new IOException("Gift expiry must be between 0 and 3650 days");
            var entry = items.find(gift.config());
            if (entry == null || !entry.grantable()) throw new IOException("Gift is not grantable: " + gift.config());
            units += gift.count();
            if (units > MAX_UNITS) throw new IOException("A mailing can carry at most " + MAX_UNITS + " item units");
            String key = gift.config() + "\u0000" + gift.bound() + "\u0000" + gift.expireDays();
            var prior = merged.get(key);
            merged.put(key, prior == null ? gift
                : new Gift(prior.config(), prior.count() + gift.count(), prior.bound(), prior.expireDays()));
        }
        return List.copyOf(merged.values());
    }

    private static String encodeGifts(List<Gift> gifts) throws IOException {
        var rows = new ArrayList<Map<String, Object>>(gifts.size());
        for (var gift : gifts) {
            rows.add(Map.of("config", gift.config(), "count", gift.count(), "bound", gift.bound(),
                "expireDays", gift.expireDays()));
        }
        return Json.stringify(rows);
    }

    @SuppressWarnings("unchecked")
    private static List<Gift> decodeGifts(String json) throws IOException {
        if (json == null || json.isBlank()) return List.of();
        try {
            var rows = (List<Object>) Json.parse(json);
            var gifts = new ArrayList<Gift>(rows.size());
            for (var row : rows) {
                var map = (Map<String, Object>) row;
                gifts.add(new Gift((String) map.get("config"), ((Number) map.get("count")).intValue(),
                    Boolean.TRUE.equals(map.get("bound")), ((Number) map.get("expireDays")).intValue()));
            }
            return List.copyOf(gifts);
        } catch (RuntimeException bad) {
            throw new IOException("Invalid stored mail gifts", bad);
        }
    }
}
