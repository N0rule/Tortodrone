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

/** Guild-owned downstream frames; shares the connection symbol tables. */
public final class GuildCodec extends WireWriter {
    public GuildCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] guild(short message,org.tortodrone.core.guild.View view)throws IOException {
        return frame(out->{objectHeader(out,message,"com.threerings.projectx.guild.data.GuildObject",
            guildOid(view.guild().id()));guildBody(out,view);});
    }

    private void guildBody(DataOutputStream out,org.tortodrone.core.guild.View view)throws IOException {
        var guild=view.guild();
        type(out,"com.threerings.projectx.guild.data.GuildPeerMarshaller");out.writeInt(guildOid(guild.id()));out.writeInt(GUILD_PEER_SERVICE);
        type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(guildOid(guild.id()));out.writeInt(GUILD_CHAT_SERVICE);
        out.writeInt((int)guild.id());
        name(out,guild.name());
        out.writeLong(guild.formed());
        type(out,WireTypes.DSET);out.writeInt(view.members().size());
        for(var member:view.members())guildMember(out,member);
        out.writeInt(0);out.writeInt(0); // hallSceneId, designSceneId: no hall scene is authored yet
        string(out,guild.motd());
        out.writeBoolean(guild.hallOpen());
        out.writeInt(guild.energy());
        out.writeInt(guild.upkeepCost());
        out.writeLong(guild.upkeepTime());
        out.writeBoolean(guild.inDefault());
        type(out,"java.util.HashSet");out.writeInt(0); // attributes: the guild shop is not served
        out.writeInt(guild.memberLimit());
    }

    private void guildMember(DataOutputStream out,org.tortodrone.core.guild.Member member)throws IOException {
        type(out,"com.threerings.projectx.guild.data.GuildMemberEntry");
        name(out,member.name());
        out.writeInt(member.worldId());
        type(out,"com.threerings.projectx.social.data.Whereabouts$Offline");out.writeLong(member.lastSession());
        out.writeByte(member.rank());
        out.writeInt(member.commandOrder());
        out.writeLong(member.joined());
    }

    public byte[] guildChanges(org.tortodrone.core.guild.View before,
            org.tortodrone.core.guild.View after)throws IOException {
        var events=new ArrayList<Writer>();int oid=guildOid(after.guild().id());
        var g0=before.guild();var g1=after.guild();
        if(!g0.name().equals(g1.name()))events.add(o->attribute(o,oid,"name",v->name(v,g1.name())));
        if(!g0.motd().equals(g1.motd()))events.add(o->attribute(o,oid,"motd",v->string(v,g1.motd())));
        if(g0.hallOpen()!=g1.hallOpen())events.add(o->attribute(o,oid,"hallOpen",v->v.writeBoolean(g1.hallOpen())));
        if(g0.energy()!=g1.energy())events.add(o->attribute(o,oid,"energy",v->v.writeInt(g1.energy())));
        if(g0.upkeepCost()!=g1.upkeepCost())events.add(o->attribute(o,oid,"upkeepCost",v->v.writeInt(g1.upkeepCost())));
        if(g0.upkeepTime()!=g1.upkeepTime())events.add(o->attribute(o,oid,"upkeepTime",v->v.writeLong(g1.upkeepTime())));
        if(g0.inDefault()!=g1.inDefault())events.add(o->attribute(o,oid,"inDefault",v->v.writeBoolean(g1.inDefault())));
        var old=new java.util.HashMap<String,org.tortodrone.core.guild.Member>();
        for(var member:before.members())old.put(member.name(),member);
        var current=new java.util.HashSet<String>();
        for(var member:after.members()){
            current.add(member.name());var previous=old.get(member.name());
            if(previous==null||previous.rank()!=member.rank()||previous.commandOrder()!=member.commandOrder())
                events.add(o->{partyEntry(o,oid,"members",previous==null?"EntryAddedEvent":"EntryUpdatedEvent");guildMember(o,member);});
        }
        for(var member:before.members())if(!current.contains(member.name()))
            events.add(o->{partyEntry(o,oid,"members","EntryRemovedEvent");name(o,member.name());});
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(events.size());for(var event:events)event.write(out);});
    }

    public byte[] guildCleared(int player)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",player);type(out,WireTypes.ARRAY_LIST);out.writeInt(3);
            attribute(out,player,"guild",o->name(o,null));
            attribute(out,player,"guildId",o->value(o,0));
            attribute(out,player,"guildOid",o->value(o,0));});
    }

    public byte[] expansionDialog(int player,long guildId,List<Integer> expansions,boolean inDefault,
            float prorate,long created)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",player);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,player,"sceneDialogCreated",o->value(o,created));
            attribute(out,player,"sceneDialog",o->{
                type(o,"com.threerings.projectx.guild.data.ExpansionDialogInfo");
                dialogBase(o);
                o.writeShort(0);o.writeShort(0); // model, animation
                string(o,null);string(o,null); // name, title
                o.writeInt((int)guildId);
                hallExpansions(o,expansions);
                o.writeBoolean(inDefault);
                o.writeFloat(prorate);
            });});
    }

    public byte[] hallGlobalDialog(int player,long guildId,int type,List<Integer> expansions,
            String current,long created)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",player);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,player,"sceneDialogCreated",o->value(o,created));
            attribute(out,player,"sceneDialog",o->{
                type(o,"com.threerings.projectx.guild.data.HallGlobalDialogInfo");
                dialogBase(o);
                o.writeShort(0);o.writeShort(0);
                string(o,null);string(o,null);
                o.writeInt((int)guildId);
                type(o,"com.threerings.projectx.guild.data.HallGlobalType");o.writeByte(type);
                hallExpansions(o,expansions);
                string(o,current==null?"":current);
            });});
    }

    private static void dialogBase(DataOutputStream out)throws IOException {
        out.writeShort(0);out.writeShort(0);out.writeFloat(0);out.writeShort(0);out.writeShort(0);
    }

    private void hallExpansions(DataOutputStream out,List<Integer> expansions)throws IOException {
        type(out,"java.util.HashSet");out.writeInt(expansions.size());
        for(int expansion:expansions){type(out,"com.threerings.projectx.guild.data.HallExpansion");out.writeByte(expansion);}
    }

    public byte[] guildLog(short request,int oid,org.tortodrone.core.guild.Page page)throws IOException {
        return frame(out->{eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(1);out.writeBoolean(true);out.writeInt(1);
            type(out,"com.threerings.projectx.guild.data.GuildLogPage");out.writeInt(page.entryCount());
            type(out,"java.util.ArrayList");out.writeInt(page.entries().size());
            for(var entry:page.entries()){
                type(out,"com.threerings.projectx.guild.data.GuildLogEntry");
                out.writeLong(entry.id());
                type(out,"com.threerings.projectx.guild.data.GuildLogEntry$Type");out.writeByte(entry.type());
                string(out,entry.knightName());
                out.writeLong(entry.when());
                var args=entry.args();
                if(args==null||args.isEmpty())out.writeShort(0);
                else{type(out,"[Ljava.lang.String;");out.writeInt(args.size());
                    out.writeShort((args.size()+7)/8);
                    for(int i=0;i<args.size();i+=8){int mask=0;for(int j=i;j<Math.min(i+8,args.size());j++)mask|=1<<(j-i);out.writeByte(mask);}
                    for(String arg:args)out.writeUTF(arg);}
            }
        });
    }

}
