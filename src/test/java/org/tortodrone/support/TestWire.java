package org.tortodrone.support;

import java.io.*;
import java.util.*;
import org.tortodrone.protocol.HandshakeCodec;

/** Independent reader for the current client schema, also usable against the packaged server. */
public final class TestWire {
    public static final String NET = "com.threerings.presents.net.";
    public static final String DOB = "com.threerings.presents.dobj.";
    public static final String INV = "com.threerings.presents.data.InvocationMarshaller$";
    private static final String PLAYER = """
        _oid:i username:o receivers:o location:o status:b awayMessage:s pawnId:i
        playerId:i machineIdent:s region:s tokens:o siteId:i playerCreated:l sessions:i
        achievements:o gifts:o survey:o queuePosition:i availableKnights:o knightId:i
        knight:o color:i eye:o height:o guild:o guildId:i guildOid:i officerOid:i
        friends:o newMailInfo:o notifications:o bagId:i items:o sprites:o harnesses:o recipes:o
        equipment:c quickbar:c equippedSprite:i loadouts:o weaponSlots:i trinketSlots:i
        heatBonus:b pvpTokenBonus:i crowns:i showEnergy:b energy:i flags:i homeTown:o
        lastTownSceneId:i zoneId:i instanceId:i zoneGeneralOid:i zoneTradeOid:i partyOid:i
        sceneDialog:o sceneDialogCreated:l newSceneDialog:o uniqueDialog:o reconnectZoneId:i
        reconnectSceneId:i pvpSignup:o pvpGame:o equipPolicy:o outbid:b progressFlags:i
        promotionFlags:c objectives:o memories:o stats:o steamVoiceChatEnabled:b
        externalSecret:l rewardIds:c whereabouts:o completedMissions:o expansions:c
        missionTests:c rank:o prestige:i lfg:b savedColors:c savedEyes:o savedHeights:o
        itemAcquireDelay:i lootReportLevel:o prefs:o
        """;
    private static final String SERVER = """
        _oid:i nodeName:s coinExchangeFee:b guildFormationCrowns:i guildFormationEnergy:i
        dungeonSparkOfLifeCost:i pvpSparkOfLifeCost:i towns:o gates:o mailFee:i mailAttachFee:i
        auctionWonFeePercent:f rebootTime:l rebootMessage:s disabledAchievements:c
        endSurveyProbability:f disabledMissionPrefixes:c scopeVariables:c loveEnabled:b
        loveUploadsEnabled:b events:c spamwords:o avoidSupportSite:b
        """;
    private final Map<Integer, String> classes = new HashMap<>();
    private final Map<Integer, String> interns = new HashMap<>();

    public TestWire() {
        String[] names = {NET + "SecureResponse", NET + "AuthResponseData", NET + "AuthResponse",
            "com.threerings.projectx.data.ProjectXAuthResponseData", "com.threerings.util.Name"};
        for (int i = 0; i < names.length; i++) classes.put(i + 1, names[i]);
    }

    public DataInputStream frame(InputStream source) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(Objects.requireNonNull(HandshakeCodec.readFrame(source))));
    }
    public DataInputStream frame(byte[] frame) throws IOException { return frame(new ByteArrayInputStream(frame)); }

    public String type(DataInputStream in) throws IOException {
        int code = in.readShort();
        if (code == 0) return null;
        if (code < 0) {
            check(-code == classes.size() + 1, "sequential downstream class mapping");
            String name = in.readUTF(); classes.put(-code, name); return name;
        }
        String name = classes.get(code); check(name != null, "known downstream class mapping"); return name;
    }

    public int bootstrap(DataInputStream in, int port) throws IOException {
        return bootstrap(in, port, null);
    }

    public int bootstrap(DataInputStream in, int port, String expectedHost) throws IOException {
        check(type(in).equals(NET + "BootstrapNotification") && in.readShort() == -1, "bootstrap notification");
        check(type(in).equals("com.threerings.projectx.data.ProjectXBootstrapData"), "current bootstrap type");
        check(in.readInt() > 0, "connection identity"); int oid = in.readInt(); check(oid > 3, "client identity reserved range");
        check(in.readBoolean() && in.readInt() == 15, "login service count");
        Set<String> services = new HashSet<>();
        for (int i = 0; i < 15; i++) {
            String name = type(in); check(name.endsWith("Marshaller"), "service handle type"); services.add(name);
            check(in.readInt() == 3 && in.readInt() == i + 1, "service routing");
        }
        check(services.size() == 15 && services.contains("com.threerings.projectx.data.KnightMarshaller")
            &&services.contains("com.threerings.projectx.social.data.SocialMarshaller")
            &&services.contains("com.threerings.projectx.admin.data.ProjectXAdminMarshaller")
            &&services.contains("com.threerings.projectx.sprites.data.BattleSpriteMarshaller")
            &&services.contains("com.threerings.projectx.auction.data.AuctionMarshaller"),
            "distinct login, trade, admin and other response services");
        check(in.readInt() == 2, "configuration identity");
        check(in.readBoolean(), "bootstrap host present");
        String host = in.readUTF();
        check(!host.isEmpty() && !host.equals("0.0.0.0") && !host.equals("::"), "bootstrap uses a concrete server address");
        if (expectedHost != null) check(host.equals(expectedHost), "bootstrap host matches connected server interface");
        check(in.readBoolean() && in.readInt() == 1 && in.readInt() == port, "advertised TCP port");
        check(in.readBoolean() && in.readInt() == 0, "no UDP endpoints");
        check(in.readInt() == 1, "server object identity");
        for (int i = 1; i < 10; i++) check(in.readInt() == 0, "unavailable optional object");
        end(in); return oid;
    }

    public Object object(DataInputStream in) throws IOException {
        String name = type(in);
        if (name == null) return null;
        return switch (name) {
            case "java.lang.String" -> in.readUTF();
            case "java.lang.Integer" -> in.readInt();
            case "com.threerings.opengl.renderer.config.ColorizationConfig$Normal" -> Map.of("colorization",in.readInt());
            case "com.threerings.util.Name" -> string(in);
            case "com.threerings.presents.client.InvocationReceiver$Registration" -> Map.of("code", string(in), "id", in.readShort());
            case "com.threerings.presents.dobj.DSet" -> {
                int count = in.readInt(); check(count >= 0 && count <= 128, "snapshot DSet count");
                List<Object> entries = new ArrayList<>(); for (int i = 0; i < count; i++) entries.add(object(in)); yield entries;
            }
            case "com.threerings.projectx.data.ProjectXTokenRing" -> in.readInt();
            case "com.threerings.projectx.data.PlayerObject" -> fields(in, PLAYER);
            case "com.threerings.projectx.data.ServerObject" -> fields(in, SERVER);
            case "com.threerings.projectx.town.data.TownSummary" -> fields(in,
                "name:s icon:s colorizations:o description:s flags:i defaultSceneId:i _townId:i");
            case "com.threerings.projectx.dungeon.data.GateSummary" -> fields(in,
                "_gateId:I _name:s _icon:s _colorizations:o _description:s _sourceId:i _destId:i _priority:i _tokens:i _ownerKnightId:i _flags:i _wheels:o");
            case "[Lcom.threerings.opengl.renderer.config.ColorizationConfig;", "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;" -> {
                check(in.readInt()==0,"empty polymorphic transport array");yield List.of();
            }
            case "com.threerings.config.dist.data.DConfigObject" -> fields(in, "_oid:i dconfigService:o added:o updated:o removed:o");
            case "com.threerings.projectx.data.LootReportLevel" -> in.readUnsignedByte();
            case "[Lcom.threerings.projectx.data.AvailableKnight;" -> {
                int count = in.readInt(); check(count >= 0 && count <= 3, "bounded knight roster");
                List<Object> entries = new ArrayList<>();
                for (int i = 0; i < count; i++) entries.add(object(in)); yield entries;
            }
            case "com.threerings.projectx.data.AvailableKnight" -> fields(in,
                "id:i name:o rank:i guild:s sessionSeconds:i model:o senders:c");
            case "com.threerings.config.ConfigReference" -> fields(in, "_name:n _arguments:o");
            case "com.threerings.config.ArgumentMap" -> {
                int count = in.readInt(); check(count >= 0 && count <= 128, "bounded preview arguments");
                Map<String, Object> arguments = new HashMap<>();
                for (int i = 0; i < count; i++) arguments.put(intern(in), object(in)); yield arguments;
            }
            case "[Ljava.lang.String;", "[Lcom.threerings.projectx.data.ProjectXCodes$EyeType;",
                "[Lcom.threerings.projectx.data.ProjectXCodes$KnightHeight;" -> {
                check(in.readInt() == 0 && in.readUnsignedShort() == 0, "empty final-component array null mask"); yield List.of();
            }
            default -> throw new AssertionError("Unexpected snapshot class: " + name);
        };
    }

    private Map<String, Object> fields(DataInputStream in, String schema) throws IOException {
        Map<String, Object> fields = new HashMap<>();
        for (String field : schema.trim().split("\\s+")) {
            String[] parts = field.split(":");
            Object value = switch (parts[1]) {
                case "i" -> in.readInt(); case "l" -> in.readLong(); case "b" -> in.readByte();
                case "I" -> in.readBoolean()?in.readInt():null;
                case "f" -> in.readFloat(); case "s" -> string(in); case "o" -> object(in); case "n" -> intern(in);
                case "c" -> { check(in.readBoolean() && in.readInt() == 0, "empty typed collection"); yield List.of(); }
                default -> throw new AssertionError("Unknown schema kind");
            };
            fields.put(parts[0], value);
        }
        return fields;
    }

    private String intern(DataInputStream in) throws IOException {
        int code = in.readShort();
        if (code == 0) return null;
        if (code < 0) {
            check(-code == interns.size() + 1, "sequential string intern mapping");
            String value = in.readUTF(); interns.put(-code, value); return value;
        }
        check(interns.containsKey(code), "known string intern mapping"); return interns.get(code);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(DataInputStream in, short id, int oid) throws IOException {
        check(type(in).equals(NET + "ObjectResponse") && in.readShort() == id, "object response correlation");
        Map<String, Object> fields = (Map<String, Object>) object(in);
        check(fields.get("_oid").equals(oid), "object response identity"); end(in); return fields;
    }
    public static String string(DataInputStream in) throws IOException { return in.readBoolean() ? in.readUTF() : null; }
    public static void end(DataInputStream in) throws IOException { check(in.available() == 0, "complete current schema boundary"); }
    public static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Synthetic client encoder, independent from the server decoder. */
    public static final class Writer {
        private final Map<String, Integer> classes = new HashMap<>();
        public Writer(String... initial) { for (String name : initial) classes.put(name, classes.size() + 1); }
        public void type(DataOutputStream out, String name) throws IOException {
            Integer code = classes.get(name);
            if (code == null) { code = classes.size() + 1; classes.put(name, code); out.writeShort(-code); out.writeUTF(name); }
            else out.writeShort(code);
        }
        public byte[] body(Encoder encoder) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); encoder.write(new DataOutputStream(bytes)); return bytes.toByteArray();
        }
        public void send(OutputStream output, Encoder encoder) throws IOException {
            byte[] body = body(encoder); new DataOutputStream(output).writeInt(body.length + 4); output.write(body); output.flush();
        }
        public void message(DataOutputStream out, String name, int id) throws IOException { type(out, NET + name); out.writeShort(id); }
    }
    @FunctionalInterface public interface Encoder { void write(DataOutputStream out) throws IOException; }
}
