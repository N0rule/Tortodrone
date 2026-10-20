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
import org.tortodrone.character.SlotUpgrades;
import org.tortodrone.world.KnightCombat;
import org.tortodrone.world.TownMovement;
import org.tortodrone.world.ActorState;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.SceneCatalog;
import org.tortodrone.world.SceneEffect;
import org.tortodrone.world.FireCatalog;
import org.tortodrone.world.PartyRegistry;

/** Downstream session messages after the secure and authentication responses. */
public final class SessionCodec {
    public static final int SERVER_OID = 1;
    public static final int CONFIG_OID = 2;
    public static final int INVOCATION_OID = 3;
    public static final int EXCHANGE_OID = 4, EXCHANGE_SERVICE = 1;
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
        "com.threerings.projectx.admin.data.ProjectXAdminMarshaller",
        "com.threerings.projectx.sprites.data.BattleSpriteMarshaller",
        "com.threerings.projectx.auction.data.AuctionMarshaller",
        "com.threerings.projectx.mission.data.MissionMarshaller",
        // The supply depot's purchase tab requires the billing service at window
        // construction; the stock exchange window throws and wedges without it.
        "com.threerings.projectx.billing.data.BillingMarshaller");
    public static final int MAX_DOWNSTREAM_FRAME = 1024 * 1024;
    public static final int TCP_MESSAGES_PER_SECOND = 100;
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
            out.writeInt(EXCHANGE_OID); out.writeInt(0); out.writeInt(0); // exchange, PvP, PvP info
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
            out.writeInt(0);out.writeInt(0);out.writeInt(org.tortodrone.storage.TownStore.SPARK_COST);out.writeInt(0); // guild costs, dungeon/PvP spark costs
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
            out.writeInt(0); out.writeInt(0); out.writeFloat(.1f); // mail fees, recovered auction sale fee
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

    public byte[] exchange(short messageId,org.tortodrone.town.ExchangeState.Book book)throws IOException{
        return frame(out->{objectHeader(out,messageId,"com.threerings.projectx.exchange.data.ExchangeObject",EXCHANGE_OID);
            type(out,"com.threerings.projectx.exchange.data.ExchangeMarshaller");out.writeInt(EXCHANGE_OID);out.writeInt(EXCHANGE_SERVICE);
            out.writeInt(book.lastPrice());exchangeLevels(out,book.buys());exchangeLevels(out,book.sells());});
    }
    public byte[] exchangeChanged(org.tortodrone.town.ExchangeState.Book book)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",EXCHANGE_OID);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(3);
            attribute(out,EXCHANGE_OID,"lastPrice",o->value(o,book.lastPrice()));attribute(out,EXCHANGE_OID,"buyOffers",o->exchangeLevels(o,book.buys()));attribute(out,EXCHANGE_OID,"sellOffers",o->exchangeLevels(o,book.sells()));});
    }
    private void exchangeLevels(DataOutputStream out,List<org.tortodrone.town.ExchangeState.Level> levels)throws IOException{
        type(out,"[Lcom.threerings.projectx.exchange.data.ConsolidatedOffer;");out.writeInt(levels.size());
        for(var level:levels){type(out,"com.threerings.projectx.exchange.data.ConsolidatedOffer");out.writeInt(level.volume());out.writeInt(level.price());}
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
    public byte[] inputThrottle() throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UpdateThrottleMessage");out.writeShort(-1);
            out.writeInt(TCP_MESSAGES_PER_SECOND);
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

    /** Item forge callbacks resolve these keys in the stock item bundle. */
    public byte[] forgeFailure(int clientOid,short requestId,String reason)throws IOException{
        if(!java.util.Set.of("m.forge_already","m.forge_locked","m.insufficient_crystals","m.costupdate").contains(reason))return invocationFailure(clientOid,requestId,reason);
        return frame(out->{eventHeader(out,"InvocationResponseEvent",clientOid);out.writeShort(requestId);out.writeByte(0);out.writeBoolean(true);out.writeInt(1);value(out,reason);});
    }
    /** Listener method zero is the standard failure callback; stock message keys stay localizable. */
    public byte[] invocationFailure(int clientOid, short requestId, String reason) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.InvocationResponseEvent"); out.writeInt(clientOid);
            out.writeShort(requestId); out.writeByte(0);
            out.writeBoolean(true); out.writeInt(1);
            // MessageBundle treats a leading tilde as literal text; known stock keys translate.
            type(out, "java.lang.String"); out.writeUTF(reason != null && (reason.matches("m\\.[a-z0-9_]+")||reason.equals("e.not_use_busy")) ? reason : "~" + reason);
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
    public byte[] tellSucceeded(int clientOid,short requestId)throws IOException {return tellSucceeded(clientOid,requestId,null);}
    public byte[] awayMessage(int oid,String message)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"awayMessage");value(out,message);});
    }
    public byte[] tellSucceeded(int clientOid,short requestId,String awayMessage)throws IOException {
        return frame(out->{
            eventHeader(out,"InvocationResponseEvent",clientOid);
            out.writeShort(requestId);out.writeByte(1);out.writeBoolean(true);out.writeInt(2);
            value(out,0L);value(out,awayMessage);
        });
    }

    /** The social feed is empty on this server; its Result listener receives one empty list. */
    public byte[] emptyListResult(int oid, short requestId) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(0);
        });
    }

    /** Uplink News returns alternating property name/value strings that the client reads as a Properties. */
    public byte[] listResult(int oid, short requestId, List<String> values) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(values.size());
            for (String value : values) { type(out, "java.lang.String"); out.writeUTF(value); }
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
            // The stock HUD requires TUTORIAL (32) before exposing notification
            // controls. Knights enter Haven directly here, bypassing the intro.
            attribute(out,clientOid,"flags",o->{type(o,"java.lang.Integer");o.writeInt(32|(enabled?256:0)|(inspectionHidden?1024:0));});
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
            out.writeInt(profile.progress().rank());out.writeInt(profile.progress().prestige());
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
            var sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);if(sprite==null)out.writeShort(0);else sprite(out,profile,sprite);emptyDSet(out);
        });
    }

    private void roster(DataOutputStream out, List<CharacterStore.RosterEntry> knights) throws IOException {
        type(out, "[Lcom.threerings.projectx.data.AvailableKnight;"); out.writeInt(knights.size());
        for (CharacterStore.RosterEntry entry : knights) {
            Knight knight = entry.knight();
            type(out, "com.threerings.projectx.data.AvailableKnight");
            out.writeInt(knight.id()); type(out, "com.threerings.util.Name"); string(out, knight.name());
            out.writeInt(entry.rank()); string(out, null); out.writeInt(0); // rank, guild, session seconds
            value(out, entry.preview());
            emptyCollection(out); // Typed Set<Name>, no mail senders.
        }
    }

    void value(DataOutputStream out,Object value) throws IOException {
        if (value == null || value==FireCatalog.NullValue.INSTANCE) { out.writeShort(0);
        } else if (value instanceof Long number) { type(out,"java.lang.Long"); out.writeLong(number);
        } else if (value instanceof SessionDecoder.EnumValue e) {type(out,e.type());out.writeByte(e.ordinal());
        } else if (value instanceof Boolean flag) { type(out,"java.lang.Boolean"); out.writeBoolean(flag);
        } else if (value instanceof StarterCatalog.Model model) {
            type(out,"com.threerings.config.ConfigReference"); intern(out,model.name());
            type(out,"com.threerings.config.ArgumentMap"); out.writeInt(model.arguments().size());
            for (var argument : new java.util.TreeMap<>(model.arguments()).entrySet()) {
                intern(out,argument.getKey()); value(out,argument.getValue());
            }
        } else if (value instanceof java.util.List<?> list) {
            type(out,"java.util.ArrayList");out.writeInt(list.size());for(var element:list)value(out,element);
        } else if (value instanceof StarterCatalog.Colorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color.value());
        } else if (value instanceof StarterCatalog.TransColorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$TransNormal");out.writeInt(color.clazz());out.writeInt(color.value());
        } else if (value instanceof StarterCatalog.CustomColorization color) {
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$CustomOffsets");out.writeInt(color.clazz());
            type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Triplet");out.writeFloat(color.hue());out.writeFloat(color.saturation());out.writeFloat(color.value());
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
            type(out,"com.threerings.util.StreamableArrayList"); out.writeInt(21);
            attribute(out,oid,"knight",o -> name(o,knight.name()));
            attribute(out,oid,"color",o -> value(o,256 | knight.color()));
            attribute(out,oid,"eye",o -> { type(o,"com.threerings.projectx.data.ProjectXCodes$EyeType");o.writeByte(knight.eye()); });
            attribute(out,oid,"height",o -> { type(o,"com.threerings.projectx.data.ProjectXCodes$KnightHeight");o.writeByte(knight.height()); });
            attribute(out,oid,"bagId",o -> value(o,profile.worldId()));
            attribute(out,oid,"items",o -> items(o,knight,profile));
            attribute(out,oid,"equipment",o -> longs(o,profile.equipment()));
            attribute(out,oid,"loadouts",o -> currentLoadout(o,profile));
            attribute(out,oid,"quickbar",o -> longs(o,new long[4]));
            attribute(out,oid,"weaponSlots",o -> value(o,SlotUpgrades.weapons(profile)));
            attribute(out,oid,"trinketSlots",o -> value(o,SlotUpgrades.trinkets(profile)));
            attribute(out,oid,"crowns",o -> value(o,profile.crowns()));
            attribute(out,oid,"energy",o -> value(o,profile.energy()));
            attribute(out,oid,"showEnergy",o -> value(o,true));
            attribute(out,oid,"homeTown",this::townSummary);
            rank(out,oid,profile);
            attribute(out,oid,"prestige",o->value(o,profile.progress().prestige()));
            attribute(out,oid,"progressFlags",o->value(o,profile.progress().progressFlags()));
            attribute(out,oid,"stats",o->{type(o,"com.threerings.stats.data.StatSet");o.writeInt(0);});
            attribute(out,oid,"pawnId",o -> value(o,profile.worldId()));
            attribute(out,oid,"knightId",o -> value(o,profile.worldId()));
        });
    }
    public byte[] knightName(int oid,String knight)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"knight");name(out,knight);});
    }
    public byte[] equipPolicy(int oid,boolean station)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"equipPolicy");value(out,new SessionDecoder.EnumValue("com.threerings.projectx.data.PlayerObject$EquipPolicy",station?2:0));});
    }
    /** TudeyBodyObject.FORCE_CLIENT_ACTION, as emitted by ForceClientAction logic. */
    public byte[] controllerAction(int oid,String command,ActorState source)throws IOException {
        if(!java.util.Set.of("forge","show_arsenal_station","auction").contains(command))throw new IOException("Unsupported controller action");
        return frame(out->{eventHeader(out,"MessageEvent",oid);string(out,"forceClientAction");
            out.writeBoolean(true);out.writeInt(2); // MessageEvent's declared Object[] field.
            type(out,"com.threerings.tudey.config.ClientActionConfig$ControllerAction");string(out,command);string(out,"");
            sourceKey(out,source);
        });
    }

    private void sourceKey(DataOutputStream out,ActorState source)throws IOException {
        if(source==null)out.writeShort(0);
        else if(source.entity()!=null&&source.entity().entrySource()){
            if(!source.entity().key().matches("[0-9]+"))throw new IOException("Placed interaction has no numeric entry identity");
            type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,Integer.parseInt(source.entity().key()));
        }else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(source.id());}
    }

    public byte[] balances(int oid,WorldProfile profile) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,oid,"crowns",o->value(o,profile.crowns()));attribute(out,oid,"energy",o->value(o,profile.energy())); });
    }
    private void rank(DataOutputStream out,int oid,WorldProfile profile)throws IOException{
        attribute(out,oid,"rank",o->{type(o,"com.threerings.projectx.mission.data.RankPair");o.writeInt(profile.progress().rank());o.writeInt(profile.progress().subrank());});
    }
    public byte[] knightProgress(int oid,WorldProfile profile)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(3);rank(out,oid,profile);attribute(out,oid,"prestige",o->value(o,profile.progress().prestige()));attribute(out,oid,"progressFlags",o->value(o,profile.progress().progressFlags()));});
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
            eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(changed.size()+removed.size()+slots.size()+(slots.isEmpty()?0:1)+2);
            attribute(out,oid,"weaponSlots",o->value(o,SlotUpgrades.weapons(after)));
            attribute(out,oid,"trinketSlots",o->value(o,SlotUpgrades.trinkets(after)));
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

    public byte[] quickbar(int oid,long[] slots)throws IOException {
        if(slots.length!=4)throw new IOException("Invalid native quickbar size");
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(4);
            for(int slot=0;slot<4;slot++){
                type(out,"com.threerings.presents.dobj.ElementUpdatedEvent");out.writeInt(oid);string(out,"quickbar");
                value(out,slots[slot]);out.writeInt(slot);
            }
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
    public record FriendView(int world,String name,int color,String location,long lastSession) { }
    /** Native social UI observes entry events, including online/offline changes and renames. */
    public byte[] friends(int oid,List<FriendView> before,List<FriendView> after)throws IOException {
        var previous=new java.util.TreeMap<String,FriendView>(String.CASE_INSENSITIVE_ORDER);for(var friend:before)previous.put(friend.name(),friend);
        var current=new java.util.TreeMap<String,FriendView>(String.CASE_INSENSITIVE_ORDER);for(var friend:after)current.put(friend.name(),friend);
        var events=new java.util.ArrayList<Writer>();
        for(var friend:before)if(!current.containsKey(friend.name()))events.add(out->{partyEntry(out,oid,"friends","EntryRemovedEvent");name(out,friend.name());});
        for(var friend:after)if(!friend.equals(previous.get(friend.name())))events.add(out->{partyEntry(out,oid,"friends",previous.containsKey(friend.name())?"EntryUpdatedEvent":"EntryAddedEvent");
            type(out,"com.threerings.projectx.social.data.FriendEntry");name(out,friend.name());out.writeInt(friend.world());
            if(friend.location()==null){type(out,"com.threerings.projectx.social.data.Whereabouts$Offline");out.writeLong(friend.lastSession());}
            else {type(out,"com.threerings.projectx.social.data.Whereabouts$Online");string(out,friend.location());out.writeInt(0);out.writeInt(0);}
            out.writeInt(friend.color());
        });
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(events.size());for(var event:events)event.write(out);});
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
    public static final int CRAFT_SERVICE=22, UNBIND_SERVICE=23, ACCESSORY_SERVICE=24, VARIANT_SERVICE=25;
    /** The announced BillingMarshaller slot; the depot purchase tab resolves it at window build. */
    public static final int BILLING_SERVICE=SERVICES.size();
    public byte[] townDialog(int playerOid,int sceneOid,org.tortodrone.town.TownCatalog.Station station,ActorState source,long created,int service)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",playerOid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,playerOid,"sceneDialogCreated",o->value(o,created));
            attribute(out,playerOid,"sceneDialog",o->{
                if(station==null){o.writeShort(0);return;}
                String kind=station.kind();
                if(kind.equals("OpenDepot")){type(o,"com.threerings.projectx.exchange.data.DepotDialogInfo");o.writeShort(0);o.writeShort(0);o.writeFloat(0);o.writeShort(0);o.writeShort(0);string(o,station.type());return;}
                if(kind.equals("OpenAuction")){type(o,"com.threerings.projectx.auction.data.AuctionDialogInfo");if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}o.writeShort(0);o.writeShort(0);return;}
                if(kind.equals("OpenShop")){
                    type(o,"com.threerings.projectx.shop.data.ShopDialogInfo");
                    if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                    o.writeShort(0);o.writeShort(0);value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                    o.writeInt(station.shop().level());value(o,new SessionDecoder.EnumValue("com.threerings.projectx.shop.data.ShopDialogInfo$Type",station.type().equals("ENERGY")?1:station.type().equals("TOKEN")?2:0));
                    int preparer=List.of("BASIC","GREMLIN","LOCKED","LOCK_NON_STACKABLE","DEPOT","STATUE").indexOf(station.shop().preparer());value(o,new SessionDecoder.EnumValue("com.threerings.projectx.shop.util.ItemPreparer",Math.max(0,preparer)));o.writeBoolean(station.shop().perKnight());
                    type(o,"com.threerings.projectx.shop.data.ShopMarshaller");o.writeInt(sceneOid);o.writeInt(service);return;
                }
                if(kind.equals("OpenHarnessInstaller")){
                    type(o,"com.threerings.projectx.sprites.data.HarnessDialogInfo");
                    if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                    o.writeShort(0);o.writeShort(0);value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                    type(o,"com.threerings.projectx.sprites.data.HarnessInstallerMarshaller");o.writeInt(sceneOid);o.writeInt(service);return;
                }
                String dialog=kind.equals("OpenUnbinder")?"UnbindDialogInfo":kind.equals("OpenAccessorizer")?"AccessoryDialogInfo":kind.equals("OpenVariantMaker")?"VariantMakerDialogInfo":"CrafterDialogInfo";
                type(o,"com.threerings.projectx.craft.data."+dialog);
                if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                o.writeShort(0);o.writeShort(0);
                value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                if(dialog.equals("CrafterDialogInfo")){
                    type(o,"com.threerings.projectx.craft.data.CrafterDialogInfo$CrafterGroup");o.writeByte(station.group().equals("SPRITE")?1:0);
                    string(o,station.type().isEmpty()?null:station.type());o.writeShort(0);
                    type(o,"com.threerings.projectx.craft.data.CraftMarshaller");o.writeInt(sceneOid);o.writeInt(service);
                    if(station.recipes().isEmpty())o.writeShort(0);else{type(o,"[Lcom.threerings.projectx.craft.config.RecipeConfig$RecipeConfigReference;");o.writeInt(station.recipes().size());for(String recipe:station.recipes()){type(o,"com.threerings.projectx.craft.config.RecipeConfig$RecipeConfigReference");value(o,new StarterCatalog.Model(recipe,Map.of()));}}
                    o.writeShort(0);
                }else{String marshaller=dialog.equals("UnbindDialogInfo")?"UnbindMarshaller":dialog.equals("AccessoryDialogInfo")?"AccessoryMarshaller":"VariantMarshaller";type(o,"com.threerings.projectx.craft.data."+marshaller);o.writeInt(sceneOid);o.writeInt(service);}
            });});
    }
    public byte[] townShop(int oid,List<org.tortodrone.town.TownCatalog.Good> goods,List<Integer> available)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"uniqueDialog");type(out,"com.threerings.projectx.shop.data.UniqueShopInfo");out.writeBoolean(true);out.writeInt(goods.size());
            for(int i=0;i<goods.size();i++){var good=goods.get(i);type(out,"com.threerings.projectx.shop.data.UniqueShopInfo$GoodCount");type(out,"com.threerings.projectx.shop.config.GoodConfig$"+(good.token().isEmpty()?"Item":"TokenItem"));out.writeInt(good.price());out.writeInt(good.energy());out.writeInt(0);out.writeBoolean(false);out.writeInt(good.count());out.writeInt(good.limit());value(out,new StarterCatalog.Model(good.item(),Map.of()));if(!good.token().isEmpty()){value(out,new StarterCatalog.Model(good.token(),Map.of()));out.writeInt(good.tokenCount());}out.writeInt(available.get(i));}
        });
    }

    public byte[] townState(int oid,WorldProfile profile)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(5);
            attribute(out,oid,"newMailInfo",o->{type(o,"com.threerings.presents.dobj.DSet");var mail=profile.town().mail().stream().filter(m->!m.read()).toList();o.writeInt(mail.size());for(var m:mail){type(o,"com.threerings.projectx.uplink.data.NewMailInfo");o.writeLong(m.id());name(o,m.sender());value(o,new SessionDecoder.EnumValue("com.threerings.projectx.uplink.data.MailType",m.nativeType()));}});
            attribute(out,oid,"recipes",o->{type(o,"com.threerings.presents.dobj.DSet");o.writeInt(profile.town().recipes().size());for(var recipe:profile.town().recipes()){type(o,"com.threerings.projectx.craft.data.Recipe");o.writeBoolean(true);o.writeLong(recipe.id());string(o,recipe.config());}});
            attribute(out,oid,"harnesses",o->{type(o,"com.threerings.presents.dobj.DSet");o.writeInt(profile.town().harnesses().size());for(var h:profile.town().harnesses())harness(o,h);});
            attribute(out,oid,"sprites",o->{type(o,"com.threerings.presents.dobj.DSet");o.writeInt(profile.town().sprites().size());for(var sprite:profile.town().sprites())sprite(o,profile,sprite);});
            attribute(out,oid,"equippedSprite",o->value(o,profile.town().equippedSprite()));});
    }
    /** Single stock PlayerObject.recipes entry; open alchemy dialogs refresh on this event. */
    public byte[] recipeAdded(int oid,org.tortodrone.town.TownState.LearnedRecipe recipe)throws IOException {
        return frame(out->{eventHeader(out,"EntryAddedEvent",oid);string(out,"recipes");
            type(out,"com.threerings.projectx.craft.data.Recipe");out.writeBoolean(true);out.writeLong(recipe.id());string(out,recipe.config());});
    }
    private void harness(DataOutputStream out,org.tortodrone.town.TownState.Harness h)throws IOException {type(out,"com.threerings.projectx.sprites.data.Harness");out.writeBoolean(true);out.writeLong(h.id());value(out,new StarterCatalog.Model(h.config(),Map.of()));}
    private void sprite(DataOutputStream out,WorldProfile owner,org.tortodrone.town.TownState.Sprite s)throws IOException {
        var h=owner.town().harnesses().stream().filter(v->v.id()==s.harness()).findFirst().orElse(new org.tortodrone.town.TownState.Harness(0,"Iron Harness"));sprite(out,owner.worldId(),s,h);
    }
    private void sprite(DataOutputStream out,int worldId,org.tortodrone.town.TownState.Sprite s,org.tortodrone.town.TownState.Harness h)throws IOException {
        type(out,"com.threerings.projectx.sprites.data.BattleSprite");string(out,s.name());out.writeFloat(s.heat());out.writeBoolean(s.lastFull()!=null);if(s.lastFull()!=null)out.writeLong(s.lastFull());out.writeInt(s.level());out.writeLong(s.fullStamp());
        out.writeBoolean(true);out.writeInt(3);for(int skill=0;skill<3;skill++){type(out,"com.threerings.projectx.sprites.data.BattleSprite$SkillBonus");primitiveInts(out,new int[]{s.powers().get(skill*3),s.powers().get(skill*3+1),s.powers().get(skill*3+2)});value(out,new SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpriteCodes$Ultimate",s.ultimates().get(skill)));}
        out.writeInt(s.perkLevel());out.writeBoolean(true);out.writeInt(s.perks().size());for(int perk:s.perks().stream().sorted().toList())value(out,new SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpritePerk",perk));
        value(out,s.perk()==null?null:new SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpritePerk",s.perk()));out.writeInt(s.id());out.writeInt(worldId);out.writeUTF(s.config());harness(out,h==null?new org.tortodrone.town.TownState.Harness(0,"Iron Harness"):h);
    }
    public byte[] itemAcquireDelay(int oid,int millis)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"itemAcquireDelay");value(out,millis);});
    }
    public byte[] townResult(int oid,short request,Object result,WorldProfile profile)throws IOException {
        listingViewer=profile==null?0:profile.worldId();
        return frame(out->{eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(1);out.writeBoolean(true);out.writeInt(1);
            if(result instanceof org.tortodrone.town.TownState.Sprite s)sprite(out,profile,s);else townValue(out,result);});
    }

    private void townValue(DataOutputStream out,Object value)throws IOException {
        if(value instanceof SessionDecoder.Multiset multiset){type(out,"com.google.common.collect.HashMultiset");out.writeInt(multiset.counts().size());for(var entry:multiset.counts().entrySet()){value(out,entry.getKey());out.writeInt(entry.getValue());}}
        else if(value instanceof java.util.List<?> list){type(out,"java.util.ArrayList");out.writeInt(list.size());for(var element:list)townValue(out,element);}
        else if(value instanceof org.tortodrone.town.ExchangeState.Offer offer){type(out,"com.threerings.coin.data.CoinExOfferInfo");out.writeInt(offer.id());out.writeBoolean(offer.buy());out.writeInt(offer.price());out.writeInt(offer.volume());}
        else if(value instanceof org.tortodrone.town.TownCatalog.DepotOffers offers){
            type(out,"java.util.HashMap");out.writeInt(offers.goods().size());for(var entry:new java.util.TreeMap<>(offers.goods()).entrySet()){
                value(out,entry.getKey());type(out,"com.threerings.projectx.mission.data.RankGood");var g=entry.getValue().good();
                type(out,"com.threerings.projectx.shop.config.GoodConfig$Item");out.writeInt(g.price());out.writeInt(g.energy());out.writeInt(entry.getValue().originalPrice());out.writeBoolean(entry.getValue().sale());out.writeInt(g.count());out.writeInt(g.limit());value(out,new StarterCatalog.Model(g.item(),Map.of()));
                out.writeBoolean(entry.getValue().featured());string(out,entry.getValue().promoHeader());string(out,entry.getValue().promoDetail());
            }
        }
        else if(value instanceof org.tortodrone.town.AuctionState.Page page){type(out,"com.threerings.projectx.auction.data.ListingsPage");out.writeInt(page.pages());out.writeBoolean(true);out.writeInt(page.listings().size());for(var listing:page.listings())listing(out,listing);}
        else if(value instanceof org.tortodrone.town.AuctionState.Listing listing)listing(out,listing);
        else if(value instanceof org.tortodrone.town.AuctionState.Update update){type(out,"com.threerings.projectx.auction.data.ListingUpdate");listing(out,update.listing());string(out,update.message());}
        else if(value instanceof org.tortodrone.town.AuctionState.Mail m){type(out,"com.threerings.projectx.uplink.data.ReceivedMail");out.writeLong(m.id());name(out,m.sender());out.writeLong(m.sent());string(out,auctionMailText(m.subject()));string(out,auctionMailText(m.body()));
            if(m.friendRequest()){type(out,"com.threerings.projectx.uplink.data.FriendRequest");out.writeInt(m.friendWorld());}
            else if(m.claimed()||m.item()==null&&m.crowns()==0)out.writeShort(0);else{type(out,"com.threerings.projectx.uplink.data.MultiAttachment");
                out.writeBoolean(m.item()!=null);if(m.item()!=null){out.writeInt(1);value(out,new StarterCatalog.Model(m.item().config(),Map.of()));out.writeInt(m.item().count());}out.writeInt(m.crowns());out.writeInt(0);}
            value(out,new SessionDecoder.EnumValue("com.threerings.projectx.uplink.data.MailType",m.nativeType()));out.writeByte((m.read()?0:1)|(m.claimed()?2:0));
        }else value(out,value);
    }
    private void listing(DataOutputStream out,org.tortodrone.town.AuctionState.Listing a)throws IOException{
        type(out,"com.threerings.projectx.auction.data.ListingInfo");out.writeLong(a.id());int status=a.bidder()==0?0:a.participated()?(a.mine()?1:3):1;
        // The caller's winning status is carried explicitly by the view, separate from seller ownership.
        if(a.participated()&&a.bidder()!=0&&!a.mine())status=a.bidder()==listingViewer?2:3;else if(!a.participated()&&a.watching())status=4;
        value(out,new SessionDecoder.EnumValue("com.threerings.projectx.auction.data.BidStatus",status));
        long hours=(a.expires()-System.currentTimeMillis())/3600000;int time=a.cancelled()?6:a.ended()||a.expires()<=System.currentTimeMillis()?5:hours<1?0:hours<4?1:hours<12?2:hours<48?3:4;
        value(out,new SessionDecoder.EnumValue("com.threerings.projectx.auction.data.TimeLeft",time));out.writeInt(a.buy());out.writeInt(a.bid());string(out,a.item().config());out.writeBoolean(false);out.writeInt(a.item().count());out.writeBoolean(a.mine());if(a.featured()){type(out,"com.threerings.projectx.auction.data.FeaturedInfo");out.writeShort(a.featuredRemaining());out.writeShort(a.featuredTotal());}else out.writeShort(0);
    }
    private static String auctionMailText(String text){
        return text!=null&&text.matches("%auction:[sm]\\.(auction|bid)_.*")?"%uplink:"+text.substring(9):text;
    }
    private int listingViewer;

    public byte[] conversationDialog(int playerOid,int sceneOid,org.tortodrone.world.SceneConversation conversation,
            String nodeId,ActorState source,long created)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",playerOid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            attribute(out,playerOid,"sceneDialogCreated",o->value(o,created));
            attribute(out,playerOid,"sceneDialog",o->{
                if(conversation==null){o.writeShort(0);return;}
                var node=conversation.nodes().get(nodeId);
                type(o,"com.threerings.projectx.data.ConversationDialogInfo");
                sourceKey(o,source);
                vector(o,source.x(),source.y());o.writeFloat(source.rotation());value(o,node.transientModel());o.writeShort(0);
                value(o,node.userInterface());value(o,conversation.model());value(o,node.animation());o.writeShort(0);
                string(o,conversation.name());string(o,conversation.bundle());string(o,node.message());
                type(o,"[Ljava.lang.String;");int count=node.responses().size();o.writeInt(count);
                o.writeShort((count+7)/8);
                for(int i=0;i<count;i+=8)o.writeByte((1<<Math.min(8,count-i))-1);
                for(var response:node.responses())o.writeUTF(response.text());
                type(o,"com.threerings.projectx.data.ConversationMarshaller");o.writeInt(sceneOid);o.writeInt(org.tortodrone.world.SceneConversation.SERVICE);o.writeLong(0);
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
        return partyOid(oid,scene.map().town()?0:interiorPartyOid(scene));
    }
    public byte[] partyOid(int player,int party)throws IOException{return frame(out->{eventHeader(out,"AttributeChangedEvent",player);string(out,"partyOid");value(out,party);});}
    public byte[] chatChannel(short message,int oid,int chatType)throws IOException {
        if(chatType!=6&&chatType!=7)throw new IOException("Unsupported chat channel type");
        return frame(out->{objectHeader(out,message,"com.threerings.projectx.data.ChatObject",oid);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(oid);out.writeInt(org.tortodrone.world.SceneChat.SERVICE);
            type(out,"com.threerings.presents.dobj.OidList");out.writeBoolean(true);out.writeInt(0);out.writeInt(0);
            type(out,"com.threerings.projectx.data.ChatType");out.writeByte(chatType);
        });
    }
    public byte[] sceneLocation(int oid,SceneRegistry.Instance scene)throws IOException{return sceneLocation(oid,scene,0,0);}
    public byte[] sceneLocation(int oid,SceneRegistry.Instance scene,int general,int trade) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(scene.map().town()?5:4);
            if(scene.map().town())attribute(out,oid,"lastTownSceneId",o->value(o,scene.map().id()));
            attribute(out,oid,"zoneId",o->value(o,scene.map().zone()));
            attribute(out,oid,"zoneGeneralOid",o->value(o,general));attribute(out,oid,"zoneTradeOid",o->value(o,trade));
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
            var icons=scene.map().content().mapIcons();
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(icons.size());int marker=1;
            for(var icon:icons){type(out,"com.threerings.projectx.data.IconMapEntry$FixedEntry");out.writeInt(marker++);string(out,icon.icon());vector(out,icon.x(),icon.y());}
            if(scene.map().town()){type(out,"com.threerings.projectx.town.data.TownSceneMarshaller");out.writeInt(scene.oid());out.writeInt(11);}
        });
    }

    /** Connection-local single-member transport required by stock level controllers. */
    public static int interiorPartyOid(SceneRegistry.Instance scene){return 500+scene.oid();}
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneRegistry.Instance scene)throws IOException {
        return interiorParty(message,partyOid,playerOid,knight,profile,scene,new org.tortodrone.world.SceneHealth.State(1,1,0));
    }
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneRegistry.Instance scene,org.tortodrone.world.SceneHealth.State health)throws IOException {
        return interiorParty(message,partyOid,playerOid,knight,profile,scene,health,org.tortodrone.world.SceneStatus.State.NORMAL);
    }
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneRegistry.Instance scene,org.tortodrone.world.SceneHealth.State health,org.tortodrone.world.SceneStatus.State status)throws IOException {
        return party(message,partyOid,List.of(new PartyRegistry.Participant(playerOid,profile.worldId(),0,knight,profile,health,status)),scene,false);
    }
    public byte[] party(short message,PartyRegistry.View view,SceneRegistry.Instance scene)throws IOException{return party(message,view.oid(),view.members(),scene,true);}
    private byte[] party(short message,int partyOid,List<PartyRegistry.Participant> members,SceneRegistry.Instance scene,boolean managed)throws IOException {
        return frame(out->{
            boolean training=scene.map().kind().equals("training");
            objectHeader(out,message,"com.threerings.projectx.dungeon.data.LevelPartyObject",partyOid);
            type(out,"com.threerings.projectx.dungeon.data.PartyMarshaller");out.writeInt(partyOid);out.writeInt(20);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(partyOid);out.writeInt(org.tortodrone.world.SceneChat.PARTY_SERVICE);
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(members.size());for(var member:members)partyMember(out,member);
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(members.size());for(var member:members)partyHealth(out,member);
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(members.size());for(var member:members)partyStatus(out,member);
            emptyCollection(out);out.writeShort(0); // Native party spawn is unset; the scene owns arrival.
            out.writeInt(0);out.writeBoolean(managed||!scene.map().content().items().isEmpty());out.writeLong(0);
            out.writeShort(0);out.writeInt(managed?PartyRegistry.CAPACITY:training?8:1);out.writeInt(0); // No dungeon matchmaking service or gate.
            out.writeShort(0);type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty");out.writeByte(0);
            emptyDSet(out);emptyCollection(out);out.writeInt(0);
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(scene.map().kind().equals("level")?0:3);
            primitiveInts(out,new int[]{0});out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(!managed);
            emptyDSet(out);string(out,null);out.writeInt(0);
            type(out,"com.threerings.projectx.dungeon.data.LevelPartyObject$FloorType");out.writeByte(4);
            type(out,"com.threerings.projectx.dungeon.data.LevelPartyObject$FloorType");out.writeByte(4);
            out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);emptyDSet(out);
            for(int i=0;i<4;i++)out.writeInt(0);out.writeLong(0);emptyCollection(out);string(out,null);
            out.writeBoolean(true);out.writeInt(0); // Empty typed Multiset<Integer>.
        });
    }
    private void partyMember(DataOutputStream out,PartyRegistry.Participant member)throws IOException {
        var knight=member.knight();type(out,"com.threerings.projectx.dungeon.data.PartyMember");out.writeInt(member.playerOid());out.writeInt(member.order());
        name(out,knight.name());out.writeInt(256|knight.color());value(out,new StarterCatalog.Model(knight.helm(),Map.of()));
        out.writeShort(0);type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(0);out.writeLong(0);
    }
    private void partyHealth(DataOutputStream out,PartyRegistry.Participant member)throws IOException {var health=member.health();type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(member.playerOid());out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());}
    private void partyStatus(DataOutputStream out,PartyRegistry.Participant member)throws IOException {var status=member.status();type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(member.playerOid());conditions(out,status);primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(member.health().reviveFlags());}
    public byte[] partyChanges(PartyRegistry.View before,PartyRegistry.View after)throws IOException {
        var events=new ArrayList<Writer>();var old=new HashMap<Integer,PartyRegistry.Participant>();for(var m:before.members())old.put(m.playerOid(),m);
        var current=new java.util.HashSet<Integer>();for(var member:after.members()){
            current.add(member.playerOid());var previous=old.get(member.playerOid());
            if(previous==null||!previous.knight().equals(member.knight())||previous.order()!=member.order())events.add(out->{partyEntry(out,after.oid(),"members",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyMember(out,member);});
            if(previous==null||!previous.health().equals(member.health()))events.add(out->{partyEntry(out,after.oid(),"health",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyHealth(out,member);});
            if(previous==null||!previous.status().equals(member.status())||previous.health().reviveFlags()!=member.health().reviveFlags())events.add(out->{partyEntry(out,after.oid(),"status",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyStatus(out,member);});
        }
        for(var member:before.members())if(!current.contains(member.playerOid()))for(String field:List.of("members","health","status"))events.add(out->{partyEntry(out,after.oid(),field,"EntryRemovedEvent");value(out,member.playerOid());});
        return frame(out->{eventHeader(out,"CompoundEvent",after.oid());type(out,"com.threerings.util.StreamableArrayList");out.writeInt(events.size());for(var event:events)event.write(out);});
    }
    private void partyEntry(DataOutputStream out,int party,String field,String event)throws IOException {type(out,"com.threerings.presents.dobj."+event);out.writeInt(party);string(out,field);}
    public byte[] partyInvitation(int player,PartyRegistry.Invitation invitation)throws IOException {
        return frame(out->{eventHeader(out,invitation.add()?"EntryAddedEvent":"EntryRemovedEvent",player);string(out,"notifications");
            if(!invitation.add()){value(out,"PARTY_INV:"+invitation.partyOid());return;}
            type(out,"com.threerings.projectx.social.data.Notification$PartyInvite");
            type(out,"com.threerings.projectx.dungeon.data.PartyKey");string(out,null);out.writeInt(invitation.partyOid());
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty");out.writeByte(0);
            type(out,"[Lcom.threerings.util.Name;");out.writeInt(invitation.names().size());for(String knight:invitation.names())name(out,knight);
            string(out,null);out.writeInt(0);out.writeInt(0);string(out,"Advanced Training Hall");out.writeInt(invitation.inviter());
        });
    }

    public byte[] partyHealth(int partyOid,int playerOid,org.tortodrone.world.SceneHealth.State health)throws IOException {
        return frame(out->{eventHeader(out,"EntryUpdatedEvent",partyOid);string(out,"health");
            type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(playerOid);
            out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());});
    }
    public byte[] partyVitals(int partyOid,int playerOid,org.tortodrone.world.SceneHealth.State health,org.tortodrone.world.SceneStatus.State status)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",partyOid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(2);
            type(out,"com.threerings.presents.dobj.EntryUpdatedEvent");out.writeInt(partyOid);string(out,"health");
            type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(playerOid);out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());
            type(out,"com.threerings.presents.dobj.EntryUpdatedEvent");out.writeInt(partyOid);string(out,"status");
            type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(playerOid);conditions(out,status);primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(health.reviveFlags());
        });
    }
    public byte[] reviveFailure(int playerOid,short request,String reason)throws IOException{
        if(!java.util.Set.of("m.costupdate","m.no_revive","m.nsf_energy").contains(reason))return invocationFailure(playerOid,request,reason);
        return frame(out->{eventHeader(out,"InvocationResponseEvent",playerOid);out.writeShort(request);out.writeByte(0);out.writeBoolean(true);out.writeInt(1);value(out,reason);});
    }
    public byte[] partyDamage(int partyOid,org.tortodrone.world.SceneCombatEvent event)throws IOException {
        return frame(out->{type(out,PREFIX+"EventNotification");out.writeShort(-1);
            type(out,"com.threerings.projectx.dungeon.data.PartyObject$DamageEvent");out.writeInt(partyOid);
            string(out,"damage");out.writeInt(event.timestamp());out.writeInt(event.actor());out.writeInt(event.amount());out.writeInt(event.source());
            type(out,"com.threerings.projectx.dungeon.util.DamageSummary$Performance");
            out.writeByte(switch(event.performance()){case "NORMAL_SMALL"->6;case "NORMAL_MEDIUM"->7;case "NORMAL_LARGE"->8;default->1;});
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
    private static void primitiveInts(DataOutputStream out,int[] values)throws IOException{out.writeBoolean(values!=null);if(values==null)return;out.writeInt(values.length);for(int v:values)out.writeInt(v);}
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
            boolean gift=definition!=null&&definition.type().equals("GiftBox");
            String subtype=gift?"GiftBoxItem":item.slot()!=WorldProfile.Slot.OTHER?"LevelItem":definition==null?"Item":switch(definition.variant()) {
                case ITEM -> "Item";case LEVEL_ITEM -> "LevelItem";case SEED_ITEM -> "SeedItem";case UPGRADE_ITEM -> "UpgradingItem";case FURNITURE_ITEM -> "FurniItem";
            };
            // Inactive UpgradingItem adds no fields to the common Item payload.
            type(out,"com.threerings.projectx.item.data."+subtype);
            if(definition!=null&&definition.variant()==ItemCatalog.Variant.FURNITURE_ITEM)out.writeInt(0); // native undeployed count
            if(gift){
                var record=item.gift();out.writeBoolean(record!=null);if(record!=null){
                    out.writeInt(record.contents().size());for(var content:record.contents()){value(out,giftReference(content));out.writeInt(content.count());}
                }
                string(out,record==null?null:record.message());out.writeInt(record==null?0:record.wrapperId());string(out,record==null?null:record.wrapperName());
            }
            if(seeded)out.writeLong(definition.seed());
            boolean level=item.slot()!=WorldProfile.Slot.OTHER||definition!=null&&definition.variant()==ItemCatalog.Variant.LEVEL_ITEM;
            if(level) {
                out.writeInt(item.level());out.writeFloat(item.heatProgress());out.writeFloat(0);
                if(item.variants().isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(item.variants().size());for(var variant:item.variants())value(out,variant);}
                var accessories=item.nativeAccessories(costume);out.writeBoolean(!accessories.isEmpty());if(!accessories.isEmpty()){out.writeInt(accessories.size());for(String accessory:accessories)value(out,new StarterCatalog.Model(accessory,Map.of()));}
            }
            // Stock Item.o / LevelItem.a compare this configuration ID, not the
            // config name. A shared zero leaves the previous weapon's sprite and
            // attack animations active after equipping a different weapon.
            out.writeLong(item.id());out.writeInt(worldId);out.writeInt(definition==null?0:definition.stockId());out.writeUTF(item.config());out.writeInt(item.count());value(out,item.expiresAt());
            // Stock Item.writeObject: bit 2 is the mutable binding flag (aTY),
            // tested by Cs(). Stock cv(true) sets both permanent and binding flags.
            out.writeByte((item.bound() || definition!=null&&definition.locked() ? 2 : 0) | (item.permanent() ? 1 : 0) | (item.cursed()?4:0));
    }
    private StarterCatalog.Model giftReference(WorldProfile.Item item){
        var args=new java.util.TreeMap<String,Object>();
        if(item.slot()!=WorldProfile.Slot.OTHER){args.put("_setLevel",item.level());args.put("_setHeat",item.heatProgress());}
        if(!item.variants().isEmpty())args.put("_setVariants",new FireCatalog.ListValue("com.threerings.config.ConfigReference",new java.util.ArrayList<>(item.variants())));
        var accessories=item.nativeAccessories("");if(!accessories.isEmpty())args.put("_setAccessories",new FireCatalog.ListValue("com.threerings.config.ConfigReference",accessories.stream().map(a->(Object)new StarterCatalog.Model(a,Map.of())).toList()));
        if(item.bound())args.put("_createLocked",true);
        return new StarterCatalog.Model(item.config(),args);
    }
    private void currentLoadout(DataOutputStream out,WorldProfile profile) throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1+profile.town().loadouts().size());
        loadout(out,profile);
        for(var saved:profile.town().loadouts().stream().sorted(java.util.Comparator.comparing(l->l.name().toLowerCase(java.util.Locale.ROOT))).toList())loadout(out,saved,profile);
    }
    private void loadoutUpdated(DataOutputStream out,int oid,WorldProfile profile)throws IOException {
        type(out,"com.threerings.presents.dobj.EntryUpdatedEvent");out.writeInt(oid);string(out,"loadouts");
        loadout(out,profile);
    }
    public byte[] loadouts(int oid,WorldProfile profile)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"loadouts");currentLoadout(out,profile);});
    }
    public byte[] loadoutChanges(int oid,WorldProfile before,WorldProfile after)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");
            var old=new java.util.LinkedHashMap<String,org.tortodrone.town.TownState.Loadout>();
            var current=new java.util.LinkedHashMap<String,org.tortodrone.town.TownState.Loadout>();
            for(var l:before.town().loadouts())old.put(l.name(),l);for(var l:after.town().loadouts())current.put(l.name(),l);
            int count=1+(int)old.keySet().stream().filter(k->!current.containsKey(k)).count()
                +(int)current.values().stream().filter(l->!l.equals(old.get(l.name()))).count();
            out.writeInt(count);
            loadoutUpdated(out,oid,after);
            for(String key:old.keySet())if(!current.containsKey(key))entryRemoved(out,oid,"loadouts",key.toLowerCase(java.util.Locale.ROOT));
            for(var l:current.values())if(!l.equals(old.get(l.name()))){event(out,old.containsKey(l.name())?"EntryUpdatedEvent":"EntryAddedEvent",oid);string(out,"loadouts");loadout(out,l,after);}
        });
    }
    private void loadout(DataOutputStream out,org.tortodrone.town.TownState.Loadout saved,WorldProfile profile)throws IOException {
        loadout(out,saved.name(),saved.equipment().stream().mapToLong(Long::longValue).toArray(),saved.sprite(),saved.harness(),saved.perk(),saved.pvp(),loadoutRarity(profile,saved.equipment().stream().mapToLong(Long::longValue).toArray()));
    }
    private void loadout(DataOutputStream out,WorldProfile profile)throws IOException {
        var sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);
        loadout(out,"",profile.equipment(),profile.town().equippedSprite(),sprite==null?0:sprite.harness(),sprite==null?null:sprite.perk(),false,loadoutRarity(profile,profile.equipment()));
    }
    private int loadoutRarity(WorldProfile profile,long[] equipment){
        int rarity=0;for(long id:equipment)if(id!=0)for(var item:profile.items())if(item.id()==id){var definition=itemCatalog.find(item.config());if(definition!=null)rarity=Math.max(rarity,definition.rarity());}
        return rarity;
    }
    private void loadout(DataOutputStream out,String name,long[] equipment,int sprite,long harness,Integer perk,boolean pvp,int rarity)throws IOException {
        type(out,"com.threerings.projectx.data.Loadout");
        int[] slots={0,1,2,3,4,15,6,7,8,9,10,11};
        out.writeBoolean(true);out.writeInt(slots.length);for(int slot:slots)out.writeLong(equipment[slot]);
        out.writeBoolean(pvp);out.writeInt(rarity);out.writeInt(sprite);out.writeLong(harness);
        value(out,perk==null?null:new SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpritePerk",perk));string(out,name);
    }

    private void townSummary(DataOutputStream out)throws IOException {
        var entry=SceneRegistry.system().entry().map();
        type(out,"com.threerings.projectx.town.data.TownSummary");string(out,entry.townName());string(out,"town");
        emptyObjectArray(out,"com.threerings.opengl.renderer.config.ColorizationConfig",false);string(out,null);
        out.writeInt(0);out.writeInt(entry.id());out.writeInt(entry.zone());
    }
    private void occupant(DataOutputStream out,ActorState actor)throws IOException {
        type(out,"com.threerings.projectx.data.ProjectXOccupantInfo");out.writeBoolean(true);out.writeInt(actor.clientOid());name(out,actor.name());out.writeByte(0);out.writeInt(actor.id());
        var progress=actor.sceneKnight()==null?org.tortodrone.character.KnightProgress.RECRUIT:actor.sceneKnight().progress();
        type(out,"com.threerings.projectx.data.PlayerObject$Type");out.writeByte(actor.gameMaster()?2:0);out.writeInt(actor.color());out.writeShort(0);type(out,"com.threerings.projectx.data.ProjectXCodes$PrestigeRank");out.writeByte(progress.prestigeRank());out.writeInt(progress.rank());
    }
    private void actorSprite(DataOutputStream out,ActorState actor)throws IOException{
        var state=actor.sceneKnight();if(state==null||state.sprite()==null)out.writeShort(0);else sprite(out,actor.id(),state.sprite(),state.harness());
    }
    private void actor(DataOutputStream out,ActorState a,boolean interior)throws IOException {
        if(a.pet()!=null){petActor(out,a);return;}
        if(a.projectile()!=null){projectile(out,a);return;}
        if(a.entity()!=null){sceneEntity(out,a);return;}
        type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(TownMovement.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());
        value(out,a.sceneKnight()==null?null:a.sceneKnight().activityData());interaction(out,a.interaction());string(out,null);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());actorSprite(out,a);
        if(interior) {
            var combat=a.combat();
            weapon(out,a.id(),combat);string(out,combat==null?null:combat.shield());string(out,combat==null?null:combat.shieldCostume());conditions(out,a.sceneKnight()==null?org.tortodrone.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
            out.writeInt(0);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().hitTime());
            out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
            out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteActor());out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteAttack());out.writeInt(combat==null?0:combat.nextDodge());
            statusModifiers(out,a.sceneKnight()==null?org.tortodrone.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }
    private void actorDelta(DataOutputStream out,ActorState a,ActorState baseline,boolean interior)throws IOException {
        // ActorDelta is final: its typed array omits a per-element type header.
        if(a.pet()!=null){petDelta(out,a);return;}
        if(a.projectile()!=null){projectileDelta(out,a);return;}
        if(a.entity()!=null){sceneEntityDelta(out,a);return;}
        out.writeInt(a.id());type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");
        boolean configChanged = baseline == null || !a.config().equals(baseline.config());
        // Preserve the reference's config object on ordinary movement; update it for actual gear changes.
        // The stock controller predicts directly on its first snapshot. Always replace activity
        // fields so that local inventory prediction cannot leak into subsequent delta baselines.
        boolean combatChanged=interior&&(a.combat()!=null||baseline!=null&&baseline.combat()!=null);
        boolean sceneData=a.sceneKnight()!=null||baseline!=null&&baseline.sceneKnight()!=null;
        boolean spriteChanged=baseline==null||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().sprite(),baseline.sceneKnight()==null?null:baseline.sceneKnight().sprite())||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().harness(),baseline.sceneKnight()==null?null:baseline.sceneKnight().harness());
        int second=a.interaction()!=null||baseline!=null&&baseline.interaction()!=null?0x1d:1;
        out.writeByte(configChanged ? 0xdd : 0xdc);out.writeByte(second|(sceneData?0x42:0)|(spriteChanged?0x80:0));
        // Mutable Dungeoneer fields: weapon 16, shields 17/18, health 22/23,
        // impulse 24, hit time 26, chain 27/28, dodge deadline 31 and modifiers 33/34. Id/created are DeltaFinal.
        if(interior){out.writeByte((combatChanged?0xf7:0)|(sceneData?8:0));out.writeByte((combatChanged?0x99:0)|(sceneData?0x64:0));out.writeByte((combatChanged?6:0)|(sceneData?1:0));}
        if (configChanged) value(out,a.config());
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());
        out.writeInt(a.activity());out.writeInt(a.activityStarted());
        if(sceneData)value(out,a.sceneKnight()==null?null:a.sceneKnight().activityData());
        if(a.interaction()!=null||baseline!=null&&baseline.interaction()!=null)interaction(out,a.interaction());
        if(sceneData)out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());
        if(spriteChanged)actorSprite(out,a);
        if(combatChanged) {
            var combat=a.combat();weapon(out,a.id(),combat);value(out,combat==null?null:combat.shield());value(out,combat==null?null:combat.shieldCostume());
        }
        if(interior&&sceneData)conditions(out,a.sceneKnight()==null?org.tortodrone.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
        if(combatChanged) {
            var combat=a.combat();
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
        }
        if(interior&&sceneData)out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().hitTime());
        if(combatChanged){var combat=a.combat();out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
        }
        if(interior&&sceneData){out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteActor());out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteAttack());}
        if(combatChanged){var combat=a.combat();out.writeInt(combat==null?0:combat.nextDodge());
        }
        if(interior&&sceneData)statusModifiers(out,a.sceneKnight()==null?org.tortodrone.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
        if(combatChanged) {
            var combat=a.combat();
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }
    private void equipmentModifiers(DataOutputStream out,org.tortodrone.world.EquipmentCatalog.Modifiers modifiers)throws IOException {
        var value=modifiers==null?org.tortodrone.world.EquipmentCatalog.Modifiers.ZERO:modifiers;
        type(out,"com.threerings.projectx.item.data.ImmutableEquipmentModifiers");
        out.writeFloat(value.chargeTime());out.writeFloat(value.speed());out.writeFloat(value.attackSpeed());out.writeFloat(value.defendingSpeed());
    }
    private void interaction(DataOutputStream out,org.tortodrone.world.InteractionState interaction)throws IOException {
        if(interaction==null){out.writeShort(0);out.writeShort(0);out.writeShort(0);return;}
        if(!java.util.Set.of("Interact","Talk","Shop","Lift","Resurrect").contains(interaction.action()))throw new IOException("Unsupported interaction action");
        type(out,"com.threerings.projectx.config.ActionDesc$"+interaction.action());
        if(interaction.entryKey()!=null){type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,Integer.parseInt(interaction.entryKey()));}
        else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(interaction.actorId());}
        value(out,interaction.description());
    }
    private void petActor(DataOutputStream out,ActorState a)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(TownMovement.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);
        petFields(out,a,false);
    }
    private void petFields(DataOutputStream out,ActorState a,boolean delta)throws IOException {
        var pet=a.pet();sprite(out,pet.knight(),pet.sprite(),pet.harness());out.writeInt(pet.knight());out.writeBoolean(pet.follow());if(delta){type(out,"[I");out.writeInt(pet.nextSkill().size());for(int next:pet.nextSkill())out.writeInt(next);}else primitiveInts(out,pet.nextSkill().stream().mapToInt(Integer::intValue).toArray());
        out.writeInt(pet.shieldHealth());out.writeInt(pet.shieldMaximum());if(delta)value(out,pet.shieldPower());else string(out,pet.shieldPower());
    }
    private void petDelta(DataOutputStream out,ActorState a)throws IOException {
        out.writeInt(a.id());type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");
        // Actor/Active/Agent mutable field order, followed by the seven SpriteActor fields.
        int mask=0x1ffdc;out.writeByte(mask);out.writeByte(mask>>>8);out.writeByte(mask>>>16);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);petFields(out,a,true);
    }
    private void projectile(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        type(out,p.nativeType().equals("ACTOR")?"com.threerings.tudey.data.actor.Actor":"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
        value(out,p.config());out.writeInt(actor.id());out.writeInt(p.created());out.writeInt(p.destroyed());
        vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(p.orbit()!=null){var orbit=p.orbit();out.writeFloat(orbit.radius());out.writeInt(orbit.followId());
            vector(out,p.originX(),p.originY());vector(out,orbit.relativeX(),orbit.relativeY());out.writeInt(orbit.collided());
            if(!p.nativeType().equals("ORBITAL")){out.writeFloat(orbit.originalRotation());out.writeInt(p.ownerOid());out.writeInt(orbit.homeTargetId());out.writeInt(0);out.writeInt(p.faction());}return;}
        if(p.nativeType().equals("ACTOR"))return;
        if(p.nativeType().equals("LASER")){out.writeBoolean(!p.fizzle());vector(out,actor.x()+(float)Math.cos(actor.rotation())*p.speed(),actor.y()+(float)Math.sin(actor.rotation())*p.speed());return;}
        if(bullet){out.writeFloat(p.maxStep());out.writeFloat(actor.direction());vector(out,p.originX(),p.originY());out.writeFloat(p.z());}
        out.writeInt(p.ownerOid());out.writeInt(0);if(bullet)out.writeInt(p.faction());else out.writeFloat(p.z());
    }
    private void sceneEntity(DataOutputStream out,ActorState a)throws IOException {
        var e=a.entity();type(out,e.nativeClass());value(out,a.config());out.writeInt(a.id());
        out.writeInt(a.created());out.writeInt(e.destroyed());vector(out,a.x(),a.y());
        out.writeFloat(a.rotation());out.writeInt(a.flags());
        if(e.active()){
            out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeInt(a.activity());
            out.writeInt(a.activityStarted());out.writeFloat(e.speed());
            if(e.kind().equals("Monster")){
                out.writeShort(0);out.writeInt(e.affectedType());out.writeInt(e.affectedTime());conditions(out,e.status());
                out.writeInt(0);out.writeInt(0);out.writeFloat(e.health());out.writeShort(0);
                statusModifiers(out,e.status().modifiers());
            }
        }else switch(e.kind()){
            case "Generator" -> out.writeInt(e.count());
            case "Door" -> out.writeInt(e.stateEntered());
            case "Switch" -> {value(out,sceneEntryKey(e.key()));out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());primitiveInts(out,e.lift()==null?null:e.lift().excluded());}
            case "Pickup" -> {out.writeBoolean(false);out.writeBoolean(false);}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Bomb" -> {out.writeInt(0);out.writeInt(0);out.writeFloat(org.tortodrone.world.ProjectileState.UNSPECIFIED_Z);}
            case "Actor" -> { }
            default -> throw new IOException("Unsupported prepared actor type");
        }
    }
    private Object sceneEntryKey(String key)throws IOException {
        try{return Integer.valueOf(key.split("/",2)[0]);}catch(NumberFormatException invalid){throw new IOException("Unsupported switch entry key: "+key,invalid);}
    }
    private void sceneEntityDelta(DataOutputStream out,ActorState a)throws IOException {
        var e=a.entity();out.writeInt(a.id());type(out,e.nativeClass());
        // DeltaFinal id/created are absent from the stock mutable-field layout.
        int mask=0x1e;
        if(e.active()){
            mask|=(1<<6)|(1<<7)|(1<<8)|(1<<9);
            if(e.kind().equals("Monster"))mask|=(1<<11)|(1<<12)|(1<<13)|(1<<16)|(1<<18); // affected type/time, conditions, health and status
            out.writeByte(mask);out.writeByte(mask>>>8);
            if(e.kind().equals("Monster"))out.writeByte(mask>>>16);
        }else{
            mask|=e.kind().equals("Switch")?0xc0:(e.kind().equals("Actor")||e.kind().equals("Bomb"))?0:e.kind().equals("DungeonLift")?0x7c0:e.kind().equals("Block$Stateful")?0xe0:0x20;
            if(e.kind().equals("Trap"))mask|=0x40;
            out.writeByte(mask);if(e.kind().equals("DungeonLift"))out.writeByte(mask>>>8);
        }
        out.writeInt(e.destroyed());vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());
        if(e.active()){
            out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(e.speed());
            if(e.kind().equals("Monster")){out.writeInt(e.affectedType());out.writeInt(e.affectedTime());conditions(out,e.status());out.writeFloat(e.health());statusModifiers(out,e.status().modifiers());}
        }else switch(e.kind()){
            case "Generator" -> out.writeInt(e.count());case "Door" -> out.writeInt(e.stateEntered());
            case "Switch" -> {out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());case "Pickup" -> out.writeShort(0);
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());
                int[] ids=e.lift()==null?null:e.lift().excluded();if(ids==null)out.writeShort(0);else{type(out,"[I");out.writeInt(ids.length);for(int id:ids)out.writeInt(id);}}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Actor","Bomb" -> { }
            default -> throw new IOException("Unsupported prepared actor delta");
        }
    }
    private void dropPoint(DataOutputStream out,org.tortodrone.world.SceneLiftState lift)throws IOException {
        if(lift==null||lift.dropPoint()==null)out.writeShort(0);else vector(out,lift.dropPoint().x(),lift.dropPoint().y());
    }
    private void neutralStatus(DataOutputStream out)throws IOException {
        statusModifiers(out,org.tortodrone.world.SceneStatus.Modifiers.NORMAL);
    }
    private void conditions(DataOutputStream out,org.tortodrone.world.SceneStatus.State status)throws IOException {
        if(status.conditions().isEmpty()){out.writeShort(0);return;}
        type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(status.conditions().size());for(var condition:status.conditions())value(out,condition);
    }
    private void statusModifiers(DataOutputStream out,org.tortodrone.world.SceneStatus.Modifiers status)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.ImmutableStatusModifiers");
        out.writeFloat(status.speed());out.writeFloat(status.attackSpeed());out.writeFloat(status.attackDamage());out.writeFloat(status.relativeDamage());
        out.writeFloat(status.defense());out.writeFloat(status.heal());out.writeFloat(1);out.writeFloat(1);out.writeFloat(0);
        out.writeBoolean(status.canMove());out.writeBoolean(status.canRotate());out.writeBoolean(status.canDefend());out.writeBoolean(status.canAttack());
        out.writeBoolean(status.asleep());out.writeBoolean(status.shocked());out.writeBoolean(status.canBeTargeted());out.writeInt(0);
    }
    public byte[] partyStatus(int partyOid,int playerOid,org.tortodrone.world.SceneStatus.State status)throws IOException {
        return frame(out->{eventHeader(out,"EntryUpdatedEvent",partyOid);string(out,"status");
            type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(playerOid);conditions(out,status);
            primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(0);});
    }
    private void projectileDelta(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        out.writeInt(actor.id());type(out,p.nativeType().equals("ACTOR")?"com.threerings.tudey.data.actor.Actor":"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
        // Actor: destroyed, translation, rotation, flags. Origin, id and created are final.
        out.writeByte(p.nativeType().equals("LASER")?0x7e:0x1e);if(p.orbit()!=null)out.writeByte(3);else if(bullet)out.writeByte(0);
        out.writeInt(p.destroyed());vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(p.orbit()!=null){vector(out,p.orbit().relativeX(),p.orbit().relativeY());out.writeInt(p.orbit().collided());}
        if(p.nativeType().equals("LASER")){out.writeBoolean(!p.fizzle());vector(out,actor.x()+(float)Math.cos(actor.rotation())*p.speed(),actor.y()+(float)Math.sin(actor.rotation())*p.speed());}
    }
    private void effect(DataOutputStream out,SceneEffect effect)throws IOException {
        type(out,"com.threerings.tudey.data.effect.Effect");value(out,effect.config());out.writeInt(effect.timestamp());
        if(effect.targetActor()==0)out.writeShort(0);else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(effect.targetActor());}
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
