package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;

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
    public static final int MAX_SENDER_LABEL = 40;
    /** SystemSender ordinals that are meaningful for staff mail. */
    public static final List<Integer> SENDERS = List.of(4, 5, 6);

    /** One gift template; the item is materialised per delivery and never expires in the inbox. */
    public record Gift(String config, int count, boolean bound) {}

    public record Campaign(
            long id,
            long created,
            long updated,
            long starts,
            long ends,
            boolean enabled,
            int sender,
            String senderLabel,
            String subject,
            String body,
            int crowns,
            int energy,
            List<Gift> gifts,
            int delivered) {
        public Campaign {
            gifts = List.copyOf(gifts);
        }
    }

    /** The optional once-per-knight welcome letter for knights created after it is enabled. */
    public record Welcome(
            boolean enabled,
            long enabledAt,
            long disabledAt,
            int sender,
            String senderLabel,
            String subject,
            String body,
            int crowns,
            int energy,
            List<Gift> gifts,
            long updated) {
        public Welcome {
            gifts = List.copyOf(gifts);
        }
    }

    private final Database database;
    private final ItemCatalog items;
    private final StaffMail staff;

    public MailCampaignStore(Database database, ItemCatalog items) {
        this.database = Objects.requireNonNull(database);
        this.items = Objects.requireNonNull(items);
        this.staff = new StaffMail(this.items);
    }

    /** Mail campaign tables for the 0.42.0 baseline. */
    static void initialize(Connection c) throws SQLException {
        MailCampaignSchema.initialize(c);
    }

    public List<Campaign> list() throws IOException {
        return database.read(c -> {
            var delivered = new HashMap<Long, Integer>();
            try (var s = c.createStatement();
                    var r = s.executeQuery(
                            "SELECT campaign,COUNT(*) FROM mail_campaign_deliveries GROUP BY campaign")) {
                while (r.next()) delivered.put(r.getLong(1), r.getInt(2));
            }
            var campaigns = new ArrayList<Campaign>();
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT * FROM mail_campaigns ORDER BY id DESC")) {
                while (r.next()) {
                    campaigns.add(new Campaign(
                            r.getLong("id"),
                            r.getLong("created"),
                            r.getLong("updated"),
                            r.getLong("starts"),
                            r.getLong("ends"),
                            r.getBoolean("enabled"),
                            r.getInt("sender"),
                            r.getString("sender_label"),
                            r.getString("subject"),
                            r.getString("body"),
                            r.getInt("crowns"),
                            r.getInt("energy"),
                            MailCampaignRules.decodeGifts(r.getString("gifts")),
                            delivered.getOrDefault(r.getLong("id"), 0)));
                }
            }
            return List.copyOf(campaigns);
        });
    }

    /** Creates ({@code id == 0}) or updates a campaign. {@code revision} is the previous updated stamp. */
    public Campaign save(
            long id,
            long revision,
            long starts,
            long ends,
            int sender,
            String senderLabel,
            String subject,
            String body,
            int crowns,
            int energy,
            List<Gift> gifts)
            throws IOException {
        String cleanSubject = MailCampaignRules.validateText(subject, MAX_SUBJECT, false, "Subject");
        String cleanBody = MailCampaignRules.validateText(body, MAX_BODY, true, "Body");
        MailCampaignRules.validateWindow(starts, ends);
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        String cleanSender = MailCampaignRules.validateSenderLabel(senderLabel);
        if (crowns < 0 || energy < 0) throw new IOException("Crowns and energy cannot be negative");
        var merged = MailCampaignRules.normalizeGifts(items, gifts);
        return database.write(c -> {
            long now = System.currentTimeMillis();
            if (id == 0) {
                if (revision != 0) throw new IOException("This mailing changed; reopen it");
                try (var s = c.prepareStatement(
                        "INSERT INTO mail_campaigns(created,updated,starts,ends,sender,sender_label,subject,body,crowns,energy,gifts) VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id")) {
                    s.setLong(1, now);
                    s.setLong(2, now);
                    s.setLong(3, starts);
                    s.setLong(4, ends);
                    s.setInt(5, sender);
                    s.setString(6, cleanSender);
                    s.setString(7, cleanSubject);
                    s.setString(8, cleanBody);
                    s.setInt(9, crowns);
                    s.setInt(10, energy);
                    s.setString(11, MailCampaignRules.encodeGifts(merged));
                    try (var r = s.executeQuery()) {
                        r.next();
                        return find(c, r.getLong(1));
                    }
                }
            }
            try (var s = c.prepareStatement(
                    "UPDATE mail_campaigns SET updated=?,starts=?,ends=?,sender=?,sender_label=?,subject=?,body=?,crowns=?,energy=?,gifts=? WHERE id=? AND updated=? AND enabled=1")) {
                s.setLong(1, now);
                s.setLong(2, starts);
                s.setLong(3, ends);
                s.setInt(4, sender);
                s.setString(5, cleanSender);
                s.setString(6, cleanSubject);
                s.setString(7, cleanBody);
                s.setInt(8, crowns);
                s.setInt(9, energy);
                s.setString(10, MailCampaignRules.encodeGifts(merged));
                s.setLong(11, id);
                s.setLong(12, revision);
                if (s.executeUpdate() != 1) throw new IOException("This mailing changed or was cancelled; reopen it");
            }
            return find(c, id);
        });
    }

    /** Stops future deliveries; mail already in an inbox stays there. */
    public Campaign cancel(long id, long revision) throws IOException {
        return database.write(c -> {
            try (var s = c.prepareStatement(
                    "UPDATE mail_campaigns SET enabled=0,updated=? WHERE id=? AND updated=? AND enabled=1")) {
                s.setLong(1, System.currentTimeMillis());
                s.setLong(2, id);
                s.setLong(3, revision);
                if (s.executeUpdate() != 1)
                    throw new IOException("This mailing changed or was already cancelled; reopen it");
            }
            return find(c, id);
        });
    }

    /** Delivers every currently active campaign once; called on selection before mail is served. */
    public boolean deliver(int world) throws IOException {
        long now = System.currentTimeMillis();
        boolean active = database.read(c -> {
            try (var s = c.prepareStatement(
                    "SELECT 1 FROM mail_campaigns WHERE enabled=1 AND starts<=? AND ends>? LIMIT 1")) {
                s.setLong(1, now);
                s.setLong(2, now);
                try (var r = s.executeQuery()) {
                    return r.next();
                }
            }
        });
        return active && database.write(c -> deliver(c, world));
    }

    /** Delivery inside a caller-owned transaction (the mail invocation). */
    boolean deliver(Connection c, int world) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        var active = new ArrayList<Campaign>();
        try (var s = c.prepareStatement(
                "SELECT * FROM mail_campaigns WHERE enabled=1 AND starts<=? AND ends>? ORDER BY id")) {
            s.setLong(1, now);
            s.setLong(2, now);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    active.add(new Campaign(
                            r.getLong("id"),
                            r.getLong("created"),
                            r.getLong("updated"),
                            r.getLong("starts"),
                            r.getLong("ends"),
                            true,
                            r.getInt("sender"),
                            r.getString("sender_label"),
                            r.getString("subject"),
                            r.getString("body"),
                            r.getInt("crowns"),
                            r.getInt("energy"),
                            MailCampaignRules.decodeGifts(r.getString("gifts")),
                            0));
                }
            }
        }
        boolean delivered = false;
        for (var campaign : active) {
            try (var s = c.prepareStatement(
                    "INSERT INTO mail_campaign_deliveries(campaign,world_id,sent) VALUES(?,?,?) ON CONFLICT DO NOTHING")) {
                s.setLong(1, campaign.id());
                s.setInt(2, world);
                s.setLong(3, now);
                s.executeUpdate();
            }
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT changes()")) {
                r.next();
                if (r.getInt(1) == 0) continue;
            }
            staff.deliver(
                    c,
                    world,
                    now,
                    campaign.subject(),
                    campaign.body(),
                    campaign.crowns(),
                    campaign.energy(),
                    staff.materialise(campaign.gifts()),
                    campaign.sender(),
                    campaign.senderLabel());
            delivered = true;
        }
        return delivered;
    }

    public Welcome welcome() throws IOException {
        return database.read(c -> readWelcome(c));
    }

    /** Saves the welcome letter. Enabling starts a new eligibility clock; edits keep it. */
    public Welcome saveWelcome(
            boolean enabled,
            int sender,
            String senderLabel,
            String subject,
            String body,
            int crowns,
            int energy,
            List<Gift> gifts)
            throws IOException {
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        String cleanSender = MailCampaignRules.validateSenderLabel(senderLabel);
        if (crowns < 0 || energy < 0) throw new IOException("Crowns and energy cannot be negative");
        var merged = MailCampaignRules.normalizeGifts(items, gifts);
        // A knight created during an earlier enabled period can still receive the letter
        // after it is switched off, so keep usable text once it has ever been enabled.
        boolean textRequired = enabled || welcome().enabledAt() > 0;
        String cleanSubject = textRequired
                ? MailCampaignRules.validateText(subject, MAX_SUBJECT, false, "Subject")
                : nullToEmpty(subject).strip();
        String cleanBody = textRequired
                ? MailCampaignRules.validateText(body, MAX_BODY, true, "Body")
                : nullToEmpty(body).strip();
        if (cleanSubject.length() > MAX_SUBJECT || cleanBody.length() > MAX_BODY)
            throw new IOException("Welcome mail is too long");
        return database.write(c -> {
            long now = System.currentTimeMillis();
            var previous = readWelcome(c);
            long enabledAt = enabled ? previous.enabled() ? previous.enabledAt() : now : previous.enabledAt();
            long disabledAt = enabled ? 0 : previous.enabled() ? now : previous.disabledAt();
            try (var s = c.prepareStatement(
                    "INSERT INTO mail_welcome(id,enabled,enabled_at,disabled_at,sender,sender_label,subject,body,crowns,energy,gifts,updated) VALUES(1,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,enabled_at=excluded.enabled_at,disabled_at=excluded.disabled_at,sender=excluded.sender,sender_label=excluded.sender_label,subject=excluded.subject,body=excluded.body,crowns=excluded.crowns,energy=excluded.energy,gifts=excluded.gifts,updated=excluded.updated")) {
                s.setBoolean(1, enabled);
                s.setLong(2, enabledAt);
                s.setLong(3, disabledAt);
                s.setInt(4, sender);
                s.setString(5, cleanSender);
                s.setString(6, cleanSubject);
                s.setString(7, cleanBody);
                s.setInt(8, crowns);
                s.setInt(9, energy);
                s.setString(10, MailCampaignRules.encodeGifts(merged));
                s.setLong(11, now);
                s.executeUpdate();
            }
            return readWelcome(c);
        });
    }

    /** One-off staff mail to a single knight, online or offline; the inbox row is the delivery. */
    public long sendTo(
            int world,
            int sender,
            String senderLabel,
            String subject,
            String body,
            int crowns,
            int energy,
            List<Gift> gifts)
            throws IOException {
        String cleanSubject = MailCampaignRules.validateText(subject, MAX_SUBJECT, false, "Subject");
        String cleanBody = MailCampaignRules.validateText(body, MAX_BODY, true, "Body");
        if (!SENDERS.contains(sender)) throw new IOException("Choose a supported sender");
        String cleanSender = MailCampaignRules.validateSenderLabel(senderLabel);
        if (crowns < 0 || energy < 0) throw new IOException("Crowns and energy cannot be negative");
        var merged = MailCampaignRules.normalizeGifts(items, gifts);
        return database.write(c -> {
            try (var s = c.prepareStatement("SELECT 1 FROM world_characters WHERE world_id=?")) {
                s.setInt(1, world);
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new IOException("Unknown knight");
                }
            }
            long now = System.currentTimeMillis();
            return staff.deliver(
                    c,
                    world,
                    now,
                    cleanSubject,
                    cleanBody,
                    crowns,
                    energy,
                    staff.materialise(merged),
                    sender,
                    cleanSender);
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
        try (var s = c.prepareStatement(
                "INSERT INTO mail_welcome_deliveries(world_id,sent) VALUES(?,?) ON CONFLICT DO NOTHING")) {
            s.setInt(1, world);
            s.setLong(2, System.currentTimeMillis());
            s.executeUpdate();
        }
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT changes()")) {
            r.next();
            if (r.getInt(1) == 0) return false;
        }
        long now = System.currentTimeMillis();
        staff.deliver(
                c,
                world,
                now,
                letter.subject(),
                letter.body(),
                letter.crowns(),
                letter.energy(),
                staff.materialise(letter.gifts()),
                letter.sender(),
                letter.senderLabel());
        return true;
    }

    private static Welcome readWelcome(Connection c) throws SQLException, IOException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT * FROM mail_welcome WHERE id=1")) {
            if (!r.next()) return new Welcome(false, 0, 0, 6, null, "", "", 0, 0, List.of(), 0);
            return new Welcome(
                    r.getBoolean("enabled"),
                    r.getLong("enabled_at"),
                    r.getLong("disabled_at"),
                    r.getInt("sender"),
                    r.getString("sender_label"),
                    r.getString("subject"),
                    r.getString("body"),
                    r.getInt("crowns"),
                    r.getInt("energy"),
                    MailCampaignRules.decodeGifts(r.getString("gifts")),
                    r.getLong("updated"));
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Campaign find(Connection c, long id) throws SQLException, IOException {
        try (var s = c.prepareStatement("SELECT * FROM mail_campaigns WHERE id=?")) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IOException("Unknown mailing");
                int delivered;
                try (var d = c.prepareStatement("SELECT COUNT(*) FROM mail_campaign_deliveries WHERE campaign=?")) {
                    d.setLong(1, id);
                    try (var count = d.executeQuery()) {
                        count.next();
                        delivered = count.getInt(1);
                    }
                }
                return new Campaign(
                        r.getLong("id"),
                        r.getLong("created"),
                        r.getLong("updated"),
                        r.getLong("starts"),
                        r.getLong("ends"),
                        r.getBoolean("enabled"),
                        r.getInt("sender"),
                        r.getString("sender_label"),
                        r.getString("subject"),
                        r.getString("body"),
                        r.getInt("crowns"),
                        r.getInt("energy"),
                        MailCampaignRules.decodeGifts(r.getString("gifts")),
                        delivered);
            }
        }
    }
}
