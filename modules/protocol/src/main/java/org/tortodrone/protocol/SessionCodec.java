package org.tortodrone.protocol;

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

/** Downstream session messages after the secure and authentication responses. */
public final class SessionCodec extends WireWriter {
    public static final int SERVER_OID = 1;
    public static final int CONFIG_OID = 2;
    public static final int INVOCATION_OID = 3;
    public static final int EXCHANGE_OID = 4, EXCHANGE_SERVICE = 1;
    public static final int MISSION_OID = 5;
    public static final int LOVE_TEST_OID = 6;
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
        "com.threerings.projectx.billing.data.BillingMarshaller",
        // Current build: the social feed reports view events and resolves party
        // suggestions through these two handles.
        "com.threerings.projectx.data.EventLogMarshaller",
        "com.threerings.projectx.love.data.LoveMarshaller",
        // A steam-aliased session resolves the stock SteamDirector partner service from the
        // bootstrap during logon; an inert registration keeps that observer from throwing.
        "com.threerings.projectx.partner.data.SteamMarshaller",
        // Guild operations. Appended last so every earlier bootstrap index is unchanged.
        "com.threerings.projectx.guild.data.GuildMarshaller");
    public static final int KNIGHT_SERVICE = serviceId("com.threerings.projectx.data.KnightMarshaller");
    public static final int CHAT_SERVICE = serviceId("com.threerings.crowd.chat.data.ChatMarshaller");
    public static final int SCENE_SERVICE = serviceId("com.threerings.whirled.data.SceneMarshaller");
    public static final int ZONE_SERVICE = serviceId("com.threerings.whirled.zone.data.ZoneMarshaller");
    public static final int ITEM_SERVICE = serviceId("com.threerings.projectx.item.data.ItemMarshaller");
    public static final int UPLINK_SERVICE = serviceId("com.threerings.projectx.uplink.data.UplinkMarshaller");
    public static final int FEED_SERVICE = serviceId("com.threerings.projectx.social.data.FeedMarshaller");
    public static final int SOCIAL_SERVICE = serviceId("com.threerings.projectx.social.data.SocialMarshaller");
    /** Stock admin service; the client's F3 dashboard tools resolve it by class from the bootstrap. */
    public static final int ADMIN_SERVICE = serviceId("com.threerings.projectx.admin.data.ProjectXAdminMarshaller");
    public static final int BATTLE_SPRITE_SERVICE = serviceId("com.threerings.projectx.sprites.data.BattleSpriteMarshaller");
    public static final int AUCTION_SERVICE = serviceId("com.threerings.projectx.auction.data.AuctionMarshaller");
    public static final int MISSION_SERVICE = serviceId("com.threerings.projectx.mission.data.MissionMarshaller");
    // The supply depot's purchase tab requires the billing service at window
    // construction; the stock exchange window throws and wedges without it.
    public static final int BILLING_SERVICE = serviceId("com.threerings.projectx.billing.data.BillingMarshaller");
    // Current build: the social feed reports view events and resolves party
    // suggestions through this handle.
    public static final int EVENT_LOG_SERVICE = serviceId("com.threerings.projectx.data.EventLogMarshaller");
    public static final int LOVE_SERVICE = serviceId("com.threerings.projectx.love.data.LoveMarshaller");
    public static final int STEAM_SERVICE = serviceId("com.threerings.projectx.partner.data.SteamMarshaller");
    public static final int GUILD_SERVICE = serviceId("com.threerings.projectx.guild.data.GuildMarshaller");
    private static int serviceId(String className) {
        int index = SERVICES.indexOf(className);
        if (index < 0) throw new IllegalStateException("Missing bootstrap service: " + className);
        return index + 1;
    }
    public SessionCodec(){this(ItemCatalog.starter());}
    /** Shared server catalogue; scene templates use it without a connection registry. */
    public ItemCatalog catalog(){return itemCatalog;}
    private final BootstrapCodec bootstrapCodec;
    private final SocialCodec socialCodec;
    private final MissionCodec missionCodec;
    private final KnightCodec knightCodec;
    private final TradeCodec tradeCodec;
    private final TownCodec townCodec;
    private final PartyCodec partyCodec;
    private final SceneCodec sceneCodec;
    private final GuildCodec guildCodec;

    public SessionCodec(ItemCatalog catalog) {
        super(new WireContext(), catalog);
        bootstrapCodec=new BootstrapCodec(context,itemCatalog);
        socialCodec=new SocialCodec(context,itemCatalog);
        missionCodec=new MissionCodec(context,itemCatalog);
        knightCodec=new KnightCodec(context,itemCatalog);
        tradeCodec=new TradeCodec(context,itemCatalog);
        townCodec=new TownCodec(context,itemCatalog);
        partyCodec=new PartyCodec(context,itemCatalog);
        sceneCodec=new SceneCodec(context,itemCatalog);
        guildCodec=new GuildCodec(context,itemCatalog);
    }
    /** Shared symbol tables and template materialization for this connection. */
    public WireContext context(){return context;}

    /** Guild objects live in their own stable OID range, above scene/trade identities. */
    public static int guildOid(long guildId){return 1_000_000+Math.toIntExact(guildId);}

    public record TradeView(int oid,Knight first,WorldProfile firstProfile,List<WorldProfile.Item> firstItems,
            Knight second,WorldProfile secondProfile,List<WorldProfile.Item> secondItems,
            int firstCrowns,int secondCrowns,int firstEnergy,int secondEnergy,int revision,int firstAccepted,int secondAccepted,boolean completed) {
        public TradeView { firstItems=List.copyOf(firstItems);secondItems=List.copyOf(secondItems); }
    }
    public record FriendView(int world,String name,int color,String location,long lastSession,int zone,int instance) {
        public FriendView(int world,String name,int color,String location,long lastSession){this(world,name,color,location,lastSession,0,0);}
    }
    /** Native social UI observes entry events, including online/offline changes and renames. */
    public static final int CRAFT_SERVICE=22, UNBIND_SERVICE=23, ACCESSORY_SERVICE=24, VARIANT_SERVICE=25;
    /** The stock party panel resolves this handle from LevelPartyObject to toggle open/private. */
    public static final int LEVEL_PARTY_SERVICE=26;
    /** Self-chosen InvocationMarshaller._invCode values for scene-entry services. Native
     * evidence (presents.data.InvocationMarshaller, presents.client.x): the client only
     * echoes _invCode back inside each InvocationRequestEvent and never validates it, so
     * any collision-free, stable assignment is correct; these are the historical picks.
     * The *_SERVICE values above and SceneChat's follow the same rule. */
    public static final int TRADE_INVOCATION=1, PROJECTX_SCENE_INVOCATION=6, TUDEY_SCENE_INVOCATION=10,
        TOWN_SCENE_INVOCATION=11, PARTY_INVOCATION=20;

    /** Compatibility transport for explicitly constructed single-member interior fixtures. */
    public static int interiorPartyOid(SceneInstanceView scene){return 500+scene.oid();}

    /** Encode the shared actor records once; the template keeps per-connection symbol
     * placeholders that each recipient materializes with its own codes. */
    public static org.tortodrone.core.world.SceneFrame sceneDeltaTemplate(ItemCatalog catalog,int sceneOid,int timestamp,
            List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,
            boolean interior,List<SceneEffect> effects,List<SceneCombatEvent> combat)throws IOException {
        return SceneDeltaTemplate.encode(catalog,sceneOid,timestamp,added,updated,removed,baseline,interior,effects,combat);
    }
    /** The scene codec used by the one-off template encoder, which never touches live state. */
    SceneCodec sceneCodec(){return sceneCodec;}
    /** Copy a shared template with this connection's codes and header fields. Returns null while
     * the connection has not seen every symbol yet; the caller then encodes this tick directly,
     * which also registers them for later ticks. Called with the encoder's output lock held, so
     * the symbol maps cannot change underneath it. */
    public byte[] materialize(org.tortodrone.core.world.SceneFrame frame,int oid,int acknowledge,short ping,int reference,short elapsed){
        return context.materialize(frame,oid,acknowledge,ping,reference,elapsed);
    }

    // ---- guild object and its roster (GuildObject fields stream in client declaration order)

    public static final int GUILD_PEER_SERVICE=28,GUILD_CHAT_SERVICE=29;

    public byte[] bootstrap(int connectionId, int clientOid, String host, int port) throws IOException { return bootstrapCodec.bootstrap(connectionId, clientOid, host, port); }
    public byte[] player(short messageId, int oid, int playerId, String username) throws IOException { return bootstrapCodec.player(messageId, oid, playerId, username); }
    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers) throws IOException { return bootstrapCodec.player(messageId, oid, playerId, username, receivers); }
    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights) throws IOException { return bootstrapCodec.player(messageId, oid, playerId, username, receivers, knights); }
    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights, boolean gameMaster) throws IOException { return bootstrapCodec.player(messageId, oid, playerId, username, receivers, knights, gameMaster); }
    public byte[] player(short messageId, int oid, int playerId, String username,
            Map<String, Short> receivers, List<org.tortodrone.core.character.RosterEntry> knights, boolean gameMaster,
            long externalSecret, boolean lookingForGroup) throws IOException { return bootstrapCodec.player(messageId, oid, playerId, username, receivers, knights, gameMaster, externalSecret, lookingForGroup); }
    public byte[] server(short messageId) throws IOException { return bootstrapCodec.server(messageId); }
    public byte[] server(short messageId,boolean soloInteriors) throws IOException { return bootstrapCodec.server(messageId, soloInteriors); }
    public byte[] configuration(short messageId) throws IOException { return bootstrapCodec.configuration(messageId); }
    public byte[] missionObject(short messageId)throws IOException { return bootstrapCodec.missionObject(messageId); }
    public byte[] loveTest(short messageId)throws IOException { return bootstrapCodec.loveTest(messageId); }
    public byte[] completedMissions(int oid,java.util.Collection<org.tortodrone.core.character.OpeningCompletion> completions)throws IOException { return missionCodec.completedMissions(oid, completions); }
    public byte[] missionSettlement(int oid,WorldProfile before,WorldProfile after,org.tortodrone.core.character.OpeningCompletion receipt,
            java.util.List<org.tortodrone.core.mission.MissionCatalog.Grant> rewards,int prestige,boolean immediate,long created)throws IOException { return missionCodec.missionSettlement(oid, before, after, receipt, rewards, prestige, immediate, created); }
    public byte[] exchange(short messageId,org.tortodrone.core.town.ExchangeState.Book book)throws IOException { return townCodec.exchange(messageId, book); }
    public byte[] exchangeChanged(org.tortodrone.core.town.ExchangeState.Book book)throws IOException { return townCodec.exchangeChanged(book); }
    public byte[] pong(short messageId, long receivedAt) throws IOException { return socialCodec.pong(messageId, receivedAt); }
    public byte[] inputThrottle() throws IOException { return socialCodec.inputThrottle(); }
    public byte[] subscriptionFailure(short messageId, int objectId, String reason) throws IOException { return socialCodec.subscriptionFailure(messageId, objectId, reason); }
    public byte[] forgeFailure(int clientOid,short requestId,String reason)throws IOException { return socialCodec.forgeFailure(clientOid, requestId, reason); }
    public byte[] invocationFailure(int clientOid, short requestId, String reason) throws IOException { return socialCodec.invocationFailure(clientOid, requestId, reason); }
    public byte[] invocationConfirmed(int clientOid, short requestId) throws IOException { return socialCodec.invocationConfirmed(clientOid, requestId); }
    public byte[] invocationResult(int clientOid,short requestId,Object result)throws IOException { return socialCodec.invocationResult(clientOid, requestId, result); }
    public byte[] tellSucceeded(int clientOid,short requestId)throws IOException { return socialCodec.tellSucceeded(clientOid, requestId); }
    public byte[] awayMessage(int oid,String message)throws IOException { return socialCodec.awayMessage(oid, message); }
    public byte[] tellSucceeded(int clientOid,short requestId,String awayMessage)throws IOException { return socialCodec.tellSucceeded(clientOid, requestId, awayMessage); }
    public byte[] emptyListResult(int oid, short requestId) throws IOException { return socialCodec.emptyListResult(oid, requestId); }
    public byte[] listResult(int oid, short requestId, List<String> values) throws IOException { return socialCodec.listResult(oid, requestId, values); }
    public byte[] rosterChanged(int clientOid, List<org.tortodrone.core.character.RosterEntry> knights) throws IOException { return knightCodec.rosterChanged(clientOid, knights); }
    public byte[] gameMaster(int clientOid,boolean enabled)throws IOException { return socialCodec.gameMaster(clientOid, enabled); }
    public byte[] playerFlags(int clientOid,boolean enabled,boolean inspectionHidden)throws IOException { return socialCodec.playerFlags(clientOid, enabled, inspectionHidden); }
    public byte[] playerFlags(int clientOid,boolean enabled,boolean inspectionHidden,boolean tutorialComplete)throws IOException { return socialCodec.playerFlags(clientOid, enabled, inspectionHidden, tutorialComplete); }
    public byte[] lookingForGroup(int oid,boolean lookingForGroup)throws IOException { return socialCodec.lookingForGroup(oid, lookingForGroup); }
    public byte[] inspected(int oid,short request,Knight knight,WorldProfile profile,boolean hidden)throws IOException { return knightCodec.inspected(oid, request, knight, profile, hidden); }
    public byte[] selected(int oid, Knight knight, WorldProfile profile) throws IOException { return knightCodec.selected(oid, knight, profile); }
    public byte[] selected(int oid, Knight knight, WorldProfile profile,
            org.tortodrone.core.guild.Guild guild) throws IOException { return knightCodec.selected(oid, knight, profile, guild); }
    public byte[] knightMemories(int oid,java.util.Set<String> names)throws IOException { return knightCodec.knightMemories(oid, names); }
    public byte[] knightObjectives(int oid,java.util.Map<String,org.tortodrone.core.character.KnightObjective.State> objectives)throws IOException { return knightCodec.knightObjectives(oid, objectives); }
    public byte[] objectives(int oid,java.util.List<org.tortodrone.core.mission.ObjectiveEntry> objectives)throws IOException { return knightCodec.objectives(oid, objectives); }
    public byte[] knightMemoryChanges(int oid,java.util.Set<String> before,java.util.Set<String> after)throws IOException { return knightCodec.knightMemoryChanges(oid, before, after); }
    public byte[] knightName(int oid,String knight)throws IOException { return knightCodec.knightName(oid, knight); }
    public byte[] equipPolicy(int oid,boolean station)throws IOException { return knightCodec.equipPolicy(oid, station); }
    public byte[] controllerAction(int oid,String command,ActorState source)throws IOException { return knightCodec.controllerAction(oid, command, source); }
    public byte[] balances(int oid,WorldProfile profile) throws IOException { return knightCodec.balances(oid, profile); }
    public byte[] knightProgress(int oid,WorldProfile profile)throws IOException { return knightCodec.knightProgress(oid, profile); }
    public byte[] itemChanges(int oid,Knight knight,WorldProfile before,WorldProfile after)throws IOException { return knightCodec.itemChanges(oid, knight, before, after); }
    public byte[] achievementChanges(int oid,WorldProfile before,WorldProfile after)throws IOException { return knightCodec.achievementChanges(oid, before, after); }
    public byte[] quickbar(int oid,long[] slots)throws IOException { return knightCodec.quickbar(oid, slots); }
    public byte[] tradeNotification(int playerOid,String sender,boolean add)throws IOException { return tradeCodec.tradeNotification(playerOid, sender, add); }
    public byte[] friends(int oid,List<FriendView> before,List<FriendView> after)throws IOException { return socialCodec.friends(oid, before, after); }
    public byte[] tradeDialog(int playerOid,TradeView view,long created)throws IOException { return tradeCodec.tradeDialog(playerOid, view, created); }
    public byte[] townDialog(int playerOid,int sceneOid,org.tortodrone.core.town.TownCatalog.Station station,ActorState source,long created,int service)throws IOException { return townCodec.townDialog(playerOid, sceneOid, station, source, created, service); }
    public byte[] townShop(int oid,List<org.tortodrone.core.town.TownCatalog.Good> goods,List<Integer> available)throws IOException { return townCodec.townShop(oid, goods, available); }
    public byte[] styles(int oid,Knight knight,org.tortodrone.core.style.Saved saved)throws IOException { return knightCodec.styles(oid, knight, saved); }
    public byte[] townState(int oid,WorldProfile profile)throws IOException { return townCodec.townState(oid, profile); }
    public byte[] recipeAdded(int oid,org.tortodrone.core.town.TownState.LearnedRecipe recipe)throws IOException { return townCodec.recipeAdded(oid, recipe); }
    public byte[] itemAcquireDelay(int oid,int millis)throws IOException { return townCodec.itemAcquireDelay(oid, millis); }
    public byte[] townResult(int oid,short request,Object result,WorldProfile profile)throws IOException { return townCodec.townResult(oid, request, result, profile); }
    public byte[] conversationDialog(int playerOid,int sceneOid,org.tortodrone.core.world.SceneConversation conversation,
            String nodeId,ActorState source,long created)throws IOException { return townCodec.conversationDialog(playerOid, sceneOid, conversation, nodeId, source, created); }
    public byte[] tradeObject(short messageId,TradeView view)throws IOException { return tradeCodec.tradeObject(messageId, view); }
    public byte[] tradeChanges(TradeView before,TradeView after)throws IOException { return tradeCodec.tradeChanges(before, after); }
    public byte[] forceTown(int oid,short receiver) throws IOException { return sceneCodec.forceTown(oid, receiver); }
    public byte[] forceScene(int oid,short receiver,SceneInstanceView scene) throws IOException { return sceneCodec.forceScene(oid, receiver, scene); }
    public byte[] forceDestination(int oid,short receiver,int zone,int scene) throws IOException { return sceneCodec.forceDestination(oid, receiver, zone, scene); }
    public byte[] readyRoomMove(int oid,short request) throws IOException { return sceneCodec.readyRoomMove(oid, request); }
    public byte[] readyRoomLocation(int oid) throws IOException { return sceneCodec.readyRoomLocation(oid); }
    public byte[] sceneMove(int oid,short request,boolean zone) throws IOException { return sceneCodec.sceneMove(oid, request, zone); }
    public byte[] sceneMove(int oid,short request,boolean zone,SceneInstanceView scene,SceneCatalogView catalog) throws IOException { return sceneCodec.sceneMove(oid, request, zone, scene, catalog); }
    public byte[] townLocation(int oid) throws IOException { return sceneCodec.townLocation(oid); }
    public byte[] sceneParty(int oid,SceneInstanceView scene)throws IOException { return partyCodec.sceneParty(oid, scene); }
    public byte[] partyOid(int player,int party)throws IOException { return partyCodec.partyOid(player, party); }
    public byte[] chatChannel(short message,int oid,int chatType)throws IOException { return socialCodec.chatChannel(message, oid, chatType); }
    public byte[] sceneLocation(int oid,SceneInstanceView scene)throws IOException { return sceneCodec.sceneLocation(oid, scene); }
    public byte[] sceneLocation(int oid,SceneInstanceView scene,int general,int trade) throws IOException { return sceneCodec.sceneLocation(oid, scene, general, trade); }
    public byte[] scene(short id,List<ActorState> actors) throws IOException { return sceneCodec.scene(id, actors); }
    public byte[] scene(short id,SceneInstanceView scene,List<ActorState> actors) throws IOException { return sceneCodec.scene(id, scene, actors); }
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene)throws IOException { return partyCodec.interiorParty(message, partyOid, playerOid, knight, profile, scene); }
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene,org.tortodrone.core.world.SceneHealth.State health)throws IOException { return partyCodec.interiorParty(message, partyOid, playerOid, knight, profile, scene, health); }
    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene,org.tortodrone.core.world.SceneHealth.State health,org.tortodrone.core.world.SceneStatus.State status)throws IOException { return partyCodec.interiorParty(message, partyOid, playerOid, knight, profile, scene, health, status); }
    public byte[] party(short message,PartyView.View view,SceneInstanceView scene)throws IOException { return partyCodec.party(message, view, scene); }
    public byte[] party(short message,PartyView.View view,SceneInstanceView scene,boolean reporting)throws IOException { return partyCodec.party(message, view, scene, reporting); }
    public byte[] finalFloorReport(int party)throws IOException { return partyCodec.finalFloorReport(party); }
    public byte[] openingFloorReport(int party)throws IOException { return partyCodec.openingFloorReport(party); }
    public byte[] partyChanges(PartyView.View before,PartyView.View after)throws IOException { return partyCodec.partyChanges(before, after); }
    public byte[] partySceneChanges(int party,SceneInstanceView before,SceneInstanceView after)throws IOException { return partyCodec.partySceneChanges(party, before, after); }
    public byte[] partyVisited(int party,java.util.Collection<Integer> coords)throws IOException { return partyCodec.partyVisited(party, coords); }
    public byte[] partyInvitation(int player,PartyView.Invitation invitation)throws IOException { return partyCodec.partyInvitation(player, invitation); }
    public byte[] partyHealth(int partyOid,int playerOid,org.tortodrone.core.world.SceneHealth.State health)throws IOException { return partyCodec.partyHealth(partyOid, playerOid, health); }
    public byte[] partyVitals(int partyOid,int playerOid,org.tortodrone.core.world.SceneHealth.State health,org.tortodrone.core.world.SceneStatus.State status)throws IOException { return partyCodec.partyVitals(partyOid, playerOid, health, status); }
    public byte[] reviveFailure(int playerOid,short request,String reason)throws IOException { return partyCodec.reviveFailure(playerOid, request, reason); }
    public byte[] chat(int sceneOid,org.tortodrone.core.world.SceneChat.Message message)throws IOException { return socialCodec.chat(sceneOid, message); }
    public byte[] chatFeedback(int sceneOid,String reason)throws IOException { return socialCodec.chatFeedback(sceneOid, reason); }
    public byte[] systemNotice(int sceneOid,String text)throws IOException { return socialCodec.systemNotice(sceneOid, text); }
    public byte[] auctionOutbid(int sceneOid,String itemName,int crowns)throws IOException { return socialCodec.auctionOutbid(sceneOid, itemName, crowns); }
    public byte[] occupants(List<ActorState> actors) throws IOException { return sceneCodec.occupants(actors); }
    public byte[] occupants(int sceneOid,List<ActorState> actors) throws IOException { return sceneCodec.occupants(sceneOid, actors); }
    public byte[] occupantChanges(int sceneOid,Map<Integer,Integer> previous,List<ActorState> actors)throws IOException { return sceneCodec.occupantChanges(sceneOid, previous, actors); }
    public byte[] occupantRoles(int sceneOid,List<ActorState> changed)throws IOException { return sceneCodec.occupantRoles(sceneOid, changed); }
    public byte[] sceneDelta(int oid,int acknowledge,int reference,int timestamp,List<ActorState> added,
            List<ActorState> updated,int[] removed) throws IOException { return sceneCodec.sceneDelta(oid, acknowledge, reference, timestamp, added, updated, removed); }
    public byte[] sceneDelta(int oid, int acknowledge, short ping, int reference, int timestamp,
            short elapsed, List<ActorState> added, List<ActorState> updated, int[] removed,
            Map<Integer, ActorState> baseline) throws IOException { return sceneCodec.sceneDelta(oid, acknowledge, ping, reference, timestamp, elapsed, added, updated, removed, baseline); }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline)throws IOException { return sceneCodec.sceneDelta(oid, sceneOid, acknowledge, ping, reference, timestamp, elapsed, added, updated, removed, baseline); }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior)throws IOException { return sceneCodec.sceneDelta(oid, sceneOid, acknowledge, ping, reference, timestamp, elapsed, added, updated, removed, baseline, interior); }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects)throws IOException { return sceneCodec.sceneDelta(oid, sceneOid, acknowledge, ping, reference, timestamp, elapsed, added, updated, removed, baseline, interior, effects); }
    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects,List<SceneCombatEvent> combat)throws IOException { return sceneCodec.sceneDelta(oid, sceneOid, acknowledge, ping, reference, timestamp, elapsed, added, updated, removed, baseline, interior, effects, combat); }
    public byte[] loadouts(int oid,WorldProfile profile)throws IOException { return knightCodec.loadouts(oid, profile); }
    public byte[] loadoutChanges(int oid,WorldProfile before,WorldProfile after)throws IOException { return knightCodec.loadoutChanges(oid, before, after); }
    public byte[] homeTown(int oid,WorldProfile profile)throws IOException { return townCodec.homeTown(oid, profile); }
    public byte[] partyStatus(int partyOid,int playerOid,org.tortodrone.core.world.SceneStatus.State status)throws IOException { return partyCodec.partyStatus(partyOid, playerOid, status); }
    public byte[] unsubscribe(short messageId, int objectId) throws IOException { return socialCodec.unsubscribe(messageId, objectId); }
    public byte[] guild(short message,org.tortodrone.core.guild.View view)throws IOException { return guildCodec.guild(message, view); }
    public byte[] guildChanges(org.tortodrone.core.guild.View before,
            org.tortodrone.core.guild.View after)throws IOException { return guildCodec.guildChanges(before, after); }
    public byte[] guildCleared(int player)throws IOException { return guildCodec.guildCleared(player); }
    public byte[] expansionDialog(int player,long guildId,List<Integer> expansions,boolean inDefault,
            float prorate,long created)throws IOException { return guildCodec.expansionDialog(player, guildId, expansions, inDefault, prorate, created); }
    public byte[] hallGlobalDialog(int player,long guildId,int type,List<Integer> expansions,
            String current,long created)throws IOException { return guildCodec.hallGlobalDialog(player, guildId, type, expansions, current, created); }
    public byte[] guildLog(short request,int oid,org.tortodrone.core.guild.Page page)throws IOException { return guildCodec.guildLog(request, oid, page); }
}
