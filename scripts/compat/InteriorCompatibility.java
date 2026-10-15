import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.io.Streamer;
import com.threerings.io.v;
import com.threerings.presents.dobj.DObject;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.presents.net.ObjectResponse;
import com.threerings.projectx.data.ProjectXSceneObject;
import com.threerings.projectx.data.ServerObject;
import com.threerings.projectx.dungeon.data.GateSummary;
import com.threerings.projectx.dungeon.data.Health;
import com.threerings.projectx.dungeon.data.ImmutableStatusModifiers;
import com.threerings.projectx.dungeon.data.LevelPartyObject;
import com.threerings.projectx.dungeon.data.DungeonPartyObject;
import com.threerings.projectx.dungeon.data.PartyMember;
import com.threerings.projectx.dungeon.data.Status;
import com.threerings.projectx.dungeon.config.DungeonActorConfig;
import com.threerings.projectx.dungeon.config.DungeonActorSpriteConfig;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.town.data.TownSummary;
import com.threerings.projectx.item.data.ImmutableEquipmentModifiers;
import com.threerings.opengl.model.config.ModelConfig;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.config.ActorConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.world.ActorState;
import org.tortodrone.world.SceneCatalog;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.character.StarterCatalog;

/** Stock-reader oracle for native dungeon party, interior place and Dungeoneer messages. */
public final class InteriorCompatibility {
    private static final String PC_CONFIG = "Character/PC/Level";
    private static final int PLAYER_OID = 1001;
    private static final int PARTY_OID = 5001;
    private static final int SCENE_OID = 6001;
    private static final int ACTOR_ID = 31;

    private InteriorCompatibility() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: InteriorCompatibility /path/to/Spiral Knights");
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        fieldLayouts();
        var manager = configManager(Path.of(args[0]));
        var pc = manager.d(ActorConfig.class, PC_CONFIG);
        if (pc == null || !PC_CONFIG.equals(pc.getName())) throw new AssertionError("Stock dungeon actor config reference missing");
        var original = pc.ai(manager);
        if (!(original instanceof DungeonActorConfig.Dungeoneer dungeonConfig) ||
            !(dungeonConfig.sprite instanceof DungeonActorSpriteConfig.Dungeoneer dungeonSprite) ||
            dungeonSprite.model == null || !"character/pc/model_level.dat".equals(dungeonSprite.model.getName()) ||
            manager.a(ModelConfig.class, dungeonSprite.model) == null)
            throw new AssertionError("Character/PC/Level did not resolve to the stock Dungeoneer sprite and level model");
        for (String kind : List.of("training", "level")) verify(kind, manager);
        System.out.println("PASS: stock reader accepts native interior party, scene config/object and Dungeoneer add/update events");
    }

    private static void verify(String kind, ConfigManager manager) throws Exception {
        var registry = new SceneRegistry(SceneCatalog.load(Path.of("content/packs/vanilla/maps")));
        SceneRegistry.install(registry);
        var base = registry.entry().map();
        var map = new SceneCatalog.MapScene(base.id(), base.zone(), base.zoneName(), base.name(), base.image(), kind,
            base.version(), base.spawnX(), base.spawnY(), base.spawnRotation(), base.instances(), base.collision(), base.layout(), base.portals());
        var scene = new SceneRegistry.Instance(SCENE_OID, 0, map);
        var knight = new Knight(1, "Interior-Knight", "Gear/Helm/Spiral Sallet (Blue)",
            "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 1);
        var profile = new WorldProfile(51, 1, knight.id(), 73, 29, 1, List.of(
            new WorldProfile.Item(101, WorldProfile.Slot.HELM, knight.helm(), null, 0, 0, true, true, true),
            new WorldProfile.Item(102, WorldProfile.Slot.ARMOR, knight.armor(), null, 0, 1, true, true, true),
            new WorldProfile.Item(103, WorldProfile.Slot.SHIELD, "Gear/Shield/Spiral Shield (Blue)", null, 0, 2, true, true, true),
            new WorldProfile.Item(104, WorldProfile.Slot.SWORD, "Weapon/Sword/Calibur", null, 0, 6, true, true, true),
            new WorldProfile.Item(105, WorldProfile.Slot.GUN, "Weapon/Gun/Blaster", null, 0, 7, true, true, true)));
        var model = new StarterCatalog.Model(PC_CONFIG, Map.of());
        var initial = new ActorState(ACTOR_ID, PLAYER_OID, knight.name(), 257, 100, 1.25f, -2.5f,
            .5f, 1.5f, 0, 0, 100, model);
        var updated = new ActorState(ACTOR_ID, PLAYER_OID, knight.name(), 257, 100, 2.25f, -1.5f,
            .75f, 2.5f, 0, 28, 150, model);
        var codec = new SessionCodec();
        var bytes = new ByteArrayOutputStream();
        append(bytes, HandshakeCodec.response((short) 1, "0123456789abcdef0123456789abcdef"));
        append(bytes, HandshakeCodec.authenticationResponse((short) 2, true, "fixture", "InteriorUser"));
        append(bytes, codec.player((short) 10, PLAYER_OID, 1, "InteriorUser"));
        append(bytes, codec.server((short) 11, true));
        append(bytes, codec.interiorInputThrottle());
        append(bytes, codec.interiorParty((short) 3, PARTY_OID, PLAYER_OID, knight, profile, scene));
        append(bytes, codec.sceneMove(PLAYER_OID, (short) 4, false, scene, registry.catalog()));
        append(bytes, codec.scene((short) 5, scene, List.of(initial)));
        append(bytes, codec.sceneDelta(PLAYER_OID, SCENE_OID, 0, (short) 0, 100, 100, (short) 50,
            List.of(initial), List.of(), new int[0], Map.of(), true));
        append(bytes, codec.sceneDelta(PLAYER_OID, SCENE_OID, 1, (short) 0, 100, 150, (short) 50,
            List.of(), List.of(updated), new int[0], Map.of(ACTOR_ID, initial), true));

        var input = new v(new ByteArrayInputStream(bytes.toByteArray()));
        input.qe(); input.qe();
        DObject player = ((ObjectResponse<?>) input.qe()).yk();
        if (player == null) throw new AssertionError("Missing stock player object");
        var server = (ServerObject) ((ObjectResponse<?>) input.qe()).yk();
        checkServer(server);
        var throttle=(com.threerings.presents.net.UpdateThrottleMessage)input.qe();
        if(throttle.messagesPerSec!=SessionCodec.INTERIOR_TCP_MESSAGES_PER_SECOND)
            throw new AssertionError("Native TCP fallback throttle did not decode");
        var party = (DungeonPartyObject) ((ObjectResponse<?>) input.qe()).yk();
        checkParty(party, knight, profile, kind);

        var moveNotification = (EventNotification) input.qe();
        var move = (InvocationResponseEvent) moveNotification.yj();
        boolean configSeen = false, modelSeen = false;
        for (Object argument : move.yd()) {
            if (kind.equals("training") && argument instanceof com.threerings.projectx.dungeon.data.TrainingSceneConfig) configSeen = true;
            if (kind.equals("level") && argument instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig) configSeen = true;
            if (argument instanceof com.threerings.tudey.data.TudeySceneModel) modelSeen = true;
        }
        if (!configSeen || !modelSeen) throw new AssertionError("Interior sceneMove response has wrong config/model for " + kind);

        DObject place = ((ObjectResponse<?>) input.qe()).yk();
        if (!(place instanceof ProjectXSceneObject) || place instanceof TownSceneObject)
            throw new AssertionError("Interior scene snapshot must decode as ProjectXSceneObject, got " + place.getClass());
        if (!place.getClass().equals(ProjectXSceneObject.class)) throw new AssertionError("Unexpected interior scene subtype: " + place.getClass());
        var projectScene = (ProjectXSceneObject) place;
        if (projectScene.occupants == null || projectScene.occupantInfo == null || projectScene.speakService == null ||
            projectScene.tudeySceneService == null || projectScene.projectXSceneService == null || projectScene.mapIcons == null)
            throw new AssertionError("Interior ProjectXSceneObject is missing native place fields");

        var added = (SceneDeltaEvent) ((EventNotification) input.qe()).yj();
        if (added.aej() == null || added.aej().length != 1 || !(added.aej()[0] instanceof Dungeoneer actor))
            throw new AssertionError("Interior actor add did not decode as Dungeoneer");
        checkActor(actor, initial, knight, profile, manager);

        var changed = (SceneDeltaEvent) ((EventNotification) input.qe()).yj();
        if (changed.aek() == null || changed.aek().length != 1) throw new AssertionError("Missing Dungeoneer update delta");
        Object deltaMask = field(changed.aek()[0], "_mask");
        if (!Arrays.equals(new byte[]{(byte) 0xdc, 1, 0, 0, 0}, (byte[]) field(deltaMask, "agL")))
            throw new AssertionError("Dungeoneer ActorDelta must use the stock five-byte field mask");
        Actor applied = (Actor) changed.aek()[0].apply(actor);
        if (!(applied instanceof Dungeoneer moved)) throw new AssertionError("Dungeoneer delta changed actor type");
        checkActor(moved, updated, knight, profile, manager);
        if (input.available() != 0) throw new AssertionError("Unexpected trailing stock reader data");
        System.out.println("PASS: " + kind + " config, ProjectXSceneObject, native party subtype and Dungeoneer delta");
    }

    private static void checkParty(DungeonPartyObject party, Knight knight, WorldProfile profile, String kind) {
        if (party.partyService == null || party.members == null || party.health == null || party.status == null ||
            party.speakService == null || party.dungeonPartyService != null)
            throw new AssertionError("Party object service/set nullability differs from native minimal-party snapshot");
        if (party.members.size() != 1 || party.health.size() != 1 || party.status.size() != 1)
            throw new AssertionError("Party member/health/status sets must each contain the owner's entry");
        PartyMember member = party.members.iterator().next();
        if (member.Qu() != PLAYER_OID || member.joinOrder != 0 || !knight.name().equals(member.name.toString()) || member.color != (256 | knight.color()))
            throw new AssertionError("Native party member identity/color mismatch: " + member);
        if (member.helm == null || !knight.helm().equals(member.helm.getName()) || member.portrait != null ||
            member.weapons == null || member.weapons.length != 0 || member.steamId != 0)
            throw new AssertionError("Native party member equipment fields mismatch");
        Health health = party.health.iterator().next();
        if (health.Qu() != PLAYER_OID || health.current != 1 || health.maximum != 1 || health.bonus != 0 || party.z(member.name))
            throw new AssertionError("Native peaceful owner health must be positive and not trigger the stock dead-party predicate");
        Status status = party.status.iterator().next();
        if (status.Qu() != PLAYER_OID || status.conditions != null || status.expires != null || status.flags != 1)
            throw new AssertionError("Native peaceful status entry must be keyed to the owner with null conditions and an emergency revive");
        if (profile.items().size() != 5) throw new AssertionError("Invalid party fixture profile");
        if (party.spawn != null) throw new AssertionError("Native party spawn must remain unset");
        if (kind.equals("training")) {
            if (party.getClass() != DungeonPartyObject.class || party.memberLimit != 8 || party.gateId != 0 || party.depth != 0)
                throw new AssertionError("Native Training party metadata mismatch");
            return;
        }
        checkLevelParty((LevelPartyObject)party);
    }

    private static void checkLevelParty(LevelPartyObject party) {
        if (party.levelPartyService != null) throw new AssertionError("Unsupported level party service advertised");
        if (party.memberLimit != 1 || party.gateId != 0 || party.depth != 0 || party.floor != 0)
            throw new AssertionError("LevelPartyObject initial party metadata mismatch");
        if (party.pendingFloorName != null || party.pendingFloorDepth != 0 || party.actualGateId != 0 ||
            party.actualFloor != 0 || party.returnFloor != 0 || !Arrays.equals(new int[]{0}, party.wedgeIndices))
            throw new AssertionError("Peaceful interior party floor/gate state mismatch: pendingName=" + party.pendingFloorName +
                " pendingDepth=" + party.pendingFloorDepth + " actualGate=" + party.actualGateId + " actualFloor=" + party.actualFloor +
                " returnFloor=" + party.returnFloor + " wedges=" + Arrays.toString(party.wedgeIndices));
        if (!"EASY".equals(party.difficulty.name()) || !"SUBTOWN".equals(party.levelType.name()) ||
            !"NO_DEPTH".equals(party.floorType.name()) || !"NO_DEPTH".equals(party.pendingFloorType.name()) ||
            !party.inviteOnly || party.allowFriends || party.allowGuild || !party.restricted || party.hasPickups || party.dismissStamp != 0)
            throw new AssertionError("LevelPartyObject enum/default flags mismatch: difficulty="+party.difficulty+" level="+party.levelType+
                " floor="+party.floorType+" pending="+party.pendingFloorType+" flags="+party.inviteOnly+","+party.allowFriends+","+party.allowGuild+","+party.restricted+
                " pickups="+party.hasPickups+" dismiss="+party.dismissStamp);
    }

    private static void checkServer(ServerObject server) throws Exception {
        if (server == null || server.towns == null || server.towns.size() != 1 || server.gates == null || server.gates.size() != 1)
            throw new AssertionError("Peaceful interior server snapshot must contain one home town and one gate summary");
        if (server.disabledMissionPrefixes == null || !server.disabledMissionPrefixes.isEmpty())
            throw new AssertionError("Peaceful interior server snapshot unexpectedly carries mission-prefix state");
        TownSummary town = server.towns.iterator().next();
        if (integer(town, "_townId") != 1 || town.defaultSceneId != 1 || !"m.haven".equals(town.name) || !"town".equals(town.icon))
            throw new AssertionError("Peaceful interior source town must be the stock home-town summary");
        GateSummary gate = server.gates.iterator().next();
        if (integer(gate, "_gateId") != 0 || !"".equals(field(gate, "_name")) || !"".equals(field(gate, "_icon")) ||
            !"".equals(field(gate, "_description")))
            throw new AssertionError("Peaceful transport gate identity/text fields mismatch");
        Object colorizations = field(gate, "_colorizations");
        Object wheels = field(gate, "_wheels");
        if (colorizations == null || java.lang.reflect.Array.getLength(colorizations) != 0 ||
            wheels == null || java.lang.reflect.Array.getLength(wheels) != 0)
            throw new AssertionError("Peaceful transport gate must have empty colorizations and wheels");
        if (integer(gate, "_sourceId") != 1 || integer(gate, "_destId") != 0)
            throw new AssertionError("Peaceful transport gate must source the stock home town and have no destination");
        for (String name : new String[]{"_priority", "_tokens", "_ownerKnightId", "_flags"})
            if (integer(gate, name) != 0) throw new AssertionError("Peaceful transport gate has nonzero " + name);
    }

    private static void checkActor(Dungeoneer actor, ActorState state, Knight knight, WorldProfile profile,
            ConfigManager manager) throws Exception {
        if (integer(actor, "_id") != state.id() || integer(actor, "_created") != state.created() ||
            number(actor, "_rotation").floatValue() != state.rotation())
            throw new AssertionError("Dungeoneer base identity/rotation mismatch");
        var translation = field(actor, "_translation");
        if (translation.getClass().getField("x").getFloat(translation) != state.x() ||
            translation.getClass().getField("y").getFloat(translation) != state.y())
            throw new AssertionError("Dungeoneer position mismatch");
        ConfigReference<?> actorConfig = (ConfigReference<?>) field(actor, "_config");
        var resolvedConfig = actorConfig == null ? null : manager.d(ActorConfig.class, actorConfig.getName());
        if (actorConfig == null || !PC_CONFIG.equals(actorConfig.getName()) ||
            !(resolvedConfig != null && resolvedConfig.ai(manager) instanceof DungeonActorConfig.Dungeoneer dungeonConfig &&
              dungeonConfig.sprite instanceof DungeonActorSpriteConfig.Dungeoneer sprite && sprite.model != null &&
              "character/pc/model_level.dat".equals(sprite.model.getName()) && manager.a(ModelConfig.class, sprite.model) != null))
            throw new AssertionError("Dungeoneer uses an unresolved/non-dungeon stock actor sprite");
        var status = (ImmutableStatusModifiers) field(actor, "_status");
        if (status == null) throw new AssertionError("Dungeoneer status modifiers are absent");
        floats(status, new String[]{"_speedModifier", "_attackSpeedModifier", "_attackDamageModifier",
            "_relativeDamageModifier", "_defenseModifier", "_healModifier", "_shieldRegenModifier",
            "_shieldRegenStartModifier"}, 1f);
        floats(status, new String[]{"_chargeTime"}, 0f);
        booleans(status, new String[]{"_canMove", "_canRotate", "_canDefend", "_canAttack", "_canBeTargeted"}, true);
        booleans(status, new String[]{"_asleep", "_shocked"}, false);
        if (integer(status, "_collisionFlags") != 0) throw new AssertionError("Unexpected Dungeoneer collision modifier");
        var equipment = (ImmutableEquipmentModifiers) field(actor, "_equipMod");
        var weapon = (ImmutableEquipmentModifiers) field(actor, "_weaponMod");
        if (equipment == null || weapon == null)
            throw new AssertionError("Dungeoneer equipment modifiers are absent/non-default");
        for (var modifiers : List.of(equipment, weapon)) floats(modifiers,
            new String[]{"_chargeTime", "_speed", "_attackSpeed", "_defendingSpeed"}, 0f);
        if (field(actor, "_weapon") != null || field(actor, "_shield") != null || field(actor, "_shieldCostume") != null ||
            field(actor, "_conditions") != null || field(actor, "_impulse") != null || field(actor, "_activityData") != null ||
            field(actor, "_actionDesc") != null || field(actor, "_actionEntity") != null || field(actor, "_description") != null ||
            field(actor, "_helpTip") != null || field(actor, "_sprite") != null)
            throw new AssertionError("Unexpected nullable Dungeoneer/Knight field value");
        if (integer(actor, "_shieldRegenStart") != 0 || integer(actor, "_shieldHit") != -300000 ||
            number(actor, "_shieldHealth").floatValue() != 0f || integer(actor, "_shieldMaxHealth") != 0 ||
            integer(actor, "_target") != 0 || integer(actor, "_hitTime") != 0 || integer(actor, "_chainCount") != 0 ||
            integer(actor, "_chainResetTime") != 0 || integer(actor, "_spriteId") != 0 || integer(actor, "_spriteAttack") != 0 ||
            integer(actor, "_nextDodge") != 0)
            throw new AssertionError("Dungeoneer fixed state defaults mismatch");
        if (integer(actor, "_activity") != state.activity() || integer(actor, "_activityStarted") != state.activityStarted())
            throw new AssertionError("Dungeoneer activity delta mismatch");
    }

    private static ConfigManager configManager(Path game) throws Exception {
        Object resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager) ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),
            com.threerings.util.R.class, String.class).newInstance(resource, null, "config/");
        manager.init();
        return manager;
    }

    private static void fieldLayouts() throws Exception {
        fields(LevelPartyObject.class, "_oid", "partyService", "speakService", "members", "health", "status", "visited",
            "spawn", "depth", "hasPickups", "dismissStamp", "dungeonPartyService", "memberLimit", "gateId",
            "levelPartyService", "difficulty", "loot", "equipment", "floor", "levelType", "wedgeIndices",
            "inviteOnly", "allowFriends", "allowGuild", "restricted", "heat", "pendingFloorName", "pendingFloorDepth",
            "floorType", "pendingFloorType", "playFanfare", "showElevatorChoice", "showLoot", "splitInvites",
            "actualGateId", "actualFloor", "returnFloor", "altWedgeIndex", "timer", "globals", "eventItem", "eventItems");
        fields(GateSummary.class, "_gateId", "_name", "_icon", "_colorizations", "_description", "_sourceId", "_destId",
            "_priority", "_tokens", "_ownerKnightId", "_flags", "_wheels");
        fields(GateSummary.Wheel.class, "_skip", "_wedges");
        fields(TownSummary.class, "name", "icon", "colorizations", "description", "flags", "defaultSceneId", "_townId");
        fields(PartyMember.class, "_playerOid", "joinOrder", "name", "color", "helm", "portrait", "weapons", "steamId");
        fields(Health.class, "_playerOid", "current", "maximum", "bonus");
        fields(Status.class, "_playerOid", "conditions", "expires", "flags");
        fields(ProjectXSceneObject.class, "_oid", "occupants", "occupantInfo", "speakService", "tudeySceneService",
            "projectXSceneService", "mapIcons");
        fields(Dungeoneer.class, "_config", "_id", "_created", "_destroyed", "_translation", "_rotation", "_flags",
            "_maxStep", "_direction", "_activity", "_activityStarted", "_activityData", "_actionDesc", "_actionEntity",
            "_description", "_helpTip", "_lift", "_sprite", "_weapon", "_shield", "_shieldCostume", "_conditions",
            "_shieldRegenStart", "_shieldHit", "_shieldHealth", "_shieldMaxHealth", "_impulse", "_target", "_hitTime",
            "_chainCount", "_chainResetTime", "_spriteId", "_spriteAttack", "_nextDodge", "_status", "_equipMod", "_weaponMod");
        fields(ImmutableStatusModifiers.class, "_speedModifier", "_attackSpeedModifier", "_attackDamageModifier",
            "_relativeDamageModifier", "_defenseModifier", "_healModifier", "_shieldRegenModifier",
            "_shieldRegenStartModifier", "_chargeTime", "_canMove", "_canRotate", "_canDefend", "_canAttack",
            "_asleep", "_shocked", "_canBeTargeted", "_collisionFlags");
        fields(ImmutableEquipmentModifiers.class, "_chargeTime", "_speed", "_attackSpeed", "_defendingSpeed");
        System.out.println("FIELDS: verified stock party, gate, place, status, modifier and Dungeoneer streamer layouts");
    }

    private static void fields(Class<?> type, String... expected) throws Exception {
        Object streamer = Streamer.bR(type);
        Field field = null;
        for (Class<?> current = streamer.getClass(); current != null && field == null; current = current.getSuperclass()) {
            try { field = current.getDeclaredField("_fields"); }
            catch (NoSuchFieldException ignored) { }
        }
        if (field == null) throw new AssertionError("Stock Streamer has no _fields for " + type.getName());
        field.setAccessible(true);
        String[] actual = Arrays.stream((Field[]) field.get(streamer)).map(Field::getName).toArray(String[]::new);
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Stock _fields changed for " + type.getName() + ": " + Arrays.toString(actual));
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }
    private static int integer(Object target, String name) throws Exception { return ((Number) field(target, name)).intValue(); }
    private static Number number(Object target, String name) throws Exception { return (Number) field(target, name); }

    private static void floats(Object target, String[] names, float expected) throws Exception {
        for (String name : names) if (number(target, name).floatValue() != expected)
            throw new AssertionError("Unexpected stock modifier " + name + "=" + number(target, name));
    }
    private static void booleans(Object target, String[] names, boolean expected) throws Exception {
        for (String name : names) if ((boolean) field(target, name) != expected)
            throw new AssertionError("Unexpected stock status modifier " + name);
    }

    private static void append(ByteArrayOutputStream bytes, byte[] frame) {
        bytes.write(frame, 4, frame.length - 4);
    }
}
