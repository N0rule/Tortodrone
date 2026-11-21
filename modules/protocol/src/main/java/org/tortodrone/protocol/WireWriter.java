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
    private static final List<String> DAMAGE_PERFORMANCE=List.of(
        "WEAK","NORMAL","STRONG","WEAK_SMALL","WEAK_MEDIUM","WEAK_LARGE",
        "NORMAL_SMALL","NORMAL_MEDIUM","NORMAL_LARGE","STRONG_SMALL","STRONG_MEDIUM","STRONG_LARGE");
    protected void tutorialGate(DataOutputStream out)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.GateSummary");out.writeBoolean(true);out.writeInt(org.tortodrone.core.mission.TutorialRoute.GATE);
        string(out,"m.tutorial_b");string(out,"queen");
        type(out,"[Lcom.threerings.opengl.renderer.config.ColorizationConfig;");out.writeInt(2);
        for(int color:new int[]{544,799}){type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal");out.writeInt(color);}
        string(out,"~");out.writeInt(0);out.writeInt(0);out.writeInt(0);out.writeInt(9);out.writeInt(0);out.writeInt(3);
        type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;");out.writeInt(1);
        type(out,"com.threerings.projectx.dungeon.data.GateSummary$Constant");out.writeByte(0);
        type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;");out.writeInt(1);
        type(out,"com.threerings.projectx.dungeon.data.GateSummary$Level");string(out,"m.tutorial_b");string(out,"clockwork");
        emptyObjectArray(out,WireTypes.COLORIZATION,false);string(out,"~");
        type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(0);out.writeBoolean(false);
        out.writeFloat(0);out.writeBoolean(true);out.writeInt(1);out.writeFloat((float)(2*Math.PI));out.writeLong(0);
    }

    protected void openingGate(DataOutputStream out,int gateId)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.MissionGateSummary");out.writeBoolean(true);out.writeInt(gateId);
        string(out,"%mission:m.ancient_generator");string(out,"pawn");
        type(out,"[Lcom.threerings.opengl.renderer.config.ColorizationConfig;");out.writeInt(2);
        for(int color:new int[]{532,787}){type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal");out.writeInt(color);}
        string(out,"m.training_1_desc");for(int i=0;i<5;i++)out.writeInt(0);out.writeInt(8); // source/dest/priority/tokens/owner/flags
        type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;");out.writeInt(2);
        for(int floor=0;floor<2;floor++){
            type(out,"com.threerings.projectx.dungeon.data.GateSummary$Constant");out.writeByte(0);
            type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;");out.writeInt(1);
            type(out,"com.threerings.projectx.dungeon.data.GateSummary$Level");string(out,"m.mission|%mission:m.ancient_generator|"+(floor==0?"m.mission_lobby":"m.training_grounds_generator"));string(out,floor==0?"lobby":"clockwork");
            emptyObjectArray(out,WireTypes.COLORIZATION,false);string(out,floor==0?"m.lobby_desc":"~");
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(floor==0?2:0);out.writeBoolean(floor!=0);
            out.writeFloat(0);out.writeBoolean(true);out.writeInt(1);out.writeFloat((float)(2*Math.PI));out.writeLong(0);
        }
        string(out,null);string(out,null); // no expansion/prestige access requirement
    }

    protected void missionGate(DataOutputStream out,org.tortodrone.core.mission.MissionGates.Gate gate)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.MissionGateSummary");out.writeBoolean(true);out.writeInt(gate.gateId());
        string(out,gate.name());string(out,gate.icon());
        type(out,"[Lcom.threerings.opengl.renderer.config.ColorizationConfig;");out.writeInt(gate.colors().size());
        for(int color:gate.colors()){type(out,"com.threerings.opengl.renderer.config.ColorizationConfig$Normal");out.writeInt(color);}
        string(out,gate.description());
        out.writeInt(gate.sourceId());out.writeInt(gate.destinationId());out.writeInt(gate.priority());out.writeInt(gate.tokens());
        out.writeInt(0);out.writeInt(gate.flags()); // owner is unset for a shared mission gate
        type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;");out.writeInt(gate.wedges().size());
        for(var wedge:gate.wedges()){
            type(out,"com.threerings.projectx.dungeon.data.GateSummary$Constant");out.writeByte(wedge.skip());
            type(out,"[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;");out.writeInt(1);
            type(out,"com.threerings.projectx.dungeon.data.GateSummary$Level");string(out,wedge.name());string(out,wedge.icon());
            emptyObjectArray(out,WireTypes.COLORIZATION,false);string(out,wedge.description());
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(levelType(wedge.type()));
            out.writeBoolean(wedge.restricted());
            out.writeFloat(wedge.velocity());out.writeBoolean(true);out.writeInt(1);out.writeFloat(wedge.length());out.writeLong(wedge.start());
        }
        string(out,null);string(out,null); // no expansion/prestige access requirement
    }

    protected static int levelType(String type){
        return switch(type){case "LEVEL"->0;case "TERMINAL"->1;case "LOBBY"->2;case "SUBTOWN"->3;case "CORE"->4;default->5;};
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

    protected void sceneDeltaHeader(DataOutputStream out,int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,short elapsed)throws IOException {
        type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.tudey.dobj.SceneDeltaEvent");out.writeInt(oid);
        out.writeInt(sceneOid);out.writeInt(acknowledge);out.writeShort(ping);out.writeInt(reference);out.writeInt(timestamp);out.writeShort(elapsed);
    }

    protected void sceneDeltaActors(DataOutputStream out,int observer,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects,List<SceneCombatEvent> combat)throws IOException {
        if(added.isEmpty())out.writeShort(0);else{type(out,WireTypes.ACTOR_ARRAY);out.writeInt(added.size());for(var a:added)actor(out,a,interior,observer);}
        if(updated.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.dobj.ActorDelta;");out.writeInt(updated.size());out.writeShort((updated.size()+7)/8);for(int i=0;i<updated.size();i+=8)out.writeByte((1<<Math.min(8,updated.size()-i))-1);for(var a:updated)actorDelta(out,a,baseline.get(a.id()),interior,observer);}
        out.writeBoolean(true);out.writeInt(removed.length);for(int id:removed)out.writeInt(id);
        if(effects.isEmpty()&&combat.isEmpty())out.writeShort(0);else {type(out,"[Lcom.threerings.tudey.data.effect.Effect;");out.writeInt(effects.size()+combat.size());for(var effect:effects)effect(out,effect);for(var damage:combat)effect(out,damage);}
    }

    protected void eventHeader(DataOutputStream out,String kind,int oid)throws IOException {
        type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.presents.dobj."+kind);out.writeInt(oid);
    }

    protected void attribute(DataOutputStream out,int oid,String field,Writer writer)throws IOException {
        type(out,"com.threerings.presents.dobj.AttributeChangedEvent");out.writeInt(oid);string(out,field);writer.write(out);
    }

    protected void name(DataOutputStream out,String name)throws IOException{if(name==null){out.writeShort(0);return;}type(out,WireTypes.NAME);string(out,name);}

    protected void longs(DataOutputStream out,long[] values)throws IOException{type(out,"[J");out.writeInt(values.length);for(long v:values)out.writeLong(v);}

    protected static void primitiveInts(DataOutputStream out,int[] values)throws IOException{out.writeBoolean(values!=null);if(values==null)return;out.writeInt(values.length);for(int v:values)out.writeInt(v);}

    protected void items(DataOutputStream out,Knight knight,WorldProfile profile)throws IOException {
        type(out,WireTypes.DSET);out.writeInt(profile.items().size());
        for(var item:profile.items())item(out,knight,profile.worldId(),item);
    }

    protected void item(DataOutputStream out,Knight knight,int worldId,WorldProfile.Item item)throws IOException {
            item(out,knight==null?null:knight.costume(),worldId,item);
    }

    protected void item(DataOutputStream out,String costume,int worldId,WorldProfile.Item item)throws IOException {
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
            out.writeLong(item.id());out.writeInt(worldId);out.writeInt(definition==null?0:definition.stockId());out.writeInt(item.count());value(out,item.expiresAt());
            // Stock Item.writeObject: bit 2 is the mutable binding flag (aTY),
            // tested by Cs(). Stock cv(true) sets both permanent and binding flags.
            out.writeByte((item.bound() || definition!=null&&definition.locked() ? 2 : 0) | (item.permanent() ? 1 : 0) | (item.cursed()?4:0));
    }

    protected StarterCatalog.Model giftReference(WorldProfile.Item item){
        var args=new java.util.TreeMap<String,Object>();
        if(item.slot()!=WorldProfile.Slot.OTHER){args.put("_setLevel",item.level());args.put("_setHeat",item.heatProgress());}
        if(!item.variants().isEmpty())args.put("_setVariants",new FireCatalog.ListValue(WireTypes.CONFIG_REFERENCE,new java.util.ArrayList<>(item.variants())));
        var accessories=item.nativeAccessories("");if(!accessories.isEmpty())args.put("_setAccessories",new FireCatalog.ListValue(WireTypes.CONFIG_REFERENCE,accessories.stream().map(a->(Object)new StarterCatalog.Model(a,Map.of())).toList()));
        if(item.bound())args.put("_createLocked",true);
        return new StarterCatalog.Model(item.config(),args);
    }

    protected void currentLoadout(DataOutputStream out,WorldProfile profile) throws IOException {
        type(out,WireTypes.DSET);out.writeInt(1+profile.town().loadouts().size());
        loadout(out,profile);
        for(var saved:profile.town().loadouts().stream().sorted(java.util.Comparator.comparing(l->l.name().toLowerCase(java.util.Locale.ROOT))).toList())loadout(out,saved,profile);
    }

    protected void loadoutUpdated(DataOutputStream out,int oid,WorldProfile profile)throws IOException {
        type(out,WireTypes.ENTRY_UPDATED_EVENT);out.writeInt(oid);string(out,"loadouts");
        loadout(out,profile);
    }

    protected void loadout(DataOutputStream out,org.tortodrone.core.town.TownState.Loadout saved,WorldProfile profile)throws IOException {
        loadout(out,saved.name(),saved.equipment().stream().mapToLong(Long::longValue).toArray(),saved.sprite(),saved.harness(),saved.perk(),saved.pvp(),loadoutRarity(profile,saved.equipment().stream().mapToLong(Long::longValue).toArray()));
    }

    protected void loadout(DataOutputStream out,WorldProfile profile)throws IOException {
        var sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);
        loadout(out,"",profile.equipment(),profile.town().equippedSprite(),sprite==null?0:sprite.harness(),sprite==null?null:sprite.perk(),false,loadoutRarity(profile,profile.equipment()));
    }

    protected int loadoutRarity(WorldProfile profile,long[] equipment){
        int rarity=0;for(long id:equipment)if(id!=0)for(var item:profile.items())if(item.id()==id){var definition=itemCatalog.find(item.config());if(definition!=null)rarity=Math.max(rarity,definition.rarity());}
        return rarity;
    }

    protected void loadout(DataOutputStream out,String name,long[] equipment,int sprite,long harness,Integer perk,boolean pvp,int rarity)throws IOException {
        type(out,"com.threerings.projectx.data.Loadout");
        int[] slots={0,1,2,3,4,15,6,7,8,9,10,11};
        out.writeBoolean(true);out.writeInt(slots.length);for(int slot:slots)out.writeLong(equipment[slot]);
        out.writeBoolean(pvp);out.writeInt(rarity);out.writeInt(sprite);out.writeLong(harness);
        value(out,perk==null?null:new org.tortodrone.core.wire.EnumValue(WireTypes.SPRITE_PERK,perk));string(out,name);
    }

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

    protected void occupant(DataOutputStream out,ActorState actor)throws IOException {
        type(out,"com.threerings.projectx.data.ProjectXOccupantInfo");out.writeBoolean(true);out.writeInt(actor.clientOid());name(out,actor.name());out.writeByte(0);out.writeInt(actor.id());
        var progress=actor.sceneKnight()==null?org.tortodrone.core.character.KnightProgress.RECRUIT:actor.sceneKnight().progress();
        type(out,"com.threerings.projectx.data.PlayerObject$Type");out.writeByte(actor.gameMaster()?2:0);out.writeInt(actor.color());out.writeShort(0);type(out,"com.threerings.projectx.data.ProjectXCodes$PrestigeRank");out.writeByte(progress.prestigeRank());out.writeInt(progress.rank());
    }

    protected void actorSprite(DataOutputStream out,ActorState actor)throws IOException{
        var state=actor.sceneKnight();if(state==null||state.sprite()==null)out.writeShort(0);else sprite(out,actor.id(),state.sprite(),state.harness());
    }

    protected Object activityData(ActorState actor,int observer) {
        // Appearance references are shared animation data. Inventory IDs and the reward
        // callback still belong exclusively to the authenticated opening connection.
        return actor.sceneKnight()==null?null:actor.sceneKnight().activityData();
    }

    protected void actor(DataOutputStream out,ActorState a,boolean interior,int observer)throws IOException {
        if(a.pet()!=null){petActor(out,a);return;}
        if(a.projectile()!=null){projectile(out,a);return;}
        if(a.entity()!=null){sceneEntity(out,a);return;}
        type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(MovementRules.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());
        value(out,activityData(a,observer));interaction(out,a.interaction());string(out,null);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());actorSprite(out,a);
        if(interior) {
            var combat=a.combat();
            weapon(out,a.id(),combat);string(out,combat==null?null:combat.shield());string(out,combat==null?null:combat.shieldCostume());conditions(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
            out.writeInt(0);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().hitTime());
            out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
            out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteActor());out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteAttack());out.writeInt(combat==null?0:combat.nextDodge());
            statusModifiers(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }

    protected void actorDelta(DataOutputStream out,ActorState a,ActorState baseline,boolean interior,int observer)throws IOException {
        // ActorDelta is final: its typed array omits a per-element type header.
        if(a.pet()!=null){petDelta(out,a);return;}
        if(a.projectile()!=null){projectileDelta(out,a);return;}
        if(a.entity()!=null){sceneEntityDelta(out,a);return;}
        out.writeInt(a.id());type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");
        boolean configChanged = baseline == null || !a.config().equals(baseline.config());
        // Preserve the reference's config object on ordinary movement; update it for actual gear changes.
        // The stock controller predicts directly on its first snapshot. Inventory
        // corrections below must not leak local prediction into later baselines.
        boolean combatChanged=interior&&(a.combat()!=null||baseline!=null&&baseline.combat()!=null);
        boolean sceneData=a.sceneKnight()!=null||baseline!=null&&baseline.sceneKnight()!=null;
        boolean spriteChanged=baseline==null||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().sprite(),baseline.sceneKnight()==null?null:baseline.sceneKnight().sprite())||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().harness(),baseline.sceneKnight()==null?null:baseline.sceneKnight().harness());
        // The stock advancer owns an emote's clock after its initial publication.
        // Passive movement/status updates must not reapply its activity to a locally
        // advanced baseline. Real activity transitions still replace both fields.
        boolean stableEmote=baseline!=null&&a.activity()>=PlayerEmotes.FIRST_ACTIVITY
            &&a.activity()<PlayerEmotes.FIRST_ACTIVITY+PlayerEmotes.ANIMATED_COUNT
            &&a.activity()==baseline.activity()&&a.activityStarted()==baseline.activityStarted();
        int second=a.interaction()!=null||baseline!=null&&baseline.interaction()!=null?0x1d:1;
        out.writeByte((configChanged ? 0xdd : 0xdc)&(stableEmote?0x7f:0xff));out.writeByte((second|(sceneData?0x42:0)|(spriteChanged?0x80:0))&(stableEmote?0xfe:0xff));
        // Mutable Dungeoneer fields: weapon 16, shields 17/18, health 22/23,
        // impulse 24, hit time 26, chain 27/28, dodge deadline 31 and modifiers 33/34. Id/created are DeltaFinal.
        if(interior){out.writeByte((combatChanged?0xf7:0)|(sceneData?8:0));out.writeByte((combatChanged?0x99:0)|(sceneData?0x64:0));out.writeByte((combatChanged?6:0)|(sceneData?1:0));}
        if (configChanged) value(out,a.config());
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());
        if(!stableEmote){out.writeInt(a.activity());out.writeInt(a.activityStarted());}
        if(sceneData)value(out,activityData(a,observer));
        if(a.interaction()!=null||baseline!=null&&baseline.interaction()!=null)interaction(out,a.interaction());
        if(sceneData)out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());
        if(spriteChanged)actorSprite(out,a);
        if(combatChanged) {
            var combat=a.combat();weapon(out,a.id(),combat);value(out,combat==null?null:combat.shield());value(out,combat==null?null:combat.shieldCostume());
        }
        if(interior&&sceneData)conditions(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
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
        if(interior&&sceneData)statusModifiers(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
        if(combatChanged) {
            var combat=a.combat();
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }

    protected void equipmentModifiers(DataOutputStream out,org.tortodrone.core.world.EquipmentCatalog.Modifiers modifiers)throws IOException {
        var value=modifiers==null?org.tortodrone.core.world.EquipmentCatalog.Modifiers.ZERO:modifiers;
        type(out,"com.threerings.projectx.item.data.ImmutableEquipmentModifiers");
        out.writeFloat(value.chargeTime());out.writeFloat(value.speed());out.writeFloat(value.attackSpeed());out.writeFloat(value.defendingSpeed());
    }

    protected void interaction(DataOutputStream out,org.tortodrone.core.world.InteractionState interaction)throws IOException {
        if(interaction==null){out.writeShort(0);out.writeShort(0);out.writeShort(0);return;}
        if(!java.util.Set.of("Interact","Talk","Shop","Lift","Resurrect","UnlockGold").contains(interaction.action()))throw new IOException("Unsupported interaction action");
        type(out,"com.threerings.projectx.config.ActionDesc$"+interaction.action());
        if(interaction.entryKey()!=null){type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,Integer.parseInt(interaction.entryKey()));}
        else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(interaction.actorId());}
        value(out,interaction.description());
    }

    protected void petActor(DataOutputStream out,ActorState a)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(MovementRules.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);
        petFields(out,a,false);
    }

    protected void petFields(DataOutputStream out,ActorState a,boolean delta)throws IOException {
        var pet=a.pet();sprite(out,pet.knight(),pet.sprite(),pet.harness());out.writeInt(pet.knight());out.writeBoolean(pet.follow());if(delta){type(out,"[I");out.writeInt(pet.nextSkill().size());for(int next:pet.nextSkill())out.writeInt(next);}else primitiveInts(out,pet.nextSkill().stream().mapToInt(Integer::intValue).toArray());
        out.writeInt(pet.shieldHealth());out.writeInt(pet.shieldMaximum());if(delta)value(out,pet.shieldPower());else string(out,pet.shieldPower());
    }

    protected void petDelta(DataOutputStream out,ActorState a)throws IOException {
        out.writeInt(a.id());type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");
        // Actor/Active/Agent mutable field order, followed by the seven SpriteActor fields.
        int mask=0x1ffdc;out.writeByte(mask);out.writeByte(mask>>>8);out.writeByte(mask>>>16);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);petFields(out,a,true);
    }

    protected void projectile(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        type(out,p.nativeType().equals("ACTOR")?WireTypes.ACTOR:"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
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

    protected void sceneEntity(DataOutputStream out,ActorState a)throws IOException {
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
            case "TerritoryProp" -> out.writeByte(e.state());
            case "Door" -> out.writeInt(e.stateEntered());
            case "Switch","EntryState" -> {value(out,sceneEntryKey(e.key()));out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());primitiveInts(out,e.lift()==null?null:e.lift().excluded());}
            case "Lift" -> {out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());}
            case "Pickup" -> {primitiveInts(out,null);primitiveInts(out,e.pickup()==null?null:new int[]{e.pickup().playerOid(),e.pickup().activated()});}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Bomb" -> {out.writeInt(0);out.writeInt(0);out.writeFloat(org.tortodrone.core.world.ProjectileState.UNSPECIFIED_Z);}
            case "Actor" -> { }
            default -> throw new IOException("Unsupported prepared actor type");
        }
    }

    protected Object sceneEntryKey(String key)throws IOException {
        if(key.matches("spawn:[0-9]+"))return null; // Native dynamically spawned switches have no scene-entry key.
        try{return Integer.valueOf(key.split("/",2)[0]);}catch(NumberFormatException invalid){throw new IOException("Unsupported switch entry key: "+key,invalid);}
    }

    protected void sceneEntityDelta(DataOutputStream out,ActorState a)throws IOException {
        var e=a.entity();out.writeInt(a.id());type(out,e.nativeClass());
        // DeltaFinal id/created are absent from the stock mutable-field layout.
        int mask=0x1e;
        if(e.active()){
            mask|=(1<<6)|(1<<7)|(1<<8)|(1<<9);
            if(e.kind().equals("Monster"))mask|=(1<<11)|(1<<12)|(1<<13)|(1<<16)|(1<<18); // affected type/time, conditions, health and status
            out.writeByte(mask);out.writeByte(mask>>>8);
            if(e.kind().equals("Monster"))out.writeByte(mask>>>16);
        }else{
            mask|=(e.kind().equals("Switch")||e.kind().equals("EntryState"))?0xc0:(e.kind().equals("Actor")||e.kind().equals("Bomb"))?0:e.kind().equals("DungeonLift")?0x7c0:e.kind().equals("Lift")?0x1c0:e.kind().equals("Block$Stateful")?0xe0:0x20;
            if(e.kind().equals("Trap"))mask|=0x40;
            if(e.kind().equals("Pickup")&&e.pickup()!=null)mask|=0x40;
            out.writeByte(mask);if(e.kind().equals("DungeonLift")||e.kind().equals("Lift"))out.writeByte(mask>>>8);
        }
        out.writeInt(e.destroyed());vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());
        if(e.active()){
            out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(e.speed());
            if(e.kind().equals("Monster")){out.writeInt(e.affectedType());out.writeInt(e.affectedTime());conditions(out,e.status());out.writeFloat(e.health());statusModifiers(out,e.status().modifiers());}
        }else switch(e.kind()){
            case "Generator" -> out.writeInt(e.count());case "Door" -> out.writeInt(e.stateEntered());
            case "TerritoryProp" -> out.writeByte(e.state());
            case "Switch","EntryState" -> {out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());case "Pickup" -> {out.writeShort(0);if(e.pickup()!=null){type(out,"[I");out.writeInt(2);out.writeInt(e.pickup().playerOid());out.writeInt(e.pickup().activated());}}
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());
                int[] ids=e.lift()==null?null:e.lift().excluded();if(ids==null)out.writeShort(0);else{type(out,"[I");out.writeInt(ids.length);for(int id:ids)out.writeInt(id);}}
            case "Lift" -> {out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Actor","Bomb" -> { }
            default -> throw new IOException("Unsupported prepared actor delta");
        }
    }

    protected void dropPoint(DataOutputStream out,org.tortodrone.core.world.SceneLiftState lift)throws IOException {
        if(lift==null||lift.dropPoint()==null)out.writeShort(0);else vector(out,lift.dropPoint().x(),lift.dropPoint().y());
    }

    protected void neutralStatus(DataOutputStream out)throws IOException {
        statusModifiers(out,org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL);
    }

    protected void conditions(DataOutputStream out,org.tortodrone.core.world.SceneStatus.State status)throws IOException {
        if(status.conditions().isEmpty()){out.writeShort(0);return;}
        type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(status.conditions().size());for(var condition:status.conditions())value(out,condition);
    }

    protected void statusModifiers(DataOutputStream out,org.tortodrone.core.world.SceneStatus.Modifiers status)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.ImmutableStatusModifiers");
        out.writeFloat(status.speed());out.writeFloat(status.attackSpeed());out.writeFloat(status.attackDamage());out.writeFloat(status.relativeDamage());
        out.writeFloat(status.defense());out.writeFloat(status.heal());out.writeFloat(1);out.writeFloat(1);out.writeFloat(0);
        out.writeBoolean(status.canMove());out.writeBoolean(status.canRotate());out.writeBoolean(status.canDefend());out.writeBoolean(status.canAttack());
        out.writeBoolean(status.asleep());out.writeBoolean(status.shocked());out.writeBoolean(status.canBeTargeted());out.writeInt(0);
    }

    protected void effect(DataOutputStream out,SceneCombatEvent damage)throws IOException {
        int ordinal=DAMAGE_PERFORMANCE.indexOf(damage.performance());
        if(ordinal<0)throw new IOException("Unsupported damage performance: "+damage.performance());
        type(out,"com.threerings.projectx.dungeon.data.effect.DamageEffect");
        value(out,new StarterCatalog.Model("Feedback/Damage",Map.of()));
        out.writeInt(damage.timestamp());
        type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(damage.actor());
        vector(out,damage.x(),damage.y());out.writeFloat(damage.rotation());
        out.writeInt(0); // Client oid: a shared effect, never a private prefire.
        out.writeBoolean(false);out.writeInt(damage.amount());out.writeInt(damage.source());
        value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.dungeon.util.DamageSummary$Performance",ordinal));
    }

    protected void projectileDelta(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        out.writeInt(actor.id());type(out,p.nativeType().equals("ACTOR")?WireTypes.ACTOR:"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
        // Actor: destroyed, translation, rotation, flags. Origin, id and created are final.
        out.writeByte(p.nativeType().equals("LASER")?0x7e:0x1e);if(p.orbit()!=null)out.writeByte(3);else if(bullet)out.writeByte(0);
        out.writeInt(p.destroyed());vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(p.orbit()!=null){vector(out,p.orbit().relativeX(),p.orbit().relativeY());out.writeInt(p.orbit().collided());}
        if(p.nativeType().equals("LASER")){out.writeBoolean(!p.fizzle());vector(out,actor.x()+(float)Math.cos(actor.rotation())*p.speed(),actor.y()+(float)Math.sin(actor.rotation())*p.speed());}
    }

    protected void effect(DataOutputStream out,SceneEffect effect)throws IOException {
        type(out,"com.threerings.tudey.data.effect.Effect");value(out,effect.config());out.writeInt(effect.timestamp());
        if(effect.targetEntry()!=null){type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,effect.targetEntry());}
        else if(effect.targetActor()==0)out.writeShort(0);else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(effect.targetActor());}
        vector(out,effect.x(),effect.y());out.writeFloat(effect.rotation());out.writeInt(effect.ownerOid());
    }

    protected void weapon(DataOutputStream out,int bag,KnightCombat.State combat)throws IOException {
        if(combat==null||combat.weapon()==null)out.writeShort(0);else item(out,(String)null,bag,combat.weapon());
    }

    protected void impulse(DataOutputStream out,KnightCombat.Impulse impulse)throws IOException {
        if(impulse==null){out.writeShort(0);return;}
        type(out,"com.threerings.projectx.dungeon.data.actor.Impulse");out.writeInt(impulse.started());out.writeInt(impulse.duration());vector(out,impulse.x(),impulse.y());
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
