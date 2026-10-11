package org.tortodrone.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.KnightCombat;
import org.tortodrone.world.TownMovement;
import org.tortodrone.world.ActorState;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.SceneCatalog;
import org.tortodrone.world.SceneEffect;
import org.tortodrone.world.FireCatalog;

/** Downstream session messages after the secure and authentication responses. */
public final class SessionCodec {
    public static final int SERVER_OID = 1;
    public static final int CONFIG_OID = 2;
    public static final int INVOCATION_OID = 3;
    /** Stock admin service; the client's F3 dashboard tools resolve it by class from the bootstrap. */
    public static final int ADMIN_SERVICE = 13;
    /** Handles needed by current login observers; operations remain explicitly limited. */
    public static final java.util.List<String> SERVICES = java.util.List.of(
        "com.threerings.projectx.data.KnightMarshaller",
        "com.threerings.crowd.data.LocationMarshaller",
        "com.threerings.crowd.chat.data.ChatMarshaller",
        "com.threerings.whirled.data.SceneMarshaller",
        "com.threerings.whirled.zone.data.ZoneMarshaller",
        "com.threerings.projectx.data.ProjectXSceneMarshaller",
        "com.threerings.projectx.data.ProjectXZoneMarshaller",
        "com.threerings.crowd.data.BodyMarshaller",
        "com.threerings.projectx.item.data.ItemMarshaller",
        "com.threerings.projectx.uplink.data.UplinkMarshaller",
        "com.threerings.projectx.social.data.FeedMarshaller",
        "com.threerings.projectx.social.data.SocialMarshaller",
        "com.threerings.projectx.admin.data.ProjectXAdminMarshaller");
    public static final int MAX_DOWNSTREAM_FRAME = 1024 * 1024;
    public static final int INTERIOR_TCP_MESSAGES_PER_SECOND = 100;
    private static final String PREFIX = "com.threerings.presents.net.";
    private final Map<String, Integer> classes = new HashMap<>();
    private final Map<String, Integer> interns = new HashMap<>();
    private final ItemCatalog itemCatalog;
    public SessionCodec(){this(ItemCatalog.starter());}
    public SessionCodec(ItemCatalog catalog) {
        itemCatalog=java.util.Objects.requireNonNull(catalog);
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
            // admin, status, place browser, client timings.
            out.writeInt(SERVER_OID);
            out.writeInt(0); out.writeInt(0); out.writeInt(0); // exchange, PvP, PvP info
            out.writeInt(0); out.writeInt(0); // mission, open hall
            // Stock ProjectXSession only names the admin object for ADMIN-tier accounts. A
            // support-plus account keeps zero and still reaches the admin service, which is
            // addressed through the bootstrap service entry above.
            out.writeInt(0); // admin
            out.writeInt(0); out.writeInt(0); // status, place browser
            out.writeInt(0); // client timings
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
            Map<String, Short> receivers, List<CharacterStore.RosterEntry> knights) throws IOException {
        return player(messageId,oid,playerId,username,receivers,knights,false);
    }
    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<CharacterStore.RosterEntry> knights, boolean gameMaster) throws IOException {
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
            type(out, "com.threerings.projectx.data.ProjectXTokenRing"); out.writeInt(gameMaster?8:0);
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
            out.writeInt(0); out.writeInt(gameMaster?256:0); out.writeShort(0); // energy, flags, home town
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
        return server(messageId,false);
    }

    public byte[] server(short messageId,boolean soloInteriors) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.projectx.data.ServerObject", SERVER_OID);
            string(out, "tortodrone-local"); out.writeByte(0); // node name, coin exchange fee
            for (int i = 0; i < 4; i++) out.writeInt(0); // guild costs, dungeon/PvP revival costs
            if(soloInteriors){type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);townSummary(out);}
            else emptyDSet(out);
            if(soloInteriors) {
                // Stock ElevatorProp dereferences the party's gate even in a
                // peaceful SUBTOWN. This empty transport entry has no floors,
                // missions or destination; no dungeon gate service is exposed.
                type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
                type(out,"com.threerings.projectx.dungeon.data.GateSummary");out.writeBoolean(true);out.writeInt(0);
                string(out,"");string(out,"");emptyObjectArray(out,"com.threerings.opengl.renderer.config.ColorizationConfig",false);
                string(out,"");out.writeInt(SceneRegistry.system().entry().map().zone());
                for(int i=0;i<5;i++)out.writeInt(0);
                emptyObjectArray(out,"com.threerings.projectx.dungeon.data.GateSummary$Wheel",false);
            } else emptyDSet(out);
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

    /** Native unreliable input falls back to the reliable FIFO when no UDP is advertised. */
    public byte[] interiorInputThrottle() throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UpdateThrottleMessage");out.writeShort(-1);
            out.writeInt(INTERIOR_TCP_MESSAGES_PER_SECOND);
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

    /** TellListener method one carries idle duration and an optional away message. */
    public byte[] tellSucceeded(int clientOid,short requestId)throws IOException {
        return frame(out->{
            eventHeader(out,"InvocationResponseEvent",clientOid);
            out.writeShort(requestId);out.writeByte(1);out.writeBoolean(true);out.writeInt(2);
            value(out,0L);value(out,null);
        });
    }

    /** News and social feed are empty on this server; Result listeners receive one list. */
    public byte[] emptyListResult(int oid, short requestId) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(0);
        });
    }

    public byte[] rosterChanged(int clientOid, List<CharacterStore.RosterEntry> knights) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.AttributeChangedEvent"); out.writeInt(clientOid);
            string(out, "availableKnights"); roster(out, knights);
        });
    }

    /** PlayerObject derives GAME_MASTER from flag 256; token 8 permits stock broadcast UI. */
    public byte[] gameMaster(int clientOid,boolean enabled)throws IOException {
        return playerFlags(clientOid,enabled,false);
    }
    public byte[] playerFlags(int clientOid,boolean enabled,boolean inspectionHidden)throws IOException {
        return frame(out->{
            eventHeader(out,"CompoundEvent",clientOid);
            type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,clientOid,"flags",o->{type(o,"java.lang.Integer");o.writeInt((enabled?256:0)|(inspectionHidden?1024:0));});
            attribute(out,clientOid,"tokens",o->{type(o,"com.threerings.projectx.data.ProjectXTokenRing");o.writeInt(enabled?8:0);});
        });
    }

    /** Stock ResultListener payload; only equipped items or their visible appearance leave the owner. */
    public byte[] inspected(int oid,short request,Knight knight,WorldProfile profile,boolean hidden)throws IOException {
        return frame(out->{eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(1);
            out.writeBoolean(true);out.writeInt(1);type(out,"com.threerings.projectx.data.PlayerEquipment");
            name(out,knight.name());out.writeInt(256|knight.color());
            type(out,"com.threerings.projectx.data.ProjectXCodes$EyeType");out.writeByte(knight.eye());
            type(out,"com.threerings.projectx.data.ProjectXCodes$KnightHeight");out.writeByte(knight.height());
            out.writeInt(1);out.writeInt(0);
            var equipped=new java.util.TreeMap<Integer,WorldProfile.Item>();
            for(var item:profile.items())if(item.equippedSlot()!=null)equipped.put(item.equippedSlot(),item);
            if(hidden) {
                var visible=new java.util.TreeMap<Integer,WorldProfile.Item>();
                for(int slot:new int[]{0,1,2}) {
                    int costume=slot==0?3:slot==1?4:15;
                    var item=equipped.getOrDefault(costume,equipped.get(slot));if(item!=null)visible.put(slot,item);
                }
                out.writeBoolean(true);out.writeInt(visible.size());
                for(var entry:visible.entrySet()){value(out,entry.getKey());value(out,new StarterCatalog.Model(entry.getValue().config(),Map.of()));}
                out.writeBoolean(false); // Full equipped items are private.
                var accessories=visible.values().stream().map(WorldProfile.Item::accessory).filter(java.util.Objects::nonNull).distinct().toList();
                out.writeBoolean(true);out.writeInt(accessories.size());
                for(String accessory:accessories)value(out,new StarterCatalog.Model(accessory+", "+knight.costume(),Map.of()));
            } else {
                out.writeBoolean(false);out.writeBoolean(true);out.writeInt(equipped.size());
                for(var entry:equipped.entrySet()){value(out,entry.getKey());item(out,knight,profile.worldId(),entry.getValue());}
                out.writeBoolean(false);
            }
            out.writeShort(0);emptyDSet(out); // No battle sprite or earned achievements in current persisted state.
        });
    }

    private void roster(DataOutputStream out, List<CharacterStore.RosterEntry> knights) throws IOException {
        type(out, "[Lcom.threerings.projectx.data.AvailableKnight;"); out.writeInt(knights.size());
        for (CharacterStore.RosterEntry entry : knights) {
            Knight knight = entry.knight();
            type(out, "com.threerings.projectx.data.AvailableKnight");
            out.writeInt(knight.id()); type(out, "com.threerings.util.Name"); string(out, knight.name());
            out.writeInt(0); string(out, null); out.writeInt(0); // rank, guild, session seconds
            value(out, entry.preview());
            emptyCollection(out); // Typed Set<Name>, no mail senders.
        }
    }

    void value(DataOutputStream out,Object value) throws IOException {
        if (value == null || value==FireCatalog.NullValue.INSTANCE) { out.writeShort(0);
        } else if (value instanceof Long number) { type(out,"java.lang.Long"); out.writeLong(number);
        } else if (value instanceof Boolean flag) { type(out,"java.lang.Boolean"); out.writeBoolean(flag);
        } else if (value instanceof StarterCatalog.Model model) {
            type(out,"com.threerings.config.ConfigReference"); intern(out,model.name());
            type(out,"com.threerings.config.ArgumentMap"); out.writeInt(model.arguments().size());
            for (var argument : new java.util.TreeMap<>(model.arguments()).entrySet()) {
                intern(out,argument.getKey()); value(out,argument.getValue());
            }
        } else if (value instanceof StarterCatalog.Colorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color.value());
        } else if (value instanceof StarterCatalog.TransColorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$TransNormal");out.writeInt(color.clazz());out.writeInt(color.value());
        } else if (value instanceof Float number) {
            type(out,"java.lang.Float");out.writeFloat(number);
        } else if (value instanceof Integer number) {
            type(out,"java.lang.Integer"); out.writeInt(number);
        } else if (value instanceof String text) {
            type(out,"java.lang.String"); out.writeUTF(text);
        } else if(value instanceof FireCatalog.EnumValue enumeration) {
            if(enumeration.ordinal()<0||enumeration.ordinal()>255)throw new IOException("Unsupported enum ordinal");
            type(out,enumeration.type());out.writeByte(enumeration.ordinal());
        } else if(value instanceof FireCatalog.DataValue object) {
            type(out,object.type());for(var field:object.fields())streamField(out,field);
        } else if(value instanceof FireCatalog.ListValue array) {
            if(array.componentType().startsWith("java.util."))throw new IOException("Unsupported streamed collection");
            type(out,"[L"+array.componentType()+";");out.writeInt(array.values().size());for(var element:array.values())value(out,element);
        } else if(value instanceof FireCatalog.MapValue map) {
            type(out,"java.util.HashMap");out.writeInt(map.values().size());for(var entry:new java.util.TreeMap<>(map.values()).entrySet()){value(out,entry.getKey());value(out,entry.getValue());}
        } else throw new IOException("Unsupported preview argument");
    }

    private void streamField(DataOutputStream out,FireCatalog.FieldValue field)throws IOException {
        Object v=field.value();switch(field.type()) {
            case "boolean" -> out.writeBoolean((Boolean)v);
            case "byte" -> out.writeByte(((Number)v).intValue());
            case "short" -> out.writeShort(((Number)v).intValue());
            case "char" -> out.writeChar(((Number)v).intValue());
            case "int" -> out.writeInt(((Number)v).intValue());
            case "long" -> out.writeLong(((Number)v).longValue());
            case "float" -> out.writeFloat(((Number)v).floatValue());
            case "double" -> out.writeDouble(((Number)v).doubleValue());
            case "java.lang.String" -> {out.writeBoolean(v!=FireCatalog.NullValue.INSTANCE);if(v!=FireCatalog.NullValue.INSTANCE)out.writeUTF((String)v);}
            default -> value(out,v);
        }
    }

    private void intern(DataOutputStream out, String value) throws IOException {
        Integer code = interns.get(value);
        if (code == null) {
            code = interns.size() + 1; interns.put(value, code);
            out.writeShort(-code); out.writeUTF(value);
        } else out.writeShort(code);
    }

    public byte[] selected(int oid, Knight knight, WorldProfile profile) throws IOException {
        return frame(out -> {
            eventHeader(out,"CompoundEvent",oid);
            type(out,"com.threerings.util.StreamableArrayList"); out.writeInt(18);
            attribute(out,oid,"knight",o -> name(o,knight.name()));
            attribute(out,oid,"color",o -> value(o,256 | knight.color()));
            attribute(out,oid,"eye",o -> { type(o,"com.threerings.projectx.data.ProjectXCodes$EyeType");o.writeByte(knight.eye()); });
            attribute(out,oid,"height",o -> { type(o,"com.threerings.projectx.data.ProjectXCodes$KnightHeight");o.writeByte(knight.height()); });
            attribute(out,oid,"bagId",o -> value(o,profile.worldId()));
            attribute(out,oid,"items",o -> items(o,knight,profile));
            attribute(out,oid,"equipment",o -> longs(o,profile.equipment()));
            attribute(out,oid,"loadouts",o -> currentLoadout(o,profile));
            attribute(out,oid,"quickbar",o -> longs(o,new long[6]));
            attribute(out,oid,"weaponSlots",o -> value(o,2));
            attribute(out,oid,"crowns",o -> value(o,profile.crowns()));
            attribute(out,oid,"energy",o -> value(o,profile.energy()));
            attribute(out,oid,"showEnergy",o -> value(o,true));
            attribute(out,oid,"homeTown",this::townSummary);
            attribute(out,oid,"rank",o->{type(o,"com.threerings.projectx.mission.data.RankPair");o.writeInt(1);o.writeInt(1);});
            attribute(out,oid,"stats",o->{type(o,"com.threerings.stats.data.StatSet");o.writeInt(0);});
            attribute(out,oid,"pawnId",o -> value(o,profile.worldId()));
            attribute(out,oid,"knightId",o -> value(o,profile.worldId()));
        });
    }

    public byte[] balances(int oid,WorldProfile profile) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,oid,"crowns",o->value(o,profile.crowns()));attribute(out,oid,"energy",o->value(o,profile.energy())); });
    }

    /** Item entry, equipment element and loadout entry events notify the open stock panels. */
    public byte[] itemChanges(int oid,Knight knight,WorldProfile before,WorldProfile after)throws IOException {
        var old=new HashMap<Long,WorldProfile.Item>();for(var item:before.items())old.put(item.id(),item);
        var changed=after.items().stream().filter(i->!i.equals(old.get(i.id()))).toList();
        var current=after.items().stream().map(WorldProfile.Item::id).collect(java.util.stream.Collectors.toSet());
        var removed=before.items().stream().filter(i->!current.contains(i.id())).toList();
        long[] oldEquipment=before.equipment(),equipment=after.equipment();
        var slots=new ArrayList<Integer>();
        for(int slot=0;slot<equipment.length;slot++)if(oldEquipment[slot]!=equipment[slot])slots.add(slot);
        return frame(out->{
            eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(changed.size()+removed.size()+slots.size()+(slots.isEmpty()?0:1));
            for(var item:removed)entryRemoved(out,oid,"items",item.id());
            for(var item:changed) {
                type(out,"com.threerings.presents.dobj."+(old.containsKey(item.id())?"EntryUpdatedEvent":"EntryAddedEvent"));
                out.writeInt(oid);string(out,"items");item(out,knight,after.worldId(),item);
            }
            for(int slot:slots) {
                type(out,"com.threerings.presents.dobj.ElementUpdatedEvent");out.writeInt(oid);string(out,"equipment");
                value(out,equipment[slot]);out.writeInt(slot);
            }
            if(!slots.isEmpty())loadoutUpdated(out,oid,after);
        });
    }

    public record TradeView(int oid,Knight first,WorldProfile firstProfile,List<WorldProfile.Item> firstItems,
            Knight second,WorldProfile secondProfile,List<WorldProfile.Item> secondItems,
            int firstCrowns,int secondCrowns,int firstEnergy,int secondEnergy,int revision,int firstAccepted,int secondAccepted,boolean completed) {
        public TradeView { firstItems=List.copyOf(firstItems);secondItems=List.copyOf(secondItems); }
    }
    public byte[] tradeNotification(int playerOid,String sender,boolean add)throws IOException {
        return frame(out->{if(add){eventHeader(out,"EntryAddedEvent",playerOid);string(out,"notifications");
            type(out,"com.threerings.projectx.social.data.Notification$TradeRequest");name(out,sender);
        }else {eventHeader(out,"EntryRemovedEvent",playerOid);string(out,"notifications");value(out,"TRADE_REQ:"+sender);}});
    }
    public byte[] tradeDialog(int playerOid,TradeView view,long created)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",playerOid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,playerOid,"sceneDialogCreated",o->value(o,created));
            attribute(out,playerOid,"sceneDialog",o->{
                if(view==null){o.writeShort(0);return;}
                type(o,"com.threerings.projectx.trade.data.TradeDialogInfo");
                o.writeShort(0);o.writeShort(0);o.writeFloat(0);o.writeShort(0);o.writeShort(0);
                type(o,"[Lcom.threerings.util.Name;");o.writeInt(2);name(o,view.first.name());name(o,view.second.name());o.writeInt(view.oid);
            });});
    }
    public byte[] tradeObject(short messageId,TradeView view)throws IOException {
        return frame(out->{objectHeader(out,messageId,"com.threerings.projectx.trade.data.TradeObject",view.oid);
            type(out,"com.threerings.projectx.trade.data.TradeMarshaller");out.writeInt(view.oid);out.writeInt(1);
            tradeItems(out,view.first,view.firstProfile,view.firstItems);tradeItems(out,view.second,view.secondProfile,view.secondItems);
            type(out,"com.threerings.presents.dobj.DSet");var ids=tradeIds(view);out.writeInt(ids.size());
            for(int i=0;i<ids.size();i++)itemOrder(out,ids.get(i),i);
            primitiveInts(out,new int[]{view.firstCrowns,view.secondCrowns});primitiveInts(out,new int[]{view.firstEnergy,view.secondEnergy});
            out.writeInt(view.revision);primitiveInts(out,new int[]{view.firstAccepted,view.secondAccepted});out.writeBoolean(view.completed);});
    }
    public byte[] tradeChanges(TradeView before,TradeView after)throws IOException {
        var events=new ArrayList<Writer>();int oid=after.oid;
        var oldIds=tradeIds(before);var newIds=tradeIds(after);
        for(int i=0;i<newIds.size();i++){long id=newIds.get(i);int order=i;
            events.add(o->{event(o,oldIds.contains(id)?"EntryUpdatedEvent":"EntryAddedEvent",oid);string(o,"orderings");itemOrder(o,id,order);});}
        tradeItemEvents(events,oid,"firstItems",before.firstItems,after.firstItems,after.first,after.firstProfile);
        tradeItemEvents(events,oid,"secondItems",before.secondItems,after.secondItems,after.second,after.secondProfile);
        for(long id:oldIds)if(!newIds.contains(id))events.add(o->entryRemoved(o,oid,"orderings",id));
        if(before.firstCrowns!=after.firstCrowns)events.add(o->element(o,oid,"crowns",0,after.firstCrowns));
        if(before.secondCrowns!=after.secondCrowns)events.add(o->element(o,oid,"crowns",1,after.secondCrowns));
        if(before.firstEnergy!=after.firstEnergy)events.add(o->element(o,oid,"energy",0,after.firstEnergy));
        if(before.secondEnergy!=after.secondEnergy)events.add(o->element(o,oid,"energy",1,after.secondEnergy));
        if(before.revision!=after.revision)events.add(o->attribute(o,oid,"revision",v->value(v,after.revision)));
        events.add(o->element(o,oid,"accepted",0,after.firstAccepted));events.add(o->element(o,oid,"accepted",1,after.secondAccepted));
        if(before.completed!=after.completed)events.add(o->attribute(o,oid,"completed",v->value(v,after.completed)));
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(events.size());for(var event:events)event.write(out);});
    }
    private void tradeItemEvents(List<Writer> events,int oid,String field,List<WorldProfile.Item> before,List<WorldProfile.Item> after,Knight knight,WorldProfile owner) {
        var old=new HashMap<Long,WorldProfile.Item>();for(var item:before)old.put(item.id(),item);
        var ids=after.stream().map(WorldProfile.Item::id).collect(java.util.stream.Collectors.toSet());
        for(var item:before)if(!ids.contains(item.id()))events.add(o->entryRemoved(o,oid,field,item.id()));
        for(var item:after)if(!item.equals(old.get(item.id())))events.add(o->{event(o,old.containsKey(item.id())?"EntryUpdatedEvent":"EntryAddedEvent",oid);string(o,field);item(o,knight,owner.worldId(),item);});
    }
    private void tradeItems(DataOutputStream out,Knight knight,WorldProfile owner,List<WorldProfile.Item> items)throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(items.size());for(var item:items)item(out,knight,owner.worldId(),item);
    }
    private static List<Long> tradeIds(TradeView view){var ids=new ArrayList<Long>();for(var item:view.firstItems)ids.add(item.id());for(var item:view.secondItems)ids.add(item.id());return ids;}
    private void itemOrder(DataOutputStream out,long id,int order)throws IOException {type(out,"com.threerings.projectx.trade.data.TradeObject$ItemOrder");out.writeLong(id);out.writeInt(order);}
    private void entryRemoved(DataOutputStream out,int oid,String field,Object key)throws IOException {event(out,"EntryRemovedEvent",oid);string(out,field);value(out,key);}
    private void element(DataOutputStream out,int oid,String field,int index,int number)throws IOException {event(out,"ElementUpdatedEvent",oid);string(out,field);value(out,number);out.writeInt(index);}
    private void event(DataOutputStream out,String kind,int oid)throws IOException {type(out,"com.threerings.presents.dobj."+kind);out.writeInt(oid);}

    public byte[] forceTown(int oid,short receiver) throws IOException {
        return forceScene(oid,receiver,SceneRegistry.system().entry());
    }
    public byte[] forceScene(int oid,short receiver,SceneRegistry.Instance scene) throws IOException {
        return frame(out -> { eventHeader(out,"InvocationNotificationEvent",oid);out.writeShort(receiver);out.writeByte(1);
            out.writeBoolean(true);out.writeInt(2);value(out,scene.map().zone());value(out,scene.map().id()); });
    }

    public byte[] sceneMove(int oid,short request,boolean zone) throws IOException {
        var registry=SceneRegistry.system();
        return sceneMove(oid,request,zone,registry.entry(),registry.catalog());
    }
    public byte[] sceneMove(int oid,short request,boolean zone,SceneRegistry.Instance scene,SceneCatalog catalog) throws IOException {
        byte[] model=scene.map().layout();
        return frame(out -> {
            eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(3);
            out.writeBoolean(true);out.writeInt(zone ? 4 : 3);value(out,scene.oid());
            type(out,scene.map().configType());string(out,scene.map().image());
            if(zone) {
                type(out,"com.threerings.whirled.zone.data.ZoneSummary");out.writeInt(scene.map().zone());name(out,scene.map().zoneName());
                var summaries=catalog.scenes().stream().filter(s->s.zone()==scene.map().zone()).toList();
                type(out,"[Lcom.threerings.whirled.zone.data.SceneSummary;");out.writeInt(summaries.size());
                for(var summary:summaries){
                    type(out,"com.threerings.whirled.zone.data.SceneSummary");out.writeInt(summary.id());string(out,summary.name());
                    emptyCollection(out);emptyCollection(out);
                }
            }
            type(out,"com.threerings.tudey.data.TudeySceneModel");out.writeInt(model.length);out.write(model);
        });
    }

    public byte[] townLocation(int oid) throws IOException {
        return sceneLocation(oid,SceneRegistry.system().entry());
    }
    public byte[] sceneParty(int oid,SceneRegistry.Instance scene)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"partyOid");
            value(out,scene.map().town()?0:interiorPartyOid(scene));});
    }
    public byte[] sceneLocation(int oid,SceneRegistry.Instance scene) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(scene.map().town()?4:3);
            if(scene.map().town())attribute(out,oid,"lastTownSceneId",o->value(o,scene.map().id()));
            attribute(out,oid,"partyOid",o->value(o,scene.map().town()?0:interiorPartyOid(scene)));
            attribute(out,oid,"zoneId",o->value(o,scene.map().zone()));
            attribute(out,oid,"location",o->{type(o,"com.threerings.whirled.data.ScenePlace");o.writeInt(scene.oid());o.writeInt(scene.map().id());}); });
    }

    public byte[] scene(short id,List<ActorState> actors) throws IOException {
        return scene(id,SceneRegistry.system().entry(),actors);
    }
    public byte[] scene(short id,SceneRegistry.Instance scene,List<ActorState> actors) throws IOException {
        return frame(out -> {
            objectHeader(out,id,scene.map().town()?"com.threerings.projectx.town.data.TownSceneObject":"com.threerings.projectx.data.ProjectXSceneObject",scene.oid());
            type(out,"com.threerings.presents.dobj.OidList");out.writeBoolean(true);out.writeInt(actors.size());for(var a:actors)out.writeInt(a.clientOid());out.writeInt(actors.size());
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(actors.size());for(var a:actors)occupant(out,a);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(scene.oid());out.writeInt(org.tortodrone.world.SceneChat.SERVICE);
            type(out,"com.threerings.tudey.data.TudeySceneMarshaller");out.writeInt(scene.oid());out.writeInt(10);
            type(out,"com.threerings.projectx.data.ProjectXSceneMarshaller");out.writeInt(scene.oid());out.writeInt(6);
            emptyDSet(out);
            if(scene.map().town()){type(out,"com.threerings.projectx.town.data.TownSceneMarshaller");out.writeInt(scene.oid());out.writeInt(11);}
        });
    }

    /** Connection-local single-member transport required by stock level controllers. */
    public static int interiorPartyOid(SceneRegistry.Instance scene){return 500+scene.oid();}
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneRegistry.Instance scene)throws IOException {
        return frame(out->{
            objectHeader(out,message,"com.threerings.projectx.dungeon.data.LevelPartyObject",partyOid);
            type(out,"com.threerings.projectx.dungeon.data.PartyMarshaller");out.writeInt(partyOid);out.writeInt(20);
            out.writeShort(0); // Party chat is outside this milestone.
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
            type(out,"com.threerings.projectx.dungeon.data.PartyMember");out.writeInt(playerOid);out.writeInt(0);
            name(out,knight.name());out.writeInt(256|knight.color());value(out,new StarterCatalog.Model(knight.helm(),Map.of()));
            out.writeShort(0);type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(0);out.writeLong(0);
            // Dungeon HUDs require entries keyed by the member's player OID even
            // in peaceful interiors. A positive normalized health unit keeps the
            // owner alive; combat health/stat progression is not implemented.
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
            type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(playerOid);
            out.writeInt(1);out.writeInt(1);out.writeInt(0);
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
            type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(playerOid);
            out.writeShort(0);out.writeBoolean(false);out.writeByte(0);
            emptyCollection(out);vector(out,scene.map().spawnX(),scene.map().spawnY());
            out.writeInt(0);out.writeBoolean(false);out.writeLong(0);
            out.writeShort(0);out.writeInt(1);out.writeInt(0); // No dungeon matchmaking service or gate.
            out.writeShort(0);type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty");out.writeByte(0);
            emptyDSet(out);emptyCollection(out);out.writeInt(0);
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(3); // SUBTOWN
            primitiveInts(out,new int[]{0});out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(true);
            emptyDSet(out);string(out,null);out.writeInt(0);
            type(out,"com.threerings.projectx.dungeon.data.LevelPartyObject$FloorType");out.writeByte(4);
            type(out,"com.threerings.projectx.dungeon.data.LevelPartyObject$FloorType");out.writeByte(4);
            out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);emptyDSet(out);
            for(int i=0;i<4;i++)out.writeInt(0);out.writeLong(0);emptyCollection(out);string(out,null);
            out.writeBoolean(true);out.writeInt(0); // Empty typed Multiset<Integer>.
        });
    }

    /** Original Crowd place chat message, carrying a server-selected visible name. */
    public byte[] chat(int sceneOid,org.tortodrone.world.SceneChat.Message message)throws IOException {
        return frame(out->{
            eventHeader(out,"MessageEvent",sceneOid);string(out,"crowd.chat");
            out.writeBoolean(true);out.writeInt(1);type(out,message.mode()==5||message.mode()==6?
                "com.threerings.projectx.data.ProjectXUserMessage":"com.threerings.crowd.chat.data.UserMessage");
            string(out,message.text());string(out,message.bundle());name(out,message.speaker());out.writeByte(message.mode());
        });
    }
    public byte[] chatFeedback(int sceneOid,String reason)throws IOException {
        return frame(out->{
            eventHeader(out,"MessageEvent",sceneOid);string(out,"crowd.chat");
            out.writeBoolean(true);out.writeInt(1);type(out,"com.threerings.crowd.chat.data.SystemMessage");
            string(out,reason);string(out,null);out.writeByte(1);
        });
    }

    /** Publish occupant metadata before adding their actors to the scene. */
    public byte[] occupants(List<ActorState> actors) throws IOException {
        return occupants(SceneRegistry.system().entry().oid(),actors);
    }
    public byte[] occupants(int sceneOid,List<ActorState> actors) throws IOException {
        return frame(out -> {
            eventHeader(out, "CompoundEvent", sceneOid);
            type(out, "com.threerings.util.StreamableArrayList");
            out.writeInt(2);
            attribute(out, sceneOid, "occupants", o -> {
                type(o, "com.threerings.presents.dobj.OidList");
                o.writeBoolean(true); o.writeInt(actors.size());
                for (var actor : actors) o.writeInt(actor.clientOid());
                o.writeInt(actors.size());
            });
            attribute(out, sceneOid, "occupantInfo", o -> {
                type(o, "com.threerings.presents.dobj.DSet"); o.writeInt(actors.size());
                for (var actor : actors) occupant(o, actor);
            });
        });
    }

    /** Notify stock OccupantDirector observers; replacing a DSet alone does not update labels. */
    public byte[] occupantRoles(int sceneOid,List<ActorState> changed)throws IOException {
        return frame(out->{
            eventHeader(out,"CompoundEvent",sceneOid);
            type(out,"com.threerings.util.StreamableArrayList");out.writeInt(changed.size());
            for(var actor:changed) {
                type(out,"com.threerings.presents.dobj.EntryUpdatedEvent");out.writeInt(sceneOid);
                string(out,"occupantInfo");occupant(out,actor);
            }
        });
    }

    public byte[] sceneDelta(int oid,int acknowledge,int reference,int timestamp,List<ActorState> added,
            List<ActorState> updated,int[] removed) throws IOException {
        return sceneDelta(oid, acknowledge, (short) 0, reference, timestamp, (short) 50,
            added, updated, removed, Map.of());
    }

    public byte[] sceneDelta(int oid, int acknowledge, short ping, int reference, int timestamp,
            short elapsed, List<ActorState> added, List<ActorState> updated, int[] removed,
            Map<Integer, ActorState> baseline) throws IOException {
        return sceneDelta(oid,SceneRegistry.system().entry().oid(),acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline);
    }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline)throws IOException {
        return sceneDelta(oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline,false);
    }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior)throws IOException {
        return sceneDelta(oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline,interior,List.of());
    }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects)throws IOException {
        return frame(out -> {
            type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.tudey.dobj.SceneDeltaEvent");out.writeInt(oid);
            out.writeInt(sceneOid);out.writeInt(acknowledge);out.writeShort(ping);out.writeInt(reference);out.writeInt(timestamp);out.writeShort(elapsed);
            if(added.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.data.actor.Actor;");out.writeInt(added.size());for(var a:added)actor(out,a,interior);}
            if(updated.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.dobj.ActorDelta;");out.writeInt(updated.size());out.writeShort((updated.size()+7)/8);for(int i=0;i<updated.size();i+=8)out.writeByte((1<<Math.min(8,updated.size()-i))-1);for(var a:updated)actorDelta(out,a,baseline.get(a.id()),interior);}
            out.writeBoolean(true);out.writeInt(removed.length);for(int id:removed)out.writeInt(id);
            if(effects.isEmpty())out.writeShort(0);else {type(out,"[Lcom.threerings.tudey.data.effect.Effect;");out.writeInt(effects.size());for(var effect:effects)effect(out,effect);}
        });
    }

    private void eventHeader(DataOutputStream out,String kind,int oid)throws IOException {
        type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.presents.dobj."+kind);out.writeInt(oid);
    }
    private void attribute(DataOutputStream out,int oid,String field,Writer writer)throws IOException {
        type(out,"com.threerings.presents.dobj.AttributeChangedEvent");out.writeInt(oid);string(out,field);writer.write(out);
    }
    private void name(DataOutputStream out,String name)throws IOException{type(out,"com.threerings.util.Name");string(out,name);}
    private void longs(DataOutputStream out,long[] values)throws IOException{type(out,"[J");out.writeInt(values.length);for(long v:values)out.writeLong(v);}
    private void ints(DataOutputStream out,int[] values)throws IOException{type(out,"[I");out.writeInt(values.length);for(int v:values)out.writeInt(v);}
    private static void primitiveInts(DataOutputStream out,int[] values)throws IOException{out.writeBoolean(true);out.writeInt(values.length);for(int v:values)out.writeInt(v);}
    private void items(DataOutputStream out,Knight knight,WorldProfile profile)throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(profile.items().size());
        for(var item:profile.items())item(out,knight,profile.worldId(),item);
    }
    private void item(DataOutputStream out,Knight knight,int worldId,WorldProfile.Item item)throws IOException {
            item(out,knight==null?null:knight.costume(),worldId,item);
    }
    private void item(DataOutputStream out,String costume,int worldId,WorldProfile.Item item)throws IOException {
            var definition=itemCatalog.find(item.config());
            boolean seeded=item.slot()==WorldProfile.Slot.OTHER&&definition!=null&&definition.variant()==ItemCatalog.Variant.SEED_ITEM;
            type(out,"com.threerings.projectx.item.data."+(item.slot()==WorldProfile.Slot.OTHER?seeded?"SeedItem":definition!=null&&definition.variant()==ItemCatalog.Variant.LEVEL_ITEM?"LevelItem":"Item":"LevelItem"));
            if(seeded)out.writeLong(definition.seed());
            boolean level=item.slot()!=WorldProfile.Slot.OTHER||definition!=null&&definition.variant()==ItemCatalog.Variant.LEVEL_ITEM;
            if(level) {
                out.writeInt(0);out.writeFloat(0);out.writeFloat(0);out.writeShort(0);
                out.writeBoolean(item.accessory()!=null);if(item.accessory()!=null){out.writeInt(1);value(out,new StarterCatalog.Model(item.accessory()+", "+costume,Map.of()));}
            }
            out.writeLong(item.id());out.writeInt(worldId);out.writeInt(0);out.writeUTF(item.config());out.writeInt(item.count());out.writeShort(0);
            // Stock Item.writeObject: bit 2 is the mutable binding flag (aTY),
            // tested by Cs(). Stock cv(true) sets both permanent and binding flags.
            out.writeByte((item.bound() || definition!=null&&definition.locked() ? 2 : 0) | (item.permanent() ? 1 : 0));
    }
    private void currentLoadout(DataOutputStream out,WorldProfile profile) throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
        loadout(out,profile);
    }
    private void loadoutUpdated(DataOutputStream out,int oid,WorldProfile profile)throws IOException {
        type(out,"com.threerings.presents.dobj.EntryUpdatedEvent");out.writeInt(oid);string(out,"loadouts");
        loadout(out,profile);
    }
    private void loadout(DataOutputStream out,WorldProfile profile)throws IOException {
        type(out,"com.threerings.projectx.data.Loadout");
        // Loadout equipment is indexed by ItemCodes.bwt, not the full equipment slot array.
        int[] slots={0,1,2,3,4,15,6,7,8,9,10,11};long[] equipment=profile.equipment();
        out.writeBoolean(true);out.writeInt(slots.length);
        for(int slot:slots)out.writeLong(equipment[slot]);
        out.writeBoolean(false);out.writeInt(0);out.writeInt(0);out.writeLong(0);
        out.writeShort(0);string(out,""); // no sprite perk; empty name is the stock current loadout.
    }
    private void townSummary(DataOutputStream out)throws IOException {
        var entry=SceneRegistry.system().entry().map();
        type(out,"com.threerings.projectx.town.data.TownSummary");string(out,entry.zoneName());string(out,"town");
        emptyObjectArray(out,"com.threerings.opengl.renderer.config.ColorizationConfig",false);string(out,null);
        out.writeInt(0);out.writeInt(entry.id());out.writeInt(entry.zone());
    }
    private void occupant(DataOutputStream out,ActorState actor)throws IOException {
        type(out,"com.threerings.projectx.data.ProjectXOccupantInfo");out.writeBoolean(true);out.writeInt(actor.clientOid());name(out,actor.name());out.writeByte(0);out.writeInt(actor.id());
        type(out,"com.threerings.projectx.data.PlayerObject$Type");out.writeByte(actor.gameMaster()?2:0);out.writeInt(actor.color());out.writeShort(0);type(out,"com.threerings.projectx.data.ProjectXCodes$PrestigeRank");out.writeByte(0);out.writeInt(1);
    }
    private void actor(DataOutputStream out,ActorState a,boolean interior)throws IOException {
        if(a.projectile()!=null){projectile(out,a);return;}
        type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(TownMovement.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());
        out.writeShort(0);out.writeShort(0);out.writeShort(0);out.writeShort(0);string(out,null);out.writeInt(0);out.writeShort(0);
        if(interior) {
            var combat=a.combat();
            weapon(out,a.id(),combat);string(out,combat==null?null:combat.shield());string(out,combat==null?null:combat.shieldCostume());out.writeShort(0);
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
            out.writeInt(0);out.writeInt(0);
            out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
            out.writeInt(0);out.writeInt(0);out.writeInt(combat==null?0:combat.nextDodge());
            type(out,"com.threerings.projectx.dungeon.data.ImmutableStatusModifiers");
            for(int i=0;i<8;i++)out.writeFloat(1);out.writeFloat(0);
            for(int i=0;i<4;i++)out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(true);out.writeInt(0);
            for(int n=0;n<2;n++){type(out,"com.threerings.projectx.item.data.ImmutableEquipmentModifiers");for(int i=0;i<4;i++)out.writeFloat(0);}
        }
    }
    private void actorDelta(DataOutputStream out,ActorState a,ActorState baseline,boolean interior)throws IOException {
        // ActorDelta is final: its typed array omits a per-element type header.
        if(a.projectile()!=null){projectileDelta(out,a);return;}
        out.writeInt(a.id());type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");
        boolean configChanged = baseline == null || !a.config().equals(baseline.config());
        // Preserve the reference's config object on ordinary movement; update it for actual gear changes.
        // The stock controller predicts directly on its first snapshot. Always replace activity
        // fields so that local inventory prediction cannot leak into subsequent delta baselines.
        boolean combatChanged=interior&&(a.combat()!=null||baseline!=null&&baseline.combat()!=null);
        out.writeByte(configChanged ? 0xdd : 0xdc);out.writeByte(1);
        // Mutable Dungeoneer fields: weapon 16, shields 17/18, health 22/23,
        // impulse 24, chain 27/28 and dodge deadline 31. Id/created are DeltaFinal.
        if(interior){out.writeByte(combatChanged?0xf7:0);out.writeByte(combatChanged?0x99:0);out.writeByte(0);}
        if (configChanged) value(out,a.config());
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());
        out.writeInt(a.activity());out.writeInt(a.activityStarted());
        if(combatChanged) {
            var combat=a.combat();weapon(out,a.id(),combat);value(out,combat==null?null:combat.shield());value(out,combat==null?null:combat.shieldCostume());
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
            out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());out.writeInt(combat==null?0:combat.nextDodge());
        }
    }
    private void projectile(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        type(out,"com.threerings.projectx.dungeon.data.actor."+(bullet?"Bullet":"Bomb"));
        value(out,p.config());out.writeInt(actor.id());out.writeInt(p.created());out.writeInt(p.destroyed());
        vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(bullet){out.writeFloat(p.maxStep());out.writeFloat(actor.direction());vector(out,p.originX(),p.originY());out.writeFloat(p.z());}
        out.writeInt(p.ownerOid());out.writeInt(0);if(bullet)out.writeInt(p.faction());else out.writeFloat(p.z());
    }
    private void projectileDelta(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        out.writeInt(actor.id());type(out,"com.threerings.projectx.dungeon.data.actor."+(bullet?"Bullet":"Bomb"));
        // Actor: destroyed, translation, rotation, flags. Origin, id and created are final.
        out.writeByte(0x1e);if(bullet)out.writeByte(0);
        out.writeInt(p.destroyed());vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
    }
    private void effect(DataOutputStream out,SceneEffect effect)throws IOException {
        type(out,"com.threerings.tudey.data.effect.Effect");value(out,effect.config());out.writeInt(effect.timestamp());out.writeShort(0);
        vector(out,effect.x(),effect.y());out.writeFloat(effect.rotation());out.writeInt(effect.ownerOid());
    }
    private void weapon(DataOutputStream out,int bag,KnightCombat.State combat)throws IOException {
        if(combat==null||combat.weapon()==null)out.writeShort(0);else item(out,(String)null,bag,combat.weapon());
    }
    private void impulse(DataOutputStream out,KnightCombat.Impulse impulse)throws IOException {
        if(impulse==null){out.writeShort(0);return;}
        type(out,"com.threerings.projectx.dungeon.data.actor.Impulse");out.writeInt(impulse.started());out.writeInt(impulse.duration());vector(out,impulse.x(),impulse.y());
    }
    private void vector(DataOutputStream out,float x,float y)throws IOException{type(out,"com.threerings.math.Vector2f");out.writeFloat(x);out.writeFloat(y);}

    public byte[] unsubscribe(short messageId, int objectId) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UnsubscribeResponse");
            out.writeShort(messageId);
            out.writeInt(objectId);
        });
    }

    void type(DataOutputStream out, String name) throws IOException {
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
        if (body.size() + 4 > MAX_DOWNSTREAM_FRAME) throw new IOException("Session frame too large");
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
