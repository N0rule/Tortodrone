package org.tortodrone.protocol;

import static org.tortodrone.protocol.SessionCodec.*;
import static org.tortodrone.protocol.WireWriter.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.MovementRules;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneSources;
import org.tortodrone.core.world.SceneSource;
import org.tortodrone.core.world.SceneInstanceView;
import org.tortodrone.core.world.SceneCatalogView;
import org.tortodrone.core.world.MapScene;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.mission.MissionCatalog;
import org.tortodrone.core.mission.MissionGates;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.AuctionState;
import org.tortodrone.core.town.ExchangeState;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.InteractionState;
import org.tortodrone.core.world.ProjectileState;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.core.mission.TutorialRoute;
import org.tortodrone.core.party.PartyView;

/** Bootstrap-owned downstream frames; shares the connection symbol tables. */
public final class BootstrapCodec extends WireWriter {
    @FunctionalInterface private interface GateWriter { void write(DataOutputStream out)throws IOException; }
    private final GateWire gates;
    public BootstrapCodec(WireContext context, ItemCatalog catalog){super(context,catalog);gates=new GateWire(context,catalog);}
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
            // Current build: server, exchange, PvP, PvP info, love test, mission,
            // open hall, admin, status, place browser, client timings.
            out.writeInt(SERVER_OID);
            out.writeInt(EXCHANGE_OID); out.writeInt(0); out.writeInt(0); // exchange, PvP, PvP info
            out.writeInt(LOVE_TEST_OID); // love test
            out.writeInt(MISSION_OID); out.writeInt(0); // registered mission object, open hall
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
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights) throws IOException {
        return player(messageId,oid,playerId,username,receivers,knights,false);
    }

    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights, boolean gameMaster) throws IOException {
        return player(messageId,oid,playerId,username,receivers,knights,gameMaster,0,false);
    }

    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights, boolean gameMaster,
            long externalSecret, boolean lookingForGroup) throws IOException {
        return frame(out -> {
            objectHeader(out, messageId, "com.threerings.projectx.data.PlayerObject", oid);
            type(out, WireTypes.NAME); string(out, username);
            type(out, WireTypes.DSET); out.writeInt(receivers.size());
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
            out.writeBoolean(false); out.writeLong(externalSecret); emptyCollection(out); // Steam voice, external secret, rewards
            out.writeShort(0); emptyDSet(out); // whereabouts, mission completions
            emptyCollection(out); emptyCollection(out); out.writeShort(0); // expansions, mission tests, rank
            out.writeInt(0); out.writeBoolean(lookingForGroup); emptyCollection(out); // prestige, LFG, saved colors
            emptyObjectArray(out, WireTypes.EYE_TYPE, true);
            emptyObjectArray(out, WireTypes.KNIGHT_HEIGHT, true);
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
            out.writeInt(0);out.writeInt(0);out.writeInt(org.tortodrone.core.economy.Fees.SPARK_COST);out.writeInt(0); // guild costs, dungeon/PvP spark costs
            var tutorial=soloInteriors&&SceneSources.current().hasTutorial();
            var registry=soloInteriors?SceneSources.current():null;
            var openingGate=registry==null?0:registry.openingGateId(org.tortodrone.core.character.OpeningMission.ANCIENT_GENERATOR);
            var missionGates=registry==null?java.util.List.<org.tortodrone.core.mission.MissionGates.Gate>of():registry.availableGates();
            var towns=registry==null?java.util.List.<org.tortodrone.core.world.MapScene>of():registry.catalog().towns().stream().filter(m->m.id()==registry.entry().map().id()||m.id()==217||m.id()==218).toList();
            if(soloInteriors){type(out,WireTypes.DSET);out.writeInt(towns.size());for(var town:towns)townSummary(out,town);}
            else emptyDSet(out);
            if(soloInteriors) {
                // A dobj DSet is binary-searched by key, so the wire order must be
                // sorted by gate id. Appending mission gates after the fixed 0/397/398
                // entries hides every imported gate from DSet.f().
                var gateWriters=new java.util.TreeMap<Integer,GateWriter>();
                // Stock ElevatorProp dereferences the party's gate even in a
                // peaceful SUBTOWN. This empty transport entry has no floors,
                // missions or destination; no dungeon gate service is exposed.
                gateWriters.put(0,o->{
                    type(o,"com.threerings.projectx.dungeon.data.GateSummary");o.writeBoolean(true);o.writeInt(0);
                    string(o,"");string(o,"");emptyObjectArray(o,WireTypes.COLORIZATION,false);
                    string(o,"");o.writeInt(SceneSources.current().entry().map().zone());
                    for(int i=0;i<5;i++)o.writeInt(0);
                    emptyObjectArray(o,"com.threerings.projectx.dungeon.data.GateSummary$Wheel",false);
                });
                if(tutorial)gateWriters.put(org.tortodrone.core.mission.TutorialRoute.GATE,gates::tutorialGate);
                if(openingGate!=0)gateWriters.put(openingGate,o->gates.openingGate(o,openingGate));
                for(var gate:missionGates)gateWriters.put(gate.gateId(),o->gates.missionGate(o,gate));
                type(out,WireTypes.DSET);out.writeInt(gateWriters.size());
                for(var writer:gateWriters.values())writer.write(out);
            } else emptyDSet(out);
            out.writeInt(org.tortodrone.core.economy.Fees.MAIL_FEE); out.writeInt(org.tortodrone.core.economy.Fees.MAIL_ATTACH_FEE); out.writeFloat(.1f); // mail fees, recovered auction sale fee
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

    public byte[] missionObject(short messageId)throws IOException {
        return frame(out->{objectHeader(out,messageId,"com.threerings.projectx.mission.data.MissionObject",MISSION_OID);emptyDSet(out);});
    }

    public byte[] loveTest(short messageId)throws IOException {
        return frame(out->{objectHeader(out,messageId,"com.threerings.projectx.love.data.LoveTestObject",LOVE_TEST_OID);emptyDSet(out);});
    }

}
