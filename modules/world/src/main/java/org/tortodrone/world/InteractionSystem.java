package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.InteractionState;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePickupState;
import org.tortodrone.core.world.ScenePortals;

/** Pickup claims, magnetism/heal contact and interaction targeting for one scene instance.
 *  Claims are settled on the scene tick; wall time never decides a claim outcome. */
final class InteractionSystem {
    private final SceneEntities scene;
    private final Set<SceneEntity> candidates=new LinkedHashSet<>();

    InteractionSystem(SceneEntities scene) {
        this.scene = scene;
    }

    private static boolean candidate(SceneEntity entity){
        return entity.interactive||SceneEntities.liftKind(entity.kind)||entity.kind.equals("Door");
    }
    void add(SceneEntity entity){if(candidate(entity))candidates.add(entity);}
    void remove(SceneEntity entity){candidates.remove(entity);}

    List<SceneEntities.Pickup> takePickups(int player){
        var result=new ArrayList<SceneEntities.Pickup>();
        for(var e:scene.entityValues())if(e.claim==player&&!e.claimTaken){e.claimTaken=true;result.add(pickup(e));}
        return List.copyOf(result);
    }
    List<SceneEntities.Pickup> pendingPickups(int player){
        return scene.entityValues().stream().filter(e->e.claim==player).map(InteractionSystem::pickup).toList();
    }
    private static SceneEntities.Pickup pickup(SceneEntity e){return new SceneEntities.Pickup(e.id,e.spec.text("pickup-item",""),e.spec.flag("pickup-transient",false),e.spec.fields().containsKey("pickup-equip-slot")?e.spec.integer("pickup-equip-slot",0):null,e.spec.integer("pickup-heat",0));}
    int heatClaim(int player,int actor){var e=scene.entity(actor);return e!=null&&e.destroyed==Integer.MAX_VALUE&&e.claim==player&&e.claimTaken?e.spec.integer("pickup-heat",0):0;}
    boolean activateHeat(int player,int actor){
        var e=scene.entity(actor);if(heatClaim(player,actor)<=0)return false;
        var target=scene.occupants().stream().filter(p->p.id()==player&&p.clientOid()>0).findFirst().orElse(null);if(target==null)return false;
        e.pickupState=new ScenePickupState(target.clientOid(),scene.lastStamp());
        float speed=e.spec.number("vacuum-speed",0);
        e.destroyed=scene.lastStamp()+(speed<=0?0:(int)(Math.hypot(target.x()-e.x,target.y()-e.y)*1000/speed));e.claim=0;e.claimTaken=false;return true;
    }
    void reconsider(int player){for(var e:scene.entityValues())e.refusedPickups.remove(player);}
    void complete(int player,int actor,boolean success){
        var e=scene.entity(actor);if(e==null||e.claim!=player)return;
        // Claims are produced by advance: settle on that scene tick, including under
        // a deterministic clock. Wall time can precede an explicitly advanced tick.
        e.claim=0;e.claimTaken=false;if(success)e.destroyed=scene.lastStamp();else e.refusedPickups.add(player);
    }
    void cancel(int player){for(var e:scene.entityValues()){if(e.claim==player&&!e.claimTaken)e.claim=0;e.refusedPickups.remove(player);}}
    int duration(int id){var e=scene.entity(id);return e==null?670:e.spec.integer("interaction-duration",Set.of("Talk","Shop").contains(action(e))?1667:670);}
    private String action(SceneEntity e){
        String station=e.spec.text("town-kind","");
        String fallback=SceneEntities.liftKind(e.kind)?"Lift":
            scene.content().conversations().containsKey(e.spec.text("dialogue",e.config.name()))?"Talk":
            Set.of("OpenShop","OpenDepot","OpenAuction").contains(station)?"Shop":"Interact";
        return e.spec.text("interaction-action",fallback);
    }
    InteractionState interaction(float x,float y,float rotation){
        return interaction(x,y,rotation,0,0);
    }
    /** Carried keys can address only a compatible closed door in this scene. */
    InteractionState interaction(float x,float y,float rotation,int owner,int lift){
        var carried=lift==0?null:scene.entity(lift);
        boolean key=carried!=null&&carried.destroyed==Integer.MAX_VALUE&&carried.carrier==owner&&SceneEntities.liftKind(carried.kind)&&carried.spec.text("actionable-signal","").equals("gold_unlock");
        SceneEntity nearest=null;float best=Float.POSITIVE_INFINITY;
        float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        for(var e:candidates)if((key?e.kind.equals("Door")&&e.spec.text("door-lock","").equals("gold")&&(e.flags&2)==0:e.interactive||SceneEntities.liftKind(e.kind)&&(e.flags&6)==0)&&e.destroyed==Integer.MAX_VALUE){
            float ox=e.spec.number("interaction-offset-x",.7f),oy=e.spec.number("interaction-offset-y",0);
            float px=x+ox*c-oy*s,py=y+ox*s+oy*c;
            float distance=(float)Math.hypot(px-e.x,py-e.y);
            float radius=e.spec.number("interaction-radius",.4f)+e.spec.number("interaction-expansion",0);
            if(e.spec.contains(e.x,e.y,e.rotation,px,py,radius)&&distance<best){nearest=e;best=distance;}
        }
        if(nearest==null)return null;
        String description=nearest.spec.text("description","");
        String action=key?"UnlockGold":action(nearest);
        return new InteractionState(nearest.id,description.isEmpty()?null:new StarterCatalog.Model(description,Map.of()),action,
            nearest.entrySource()?nearest.binding.key():null);
    }
    void collect(SceneEntity e,float elapsed,List<ActorState> players){
        boolean heal=e.spec.integer("pickup-heal",0)>0;
        if(e.spec.text("pickup-item","").isEmpty()&&e.spec.integer("pickup-heat",0)==0&&!heal)return;
        for(var player:players){
            if(!scene.activePlayer(player.id())||!scene.pickupEligible(player.id(),e.spec))continue;
            float dx=player.x()-e.x,dy=player.y()-e.y,distance=(float)Math.hypot(dx,dy);
            float reach=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
            if(distance>reach+e.spec.number("vacuum-expansion",0)){e.refusedPickups.remove(player.id());continue;}
            if(e.refusedPickups.contains(player.id()))continue;
            if(e.spec.integer("pickup-heat",0)>0){e.claim=player.id();return;}
            if(distance<=reach){
                // Native hearts heal on contact and stay put while the knight is full.
                if(heal){
                    if(scene.healPlayer(player.id(),e.spec.integer("pickup-heal",0)))e.destroyed=scene.lastStamp();
                    else e.refusedPickups.add(player.id());
                }else e.claim=player.id();
                return;
            }
            float step=Math.min(distance,e.spec.number("vacuum-speed",0)*elapsed);
            float x=e.x+dx*step/distance,y=e.y+dy*step/distance;
            if(scene.terrain().walkableSegment(e.x,e.y,x,y)){e.x=x;e.y=y;scene.moved(e);}
            return;
        }
    }
}
