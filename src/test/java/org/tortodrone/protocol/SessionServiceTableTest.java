package org.tortodrone.protocol;

import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** The bootstrap service table is the single source of truth for session service handles. */
public final class SessionServiceTableTest {
    public static void main(String[] args) {
        Map<Integer,String> expected = Map.ofEntries(
            Map.entry(SessionCodec.KNIGHT_SERVICE, "com.threerings.projectx.data.KnightMarshaller"),
            Map.entry(SessionCodec.CHAT_SERVICE, "com.threerings.crowd.chat.data.ChatMarshaller"),
            Map.entry(SessionCodec.SCENE_SERVICE, "com.threerings.whirled.data.SceneMarshaller"),
            Map.entry(SessionCodec.ZONE_SERVICE, "com.threerings.whirled.zone.data.ZoneMarshaller"),
            Map.entry(SessionCodec.ITEM_SERVICE, "com.threerings.projectx.item.data.ItemMarshaller"),
            Map.entry(SessionCodec.UPLINK_SERVICE, "com.threerings.projectx.uplink.data.UplinkMarshaller"),
            Map.entry(SessionCodec.FEED_SERVICE, "com.threerings.projectx.social.data.FeedMarshaller"),
            Map.entry(SessionCodec.SOCIAL_SERVICE, "com.threerings.projectx.social.data.SocialMarshaller"),
            Map.entry(SessionCodec.ADMIN_SERVICE, "com.threerings.projectx.admin.data.ProjectXAdminMarshaller"),
            Map.entry(SessionCodec.BATTLE_SPRITE_SERVICE, "com.threerings.projectx.sprites.data.BattleSpriteMarshaller"),
            Map.entry(SessionCodec.AUCTION_SERVICE, "com.threerings.projectx.auction.data.AuctionMarshaller"),
            Map.entry(SessionCodec.MISSION_SERVICE, "com.threerings.projectx.mission.data.MissionMarshaller"),
            Map.entry(SessionCodec.BILLING_SERVICE, "com.threerings.projectx.billing.data.BillingMarshaller"),
            Map.entry(SessionCodec.EVENT_LOG_SERVICE, "com.threerings.projectx.data.EventLogMarshaller"),
            Map.entry(SessionCodec.LOVE_SERVICE, "com.threerings.projectx.love.data.LoveMarshaller"));
        for (var service : expected.entrySet()) {
            check(service.getKey() > 0 && service.getKey() <= SessionCodec.SERVICES.size(),
                "service handle is within the bootstrap table: " + service.getValue());
            check(SessionCodec.SERVICES.get(service.getKey() - 1).equals(service.getValue()),
                "service handle resolves to its marshaller: " + service.getValue());
        }
        check(new HashSet<>(SessionCodec.SERVICES).size() == SessionCodec.SERVICES.size(),
            "bootstrap service table has distinct marshallers");
        check(SessionCodec.SERVICES.size() == 19, "current bootstrap service count");
        System.out.println("PASS: bootstrap service handles derive from one ordered table");
    }
}
