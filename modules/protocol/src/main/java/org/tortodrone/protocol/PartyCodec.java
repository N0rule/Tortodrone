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

/** Party-owned downstream frames; shares the connection symbol tables. */
public final class PartyCodec extends WireWriter {
    public PartyCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] sceneParty(int oid,SceneInstanceView scene)throws IOException {
        return partyOid(oid,scene.map().town()?0:interiorPartyOid(scene));
    }

    public byte[] partyOid(int player,int party)throws IOException{return frame(out->{eventHeader(out,"AttributeChangedEvent",player);string(out,"partyOid");value(out,party);});}

    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene)throws IOException {
        return interiorParty(message,partyOid,playerOid,knight,profile,scene,new org.tortodrone.core.world.SceneHealth.State(1,1,0));
    }

    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene,org.tortodrone.core.world.SceneHealth.State health)throws IOException {
        return interiorParty(message,partyOid,playerOid,knight,profile,scene,health,org.tortodrone.core.world.SceneStatus.State.NORMAL);
    }

    public byte[] interiorParty(short message,int partyOid,int playerOid,Knight knight,WorldProfile profile,SceneInstanceView scene,org.tortodrone.core.world.SceneHealth.State health,org.tortodrone.core.world.SceneStatus.State status)throws IOException {
        return party(message,partyOid,List.of(new PartyView.Participant(playerOid,profile.worldId(),0,knight,profile,health,status)),scene,false);
    }

    public byte[] party(short message,PartyView.View view,SceneInstanceView scene)throws IOException{return party(message,view,scene,false);}

    public byte[] party(short message,PartyView.View view,SceneInstanceView scene,boolean reporting)throws IOException{return party(message,view.oid(),view.members(),scene,!view.automatic(),view.automatic(),view.inviteOnly(),view.allowFriends(),view.allowGuild(),reporting);}

    private byte[] party(short message,int partyOid,List<PartyView.Participant> members,SceneInstanceView scene,boolean managed)throws IOException {
        return party(message,partyOid,members,scene,managed,false,false,false,false,false);
    }

    private byte[] party(short message,int partyOid,List<PartyView.Participant> members,SceneInstanceView scene,boolean managed,boolean automatic,boolean inviteOnly,boolean allowFriends,boolean allowGuild,boolean reporting)throws IOException {
        return frame(out->{
            boolean training=scene.map().kind().equals("training");
            boolean tutorial=scene.hasTutorial();
            boolean mission=scene.hasMission();
            objectHeader(out,message,"com.threerings.projectx.dungeon.data."+(mission?"MissionPartyObject":"LevelPartyObject"),partyOid);
            type(out,"com.threerings.projectx.dungeon.data.PartyMarshaller");out.writeInt(partyOid);out.writeInt(PARTY_INVOCATION);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(partyOid);out.writeInt(org.tortodrone.core.world.SceneChat.PARTY_SERVICE);
            type(out,WireTypes.DSET);out.writeInt(members.size());for(var member:members.stream().sorted(java.util.Comparator.comparingInt(PartyView.Participant::playerOid)).toList())partyMember(out,member);
            type(out,WireTypes.DSET);out.writeInt(members.size());for(var member:members.stream().sorted(java.util.Comparator.comparingInt(PartyView.Participant::playerOid)).toList())partyHealth(out,member);
            type(out,WireTypes.DSET);out.writeInt(members.size());for(var member:members.stream().sorted(java.util.Comparator.comparingInt(PartyView.Participant::playerOid)).toList())partyStatus(out,member);
            emptyCollection(out);out.writeShort(0); // Native party spawn is unset; the scene owns arrival.
            out.writeInt(mission?1:0);out.writeInt(scene.map().zone());out.writeInt(scene.map().town()?scene.oid():0); // depth, zone, instance
            // hasPickups enables the native quickbar/level HUD. Only a scene's own
            // vial/capsule catalogue counts; the global owned-item uses are not pickups.
            boolean pickups=scene.map().content().items().values().stream()
                .anyMatch(item->item.text("kind","").equals("vial")||item.text("kind","").equals("capsule"));
            out.writeBoolean(!tutorial&&(automatic||managed||training||pickups));out.writeLong(0);
            out.writeShort(0);out.writeInt(tutorial||mission?1:managed||automatic?PartyView.CAPACITY:training?8:1);out.writeInt(mission?scene.missionGateId():tutorial?org.tortodrone.core.mission.TutorialRoute.GATE:0);
            type(out,"com.threerings.projectx.dungeon.data.LevelPartyMarshaller");out.writeInt(partyOid);out.writeInt(LEVEL_PARTY_SERVICE);
            type(out,WireTypes.DIFFICULTY);out.writeByte(mission?scene.missionDifficulty():tutorial?1:0);
            emptyDSet(out);emptyCollection(out);out.writeInt(mission?1:0);
            type(out,"com.threerings.projectx.dungeon.data.DungeonCodes$LevelType");out.writeByte(scene.map().kind().equals("level")?0:3);
            primitiveInts(out,mission?new int[]{0,0}:new int[]{0});out.writeBoolean(tutorial||mission||inviteOnly);out.writeBoolean(!tutorial&&!mission&&allowFriends);out.writeBoolean(!tutorial&&!mission&&allowGuild);out.writeBoolean(mission||!tutorial&&!managed);
            type(out,WireTypes.DSET);out.writeInt(members.size());for(var member:members.stream().sorted(java.util.Comparator.comparingInt(PartyView.Participant::playerOid)).toList())partyHeat(out,member);
            string(out,null);out.writeInt(reporting?-1:0);
            type(out,WireTypes.FLOOR_TYPE);out.writeByte(mission?0:tutorial?1:4);
            type(out,WireTypes.FLOOR_TYPE);out.writeByte(reporting?0:4);
            out.writeBoolean(mission&&reporting);out.writeBoolean(false);out.writeBoolean(mission&&reporting);emptyDSet(out);
            // The native elevator and floor HUD index the gate's wheels by actualFloor and look
            // up actualGateId in the served gate set; both stay zero outside a mission run.
            out.writeInt(mission?scene.missionGateId():0);out.writeInt(mission?scene.missionFloor():0);
            out.writeInt(0);out.writeInt(0);out.writeLong(0);emptyCollection(out);string(out,null);
            out.writeBoolean(true);out.writeInt(0); // Empty typed Multiset<Integer>.
            if(mission)string(out,scene.missionId());
        });
    }

    public byte[] finalFloorReport(int party)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",party);type(out,WireTypes.ARRAY_LIST);out.writeInt(4);
            attribute(out,party,"pendingFloorType",o->{type(o,WireTypes.FLOOR_TYPE);o.writeByte(0);});
            attribute(out,party,"pendingFloorDepth",o->value(o,-1));
            attribute(out,party,"pendingFloorName",o->value(o,null));
            attribute(out,party,"returnFloor",o->value(o,0));
        });
    }

    public byte[] openingFloorReport(int party)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",party);type(out,WireTypes.ARRAY_LIST);out.writeInt(6);
            attribute(out,party,"playFanfare",o->value(o,true));attribute(out,party,"showLoot",o->value(o,true));
            attribute(out,party,"pendingFloorType",o->{type(o,WireTypes.FLOOR_TYPE);o.writeByte(0);});
            attribute(out,party,"pendingFloorDepth",o->value(o,-1));attribute(out,party,"pendingFloorName",o->value(o,null));attribute(out,party,"returnFloor",o->value(o,0));
        });
    }

    private void partyMember(DataOutputStream out,PartyView.Participant member)throws IOException {
        var knight=member.knight();type(out,"com.threerings.projectx.dungeon.data.PartyMember");out.writeInt(member.playerOid());out.writeInt(member.order());
        var portrait=StarterCatalog.portrait(knight,member.profile(),itemCatalog);
        name(out,knight.name());out.writeInt(256|knight.color());value(out,portrait.arguments().get("Helm"));
        value(out,portrait);type(out,"[Lcom.threerings.config.ConfigReference;");out.writeInt(4);
        for(String weapon:partyWeapons(member.profile()))value(out,weapon==null?null:new StarterCatalog.Model(weapon,Map.of()));
        out.writeLong(0);
    }

    private String[] partyWeapons(WorldProfile profile){
        var result=new String[4];int[] slots={6,7,10,11};
        for(var item:profile.items())if(item.equippedSlot()!=null)for(int i=0;i<slots.length;i++)if(item.equippedSlot()==slots[i])result[i]=item.config();
        return result;
    }

    private boolean partyAppearanceChanged(PartyView.Participant before,PartyView.Participant after){
        return !StarterCatalog.portrait(before.knight(),before.profile(),itemCatalog).equals(StarterCatalog.portrait(after.knight(),after.profile(),itemCatalog))
            ||!java.util.Arrays.equals(partyWeapons(before.profile()),partyWeapons(after.profile()));
    }

    private void partyHealth(DataOutputStream out,PartyView.Participant member)throws IOException {var health=member.health();type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(member.playerOid());out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());}

    private void partyHeat(DataOutputStream out,PartyView.Participant member)throws IOException {type(out,"com.threerings.projectx.dungeon.data.Heat");out.writeInt(member.playerOid());out.writeFloat(member.heat());out.writeFloat(0);out.writeBoolean(false);out.writeBoolean(false);out.writeBoolean(false);}

    private void partyStatus(DataOutputStream out,PartyView.Participant member)throws IOException {var status=member.status();type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(member.playerOid());conditions(out,status);primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(member.health().reviveFlags());}

    public byte[] partyChanges(PartyView.View before,PartyView.View after)throws IOException {
        var events=new ArrayList<Writer>();var old=new HashMap<Integer,PartyView.Participant>();for(var m:before.members())old.put(m.playerOid(),m);
        // The stock party panel redraws its open/private controls from these attributes alone.
        if(before.inviteOnly()!=after.inviteOnly())events.add(out->attribute(out,after.oid(),"inviteOnly",o->value(o,after.inviteOnly())));
        if(before.allowFriends()!=after.allowFriends())events.add(out->attribute(out,after.oid(),"allowFriends",o->value(o,after.allowFriends())));
        if(before.allowGuild()!=after.allowGuild())events.add(out->attribute(out,after.oid(),"allowGuild",o->value(o,after.allowGuild())));
        var current=new java.util.HashSet<Integer>();for(var member:after.members()){
            current.add(member.playerOid());var previous=old.get(member.playerOid());
            if(previous==null||!previous.health().equals(member.health()))events.add(out->{partyEntry(out,after.oid(),"health",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyHealth(out,member);});
            if(previous==null||!previous.status().equals(member.status())||previous.health().reviveFlags()!=member.health().reviveFlags())events.add(out->{partyEntry(out,after.oid(),"status",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyStatus(out,member);});
            if(previous==null||previous.heat()!=member.heat())events.add(out->{partyEntry(out,after.oid(),"heat",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyHeat(out,member);});
            // Member listeners construct HUD rows synchronously and require both vitals entries.
            if(previous==null||!previous.knight().equals(member.knight())||previous.order()!=member.order()||partyAppearanceChanged(previous,member))events.add(out->{partyEntry(out,after.oid(),"members",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");partyMember(out,member);});
        }
        for(var member:before.members())if(!current.contains(member.playerOid()))for(String field:List.of("members","health","status","heat"))events.add(out->{partyEntry(out,after.oid(),field,"EntryRemovedEvent");value(out,member.playerOid());});
        return frame(out->{eventHeader(out,"CompoundEvent",after.oid());type(out,WireTypes.ARRAY_LIST);out.writeInt(events.size());for(var event:events)event.write(out);});
    }

    public byte[] partyInvitation(int player,PartyView.Invitation invitation)throws IOException {
        return frame(out->{eventHeader(out,invitation.add()?"EntryAddedEvent":"EntryRemovedEvent",player);string(out,"notifications");
            if(!invitation.add()){value(out,"PARTY_INV:"+invitation.partyOid());return;}
            type(out,"com.threerings.projectx.social.data.Notification$PartyInvite");
            type(out,"com.threerings.projectx.dungeon.data.PartyKey");string(out,null);out.writeInt(invitation.partyOid());
            type(out,WireTypes.DIFFICULTY);out.writeByte(0);
            type(out,WireTypes.NAME_ARRAY);out.writeInt(invitation.names().size());for(String knight:invitation.names())name(out,knight);
            string(out,null);out.writeInt(0);out.writeInt(0);string(out,"Advanced Training Hall");out.writeInt(invitation.inviter());
        });
    }

    public byte[] partyHealth(int partyOid,int playerOid,org.tortodrone.core.world.SceneHealth.State health)throws IOException {
        return frame(out->{eventHeader(out,"EntryUpdatedEvent",partyOid);string(out,"health");
            type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(playerOid);
            out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());});
    }

    public byte[] partyVitals(int partyOid,int playerOid,org.tortodrone.core.world.SceneHealth.State health,org.tortodrone.core.world.SceneStatus.State status)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",partyOid);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            type(out,WireTypes.ENTRY_UPDATED_EVENT);out.writeInt(partyOid);string(out,"health");
            type(out,"com.threerings.projectx.dungeon.data.Health");out.writeInt(playerOid);out.writeInt(health.current());out.writeInt(health.maximum());out.writeInt(health.bonus());
            type(out,WireTypes.ENTRY_UPDATED_EVENT);out.writeInt(partyOid);string(out,"status");
            type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(playerOid);conditions(out,status);primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(health.reviveFlags());
        });
    }

    public byte[] reviveFailure(int playerOid,short request,String reason)throws IOException{
        if(!java.util.Set.of("m.costupdate","m.no_revive","m.nsf_energy").contains(reason))return invocationFailure(playerOid,request,reason);
        return frame(out->{eventHeader(out,"InvocationResponseEvent",playerOid);out.writeShort(request);out.writeByte(0);out.writeBoolean(true);out.writeInt(1);value(out,reason);});
    }

    public byte[] partyStatus(int partyOid,int playerOid,org.tortodrone.core.world.SceneStatus.State status)throws IOException {
        return frame(out->{eventHeader(out,"EntryUpdatedEvent",partyOid);string(out,"status");
            type(out,"com.threerings.projectx.dungeon.data.Status");out.writeInt(playerOid);conditions(out,status);
            primitiveInts(out,status.expires().isEmpty()?null:status.expires().stream().mapToInt(Integer::intValue).toArray());out.writeByte(0);});
    }

}
