package org.tortodrone.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.character.Knight;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.world.TownSquare;
import org.tortodrone.world.TownMovement;
import org.tortodrone.world.ActorState;

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
        "com.threerings.crowd.data.BodyMarshaller",
        "com.threerings.projectx.item.data.ItemMarshaller",
        "com.threerings.projectx.uplink.data.UplinkMarshaller",
        "com.threerings.projectx.social.data.FeedMarshaller");
    public static final int MAX_DOWNSTREAM_FRAME = 1024 * 1024;
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

    /** News and social feed are empty on this server; Result listeners receive one list. */
    public byte[] emptyListResult(int oid, short requestId) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, "com.threerings.util.StreamableArrayList"); out.writeInt(0);
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

    void value(DataOutputStream out,Object value) throws IOException {
        if (value == null) { out.writeShort(0);
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

    public byte[] equipped(int oid,Knight knight,WorldProfile profile) throws IOException {
        return frame(out -> {
            eventHeader(out,"CompoundEvent",oid);
            type(out,"com.threerings.util.StreamableArrayList");out.writeInt(3);
            attribute(out,oid,"items",o -> items(o,knight,profile));
            attribute(out,oid,"equipment",o -> longs(o,profile.equipment()));
            attribute(out,oid,"loadouts",o -> currentLoadout(o,profile));
        });
    }

    public byte[] forceTown(int oid,short receiver) throws IOException {
        return frame(out -> { eventHeader(out,"InvocationNotificationEvent",oid);out.writeShort(receiver);out.writeByte(1);
            out.writeBoolean(true);out.writeInt(2);value(out,TownSquare.ZONE_ID);value(out,TownSquare.SCENE_ID); });
    }

    public byte[] sceneMove(int oid,short request,boolean zone) throws IOException {
        byte[] model=TownSquare.model();
        return frame(out -> {
            eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(3);
            out.writeBoolean(true);out.writeInt(zone ? 4 : 3);value(out,TownSquare.SCENE_OID);
            type(out,"com.threerings.projectx.town.data.TownSceneConfig");string(out,"haven.jpg");
            if(zone) {
                type(out,"com.threerings.whirled.zone.data.ZoneSummary");out.writeInt(TownSquare.ZONE_ID);name(out,"Haven");
                type(out,"[Lcom.threerings.whirled.zone.data.SceneSummary;");out.writeInt(1);
                type(out,"com.threerings.whirled.zone.data.SceneSummary");out.writeInt(TownSquare.SCENE_ID);string(out,"%town:m.haven_town_square");
                emptyCollection(out);emptyCollection(out);
            }
            type(out,"com.threerings.tudey.data.TudeySceneModel");out.writeInt(model.length);out.write(model);
        });
    }

    public byte[] townLocation(int oid) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,"com.threerings.util.StreamableArrayList");out.writeInt(3);
            attribute(out,oid,"lastTownSceneId",o->value(o,1));attribute(out,oid,"zoneId",o->value(o,TownSquare.ZONE_ID));
            attribute(out,oid,"location",o->{type(o,"com.threerings.whirled.data.ScenePlace");o.writeInt(TownSquare.SCENE_OID);o.writeInt(TownSquare.SCENE_ID);}); });
    }

    public byte[] scene(short id,List<ActorState> actors) throws IOException {
        return frame(out -> {
            objectHeader(out,id,"com.threerings.projectx.town.data.TownSceneObject",TownSquare.SCENE_OID);
            type(out,"com.threerings.presents.dobj.OidList");out.writeBoolean(true);out.writeInt(actors.size());for(var a:actors)out.writeInt(a.clientOid());out.writeInt(actors.size());
            type(out,"com.threerings.presents.dobj.DSet");out.writeInt(actors.size());for(var a:actors)occupant(out,a);
            out.writeShort(0); // no scene chat yet
            type(out,"com.threerings.tudey.data.TudeySceneMarshaller");out.writeInt(TownSquare.SCENE_OID);out.writeInt(10);
            type(out,"com.threerings.projectx.data.ProjectXSceneMarshaller");out.writeInt(TownSquare.SCENE_OID);out.writeInt(6);
            emptyDSet(out);
            type(out,"com.threerings.projectx.town.data.TownSceneMarshaller");out.writeInt(TownSquare.SCENE_OID);out.writeInt(11);
        });
    }

    /** Publish occupant metadata before adding their actors to the scene. */
    public byte[] occupants(List<ActorState> actors) throws IOException {
        return frame(out -> {
            eventHeader(out, "CompoundEvent", TownSquare.SCENE_OID);
            type(out, "com.threerings.util.StreamableArrayList");
            out.writeInt(2);
            attribute(out, TownSquare.SCENE_OID, "occupants", o -> {
                type(o, "com.threerings.presents.dobj.OidList");
                o.writeBoolean(true); o.writeInt(actors.size());
                for (var actor : actors) o.writeInt(actor.clientOid());
                o.writeInt(actors.size());
            });
            attribute(out, TownSquare.SCENE_OID, "occupantInfo", o -> {
                type(o, "com.threerings.presents.dobj.DSet"); o.writeInt(actors.size());
                for (var actor : actors) occupant(o, actor);
            });
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
        return frame(out -> {
            type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.tudey.dobj.SceneDeltaEvent");out.writeInt(oid);
            out.writeInt(TownSquare.SCENE_OID);out.writeInt(acknowledge);out.writeShort(ping);out.writeInt(reference);out.writeInt(timestamp);out.writeShort(elapsed);
            if(added.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.data.actor.Actor;");out.writeInt(added.size());for(var a:added)actor(out,a);}
            if(updated.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.dobj.ActorDelta;");out.writeInt(updated.size());out.writeShort((updated.size()+7)/8);for(int i=0;i<updated.size();i+=8)out.writeByte((1<<Math.min(8,updated.size()-i))-1);for(var a:updated)actorDelta(out,a,baseline.get(a.id()));}
            out.writeBoolean(true);out.writeInt(removed.length);for(int id:removed)out.writeInt(id);out.writeShort(0);
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
    private void items(DataOutputStream out,Knight knight,WorldProfile profile)throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(profile.items().size());
        for(var item:profile.items()) {
            type(out,"com.threerings.projectx.item.data.LevelItem");out.writeInt(0);out.writeFloat(0);out.writeFloat(0);out.writeShort(0);
            out.writeBoolean(item.accessory()!=null);if(item.accessory()!=null){out.writeInt(1);value(out,new StarterCatalog.Model(item.accessory()+", "+knight.costume(),Map.of()));}
            out.writeLong(item.id());out.writeInt(profile.worldId());out.writeInt(0);out.writeUTF(item.config());out.writeInt(1);out.writeShort(0);
            // Stock Item.writeObject: bit 2 is the mutable binding flag (aTY),
            // tested by Cs(). Bit 1 is the permanent lock and remains unset.
            out.writeByte(item.bound() ? 2 : 0);
        }
    }
    private void currentLoadout(DataOutputStream out,WorldProfile profile) throws IOException {
        type(out,"com.threerings.presents.dobj.DSet");out.writeInt(1);
        type(out,"com.threerings.projectx.data.Loadout");
        // Loadout equipment is indexed by ItemCodes.bwt, not the full equipment slot array.
        int[] slots={0,1,2,3,4,15,6,7,8,9,10,11};long[] equipment=profile.equipment();
        out.writeBoolean(true);out.writeInt(slots.length);
        for(int slot:slots)out.writeLong(equipment[slot]);
        out.writeBoolean(false);out.writeInt(0);out.writeInt(0);out.writeLong(0);
        out.writeShort(0);string(out,""); // no sprite perk; empty name is the stock current loadout.
    }
    private void townSummary(DataOutputStream out)throws IOException {
        type(out,"com.threerings.projectx.town.data.TownSummary");string(out,"Haven");string(out,null);
        emptyObjectArray(out,"com.threerings.opengl.renderer.config.ColorizationConfig",false);string(out,null);
        out.writeInt(0);out.writeInt(TownSquare.SCENE_ID);out.writeInt(1);
    }
    private void occupant(DataOutputStream out,ActorState actor)throws IOException {
        type(out,"com.threerings.projectx.data.ProjectXOccupantInfo");out.writeBoolean(true);out.writeInt(actor.clientOid());name(out,actor.name());out.writeByte(0);out.writeInt(actor.id());
        type(out,"com.threerings.projectx.data.PlayerObject$Type");out.writeByte(0);out.writeInt(actor.color());out.writeShort(0);type(out,"com.threerings.projectx.data.ProjectXCodes$PrestigeRank");out.writeByte(0);out.writeInt(1);
    }
    private void actor(DataOutputStream out,ActorState a)throws IOException {
        type(out,"com.threerings.projectx.data.actor.Knight");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(TownMovement.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());
        out.writeShort(0);out.writeShort(0);out.writeShort(0);out.writeShort(0);string(out,null);out.writeInt(0);out.writeShort(0);
    }
    private void actorDelta(DataOutputStream out,ActorState a,ActorState baseline)throws IOException {
        // ActorDelta is final: its typed array omits a per-element type header.
        out.writeInt(a.id());type(out,"com.threerings.projectx.data.actor.Knight");
        boolean configChanged = baseline == null || !a.config().equals(baseline.config());
        // Preserve the reference's config object on ordinary movement; update it for actual gear changes.
        // The stock controller predicts directly on its first snapshot. Always replace activity
        // fields so that local inventory prediction cannot leak into subsequent delta baselines.
        out.writeByte(configChanged ? 0xdd : 0xdc);out.writeByte(1);
        if (configChanged) value(out,a.config());
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());
        out.writeInt(a.activity());out.writeInt(a.activityStarted());
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
