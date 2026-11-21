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

/** Knight-owned downstream frames; shares the connection symbol tables. */
public final class KnightCodec extends WireWriter {
    public KnightCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] rosterChanged(int clientOid, List<org.tortodrone.core.character.RosterEntry> knights) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.AttributeChangedEvent"); out.writeInt(clientOid);
            string(out, "availableKnights"); roster(out, knights);
        });
    }

    public byte[] inspected(int oid,short request,Knight knight,WorldProfile profile,boolean hidden)throws IOException {
        return frame(out->{eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(1);
            out.writeBoolean(true);out.writeInt(1);type(out,"com.threerings.projectx.data.PlayerEquipment");
            name(out,knight.name());out.writeInt(256|knight.color());
            type(out,WireTypes.EYE_TYPE);out.writeByte(knight.eye());
            type(out,WireTypes.KNIGHT_HEIGHT);out.writeByte(knight.height());
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

    public byte[] selected(int oid, Knight knight, WorldProfile profile) throws IOException {
        return selected(oid,knight,profile,null);
    }

    public byte[] selected(int oid, Knight knight, WorldProfile profile,
            org.tortodrone.core.guild.Guild guild) throws IOException {
        return frame(out -> {
            eventHeader(out,"CompoundEvent",oid);
            type(out,WireTypes.ARRAY_LIST); out.writeInt(23+(guild==null?0:3));
            attribute(out,oid,"knight",o -> name(o,knight.name()));
            attribute(out,oid,"color",o -> value(o,256 | knight.color()));
            attribute(out,oid,"eye",o -> { type(o,WireTypes.EYE_TYPE);o.writeByte(knight.eye()); });
            attribute(out,oid,"height",o -> { type(o,WireTypes.KNIGHT_HEIGHT);o.writeByte(knight.height()); });
            attribute(out,oid,"bagId",o -> value(o,profile.worldId()));
            attribute(out,oid,"items",o -> items(o,knight,profile));
            attribute(out,oid,"equipment",o -> longs(o,profile.equipment()));
            attribute(out,oid,"loadouts",o -> currentLoadout(o,profile));
            attribute(out,oid,"quickbar",o -> longs(o,new long[4]));
            attribute(out,oid,"weaponSlots",o -> value(o,SlotUpgrades.weapons(profile)));
            attribute(out,oid,"trinketSlots",o -> value(o,SlotUpgrades.trinkets(profile)));
            attribute(out,oid,"heatBonus",o -> value(o,SlotUpgrades.heatBonusActive(profile)));
            attribute(out,oid,"pvpTokenBonus",o -> value(o,SlotUpgrades.pvpTokenBonus(profile)));
            attribute(out,oid,"crowns",o -> value(o,profile.crowns()));
            attribute(out,oid,"energy",o -> value(o,profile.energy()));
            attribute(out,oid,"showEnergy",o -> value(o,true));
            attribute(out,oid,"homeTown",o->homeTown(o,profile));
            rank(out,oid,profile);
            attribute(out,oid,"prestige",o->value(o,profile.progress().prestige()));
            attribute(out,oid,"progressFlags",o->value(o,profile.progress().progressFlags()));
            attribute(out,oid,"stats",o->{type(o,"com.threerings.stats.data.StatSet");o.writeInt(0);});
            attribute(out,oid,"pawnId",o -> value(o,profile.worldId()));
            attribute(out,oid,"knightId",o -> value(o,profile.worldId()));
            if(guild!=null){
                attribute(out,oid,"guild",o -> name(o,guild.name()));
                attribute(out,oid,"guildId",o -> value(o,(int)guild.id()));
                attribute(out,oid,"guildOid",o -> value(o,guildOid(guild.id())));
            }
        });
    }

    public byte[] knightMemories(int oid,java.util.Set<String> names)throws IOException {
        var sorted=names.stream().sorted().toList();if(sorted.size()>256)throw new IOException("Too many knight memories");
        for(String name:sorted)new org.tortodrone.core.character.KnightMemory(name,true);
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"memories");
            type(out,WireTypes.DSET);out.writeInt(sorted.size());
            for(String name:sorted)knightMemory(out,name);
        });
    }

    private void knightMemory(DataOutputStream out,String name)throws IOException {
        type(out,"com.threerings.projectx.data.Memory");type(out,"com.threerings.projectx.config.Persistence");out.writeByte(3);string(out,name);
    }

    public byte[] knightObjectives(int oid,java.util.Map<String,org.tortodrone.core.character.KnightObjective.State> objectives)throws IOException {
        return objectives(oid,objectives.values().stream()
            .map(value->new org.tortodrone.core.mission.ObjectiveEntry(value.index(),value.name(),value.text(),org.tortodrone.core.mission.ObjectiveEntry.KNIGHT))
            .toList());
    }

    /** The complete native objectives set: knight-persistent entries plus the active mission bar. */
    public byte[] objectives(int oid,java.util.List<org.tortodrone.core.mission.ObjectiveEntry> objectives)throws IOException {
        if(objectives.size()>256)throw new IOException("Too many objectives");
        var ordered=objectives.stream().sorted(java.util.Comparator.comparingInt(org.tortodrone.core.mission.ObjectiveEntry::index)).toList();
        for(int i=1;i<ordered.size();i++)if(ordered.get(i-1).index()==ordered.get(i).index())throw new IOException("Duplicate objective index");
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"objectives");type(out,WireTypes.DSET);out.writeInt(ordered.size());
            for(var objective:ordered){
                type(out,"com.threerings.projectx.data.Objective");
                type(out,"com.threerings.projectx.config.ObjectiveConfig$Simple");
                string(out,objective.text());for(int i=0;i<4;i++)out.writeFloat(.5f);value(out,null);
                type(out,"com.threerings.projectx.config.Persistence");out.writeByte(objective.persistence());out.writeBoolean(true);out.writeInt(objective.index());string(out,objective.name());
            }
        });
    }

    public byte[] knightMemoryChanges(int oid,java.util.Set<String> before,java.util.Set<String> after)throws IOException {
        var removed=before.stream().filter(n->!after.contains(n)).sorted().toList();var added=after.stream().filter(n->!before.contains(n)).sorted().toList();
        if(before.size()>256||after.size()>256)throw new IOException("Too many knight memories");
        for(String name:before)new org.tortodrone.core.character.KnightMemory(name,true);
        for(String name:after)new org.tortodrone.core.character.KnightMemory(name,true);
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(removed.size()+added.size());
            for(String name:removed){partyEntry(out,oid,"memories","EntryRemovedEvent");value(out,name);}
            for(String name:added){partyEntry(out,oid,"memories","EntryAddedEvent");knightMemory(out,name);}
        });
    }

    public byte[] knightName(int oid,String knight)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"knight");name(out,knight);});
    }

    public byte[] equipPolicy(int oid,boolean station)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"equipPolicy");value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.data.PlayerObject$EquipPolicy",station?2:0));});
    }

    public byte[] controllerAction(int oid,String command,ActorState source)throws IOException {
        if(!java.util.Set.of("forge","show_arsenal_station","auction","new_mission").contains(command))throw new IOException("Unsupported controller action");
        return frame(out->{eventHeader(out,"MessageEvent",oid);string(out,"forceClientAction");
            out.writeBoolean(true);out.writeInt(2); // MessageEvent's declared Object[] field.
            type(out,"com.threerings.tudey.config.ClientActionConfig$ControllerAction");string(out,command);string(out,"");
            sourceKey(out,source);
        });
    }


    public byte[] balances(int oid,WorldProfile profile) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,oid,"crowns",o->value(o,profile.crowns()));attribute(out,oid,"energy",o->value(o,profile.energy())); });
    }

    public byte[] knightProgress(int oid,WorldProfile profile)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(3);rank(out,oid,profile);attribute(out,oid,"prestige",o->value(o,profile.progress().prestige()));attribute(out,oid,"progressFlags",o->value(o,profile.progress().progressFlags()));});
    }

    public byte[] itemChanges(int oid,Knight knight,WorldProfile before,WorldProfile after)throws IOException {
        var old=new HashMap<Long,WorldProfile.Item>();for(var item:before.items())old.put(item.id(),item);
        var changed=after.items().stream().filter(i->!i.equals(old.get(i.id()))).toList();
        var current=after.items().stream().map(WorldProfile.Item::id).collect(java.util.stream.Collectors.toSet());
        var removed=before.items().stream().filter(i->!current.contains(i.id())).toList();
        long[] oldEquipment=before.equipment(),equipment=after.equipment();
        var slots=new ArrayList<Integer>();
        for(int slot=0;slot<equipment.length;slot++)if(oldEquipment[slot]!=equipment[slot])slots.add(slot);
        return frame(out->{
            eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(changed.size()+removed.size()+slots.size()+(slots.isEmpty()?0:1)+4);
            attribute(out,oid,"weaponSlots",o->value(o,SlotUpgrades.weapons(after)));
            attribute(out,oid,"trinketSlots",o->value(o,SlotUpgrades.trinkets(after)));
            attribute(out,oid,"heatBonus",o->value(o,SlotUpgrades.heatBonusActive(after)));
            attribute(out,oid,"pvpTokenBonus",o->value(o,SlotUpgrades.pvpTokenBonus(after)));
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
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(4);
            for(int slot=0;slot<4;slot++){
                type(out,"com.threerings.presents.dobj.ElementUpdatedEvent");out.writeInt(oid);string(out,"quickbar");
                value(out,slots[slot]);out.writeInt(slot);
            }
        });
    }

    public byte[] styles(int oid,Knight knight,org.tortodrone.core.style.Saved saved)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(6);
            attribute(out,oid,"color",o->value(o,256|knight.color()));
            attribute(out,oid,"eye",o->value(o,new org.tortodrone.core.wire.EnumValue(WireTypes.EYE_TYPE,knight.eye())));
            attribute(out,oid,"height",o->value(o,new org.tortodrone.core.wire.EnumValue(WireTypes.KNIGHT_HEIGHT,knight.height())));
            attribute(out,oid,"savedColors",o->{type(o,"[I");o.writeInt(saved.colors().size());for(int color:saved.colors())o.writeInt(color);});
            attribute(out,oid,"savedEyes",o->styleArray(o,"EyeType",saved.eyes()));
            attribute(out,oid,"savedHeights",o->styleArray(o,"KnightHeight",saved.heights()));
        });
    }

    private void styleArray(DataOutputStream out,String kind,List<Integer> values)throws IOException {
        type(out,"[Lcom.threerings.projectx.data.ProjectXCodes$"+kind+";");out.writeInt(values.size());
        out.writeShort((values.size()+7)/8);for(int i=0;i<values.size();i+=8)out.writeByte((1<<Math.min(8,values.size()-i))-1);
        for(int value:values)out.writeByte(value);
    }

    public byte[] loadouts(int oid,WorldProfile profile)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"loadouts");currentLoadout(out,profile);});
    }

    public byte[] loadoutChanges(int oid,WorldProfile before,WorldProfile after)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);
            var old=new java.util.LinkedHashMap<String,org.tortodrone.core.town.TownState.Loadout>();
            var current=new java.util.LinkedHashMap<String,org.tortodrone.core.town.TownState.Loadout>();
            for(var l:before.town().loadouts())old.put(l.name(),l);for(var l:after.town().loadouts())current.put(l.name(),l);
            int count=1+(int)old.keySet().stream().filter(k->!current.containsKey(k)).count()
                +(int)current.values().stream().filter(l->!l.equals(old.get(l.name()))).count();
            out.writeInt(count);
            loadoutUpdated(out,oid,after);
            for(String key:old.keySet())if(!current.containsKey(key))entryRemoved(out,oid,"loadouts",key.toLowerCase(java.util.Locale.ROOT));
            for(var l:current.values())if(!l.equals(old.get(l.name()))){event(out,old.containsKey(l.name())?"EntryUpdatedEvent":"EntryAddedEvent",oid);string(out,"loadouts");loadout(out,l,after);}
        });
    }

}
