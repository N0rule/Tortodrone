package org.tortodrone.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.character.Knight;
import org.tortodrone.character.StarterCatalog;

/** Downstream session messages after the secure and authentication responses. */
public final class SessionCodec {
    public static final int SERVER_OID = 1;
    public static final int CONFIG_OID = 2;
    public static final int INVOCATION_OID = 3;
    /** Handles needed by current login observers; operations remain explicitly limited. */
    public static final java.util.List<String> SERVICES = java.util.List.of(
        "com.threerings.projectx.data.KnightMarshaller",
        "com.threerings.crowd.data.LocationMarshaller",
        "com.threerings.crowd.chat.data.ChatMarshaller",
        "com.threerings.whirled.data.SceneMarshaller",
        "com.threerings.whirled.zone.data.ZoneMarshaller",
        "com.threerings.projectx.data.ProjectXSceneMarshaller",
        "com.threerings.projectx.data.ProjectXZoneMarshaller",
        "com.threerings.crowd.data.BodyMarshaller");
    private static final String PREFIX = "com.threerings.presents.net.";
    private final Map<String, Integer> classes = new HashMap<>();
    private final Map<String, Integer> interns = new HashMap<>();

    public SessionCodec() {
        // HandshakeCodec has already introduced these five downstream mappings.
        classes.put(PREFIX + "SecureResponse", 1);
        classes.put(PREFIX + "AuthResponseData", 2);
        classes.put(PREFIX + "AuthResponse", 3);
        classes.put("com.threerings.projectx.data.ProjectXAuthResponseData", 4);
        classes.put("com.threerings.util.Name", 5);
    }

    public byte[] bootstrap(int connectionId, int clientOid, String host, int port) throws IOException {
        if (connectionId <= 0 || clientOid <= 0 || port < 1 || port > 65535 || host == null || host.isBlank()) {
            throw new IOException("Invalid bootstrap endpoint or identity");
        }
        return frame(out -> {
            type(out, PREFIX + "BootstrapNotification");
            out.writeShort(-1); // Unsolicited downstream notification.
            type(out, "com.threerings.projectx.data.ProjectXBootstrapData");
            out.writeInt(connectionId);
            out.writeInt(clientOid);
            out.writeBoolean(true); out.writeInt(SERVICES.size());
            for (int i = 0; i < SERVICES.size(); i++) {
                type(out, SERVICES.get(i));
                out.writeInt(INVOCATION_OID); out.writeInt(i + 1);
            }
            out.writeInt(CONFIG_OID);
            out.writeBoolean(true); out.writeUTF(host);
            out.writeBoolean(true); out.writeInt(1); out.writeInt(port);
            out.writeBoolean(true); out.writeInt(0); // No datagram endpoints advertised.
            // Current build: server, exchange, PvP, PvP info, mission, open hall,
            // admin, status, place browser, client timings. No nodePulseOid field.
            out.writeInt(SERVER_OID);
            for (int i = 1; i < 10; i++) out.writeInt(0);
        });
    }

    public byte[] player(short messageId, int oid, int playerId, String username) throws IOException {
        return player(messageId, oid, playerId, username, Map.of());
    }

    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers) throws IOException {
        return player(messageId, oid, playerId, username, receivers, List.of());
    }

    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<Knight> knights) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.projectx.data.PlayerObject", oid);
            type(out, "com.threerings.util.Name"); string(out, username);
            type(out, "com.threerings.presents.dobj.DSet"); out.writeInt(receivers.size());
            for (var receiver : new java.util.TreeMap<>(receivers).entrySet()) {
                type(out, "com.threerings.presents.client.InvocationReceiver$Registration");
                string(out, receiver.getKey()); out.writeShort(receiver.getValue());
            }
            out.writeShort(0); out.writeByte(0); string(out, null); // location, status, away message
            out.writeInt(0); // pawn ID
            out.writeInt(playerId); string(out, null); string(out, "us-east");
            type(out, "com.threerings.projectx.data.ProjectXTokenRing"); out.writeInt(0);
            out.writeInt(0); out.writeLong(0); out.writeInt(1); // site, creation time, session count
            emptyDSet(out); emptyDSet(out); // achievements, gifts
            out.writeShort(0); out.writeInt(0); // survey, queue position
            roster(out, knights);
            out.writeInt(0); out.writeShort(0); out.writeInt(0); // knight ID, name, color
            out.writeShort(0); out.writeShort(0); out.writeShort(0); // eye, height, guild
            out.writeInt(0); out.writeInt(0); out.writeInt(0); // guild ID/OID, officer OID
            emptyDSet(out); emptyDSet(out); emptyDSet(out); // friends, mail, notifications
            out.writeInt(0); // bag ID
            for (int i = 0; i < 4; i++) emptyDSet(out); // items, sprites, harnesses, recipes
            emptyCollection(out); emptyCollection(out); // equipment, quickbar (empty long arrays)
            out.writeInt(0); emptyDSet(out); // equipped sprite, loadouts
            out.writeInt(0); out.writeInt(0); out.writeBoolean(false); // weapon/trinket slots, heat bonus
            out.writeInt(0); out.writeInt(0); out.writeBoolean(false); // PvP bonus, crowns, show energy
            out.writeInt(0); out.writeInt(0); out.writeShort(0); // energy, flags, home town
            for (int i = 0; i < 6; i++) out.writeInt(0); // town, zone, instance, general/trade/party OIDs
            out.writeShort(0); out.writeLong(0); // scene dialog and creation time
            out.writeShort(0); out.writeShort(0); // new scene dialog, unique dialog
            out.writeInt(0); out.writeInt(0); // reconnect zone and scene
            out.writeShort(0); out.writeShort(0); out.writeShort(0); // PvP signup/game, equip policy
            out.writeBoolean(false); out.writeInt(0); emptyCollection(out); // outbid, progress, promotions
            emptyDSet(out); emptyDSet(out); out.writeShort(0); // objectives, memories, stats
            out.writeBoolean(false); out.writeLong(0); emptyCollection(out); // Steam voice, external secret, rewards
            out.writeShort(0); emptyDSet(out); // whereabouts, mission completions
            emptyCollection(out); emptyCollection(out); out.writeShort(0); // expansions, mission tests, rank
            out.writeInt(0); out.writeBoolean(false); emptyCollection(out); // prestige, LFG, saved colors
            emptyObjectArray(out, "com.threerings.projectx.data.ProjectXCodes$EyeType", true);
            emptyObjectArray(out, "com.threerings.projectx.data.ProjectXCodes$KnightHeight", true);
            out.writeInt(0); // item acquisition delay
            type(out, "com.threerings.projectx.data.LootReportLevel"); out.writeByte(0); // ALL, ordinal policy
            emptyDSet(out); // preferences
        });
    }

    public byte[] server(short messageId) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.projectx.data.ServerObject", SERVER_OID);
            string(out, "tortodrone-local"); out.writeByte(0); // node name, coin exchange fee
            for (int i = 0; i < 4; i++) out.writeInt(0); // guild costs, dungeon/PvP revival costs
            emptyDSet(out); emptyDSet(out); // towns, gates
            out.writeInt(0); out.writeInt(0); out.writeFloat(0); // mail fees, auction fee
            out.writeLong(0); string(out, null); emptyCollection(out); // reboot, disabled achievements
            out.writeFloat(0); emptyCollection(out); emptyCollection(out); // surveys, missions, scope vars
            out.writeBoolean(false); out.writeBoolean(false); emptyCollection(out); // love, uploads, events
            emptyObjectArray(out, "java.lang.String", true); out.writeBoolean(true); // spam words, support site
        });
    }

    public byte[] configuration(short messageId) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.config.dist.data.DConfigObject", CONFIG_OID);
            out.writeShort(0); // No remote configuration editing service.
            emptyDSet(out); emptyDSet(out); emptyDSet(out); // added, updated, removed
        });
    }

    private void objectHeader(DataOutputStream out, short id, String name, int oid) throws IOException {
        type(out, PREFIX + "ObjectResponse"); out.writeShort(id); type(out, name); out.writeInt(oid);
    }

    private void emptyDSet(DataOutputStream out) throws IOException {
        type(out, "com.threerings.presents.dobj.DSet"); out.writeInt(0);
    }

    private void emptyObjectArray(DataOutputStream out, String component, boolean finalComponent) throws IOException {
        type(out, "[L" + component + ";"); out.writeInt(0);
        if (finalComponent) out.writeShort(0); // Empty array null mask for final component types.
    }

    private static void emptyCollection(DataOutputStream out) throws IOException {
        out.writeBoolean(true); out.writeInt(0);
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    public byte[] pong(short messageId, long receivedAt) throws IOException {
        long now = System.currentTimeMillis();
        int delay = (int) Math.min(Integer.MAX_VALUE, Math.max(0, now - receivedAt));
        return frame(out -> {
            type(out, PREFIX + "PongResponse");
            out.writeShort(messageId);
            out.writeLong(now);
            out.writeInt(delay);
        });
    }

    public byte[] subscriptionFailure(short messageId, int objectId, String reason) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "FailureResponse");
            out.writeShort(messageId);
            out.writeInt(objectId);
            out.writeBoolean(true); out.writeUTF(reason);
        });
    }

    /** Listener method zero is the standard failure callback; reason is literal display text. */
    public byte[] invocationFailure(int clientOid, short requestId, String reason) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.InvocationResponseEvent"); out.writeInt(clientOid);
            out.writeShort(requestId); out.writeByte(0);
            out.writeBoolean(true); out.writeInt(1);
            // MessageBundle's literal prefix avoids looking up our text as a translation key.
            type(out, "java.lang.String"); out.writeUTF("~" + reason);
        });
    }

    /** Confirm listeners use method one with a null argument array for success. */
    public byte[] invocationConfirmed(int clientOid, short requestId) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.InvocationResponseEvent"); out.writeInt(clientOid);
            out.writeShort(requestId); out.writeByte(1); out.writeBoolean(false);
        });
    }

    public byte[] rosterChanged(int clientOid, List<Knight> knights) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.AttributeChangedEvent"); out.writeInt(clientOid);
            string(out, "availableKnights"); roster(out, knights);
        });
    }

    private void roster(DataOutputStream out, List<Knight> knights) throws IOException {
        type(out, "[Lcom.threerings.projectx.data.AvailableKnight;"); out.writeInt(knights.size());
        for (Knight knight : knights) {
            type(out, "com.threerings.projectx.data.AvailableKnight");
            out.writeInt(knight.id()); type(out, "com.threerings.util.Name"); string(out, knight.name());
            out.writeInt(0); string(out, null); out.writeInt(0); // rank, guild, session seconds
            value(out, StarterCatalog.preview(knight));
            emptyCollection(out); // Typed Set<Name>, no mail senders.
        }
    }

    private void value(DataOutputStream out,Object value) throws IOException {
        if (value instanceof StarterCatalog.Model model) {
            type(out,"com.threerings.config.ConfigReference"); intern(out,model.name());
            type(out,"com.threerings.config.ArgumentMap"); out.writeInt(model.arguments().size());
            for (var argument : new java.util.TreeMap<>(model.arguments()).entrySet()) {
                intern(out,argument.getKey()); value(out,argument.getValue());
            }
        } else if (value instanceof StarterCatalog.Colorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color.value());
        } else if (value instanceof Integer number) {
            type(out,"java.lang.Integer"); out.writeInt(number);
        } else if (value instanceof String text) {
            type(out,"java.lang.String"); out.writeUTF(text);
        } else throw new IOException("Unsupported preview argument");
    }

    private void intern(DataOutputStream out, String value) throws IOException {
        Integer code = interns.get(value);
        if (code == null) {
            code = interns.size() + 1; interns.put(value, code);
            out.writeShort(-code); out.writeUTF(value);
        } else out.writeShort(code);
    }

    public byte[] unsubscribe(short messageId, int objectId) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UnsubscribeResponse");
            out.writeShort(messageId);
            out.writeInt(objectId);
        });
    }

    private void type(DataOutputStream out, String name) throws IOException {
        Integer code = classes.get(name);
        if (code != null) {
            out.writeShort(code);
        } else {
            int next = classes.size() + 1;
            out.writeShort(-next);
            out.writeUTF(name);
            classes.put(name, next);
        }
    }

    private static byte[] frame(Writer writer) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(body));
        if (body.size() + 4 > HandshakeCodec.MAX_FRAME) throw new IOException("Session frame too large");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(body.size() + 4);
        body.writeTo(out);
        return frame.toByteArray();
    }

    @FunctionalInterface private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
