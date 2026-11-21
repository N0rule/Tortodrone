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

/** Scene-owned downstream frames; shares the connection symbol tables. */
public final class SceneCodec extends WireWriter {
    public SceneCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] forceTown(int oid,short receiver) throws IOException {
        return forceScene(oid,receiver,SceneSources.current().entry());
    }

    public byte[] forceScene(int oid,short receiver,SceneInstanceView scene) throws IOException {
        return forceDestination(oid,receiver,scene.map().zone(),scene.map().id());
    }

    public byte[] forceDestination(int oid,short receiver,int zone,int scene) throws IOException {
        return frame(out -> { eventHeader(out,"InvocationNotificationEvent",oid);out.writeShort(receiver);out.writeByte(1);
            out.writeBoolean(true);out.writeInt(2);value(out,zone);value(out,scene); });
    }

    public byte[] readyRoomMove(int oid,short request) throws IOException {
        return frame(out -> {
            eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(2);
            out.writeBoolean(true);out.writeInt(3);value(out,org.tortodrone.core.world.ReadyRoom.PLACE);
            type(out,"com.threerings.projectx.data.ReadyRoomSceneConfig");string(out,null);
            type(out,"com.threerings.whirled.zone.data.ZoneSummary");out.writeInt(org.tortodrone.core.world.ReadyRoom.ZONE);name(out,"");
            type(out,"[Lcom.threerings.whirled.zone.data.SceneSummary;");out.writeInt(1);
            type(out,"com.threerings.whirled.zone.data.SceneSummary");out.writeInt(org.tortodrone.core.world.ReadyRoom.SCENE);string(out,"~");
            emptyCollection(out);emptyCollection(out);
        });
    }

    public byte[] readyRoomLocation(int oid) throws IOException {
        return frame(out -> {
            eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(5);
            attribute(out,oid,"zoneId",o->value(o,org.tortodrone.core.world.ReadyRoom.ZONE));
            attribute(out,oid,"instanceId",o->value(o,0));
            attribute(out,oid,"zoneGeneralOid",o->value(o,0));attribute(out,oid,"zoneTradeOid",o->value(o,0));
            attribute(out,oid,"location",o->value(o,null));
        });
    }

    public byte[] sceneMove(int oid,short request,boolean zone) throws IOException {
        var registry=SceneSources.current();
        return sceneMove(oid,request,zone,registry.entry(),registry.catalog());
    }

    public byte[] sceneMove(int oid,short request,boolean zone,SceneInstanceView scene,SceneCatalogView catalog) throws IOException {
        byte[] model=scene.map().layout();
        return frame(out -> {
            eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(3);
            out.writeBoolean(true);out.writeInt(zone ? 4 : 3);value(out,scene.oid());
            type(out,scene.map().configType());string(out,scene.map().image());
            if(zone) {
                type(out,"com.threerings.whirled.zone.data.ZoneSummary");out.writeInt(scene.map().zone());name(out,scene.map().zoneName());
                var summaries=catalog.zoneScenes(scene.map().zone());
                type(out,"[Lcom.threerings.whirled.zone.data.SceneSummary;");out.writeInt(summaries.size());
                for(var summary:summaries){
                    type(out,"com.threerings.whirled.zone.data.SceneSummary");out.writeInt(summary.id());string(out,summary.name());
                    emptyCollection(out);emptyCollection(out);
                }
            }
            type(out,"com.threerings.tudey.data.TudeySceneModel");out.writeInt(model.length);out.write(model);
        });
    }

    public byte[] townLocation(int oid) throws IOException {
        return sceneLocation(oid,SceneSources.current().entry());
    }

    public byte[] sceneLocation(int oid,SceneInstanceView scene)throws IOException{return sceneLocation(oid,scene,0,0);}

    public byte[] sceneLocation(int oid,SceneInstanceView scene,int general,int trade) throws IOException {
        return frame(out -> { eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(scene.map().town()?6:5);
            if(scene.map().town())attribute(out,oid,"lastTownSceneId",o->value(o,scene.map().id()));
            attribute(out,oid,"zoneId",o->value(o,scene.map().zone()));
            attribute(out,oid,"instanceId",o->value(o,scene.map().town()?scene.oid():0));
            attribute(out,oid,"zoneGeneralOid",o->value(o,general));attribute(out,oid,"zoneTradeOid",o->value(o,trade));
            attribute(out,oid,"location",o->{type(o,"com.threerings.whirled.data.ScenePlace");o.writeInt(scene.oid());o.writeInt(scene.map().id());}); });
    }

    public byte[] scene(short id,List<ActorState> actors) throws IOException {
        return scene(id,SceneSources.current().entry(),actors);
    }

    public byte[] scene(short id,SceneInstanceView scene,List<ActorState> actors) throws IOException {
        return frame(out -> {
            objectHeader(out,id,scene.map().town()?"com.threerings.projectx.town.data.TownSceneObject":"com.threerings.projectx.data.ProjectXSceneObject",scene.oid());
            type(out,"com.threerings.presents.dobj.OidList");out.writeBoolean(true);out.writeInt(actors.size());for(var a:actors)out.writeInt(a.clientOid());out.writeInt(actors.size());
            // DSet.readObject retains wire order; its key lookup requires ascending bodyOid.
            type(out,WireTypes.DSET);out.writeInt(actors.size());for(var a:actors.stream().sorted(java.util.Comparator.comparingInt(ActorState::clientOid)).toList())occupant(out,a);
            type(out,"com.threerings.crowd.chat.data.SpeakMarshaller");out.writeInt(scene.oid());out.writeInt(org.tortodrone.core.world.SceneChat.SERVICE);
            type(out,"com.threerings.tudey.data.TudeySceneMarshaller");out.writeInt(scene.oid());out.writeInt(TUDEY_SCENE_INVOCATION);
            type(out,"com.threerings.projectx.data.ProjectXSceneMarshaller");out.writeInt(scene.oid());out.writeInt(PROJECTX_SCENE_INVOCATION);
            var icons=scene.map().content().mapIcons();
            type(out,WireTypes.DSET);out.writeInt(icons.size());int marker=1;
            for(var icon:icons){type(out,"com.threerings.projectx.data.IconMapEntry$FixedEntry");out.writeInt(marker++);string(out,icon.icon());vector(out,icon.x(),icon.y());}
            if(scene.map().town()){type(out,"com.threerings.projectx.town.data.TownSceneMarshaller");out.writeInt(scene.oid());out.writeInt(TOWN_SCENE_INVOCATION);}
        });
    }

    public byte[] occupants(List<ActorState> actors) throws IOException {
        return occupants(SceneSources.current().entry().oid(),actors);
    }

    public byte[] occupants(int sceneOid,List<ActorState> actors) throws IOException {
        return frame(out -> {
            eventHeader(out, "CompoundEvent", sceneOid);
            type(out, WireTypes.ARRAY_LIST);
            out.writeInt(2);
            attribute(out, sceneOid, "occupants", o -> {
                type(o, "com.threerings.presents.dobj.OidList");
                o.writeBoolean(true); o.writeInt(actors.size());
                for (var actor : actors) o.writeInt(actor.clientOid());
                o.writeInt(actors.size());
            });
            attribute(out, sceneOid, "occupantInfo", o -> {
                type(o, WireTypes.DSET); o.writeInt(actors.size());
                for (var actor : actors.stream().sorted(java.util.Comparator.comparingInt(ActorState::clientOid)).toList()) occupant(o, actor);
            });
        });
    }

    public byte[] occupantChanges(int sceneOid,Map<Integer,Integer> previous,List<ActorState> actors)throws IOException {
        var current=new java.util.LinkedHashMap<Integer,ActorState>();
        for(var actor:actors)current.put(actor.id(),actor);
        return frame(out->{
            eventHeader(out,"CompoundEvent",sceneOid);
            type(out,WireTypes.ARRAY_LIST);
            out.writeInt(2*((int)previous.keySet().stream().filter(id->(!current.containsKey(id)||current.get(id).clientOid()!=previous.get(id))).count()
                +(int)current.keySet().stream().filter(id->(!previous.containsKey(id)||previous.get(id)!=current.get(id).clientOid())).count()));
            for(var old:previous.entrySet())if(!current.containsKey(old.getKey())||current.get(old.getKey()).clientOid()!=old.getValue()){
                type(out,"com.threerings.presents.dobj.EntryRemovedEvent");out.writeInt(sceneOid);string(out,"occupantInfo");value(out,old.getValue());
                type(out,"com.threerings.presents.dobj.ObjectRemovedEvent");out.writeInt(sceneOid);string(out,"occupants");out.writeInt(old.getValue());
            }
            for(var actor:actors)if(!previous.containsKey(actor.id())||previous.get(actor.id())!=actor.clientOid()){
                type(out,"com.threerings.presents.dobj.EntryAddedEvent");out.writeInt(sceneOid);string(out,"occupantInfo");occupant(out,actor);
                type(out,"com.threerings.presents.dobj.ObjectAddedEvent");out.writeInt(sceneOid);string(out,"occupants");out.writeInt(actor.clientOid());
            }
        });
    }

    public byte[] occupantRoles(int sceneOid,List<ActorState> changed)throws IOException {
        return frame(out->{
            eventHeader(out,"CompoundEvent",sceneOid);
            type(out,WireTypes.ARRAY_LIST);out.writeInt(changed.size());
            for(var actor:changed) {
                type(out,WireTypes.ENTRY_UPDATED_EVENT);out.writeInt(sceneOid);
                string(out,"occupantInfo");occupant(out,actor);
            }
        });
    }

    public byte[] sceneDelta(int oid,int acknowledge,int reference,int timestamp,List<ActorState> added,
            List<ActorState> updated,int[] removed) throws IOException {
        return sceneDelta(oid, acknowledge, (short) 0, reference, timestamp, (short) 50,
            added, updated, removed, Map.of());
    }

    public byte[] sceneDelta(int oid, int acknowledge, short ping, int reference, int timestamp,
            short elapsed, List<ActorState> added, List<ActorState> updated, int[] removed,
            Map<Integer, ActorState> baseline) throws IOException {
        return sceneDelta(oid,SceneSources.current().entry().oid(),acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline);
    }

    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline)throws IOException {
        return sceneDelta(oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline,false);
    }

    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior)throws IOException {
        return sceneDelta(oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline,interior,List.of());
    }

    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects)throws IOException {
        return sceneDelta(oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed,added,updated,removed,baseline,interior,effects,List.of());
    }

    public byte[] sceneDelta(int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,
            short elapsed,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects,List<SceneCombatEvent> combat)throws IOException {
        return frame(out -> {
            sceneDeltaHeader(out,oid,sceneOid,acknowledge,ping,reference,timestamp,elapsed);
            sceneDeltaActors(out,oid,added,updated,removed,baseline,interior,effects,combat);
        });
    }

}
