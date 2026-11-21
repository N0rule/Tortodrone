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

/** Social-owned downstream frames; shares the connection symbol tables. */
public final class SocialCodec extends WireWriter {
    public SocialCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
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

    public byte[] inputThrottle() throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UpdateThrottleMessage");out.writeShort(-1);
            out.writeInt(TCP_MESSAGES_PER_SECOND);
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

    public byte[] forgeFailure(int clientOid,short requestId,String reason)throws IOException{
        if(!java.util.Set.of("m.forge_already","m.forge_locked","m.insufficient_crystals","m.costupdate").contains(reason))return invocationFailure(clientOid,requestId,reason);
        return frame(out->{eventHeader(out,"InvocationResponseEvent",clientOid);out.writeShort(requestId);out.writeByte(0);out.writeBoolean(true);out.writeInt(1);value(out,reason);});
    }


    public byte[] invocationConfirmed(int clientOid, short requestId) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "EventNotification"); out.writeShort(-1);
            type(out, "com.threerings.presents.dobj.InvocationResponseEvent"); out.writeInt(clientOid);
            out.writeShort(requestId); out.writeByte(1); out.writeBoolean(false);
        });
    }

    public byte[] invocationResult(int clientOid,short requestId,Object result)throws IOException {
        return frame(out->{eventHeader(out,"InvocationResponseEvent",clientOid);out.writeShort(requestId);out.writeByte(1);out.writeBoolean(true);out.writeInt(1);value(out,result);});
    }

    public byte[] tellSucceeded(int clientOid,short requestId)throws IOException {return tellSucceeded(clientOid,requestId,null);}

    public byte[] awayMessage(int oid,String message)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"awayMessage");value(out,message);});
    }

    public byte[] tellSucceeded(int clientOid,short requestId,String awayMessage)throws IOException {
        return frame(out->{
            eventHeader(out,"InvocationResponseEvent",clientOid);
            out.writeShort(requestId);out.writeByte(1);out.writeBoolean(true);out.writeInt(2);
            value(out,0L);value(out,awayMessage);
        });
    }

    public byte[] emptyListResult(int oid, short requestId) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, WireTypes.ARRAY_LIST); out.writeInt(0);
        });
    }

    public byte[] listResult(int oid, short requestId, List<String> values) throws IOException {
        return frame(out -> {
            eventHeader(out, "InvocationResponseEvent", oid);
            out.writeShort(requestId); out.writeByte(1);
            out.writeBoolean(true); out.writeInt(1);
            type(out, WireTypes.ARRAY_LIST); out.writeInt(values.size());
            for (String value : values) { type(out, "java.lang.String"); out.writeUTF(value); }
        });
    }

    public byte[] gameMaster(int clientOid,boolean enabled)throws IOException {
        return playerFlags(clientOid,enabled,false);
    }

    public byte[] playerFlags(int clientOid,boolean enabled,boolean inspectionHidden)throws IOException {
        return playerFlags(clientOid,enabled,inspectionHidden,true);
    }

    public byte[] playerFlags(int clientOid,boolean enabled,boolean inspectionHidden,boolean tutorialComplete)throws IOException {
        return frame(out->{
            eventHeader(out,"CompoundEvent",clientOid);
            type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,clientOid,"flags",o->{type(o,"java.lang.Integer");o.writeInt((tutorialComplete?32:0)|(enabled?256:0)|(inspectionHidden?1024:0));});
            attribute(out,clientOid,"tokens",o->{type(o,"com.threerings.projectx.data.ProjectXTokenRing");o.writeInt(enabled?8:0);});
        });
    }

    public byte[] lookingForGroup(int oid,boolean lookingForGroup)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"lfg");value(out,lookingForGroup);});
    }

    public byte[] friends(int oid,List<FriendView> before,List<FriendView> after)throws IOException {
        var previous=new java.util.TreeMap<String,FriendView>(String.CASE_INSENSITIVE_ORDER);for(var friend:before)previous.put(friend.name(),friend);
        var current=new java.util.TreeMap<String,FriendView>(String.CASE_INSENSITIVE_ORDER);for(var friend:after)current.put(friend.name(),friend);
        var events=new java.util.ArrayList<Writer>();
        for(var friend:before)if(!current.containsKey(friend.name()))events.add(out->{partyEntry(out,oid,"friends","EntryRemovedEvent");name(out,friend.name());});
        for(var friend:after)if(!friend.equals(previous.get(friend.name())))events.add(out->{partyEntry(out,oid,"friends",previous.containsKey(friend.name())?"EntryUpdatedEvent":"EntryAddedEvent");
            type(out,"com.threerings.projectx.social.data.FriendEntry");name(out,friend.name());out.writeInt(friend.world());
            if(friend.location()==null){type(out,"com.threerings.projectx.social.data.Whereabouts$Offline");out.writeLong(friend.lastSession());}
            else {type(out,"com.threerings.projectx.social.data.Whereabouts$Online");string(out,friend.location());out.writeInt(friend.zone());out.writeInt(friend.instance());}
            out.writeInt(friend.color());
        });
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(events.size());for(var event:events)event.write(out);});
    }

    public byte[] chatChannel(short message,int oid,int chatType)throws IOException {
        if(chatType!=6&&chatType!=7)throw new IOException("Unsupported chat channel type");
        return frame(out->{objectHeader(out,message,"com.threerings.projectx.data.ZoneInstanceObject",oid);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(oid);out.writeInt(org.tortodrone.core.world.SceneChat.SERVICE);
        });
    }

    public byte[] chat(int sceneOid,org.tortodrone.core.world.SceneChat.Message message)throws IOException {
        return frame(out->{
            eventHeader(out,"MessageEvent",sceneOid);string(out,"crowd.chat");
            out.writeBoolean(true);out.writeInt(1);type(out,message.mode()==5||message.mode()==6?
                "com.threerings.projectx.data.ProjectXUserMessage":"com.threerings.crowd.chat.data.UserMessage");
            string(out,message.text());string(out,message.bundle());name(out,message.speaker());out.writeByte(message.mode());
        });
    }

    public byte[] chatFeedback(int sceneOid,String reason)throws IOException {
        return systemNotice(sceneOid,reason);
    }

    public byte[] systemNotice(int sceneOid,String text)throws IOException {
        return systemMessage(sceneOid,text,null);
    }

    public byte[] auctionOutbid(int sceneOid,String itemName,int crowns)throws IOException {
        return systemMessage(sceneOid,"m.note_outbid|"+itemName+"|~"+crowns,"auction");
    }

    private byte[] systemMessage(int sceneOid,String reason,String bundle)throws IOException {
        return frame(out->{
            eventHeader(out,"MessageEvent",sceneOid);string(out,"crowd.chat");
            out.writeBoolean(true);out.writeInt(1);type(out,"com.threerings.crowd.chat.data.SystemMessage");
            string(out,reason);string(out,bundle);out.writeByte(1);
        });
    }

    public byte[] unsubscribe(short messageId, int objectId) throws IOException {
        return frame(out -> {
            type(out, PREFIX + "UnsubscribeResponse");
            out.writeShort(messageId);
            out.writeInt(objectId);
        });
    }

}
