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

/** Shared wire primitives and record encoders. Feature codecs extend this class so they encode
 * through one per-connection {@link WireContext} without owning private symbol tables. */
public abstract class WireWriter {
    protected static final String PREFIX = WireTypes.NET_PREFIX;
    public static final int MAX_DOWNSTREAM_FRAME = 1024 * 1024;
    public static final int TCP_MESSAGES_PER_SECOND = 100;
    protected final WireContext context;
    protected final ItemCatalog itemCatalog;

    protected WireWriter(WireContext context, ItemCatalog itemCatalog) {
        this.context = java.util.Objects.requireNonNull(context);
        this.itemCatalog = java.util.Objects.requireNonNull(itemCatalog);
    }
    protected static StarterCatalog.Model grantModel(org.tortodrone.core.mission.MissionCatalog.Grant grant){
        var args=new java.util.TreeMap<String,Object>();
        if(grant.locked())args.put("_createLocked",true);
        if(!grant.variants().isEmpty())args.put("_setVariants",new org.tortodrone.core.world.FireCatalog.ListValue(WireTypes.CONFIG_REFERENCE,
            new java.util.ArrayList<Object>(grant.variants().stream().map(name->(Object)new StarterCatalog.Model(name,java.util.Map.of())).toList())));
        return new StarterCatalog.Model(grant.config(),args);
    }

    protected void objectHeader(DataOutputStream out, short id, String name, int oid) throws IOException {
        type(out, PREFIX + "ObjectResponse"); out.writeShort(id); type(out, name); out.writeInt(oid);
    }

    protected void emptyDSet(DataOutputStream out) throws IOException {
        type(out, WireTypes.DSET); out.writeInt(0);
    }

    protected void emptyObjectArray(DataOutputStream out, String component, boolean finalComponent) throws IOException {
        type(out, "[L" + component + ";"); out.writeInt(0);
        if (finalComponent) out.writeShort(0); // Empty array null mask for final component types.
    }

    protected static void emptyCollection(DataOutputStream out) throws IOException {
        out.writeBoolean(true); out.writeInt(0);
    }

    /** One stock {@code com.threerings.projectx.data.Achievement} DSet entry. */
    protected void achievement(DataOutputStream out, int type, long unlocked) throws IOException {
        type(out,"com.threerings.projectx.data.Achievement");
        type(out,"com.threerings.projectx.data.Achievement$Type");
        out.writeByte(type);
        out.writeLong(unlocked);
    }

    /** The native account achievements set, ordered by its enum key. */
    protected void achievements(DataOutputStream out, org.tortodrone.core.character.AchievementState state) throws IOException {
        var unlocked = new java.util.TreeMap<>(state.unlocked());
        type(out, WireTypes.DSET);
        out.writeInt(unlocked.size());
        for (var entry : unlocked.entrySet()) achievement(out, entry.getKey(), entry.getValue());
    }

    /** {@code StatSet} is a {@code DSet<IntStat>}; every stock prototype is an IntStat. */
    protected void statSet(DataOutputStream out, java.util.Map<Integer,Integer> values) throws IOException {
        // DSet keys are StatType names, so the on-wire order is the key's string order,
        // not the enum ordinal order.
        var ordered = new java.util.TreeMap<Integer,Integer>(
            java.util.Comparator.comparing(org.tortodrone.core.character.NativeStats::name));
        ordered.putAll(values);
        type(out,"com.threerings.stats.data.StatSet");
        out.writeInt(ordered.size());
        for (var entry : ordered.entrySet()) {
            int stat = entry.getKey();
            if (stat < 0 || stat >= org.tortodrone.core.character.NativeStats.count())
                throw new IOException("Unknown lifetime stat " + stat);
            int value = entry.getValue();
            if (value < 0) throw new IOException("Negative lifetime stat");
            type(out,"com.threerings.stats.data.IntStat");
            type(out,"com.threerings.projectx.data.StatType");
            out.writeByte(stat);
            out.writeBoolean(false); // _modified is client-local state
            out.writeByte(0);        // _modCount
            out.writeInt(value);
        }
    }

    /** A non-empty boxed integer set uses the stock field form: non-null flag, size, then
     *  each element behind the connection-local `java.lang.Integer` class code. */
    protected void integerSet(DataOutputStream out, java.util.Set<Integer> values) throws IOException {
        out.writeBoolean(true); out.writeInt(values.size());
        for(int value:values){type(out,"java.lang.Integer");out.writeInt(value);}
    }

    protected static void string(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    protected void roster(DataOutputStream out, List<org.tortodrone.core.character.RosterEntry> knights) throws IOException {
        type(out, "[Lcom.threerings.projectx.data.AvailableKnight;"); out.writeInt(knights.size());
        for (org.tortodrone.core.character.RosterEntry entry : knights) {
            Knight knight = entry.knight();
            type(out, "com.threerings.projectx.data.AvailableKnight");
            out.writeInt(knight.id()); type(out, WireTypes.NAME); string(out, knight.name());
            out.writeInt(entry.rank()); string(out, null); out.writeInt(0); // rank, guild, session seconds
            value(out, entry.preview());
            emptyCollection(out); // Typed Set<Name>, no mail senders.
        }
    }

    void value(DataOutputStream out,Object value) throws IOException {
        if (value == null || value==FireCatalog.NullValue.INSTANCE) { out.writeShort(0);
        } else if (value instanceof Long number) { type(out,"java.lang.Long"); out.writeLong(number);
        } else if (value instanceof org.tortodrone.core.wire.EnumValue e) {type(out,e.type());out.writeByte(e.ordinal());
        } else if (value instanceof Boolean flag) { type(out,"java.lang.Boolean"); out.writeBoolean(flag);
        } else if (value instanceof StarterCatalog.Model model) {
            type(out,WireTypes.CONFIG_REFERENCE); intern(out,model.name());
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
            type(out,"[L"+array.componentType()+";");int size=array.values().size();out.writeInt(size);
            if(array.componentType().equals("java.lang.String")){
                out.writeShort((size+7)/8);
                for(int i=0;i<size;i+=8){int mask=0;for(int j=i;j<Math.min(i+8,size);j++)if(array.values().get(j)!=FireCatalog.NullValue.INSTANCE)mask|=1<<(j-i);out.writeByte(mask);}
                for(var element:array.values())if(element!=FireCatalog.NullValue.INSTANCE){if(!(element instanceof String text))throw new IOException("Invalid String array element");out.writeUTF(text);}
            }else for(var element:array.values())value(out,element);
        } else if(value instanceof FireCatalog.MapValue map) {
            type(out,"java.util.HashMap");out.writeInt(map.values().size());for(var entry:new java.util.TreeMap<>(map.values()).entrySet()){value(out,entry.getKey());value(out,entry.getValue());}
        } else throw new IOException("Unsupported preview argument");
    }

    protected void streamField(DataOutputStream out,FireCatalog.FieldValue field)throws IOException {
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

    protected void intern(DataOutputStream out, String value) throws IOException {
        context.intern(out,value);
    }

    protected void rank(DataOutputStream out,int oid,WorldProfile profile)throws IOException{
        attribute(out,oid,"rank",o->{type(o,"com.threerings.projectx.mission.data.RankPair");o.writeInt(profile.progress().rank());o.writeInt(profile.progress().subrank());});
    }

    protected void harness(DataOutputStream out,org.tortodrone.core.town.TownState.Harness h)throws IOException {type(out,"com.threerings.projectx.sprites.data.Harness");out.writeBoolean(true);out.writeLong(h.id());value(out,new StarterCatalog.Model(h.config(),Map.of()));}

    protected void sprite(DataOutputStream out,WorldProfile owner,org.tortodrone.core.town.TownState.Sprite s)throws IOException {
        var h=owner.town().harnesses().stream().filter(v->v.id()==s.harness()).findFirst().orElse(new org.tortodrone.core.town.TownState.Harness(0,"Iron Harness"));sprite(out,owner.worldId(),s,h);
    }

    protected void sprite(DataOutputStream out,int worldId,org.tortodrone.core.town.TownState.Sprite s,org.tortodrone.core.town.TownState.Harness h)throws IOException {
        type(out,"com.threerings.projectx.sprites.data.BattleSprite");string(out,s.name());out.writeFloat(s.heat());out.writeBoolean(s.lastFull()!=null);if(s.lastFull()!=null)out.writeLong(s.lastFull());out.writeInt(s.level());out.writeLong(s.fullStamp());
        out.writeBoolean(true);out.writeInt(3);for(int skill=0;skill<3;skill++){type(out,"com.threerings.projectx.sprites.data.BattleSprite$SkillBonus");primitiveInts(out,new int[]{s.powers().get(skill*3),s.powers().get(skill*3+1),s.powers().get(skill*3+2)});value(out,new org.tortodrone.core.wire.EnumValue(WireTypes.SPRITE_ULTIMATE,s.ultimates().get(skill)));}
        out.writeInt(s.perkLevel());out.writeBoolean(true);out.writeInt(s.perks().size());for(int perk:s.perks().stream().sorted().toList())value(out,new org.tortodrone.core.wire.EnumValue(WireTypes.SPRITE_PERK,perk));
        value(out,s.perk()==null?null:new org.tortodrone.core.wire.EnumValue(WireTypes.SPRITE_PERK,s.perk()));out.writeInt(s.id());out.writeInt(worldId);out.writeUTF(s.config());harness(out,h==null?new org.tortodrone.core.town.TownState.Harness(0,"Iron Harness"):h);
    }

    protected void entryRemoved(DataOutputStream out,int oid,String field,Object key)throws IOException {event(out,"EntryRemovedEvent",oid);string(out,field);value(out,key);}

    protected void element(DataOutputStream out,int oid,String field,int index,int number)throws IOException {event(out,"ElementUpdatedEvent",oid);string(out,field);value(out,number);out.writeInt(index);}

    protected void event(DataOutputStream out,String kind,int oid)throws IOException {type(out,"com.threerings.presents.dobj."+kind);out.writeInt(oid);}

    protected void partyEntry(DataOutputStream out,int party,String field,String event)throws IOException {type(out,"com.threerings.presents.dobj."+event);out.writeInt(party);string(out,field);}

    protected void eventHeader(DataOutputStream out,String kind,int oid)throws IOException {
        type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.presents.dobj."+kind);out.writeInt(oid);
    }

    protected void attribute(DataOutputStream out,int oid,String field,Writer writer)throws IOException {
        type(out,"com.threerings.presents.dobj.AttributeChangedEvent");out.writeInt(oid);string(out,field);writer.write(out);
    }

    protected void name(DataOutputStream out,String name)throws IOException{if(name==null){out.writeShort(0);return;}type(out,WireTypes.NAME);string(out,name);}

    protected void longs(DataOutputStream out,long[] values)throws IOException{type(out,"[J");out.writeInt(values.length);for(long v:values)out.writeLong(v);}

    protected static void primitiveInts(DataOutputStream out,int[] values)throws IOException{out.writeBoolean(values!=null);if(values==null)return;out.writeInt(values.length);for(int v:values)out.writeInt(v);}

    protected void townSummary(DataOutputStream out)throws IOException {
        townSummary(out,SceneSources.current().entry().map());
    }

    protected void townSummary(DataOutputStream out,org.tortodrone.core.world.MapScene entry)throws IOException {
        type(out,"com.threerings.projectx.town.data.TownSummary");string(out,entry.townName());string(out,"town");
        emptyObjectArray(out,WireTypes.COLORIZATION,false);string(out,null);
        // TownSummary carries the unqualified town ID; its native zR() qualifies it.
        // Captured initial Camp alone carries the intro-town flag.
        out.writeInt(entry.id()==217?1:0);out.writeInt(entry.id());out.writeInt(entry.zone()&0x00ffffff);
    }

    protected void homeTown(DataOutputStream out,WorldProfile profile)throws IOException {
        var registry=SceneSources.current();int stage=profile.progress().openingStage();
        if(stage==2)townSummary(out,registry.catalog().scene(218));
        else if(stage>=0&&stage<2)townSummary(out,registry.catalog().scene(217));
        else townSummary(out);
    }

    protected void conditions(DataOutputStream out,org.tortodrone.core.world.SceneStatus.State status)throws IOException {
        if(status.conditions().isEmpty()){out.writeShort(0);return;}
        type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(status.conditions().size());for(var condition:status.conditions())value(out,condition);
    }

    protected void vector(DataOutputStream out,float x,float y)throws IOException{type(out,WireTypes.VECTOR2F);out.writeFloat(x);out.writeFloat(y);}

    void type(DataOutputStream out, String name) throws IOException {
        context.type(out,name);
    }

    protected static byte[] frame(Writer writer) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(body));
        if (body.size() + 4 > MAX_DOWNSTREAM_FRAME) throw new IOException("Session frame too large");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(body.size() + 4);
        body.writeTo(out);
        return frame.toByteArray();
    }

    protected void sourceKey(DataOutputStream out,ActorState source)throws IOException {
        if(source==null)out.writeShort(0);
        else if(source.entity()!=null&&source.entity().entrySource()){
            if(!source.entity().key().matches("[0-9]+"))throw new IOException("Placed interaction has no numeric entry identity");
            type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,Integer.parseInt(source.entity().key()));
        }else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(source.id());}
    }

    protected byte[] invocationFailure(int clientOid, short requestId, String reason) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.InvocationResponseEvent"); out.writeInt(clientOid);
            out.writeShort(requestId); out.writeByte(0);
            out.writeBoolean(true); out.writeInt(1);
            // MessageBundle treats a leading tilde as literal text; known stock keys translate.
            // Qualified keys (%bundle:key|~arg) resolve through MessageUtil.qualify and must
            // keep their prefix or the client would print the whole compound key literally.
            type(out, "java.lang.String"); out.writeUTF(reason != null && (reason.startsWith("%")||reason.matches("m\\.[a-z0-9_]+")||reason.equals("e.not_use_busy")) ? reason : "~" + reason);
        });
    }

    protected interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
