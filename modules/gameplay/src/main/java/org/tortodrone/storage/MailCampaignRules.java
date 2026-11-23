package org.tortodrone.storage;

import java.io.IOException;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;

/** Validation and gift encoding for administrative mail campaigns. */
final class MailCampaignRules {
    private MailCampaignRules() { }

    static void validateWindow(long starts, long ends) throws IOException {
        if (starts < 0 || ends < 0) throw new IOException("Choose a valid delivery window");
        if (ends <= starts) throw new IOException("The mailing must end after it starts");
        if (ends - starts > 10L * 366 * 86_400_000L) throw new IOException("The delivery window is too long");
    }

    static String validateSenderLabel(String value) throws IOException {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith(" ") || value.chars().anyMatch(Character::isISOControl))
            throw new IOException("Sender name contains unsupported characters");
        String label = value.strip();
        if (label.length() > MailCampaignStore.MAX_SENDER_LABEL) throw new IOException("Sender name must be at most " + MailCampaignStore.MAX_SENDER_LABEL + " characters");
        return label;
    }

    static String validateText(String value, int limit, boolean multiline, String label) throws IOException {
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
    static List<MailCampaignStore.Gift> normalizeGifts(ItemCatalog items,List<MailCampaignStore.Gift> gifts) throws IOException {
        if (gifts == null || gifts.size() > MailCampaignStore.MAX_UNITS) throw new IOException("Add at most " + MailCampaignStore.MAX_UNITS + " gift stacks");
        var merged = new LinkedHashMap<String, MailCampaignStore.Gift>();
        int units = 0;
        for (var gift : gifts) {
            if (gift == null || gift.config() == null || gift.config().isBlank()) throw new IOException("Choose a gift item");
            if (gift.count() < 1 || gift.count() > 999) throw new IOException("Gift quantities must be between 1 and 999");
            var entry = items.find(gift.config());
            if (entry == null || !entry.grantable()) throw new IOException("Gift is not grantable: " + gift.config());
            units += gift.count();
            if (units > MailCampaignStore.MAX_UNITS) throw new IOException("A mailing can carry at most " + MailCampaignStore.MAX_UNITS + " item units");
            String key = gift.config() + "\u0000" + gift.bound();
            var prior = merged.get(key);
            merged.put(key, prior == null ? gift
                : new MailCampaignStore.Gift(prior.config(), prior.count() + gift.count(), prior.bound()));
        }
        return List.copyOf(merged.values());
    }

    static String encodeGifts(List<MailCampaignStore.Gift> gifts) throws IOException {
        var rows = new ArrayList<Map<String, Object>>(gifts.size());
        for (var gift : gifts) {
            rows.add(Map.of("config", gift.config(), "count", gift.count(), "bound", gift.bound()));
        }
        return Json.stringify(rows);
    }

    @SuppressWarnings("unchecked")
    static List<MailCampaignStore.Gift> decodeGifts(String json) throws IOException {
        if (json == null || json.isBlank()) return List.of();
        try {
            var rows = (List<Object>) Json.parse(json);
            var gifts = new ArrayList<MailCampaignStore.Gift>(rows.size());
            for (var row : rows) {
                var map = (Map<String, Object>) row;
                gifts.add(new MailCampaignStore.Gift((String) map.get("config"), ((Number) map.get("count")).intValue(),
                    Boolean.TRUE.equals(map.get("bound"))));
            }
            return List.copyOf(gifts);
        } catch (RuntimeException bad) {
            throw new IOException("Invalid stored mail gifts", bad);
        }
    }
}
