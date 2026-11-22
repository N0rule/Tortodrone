package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneShape;

/** Region damage, impulse and monster hurt resolution for one scene instance. The entity
 *  model owns actor identity, targets and hazard insertion; this system applies native
 *  region geometry, damage masks, defenses and reaction policy. */
final class DamageSystem {
    private final SceneEntities scene;

    DamageSystem(SceneEntities scene) {
        this.scene = scene;
    }

    void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damage(region,x,y,rotation,stamp,owner,null);
    }
    void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){
        damage(region,x,y,rotation,stamp,owner,sourceShape,null);
    }
    void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Set<Integer> hit){
        damage(region,x,y,rotation,stamp,owner,null,hit);
    }
    void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object shape,Set<Integer> hit){
        damage(region,x,y,rotation,stamp,owner,shape,hit);
    }
    void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape,Set<Integer> hitOnce){
        Object damage=region.arguments().get("damage"),shape=region.arguments().get("region");
        var source=scene.entity(owner);float expansion=SceneDamage.number(shape,"expansion",0);
        float multiplier=source==null?scene.occupants().stream().filter(p->p.id()==owner&&p.sceneKnight()!=null).map(p->p.sceneKnight().status().modifiers().attackDamage()).findFirst().orElse(1f):source.status.state().modifiers().attackDamage();
        float relative=source==null?scene.occupants().stream().filter(p->p.id()==owner&&p.sceneKnight()!=null).map(p->p.sceneKnight().status().modifiers().relativeDamage()).findFirst().orElse(1f):source.status.state().modifiers().relativeDamage();
        Object defined=region.arguments().getOrDefault("shape",SceneDamage.field(shape,"shape"));
        if(!(defined instanceof FireCatalog.DataValue)&&shape instanceof FireCatalog.DataValue value&&value.type().endsWith("$Default")){
            var model=source==null?null:source.spec.model("source-shape");
            if(sourceShape!=null)defined=sourceShape;
            else if(model!=null)defined=model.arguments().get("value");
            else expansion+=source==null?ScenePortals.PAWN_RADIUS:source.spec.number("radius",0);
        }
        // See SceneEntities.ROUNDHOUSE_MEDIUM_CONFIG: the blank base circle expands to the resolved Medium radius.
        if(source!=null&&SceneEntities.ROUNDHOUSE_MEDIUM_CONFIG.equals(source.config.name())&&defined instanceof FireCatalog.DataValue blank
                &&blank.type().endsWith("$TransformedShape")
                &&SceneDamage.field(defined,"shape") instanceof FireCatalog.DataValue inner&&inner.type().endsWith("$Circle")
                &&SceneDamage.number(inner,"radius",0)==1f)expansion+=SceneEntities.ROUNDHOUSE_MEDIUM_RADIUS-1f;
        // The official explosive detonation (every Block/Explosive* variant detonates through
        // the one "Block/Explosive Block - Explosion" action) carries a Default region with
        // expansion 1.0; the 2026 client export strips regions to that constructor's default
        // 0, leaving the blast at the block's own 1x1 footprint, which a knight hugging the
        // solid block can never enter. The live capture brackets the official blast - a knight
        // 1.48 off-centre (0.98 outside the footprint) is hit at the detonation timestamp,
        // and knights 2.4 away are not - and the native Polygon corner expansion measures
        // 2.41 x 2.41 on the block's own rectangle.
        // SceneEntity.kind is the native-type's simple name ("Block$Stateful" here), so match the
        // family by prefix like the other block paths do.
        if(source!=null&&source.kind.startsWith("Block")&&source.config.name().startsWith("Block/Explosive")
                &&shape instanceof FireCatalog.DataValue blast&&blast.type().endsWith("$Default"))
            expansion=Math.max(expansion,1f);
        {
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            for(var player:scene.occupants()){float dx=player.x()-x,dy=player.y()-y;
                boolean hit=defined instanceof FireCatalog.DataValue?SceneShape.intersects(defined,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+expansion;
                int sourceId=source==null?owner:source.kind.equals("Monster")?owner:(SceneEntities.liftKind(source.kind)||source.kind.equals("Bomb"))?source.owner:0;
                if(hit&&damageClear(region,x,y,player.x(),player.y(),owner,player.id())&&(hitOnce==null||hitOnce.add(player.id()))){
                    // One trap action can cover several occupants. Record each
                    // hit here so the next sensor cannot damage them again on
                    // the same tick or before the authored refractory deadline.
                    if(source!=null&&source.kind.equals("Trap")){
                        if(SceneClock.before(stamp,source.hazardHits.getOrDefault(player.id(),0)))continue;
                        source.hazardHits.put(player.id(),stamp+source.spec.integer("hazard-refractory",0));
                    }
                    // Native DamageRegionFromPoint pushes every actor the region damaged.
                    var impulse=createImpulse(region,x,y,rotation,player.x(),player.y(),Math.round(scene.content().stats().getOrDefault("player.impulse-level",1f)),stamp);
                    scene.damagePlayer(new SceneEntities.PlayerHit(player.id(),damage,stamp,sourceId,multiplier,impulse));
                }
            }
        }
        for(var e:List.copyOf(scene.entityValues())){
            if(e.destroyed!=Integer.MAX_VALUE)continue;
            // Native BlockLogic.wasDamaged returns false without a damageAction. A
            // global music block has no physical body or damage reaction to evaluate.
            if(e.kind.equals("Block")&&e.collisionFlags==0&&e.spec.text("shape","").equals("Global")&&e.spec.text("damage-action","").isEmpty())continue;
            float dx=e.x-x,dy=e.y-y,c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            boolean hit;
            if(defined instanceof FireCatalog.DataValue geometry){
                float tx=dx*c+dy*s,ty=-dx*s+dy*c,r=e.spec.number("radius",0);
                hit=e.shape==null||geometry.type().endsWith("$Segment")?SceneShape.intersects(geometry,tx,ty,r+Math.max(0,expansion)):
                    SceneCollision.overlaps(geometry,x,y,rotation,e.shape,e.x,e.y,e.rotation,Math.max(0,expansion));
            }else hit=e.spec.contains(e.x,e.y,e.rotation,x,y,Math.max(0,expansion));
            if(!hit||!damageClear(region,x,y,e.x,e.y,owner,e.id))continue;
            if(hitOnce!=null&&!hitOnce.add(e.id))continue;
            if(e.config.name().equals("Bomb/Monster/Oil Slick")&&scene.lightsOil(damage)){scene.lightOil(e,stamp);continue;}
            if(e.kind.equals("Switch")){
                String mode=e.spec.text("switch-mode","");
                Object switchDamage=SceneDamage.prune(damage,e.spec.integer("switch-damage-mask",255));
                if(mode.startsWith("damage-")&&(!mode.equals("damage-once")||e.state==0)&&SceneClock.reached(stamp,e.nextSwitch)&&SceneDamage.amount(switchDamage,scene.content(),e.spec,multiplier)>0){
                    scene.setSwitch(e,e.state==0?1:0,stamp);e.nextSwitch=stamp+e.spec.integer(mode.equals("damage-timed")?"switch-delay":"switch-cooldown",0);
                }
                continue;
            }
            if(e.spec.integer("damage-mask",0)==0)continue;
            Object accepted=SceneDamage.prune(damage,e.spec.integer("damage-mask",0));
            var defense=new HashMap<>(e.spec.fields());
            defense.replaceAll((key,value)->key.startsWith("defense.")?Float.toString(Float.parseFloat(value)*e.status.state().modifiers().defense()):value);
            var gear=region.arguments().get("equipment-damage") instanceof GearStats.Damage bonus?bonus:GearStats.Damage.NONE;
            float amount=SceneDamage.amount(accepted,scene.content(),new SceneContent.Spec(defense),multiplier,gear,relative);
            if(e.kind.equals("Monster")){
                amount+=e.status.damaged(amount,stamp);e.status.apply(accepted,stamp);if(amount>0){
                    int dealt=(int)Math.ceil(amount);hurt(e,dealt,stamp,SceneDamage.reacts(accepted),SceneDamage.major(accepted));
                    // Being hit is authoritative: a dormant monster wakes even with no player nearby.
                    e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;e.engagedUntil=stamp+SceneEntities.DAMAGE_ENGAGE_MILLIS;e.sightStamp=Integer.MIN_VALUE;e.returning=false;
                    if(e.targeting!=null&&scene.targetCandidates(e,scene.occupants()).stream().anyMatch(p->p.id()==owner&&scene.targetActive(p))&&(e.victims.containsKey(owner)||e.victims.size()<256)){
                        var previous=e.victims.getOrDefault(owner,new SceneEntity.Threat(0,0));
                        e.victims.put(owner,new SceneEntity.Threat(Math.min(1_000_000,previous.hits()+1),Math.min(1_000_000_000f,previous.damage()+dealt)));e.nextAcquire=stamp;
                    }
                    applyImpulse(e,region,x,y,rotation,stamp);
                    if(e.health==0)e.status.clear();
                    int sourceId=source!=null&&(SceneEntities.liftKind(source.kind)||source.kind.equals("Bomb"))?source.owner:owner;
                    // Native MonsterLogic promotes the damage summary's performance (weak against
                    // the target's defenses, strong into them) by the dealt fraction of its health.
                    String tier=SceneDamage.performance(accepted,scene.content(),new SceneContent.Spec(defense),multiplier,gear,relative);
                    float fraction=dealt/e.spec.number("health",1);
                    scene.combatEvent(new SceneCombatEvent(0,stamp,e.id,dealt,sourceId,e.x,e.y,e.rotation,tier+(fraction>.4f?"_LARGE":fraction>.2f?"_MEDIUM":"_SMALL")));
                }
            }else if(amount>0&&(e.kind.startsWith("Block")||e.spec.text("damage-action","").equals("destroy")))blockHit(e,stamp);
        }
    }
    private boolean damageClear(StarterCatalog.Model region,float x,float y,float tx,float ty,int owner,int target){
        int mask=region.arguments().get("collision-mask") instanceof Number n?n.intValue():0;
        if(mask==0)return true;
        if(!scene.staticCollision().clearSegment(mask,SceneEntities.POINT,x,y,tx,ty,0,scene.disabledCollisionSources()))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
        for(var obstacle:scene.entityValues()){
            if(obstacle.id==owner||obstacle.id==target||obstacle.destroyed!=Integer.MAX_VALUE||obstacle.carrier!=0
                    ||(obstacle.collisionFlags&mask)==0||obstacle.kind.equals("Door")&&(obstacle.flags&6)==2)continue;
            for(int i=0;i<=steps;i++)if(obstacle.spec.contains(obstacle.x,obstacle.y,obstacle.rotation,x+(tx-x)*i/steps,y+(ty-y)*i/steps,0))return false;
        }
        return true;
    }
    /** Retail Bomb/Revive/Revive: radius 2, level 13, five-unit radial impulse over 400 ms. */
    void reviveImpulse(ActorState player,int stamp){
        var region=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of("shape",
            new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,Map.of("radius",2f))));
        var impulse=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("level",13,"duration",400,
            "translation",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.VECTOR2F,Map.of("x",5f,"y",0f))));
        impulse(new StarterCatalog.Model("ImpartImpulse",Map.of("region",region,"impulse",impulse,"flags",2)),player.x(),player.y(),0,stamp,player.id());
    }
    void impulse(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        Object raw=region.arguments().get("region"),shape=region.arguments().getOrDefault("shape",SceneDamage.field(raw,"shape"));
        float expansion=SceneDamage.number(raw,"expansion",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        int flags=region.arguments().get("flags") instanceof Number n?n.intValue():1;
        for(var e:scene.entityValues())if(e.id!=owner&&e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE&&(e.spec.integer("damage-mask",0)&flags)!=0){
            float dx=e.x-x,dy=e.y-y;
            if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,e.spec.number("radius",0)+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+e.spec.number("radius",0)+expansion)
                applyImpulse(e,region,x,y,rotation,stamp);
        }
        if((flags&Math.round(scene.content().stats().getOrDefault("player.damage-mask",SceneContent.DEFAULT_PLAYER_DAMAGE_MASK)))!=0)for(var player:scene.occupants())if(player.id()!=owner&&scene.activePlayer(player.id())){
            float dx=player.x()-x,dy=player.y()-y;
            if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=2*ScenePortals.PAWN_RADIUS+expansion){
                var impulse=createImpulse(region,x,y,rotation,player.x(),player.y(),Math.round(scene.content().stats().getOrDefault("player.impulse-level",1f)),stamp);
                if(impulse!=null)scene.impulsePlayer(player.id(),impulse);
            }
        }
    }
    boolean monsters(Object region,float x,float y,float rotation){
        Object shape=SceneDamage.field(region,"shape");float expansion=SceneDamage.number(region,"expansion",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        for(var e:scene.entityValues())if(e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE){
            float dx=e.x-x,dy=e.y-y;if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,e.spec.number("radius",0)+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+e.spec.number("radius",0)+expansion)return true;
        }return false;
    }
    private KnightCombat.Impulse createImpulse(StarterCatalog.Model region,float x,float y,float rotation,float tx,float ty,int level,int stamp){
        Object origin=region.arguments().get("impulse-origin");float ox=SceneDamage.number(origin,"x",0),oy=SceneDamage.number(origin,"y",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        return SceneDamage.impulse(region.arguments().get("impulse"),x+ox*c-oy*s,y+ox*s+oy*c,tx,ty,level,stamp);
    }
    private void applyImpulse(SceneEntity entity,StarterCatalog.Model region,float x,float y,float rotation,int stamp){
        // The shared combat table stores stats as floats and actor() injects them unrounded
        // ("1.0"); impulse levels are whole tiers, so read and round like the player side.
        var impulse=createImpulse(region,x,y,rotation,entity.x,entity.y,Math.round(entity.spec.number("impulse-level",1)),stamp);
        if(impulse!=null){entity.impulse=SceneDamage.combine(impulse,entity.impulse);entity.impulseAdvanced=stamp;}
    }
    private void blockHit(SceneEntity e,int stamp){
        e.hitTime=stamp;
        switch(e.spec.text("kind","")){
            case "TimedBlock" -> {if(e.state==0){e.state=1;e.stateEntered=stamp;e.detonateAt=stamp+e.spec.integer("block-fuse",0);}}
            case "StarlinkBlock" -> clearLinks(e,stamp,false);
            case "GhostlinkBlock" -> clearLinks(e,stamp,true);
            case "StoneBlock" -> {if(e.state<e.spec.integer("block-hits",1)-1){e.state++;e.stateEntered=stamp;}else e.destroyed=stamp;}
            default -> {
                if(e.spec.text("damage-action","").equals("destroy"))e.destroyed=stamp;
                else if(e.config.name().startsWith("Block/Treasure, Health")){
                    // Native treasure blocks run a TableSpawnPerMember of hearts and
                    // then DestroyActor. The heart table is not expressible in scene.content(),
                    // so drop the tutorial heart and destroy the block like stock.
                    e.destroyed=stamp;
                    scene.dropHeart(e,stamp);
                }
            }
        }
    }
    void clearLinks(SceneEntity source,int stamp,boolean ghost){
        if(source.destroyed!=Integer.MAX_VALUE)return;source.destroyed=stamp;if(ghost)source.flags|=2;
        float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
        for(var target:scene.entityValues())if(target!=source&&target.destroyed==Integer.MAX_VALUE&&target.kind.startsWith("Block")
            &&(ghost||target.spec.text("kind","").equals("StarlinkBlock")&&target.spec.integer("block-color",0)==source.spec.integer("block-color",0))){
            if(target.spec.contains(target.x,target.y,target.rotation,source.x+c,source.y+s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-c,source.y-s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-s,source.y+c,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x+s,source.y-c,0))scene.linkLater(target,stamp+50,ghost);
        }
    }
    /** Native MonsterLogic.hurt: only reacting damage marks the hit effect, and the
     * hurt activity follows shouldReact rather than any single hit's size. */
    void hurt(SceneEntity e,float amount,int stamp,boolean react,boolean major){
        if(e.health<=0)return;
        e.health=Math.max(0,e.health-amount);e.hitTime=stamp;
        e.reactDamage.addLast(new int[]{(int)Math.ceil(amount),stamp+REACT_WINDOW_MILLIS});
        while(e.reactDamage.size()>64)e.reactDamage.removeFirst();
        if(react){e.affectedTime=stamp;e.affectedType=0;}
        if(e.health==0){
            e.activity=2;e.activityStarted=stamp;e.destroyed=stamp+e.spec.integer("death-time",100);e.flags=0;
            String death=e.spec.text("death-action","");
            if(!death.isBlank())scene.fireDeathAction(e,death);
        }
        else if(shouldReact(e,react,major,stamp)){e.activity=1;e.activityStarted=stamp;}
    }
    /** Native window over which MonsterLogic sums damage for a major reaction. */
    static final int REACT_WINDOW_MILLIS=1000;
    /** Native MonsterLogic.shouldReact: an interruptable attack yields to any reacting hit;
     * otherwise only major damage whose one-second sum strictly exceeds min-react-percent. */
    private static boolean shouldReact(SceneEntity e,boolean react,boolean major,int stamp){
        if(react&&e.activity>=4&&e.activity<16&&e.spec.flag("attack."+(e.activity-4)+".interruptable",false))return true;
        long sum=0;for(var it=e.reactDamage.iterator();it.hasNext();){int[] rec=it.next();if(rec[1]<stamp)it.remove();else sum+=rec[0];}
        float maximum=e.maximumHealth>0?e.maximumHealth:e.spec.number("health",0);
        if(major&&maximum>0&&sum/maximum>e.spec.number("min-react-percent",0)){e.reactDamage.clear();return true;}
        return false;
    }
}
