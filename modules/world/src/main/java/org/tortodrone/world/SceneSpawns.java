package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.MonsterType;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePortals;

/** Prepared actor creation for one scene instance: placed items, loot pickups, lifecycle
 *  templates, scripted batches and authorized admin groups. The entity model owns insertion,
 *  identity order and spatial bookkeeping. */
final class SceneSpawns {
    private final SceneEntities scene;
    private boolean soloRewards;
    private int sceneSpawnStamp,sceneSpawnCount;

    SceneSpawns(SceneEntities scene) {
        this.scene = scene;
    }

    record SpawnSource(SceneEntities scene,int id,int faction,String identity) { }
    /** Set for instances that never admit a second member (the private tutorial). */
    void soloRewards(boolean value){soloRewards=value;}

    int itemActor(ActorState player,SceneContent.Spec actor,int stamp){
        if(scene.entityCount()+scene.pendingCount()>=4096)return 0;
        var spec=new SceneContent.Spec(new TreeMap<>(actor.fields()));
        var binding=new SceneContent.Entry("item:"+scene.nextEntityId(),player.x(),player.y(),player.rotation(),spec);
        var entity=scene.spawn(binding,spec,null,stamp);entity.faction=0;return entity.id;
    }
    /** Create and carry the lift authored by a native lift-item use. */
    int liftItem(ActorState player,SceneContent.Spec lift,int stamp){
        if(scene.entityCount()+scene.pendingCount()>=4096)return 0;
        var binding=new SceneContent.Entry("item:"+scene.nextEntityId(),player.x(),player.y(),player.rotation(),lift);
        var entity=scene.spawn(binding,lift,null,stamp);
        if(!scene.carry(entity.id,player,stamp)){scene.unlink(entity);return 0;}return entity.id;
    }
    /** Reclaim an item entity whose payment transaction was refused. */
    boolean pickup(StarterCatalog.Model specification,float x,float y,float rotation,int stamp){
        if(specification==null||!(specification.arguments().get("fields") instanceof String json)||scene.entityCount()+scene.pendingCount()>=4096)return false;
        try{
            if(!(org.tortodrone.core.Json.parse(json) instanceof Map<?,?> raw))return false;
            var fields=new HashMap<String,String>();for(var entry:raw.entrySet()){
                if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value))return false;
                fields.put(key,value);
            }
            var spec=new SceneContent.Spec(fields);
            if(!spec.text("native-type","").equals("com.threerings.projectx.dungeon.data.actor.Pickup"))return false;
            boolean item=!spec.text("pickup-item","").isBlank(),heat=spec.integer("pickup-heat",0)>0;
            int heal=spec.integer("pickup-heal",0);
            if((item?1:0)+(heat?1:0)+(heal>0?1:0)!=1||heal<0||heal>1_000_000)return false;
            // Native party pickups pay every member. Heat claims already credit the whole
            // instance; an item/crown claim has one claimant, which is exact only when the
            // instance can never hold a second member.
            if(item&&spec.flag("pickup-party",false)&&!soloRewards)return false;
            scene.spawn(new SceneContent.Entry("loot:"+scene.nextEntityId(),x,y,rotation,spec),spec,null,stamp);return true;
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared loot actor",invalid);}
    }
    /** A lifecycle graph may request a prepared entity, never an arbitrary native class.
     * Stage the actor: death callbacks may run while the damage target set is being iterated. */
    Object sceneActorSource(int source){
        var parent=scene.entity(source);return parent==null||!parent.kind.equals("Monster")?null:new SpawnSource(scene,source,parent.faction,"actor:"+source);
    }
    boolean sceneActor(String template,float x,float y,float rotation,int stamp,Object source){
        var spec=scene.content().actorTemplates().get(template);
        if(spec==null||!(source instanceof SpawnSource parent)||parent.scene()!=scene||!scene.hasEntity(parent.id())||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation)
            ||scene.entityCount()+scene.pendingCount()>=4096||scene.nextEntityId()==Integer.MAX_VALUE)return false;
        if(sceneSpawnStamp!=stamp){sceneSpawnStamp=stamp;sceneSpawnCount=0;}
        if(sceneSpawnCount>=128)return false;
        var binding=new SceneContent.Entry("spawn:"+scene.nextEntityId(),x,y,rotation,spec);
        var entity=new SceneEntity(scene.nextEntityId(),stamp,binding,spec,null,scene.content());entity.faction=parent.faction();entity.scriptSource=parent.identity();
        if(spec.integer("activate-time",0)>0)entity.activity=3;
        scene.stagePending(entity);scene.nextEntityId(scene.nextEntityId()+1);sceneSpawnCount++;return true;
    }
    void generate(SceneEntity pad,int stamp){
        var damage=pad.spec.model("generated-damage");
        boolean blocked=false;
        if(damage!=null&&damage.arguments().get("value") instanceof FireCatalog.DataValue){
            for(var player:scene.occupants())if(pad.spec.contains(pad.x,pad.y,pad.rotation,player.x(),player.y(),ScenePortals.PAWN_RADIUS)){
                scene.damagePlayer(new SceneEntities.PlayerHit(player.id(),damage.arguments().get("value"),stamp,0));blocked=true;
            }
        }
        if(blocked){pad.nextGeneration=stamp+pad.spec.integer("delay",0);return;}
        scene.spawn(pad.binding,pad.spec.child(),pad,stamp);pad.count++;pad.nextGeneration=0;pad.flags&=~2;
    }
    /**
     * Spawn catalogue-backed monsters for an authorized administrator. The complete request is
     * validated before insertion, so an unknown/unsupported type, blocked position or room/cap
     * refusal never leaves a partial group behind. This is the same creation path as scene
     * placement and waves.
     */
    int monsters(String reference,float x,float y,float rotation,int count,float radius,int stamp)throws java.io.IOException {
        if(count<1||count>SceneEntities.MAX_ALIVE_MONSTERS)
            throw new java.io.IOException("Count must be between 1 and "+SceneEntities.MAX_ALIVE_MONSTERS);
        if(!Float.isFinite(radius)||radius<0||radius>SceneEntities.MAX_ADMIN_SPAWN_RADIUS)
            throw new java.io.IOException("Radius must be between 0 and "+SceneEntities.MAX_ADMIN_SPAWN_RADIUS);
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation))
            throw new java.io.IOException("Invalid monster spawn position");
        MonsterType type=scene.monsters.resolveSpawnable(reference);
        int alive=scene.liveMonsters();
        if(alive+count>SceneEntities.MAX_ALIVE_MONSTERS)
            throw new java.io.IOException("The scene already has "+alive+" live monsters; the limit is "+SceneEntities.MAX_ALIVE_MONSTERS);
        if((long)scene.entityCount()+scene.pendingCount()+count>=4096||(long)scene.nextEntityId()+count>Integer.MAX_VALUE)
            throw new java.io.IOException("The scene has no room for new actors");
        var fields=scene.monsters.fields(type);
        var points=new ArrayList<ScenePathing.Point>(count);
        for(int index=0;index<count;index++){
            float angle=(float)(Math.PI*2*index/count);
            float px=x+(count==1?0:radius*(float)Math.cos(angle));
            float py=y+(count==1?0:radius*(float)Math.sin(angle));
            if(!scene.terrain().walkable(px,py))
                throw new java.io.IOException("Spawn position is outside walkable terrain");
            if(!scene.staticCollision().isEmpty()&&(!scene.staticCollision().floorSupported(px,py)
                    ||scene.staticCollision().penetration(scene.pawnCollisionMask(),px,py,ScenePortals.PAWN_RADIUS).present()))
                throw new java.io.IOException("Spawn position is blocked");
            points.add(new ScenePathing.Point(px,py));
        }
        var created=new ArrayList<SceneEntity>(count);
        try{
            for(var point:points){
                var binding=new SceneContent.Entry("admin:"+scene.nextEntityId(),point.x(),point.y(),rotation,new SceneContent.Spec(fields));
                var entity=scene.spawn(binding,binding.spec(),null,stamp);entity.monsterType=type.id();
                created.add(entity);
            }
        }catch(RuntimeException failed){
            for(var entity:created)scene.despawn(entity);
            throw failed;
        }
        return created.size();
    }
    boolean scripted(SceneActions.Spawn actor,int stamp,String source,String activator){
        List<ActorState> origins;
        if(actor.activatorLocation()){
            var origin=scene.occupants().stream().filter(player->activator.equals("actor:"+player.id())).findFirst().orElse(null);
            if(origin==null)origin=scene.scriptSource(activator,stamp);
            if(origin==null)return false;origins=List.of(origin);
        }else if(actor.locationTag().isEmpty()){
            if(source.startsWith("entry:")){
                String key=source.substring(6);var entry=scene.content().entries().stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
                if(entry==null)return false;origins=List.of(scene.entrySource(entry,stamp));
            }else{var origin=scene.scriptSource(source,stamp);if(origin==null)return false;origins=List.of(origin);}
        }else origins=scene.taggedSources(actor.locationTag(),stamp);
        if(origins.isEmpty()||origins.size()>256||scene.entityCount()+scene.pendingCount()+origins.size()>4096)return false;
        var spec=new SceneContent.Spec(actor.fields());
        var staged=new ArrayList<SceneEntity>();int next=scene.nextEntityId();
        for(var origin:origins){
            var binding=new SceneContent.Entry("spawn:"+next,origin.x(),origin.y(),origin.rotation(),spec);
            var entity=new SceneEntity(next++,stamp,binding,spec,null,scene.content());entity.scriptSource=source;
            if(actor.activate()&&spec.integer("activate-time",0)>0)entity.activity=3;
            staged.add(entity);
        }
        // Validate the whole selection before publication; a failed batch changes no actor IDs.
        for(var entity:staged)scene.insert(entity);scene.nextEntityId(next);return true;
    }
}
