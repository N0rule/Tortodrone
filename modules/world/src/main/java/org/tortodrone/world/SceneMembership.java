package org.tortodrone.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.FloorAdvanceRequest;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.SceneKnightState;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.WeaponCatalog;

/** One connection's membership in a scene: per-knight movement, combat, health, inventory,
 *  story, floor and lift state. The simulation owns the shared entity model and systems; every
 *  public operation revalidates membership and runs under the simulation monitor. */
public final class SceneMembership implements AutoCloseable {
private static final int DROP_FLIGHT_MILLIS = 400;
final SceneSimulation scene;
    ActorState appearance;
    final TownMovement movement;
    KnightCombat combat;
    org.tortodrone.core.character.WorldProfile combatProfile;
    final Consumer<SceneSnapshot> initialPublish;
    Consumer<SceneSnapshot> publish;
    boolean entered, detached, arrived;
    int interactionTarget;
    int lift,dropAt;
    final SceneHealth health;
    float floorHeat;
    boolean revivePending;
    org.tortodrone.core.town.TownState.Sprite sprite;
    org.tortodrone.core.town.TownState.Harness harness;
    final PetCompanion pet;
    int auraOwner;
    org.tortodrone.core.character.KnightProgress progress=org.tortodrone.core.character.KnightProgress.RECRUIT;
    long pendingItem;int itemLand;SceneContent.Spec itemSpec;
    final ArrayDeque<Long> usedItems=new ArrayDeque<>();
    public List<Long> takeUsedItems(){synchronized(scene){check();var used=List.copyOf(usedItems);usedItems.clear();return used;}}
    public boolean useItem(long id,SceneContent.Spec spec){synchronized(scene){
        check();if(!entered||!portalOpen()||!health.alive()||pendingItem!=0||lift!=0||usedItems.contains(id))return false;
        if(spec.text("kind","").equals("vial")){
            int stamp=SceneClock.now();if(!movement.useLift(stamp))return false;lift=scene.entities.vial(actor(),spec,stamp);
            if(lift==0){movement.resetInput(stamp);return false;}usedItems.add(id);health.curseItem(id,stamp);updateLife(stamp);return true;
        }
        if(!spec.text("kind","").equals("capsule"))return false;
        if(spec.integer("heal",0)<=0)try{if(!SceneWeapons.usable(FireCatalog.actionJson(spec.text("use-action",""))))return false;}
        catch(java.io.IOException invalid){return false;}
        int stamp=SceneClock.now();if(!movement.useItem(new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config",""),Map.of()),stamp))return false;
        // The native use activity is accepted even when a Health Capsule has
        // nothing to heal. This attempt has no item action or consumption.
        if(spec.integer("heal",0)>0&&health.state().current()>=health.state().maximum())return true;
        health.curseItem(id,stamp);if(!health.alive()){usedItems.add(id);updateLife(stamp);return true;}
        pendingItem=id;itemLand=stamp+spec.integer("delay",0);itemSpec=spec;return true;
    }}
    /** Outcome of a persistent owned-item use; a native full-health heart is accepted
     *  without a payment and is therefore not consumed. */
    public record ItemUse<T>(boolean consumed,T payment) { }
    /** Validate, charge and apply one persistent bag item while the scene lock is held.
     *  Payment is committed only after the effect can be applied, and an item entity is
     *  reclaimed when the transaction is refused. */
    public <T> ItemUse<T> useOwnedItem(long id,SceneContent.Spec spec,SceneSimulation.RevivePayment<T> payment)throws java.io.IOException {synchronized(scene){
        check();if(!entered||!portalOpen()||!health.alive()||pendingItem!=0||lift!=0||usedItems.contains(id))throw new java.io.IOException("e.not_use_busy");
        String kind=spec.text("kind","");int stamp=SceneClock.now();
        switch(kind){
            case "heal" -> {
                int heal=spec.integer("heal",0);if(heal<=0)throw new java.io.IOException("m.internal_error");
                if(!movement.useItem(new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config",""),Map.of()),stamp))throw new java.io.IOException("e.not_use_busy");
                if(health.state().current()>=health.state().maximum())return new ItemUse<>(false,null);
                T result=payment.commit();health.heal(heal);return new ItemUse<>(true,result);
            }
            case "lethal" -> {
                Object damage;
                try{damage=FireCatalog.valueJson(spec.text("damage",""));}catch(java.io.IOException invalid){throw new java.io.IOException("m.internal_error",invalid);}
                if(!movement.useItem(new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config",""),Map.of()),stamp))throw new java.io.IOException("e.not_use_busy");
                T result=payment.commit();var hit=health.hit(damage,stamp);
                if(hit.amount()>0)scene.feedback(new SceneCombatEvent(0,stamp,appearance.id(),hit.feedback(),0,movement.x(),movement.y(),movement.rotation(),"NORMAL"));
                updateLife(stamp);return new ItemUse<>(true,result);
            }
            case "spawn-actor" -> {
                if(!movement.useItem(new org.tortodrone.core.character.StarterCatalog.Model(spec.text("config",""),Map.of()),stamp))throw new java.io.IOException("e.not_use_busy");
                int actor=scene.entities.spawnItemActor(actor(),spec.section("actor."),stamp);
                if(actor==0)throw new java.io.IOException("e.not_use_busy");
                T result;
                try{result=payment.commit();}catch(java.io.IOException|RuntimeException failed){scene.entities.discard(actor);throw failed;}
                return new ItemUse<>(true,result);
            }
            case "lift" -> {
                if(!movement.useLift(stamp))throw new java.io.IOException("e.not_use_busy");
                int carried=scene.entities.liftItem(actor(),spec.section("lift."),stamp);
                if(carried==0){movement.resetInput(stamp);throw new java.io.IOException("e.not_use_busy");}
                T result;
                try{result=payment.commit();}catch(java.io.IOException|RuntimeException failed){scene.entities.discard(carried);movement.resetInput(stamp);throw failed;}
                lift=carried;return new ItemUse<>(true,result);
            }
            default -> throw new java.io.IOException("m.internal_error");
        }
    }}
    /** Server-only notification after a committed item/economy operation. */
    public void effect(org.tortodrone.core.character.StarterCatalog.Model effect){synchronized(scene){
        check();if(entered&&portalOpen())scene.weapons.effect(effect,actor(),SceneClock.now());
    }}
    public SceneHealth.State health(){synchronized(scene){check();return health.state();}}
    public boolean arsenalZone(){synchronized(scene){check();return entered&&portalOpen()&&scene.arsenalZones.stream().anyMatch(e->e.spec().contains(e.x(),e.y(),e.rotation(),movement.x(),movement.y(),ScenePortals.PAWN_RADIUS));}}
    public boolean equipmentAllowed(){synchronized(scene){check();return entered&&portalOpen()&&health.alive()&&(!scene.instance.map().kind().equals("level")||arsenalZone());}}
    List<org.tortodrone.core.character.WorldProfile.Item> carriedItems=List.of();
    public void inventory(org.tortodrone.core.character.WorldProfile profile){synchronized(scene){
        check();carriedItems=profile.items();health.inventory(profile);scene.entities.reconsiderPickups(appearance.id());
    }}
    /**
     * Native transient-pickup capacity: consumables are stackable into a single Item
     * per distinct type (wire captures show repeated EntryUpdated, never a new slot),
     * the HUD belt has exactly 4 quickbar slots (interface_quickbar.dat slot0..3), and
     * every capsule/vial Pickup actor carries failureEffect=Player/Full Inventory.
     * The per-type cap of 3 matches stock-server behavior (wiki-corroborated); it is
     * not present as a client constant. So: at most 3 of a held type, at most 4 types.
     */
    boolean canPickup(SceneContent.Spec spec){
        if(!spec.flag("pickup-transient",false))return true;
        String item=spec.text("pickup-item","");
        if(spec.fields().containsKey("pickup-equip-slot")){
            int slot=spec.integer("pickup-equip-slot",0);
            var equipped=carriedItems.stream().filter(i->Objects.equals(i.equippedSlot(),slot)).findFirst().orElse(null);
            var stats=scene.instance.map().content().stats();
            return equipped==null||equipped.id()<0&&stats.getOrDefault("gear."+item+".health-bonus",0f)>stats.getOrDefault("gear."+equipped.config()+".health-bonus",0f);
        }
        var counts=new HashMap<String,Integer>();
        for(var carried:carriedItems)if(carried.id()<0&&carried.equippedSlot()==null)counts.merge(carried.config(),carried.count(),Integer::sum);
        // Claims reserve capacity until the session commits or refuses them.
        for(var reserved:scene.entities.pendingPickups(appearance.id()))if(reserved.temporary()&&reserved.equippedSlot()==null)counts.merge(reserved.item(),1,Integer::sum);
        return counts.getOrDefault(item,0)<3&&(counts.containsKey(item)||counts.size()<4);
    }
    public SceneStatus.State status(){synchronized(scene){check();return health.status();}}
    public boolean heal(int amount){synchronized(scene){check();return heal(amount,SceneClock.now());}}
    boolean heal(int amount,int stamp){
        if(!health.heal(amount))return false;
        var capsule=scene.instance.map().content().items().get("Pickup/Capsule/Health Capsule");
        if(capsule!=null)scene.weapons.effect(capsule.model("use-effect"),actor(),stamp);
        return true;
    }
    public int takeInteraction(){synchronized(scene){check();int target=interactionTarget;interactionTarget=0;return target;}}
    public List<SceneEntities.Pickup> takePickups(){synchronized(scene){check();return scene.entities.takePickups(appearance.id());}}
    public float floorHeat(){synchronized(scene){check();return floorHeat;}}
    /** Consume a scene-owned claim and credit the floor atomically, before publication. */
    public boolean collectHeat(int entity){synchronized(scene){
        check();if(!entered||!portalOpen()||!health.alive())return false;
        int amount=scene.entities.heatClaim(appearance.id(),entity);if(amount<=0)return false;
        var recipients=scene.members.values().stream().filter(m->m.entered&&!m.detached).toList();
        if(recipients.stream().anyMatch(m->m.floorHeat>1_000_000-amount))return false;
        if(!scene.entities.activateHeat(appearance.id(),entity))return false;
        for(var member:recipients)member.floorHeat+=amount;
        return true;
    }}
    public String pickupReceipt(int entity){return scene.pickupEpoch+":"+entity;}
    public void completePickup(int entity,boolean success){synchronized(scene){scene.entities.completePickup(appearance.id(),entity,success);}}
    volatile Runnable sceneFailure;
    /** Owner callback invoked after a tick failure, outside the simulation lock. */
    public void onSceneFailure(Runnable callback){sceneFailure=callback;}
    SceneMembership(SceneSimulation scene,ActorState actor,Consumer<SceneSnapshot> publish) {
        this.scene=scene;
        health=new SceneHealth(scene.instance.map().content());
        health.tutorialRespawn(scene.instance.tutorial()!=null);
        pet=new PetCompanion(this,scene);
        appearance=actor;this.initialPublish=this.publish=publish;
        health.spriteShield((damage,stamp,multiplier)->{
            var shield=auraOwner==0?this:scene.members.get(auraOwner);
            if(shield==null||!shield.entered||!shield.health.alive()||shield.sprite==null||shield.pet.shield<=0)return SceneHealth.SpriteBlock.NONE;
            int amount=(int)Math.ceil(SceneDamage.amount(SceneDamage.select(damage,false),scene.instance.map().content(),shield.pet.shieldDefense,multiplier));
            int remaining=shield.pet.shield;shield.pet.shield=Math.max(0,remaining-amount);
            return new SceneHealth.SpriteBlock(amount==0?0:Math.max(0,(amount-remaining)/(float)amount),remaining>amount);
        });
        movement=new TownMovement(scene.instance.map().collision(),actor.x(),actor.y(),actor.rotation(),scene.instance.map().portals());
        movement.resetInput(actor.created());
        movement.beginLoading(actor.created());
        movement.dynamicPenetration((x,y)->scene.entities.penetration(x,y,actor.id()));
        int collisionMask=Math.round(scene.instance.map().content().stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK));
        movement.staticCollision(scene.instance.map().staticCollision(),collisionMask);
        if(scene.instance.map().exactCollision()!=null)movement.exactCollision(scene.instance.map().exactCollision(),collisionMask,(x,y)->scene.entities.elevatorFloor(x,y));
        movement.interaction(stamp->{var prompt=scene.entities.interaction(movement.x(),movement.y(),movement.rotation(),appearance.id(),lift);
            if(prompt==null)return false;
            if(prompt.action().equals("Lift")){
                if(lift!=0||!movement.lift(stamp))return false;
                if(!scene.entities.carry(prompt.actorId(),actor(),stamp)){movement.resetInput(stamp);return false;}
                lift=prompt.actorId();return true;
            }
            scene.entities.openArsenal(prompt.actorId(),stamp);
            movement.interactionDuration(scene.entities.interactionDuration(prompt.actorId()));interactionTarget=prompt.actorId();return true;});
        movement.throwing(stamp->{if(lift==0)return false;
            boolean snap=scene.entities.prepareDrop(lift,actor());
            boolean drop=!scene.entities.canThrow(lift)||snap;
            movement.thrown(drop,stamp);if(drop)dropAt=stamp+DROP_FLIGHT_MILLIS;else{scene.entities.throwLift(lift,actor(),stamp);lift=0;}return true;});
    }
    public void enter(){enter(initialPublish);}
    public void enter(Consumer<SceneSnapshot> publication){synchronized(scene){check();
        publish=Objects.requireNonNull(publication);
        if(!arrived){arrived=true;if(!scene.instance.map().town())health.protectEntry(SceneClock.now());}
        entered=true;if(!scene.instance.map().town())scene.recordVisited(movement.x(),movement.y());scene.start();}}
    public void pause(){synchronized(scene){if(detached)return;cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();entered=false;pendingConversation=null;pendingMemories.clear();pendingObjectives.clear();activeFloorAdvance=pendingFloorAdvance=null;scene.weapons.cancel(appearance.id());pet.cancel();scene.entities.releaseLifts(appearance.id(),SceneClock.now());lift=dropAt=0;pendingItem=0;itemSpec=null;pendingAnimated=null;movement.resetInput(SceneClock.now());scene.stopIfIdle();}}
    public void resetInput(){synchronized(scene){check();pendingAnimated=null;scene.weapons.cancelAttacks(appearance.id());movement.resetInput(SceneClock.now());}}
    public boolean teleport(float x,float y,float rotation){synchronized(scene){
        check();if(!entered||!portalOpen()||lift!=0||pendingItem!=0||!health.alive())return false;
        if(!movement.teleport(x,y,rotation,SceneClock.now()))return false;
        interactionTarget=0;scene.weapons.cancelAttacks(appearance.id());return true;
    }}
    public boolean input(int stamp,float facing,float heading,int flags,int sceneTime,long now) {
        return input(stamp,facing,heading,flags,stamp,sceneTime,now);
    }
    public boolean input(int stamp,float facing,float heading,int flags,int actionChanged,int sceneTime,long now) {
        synchronized(scene){check();
            boolean loading=movement.activity()==TownMovement.LOADING;
            movement.disabledCollision(scene.entities.disabledCollisionSources());
            scene.weapons.disabledSources(scene.entities.disabledCollisionSources());
            boolean accepted=entered&&portalOpen()&&movement.accept(stamp,facing,heading,flags,actionChanged,sceneTime,now);
            // Native DungeoneerLogic.finishedLoading arms INITIAL_INVULNERABILITY when the
            // client's first input ends the loading pose; scene.town knights receive none.
            if(accepted&&loading&&!scene.instance.map().town())health.protectLoading(SceneClock.now());
            return accepted;}
    }
    public void equipment(org.tortodrone.core.character.WorldProfile profile) {
        synchronized(scene){check();
            int priorSprite=sprite==null?0:sprite.id();
            combatProfile=profile;carriedItems=profile.items();progress=profile.progress();sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);
            if(priorSprite!=(sprite==null?0:sprite.id())){
                pet.changeSprite();
                // Preserve the recharge deadlines: swapping sprites cannot bypass cooldowns.
            }
            harness=sprite==null?null:profile.town().harnesses().stream().filter(h->h.id()==sprite.harness()).findFirst().orElse(new org.tortodrone.core.town.TownState.Harness(0,"Iron Harness"));if(combat!=null){var before=combat.state().weapon();combat.equipment(profile);
            if(!Objects.equals(before,combat.state().weapon()))scene.weapons.cancelAttacks(appearance.id());}health.equipment(profile,scene.town);}
    }
    public void combat(WeaponCatalog catalog,org.tortodrone.core.character.WorldProfile profile) {
        combat(catalog,EquipmentCatalog.empty(),profile);
    }
    public void combat(WeaponCatalog catalog,EquipmentCatalog modifiers,org.tortodrone.core.character.WorldProfile profile) {
        synchronized(scene){check();if(scene.instance.map().town())throw new IllegalStateException("Town Knights are peaceful");
            combatProfile=profile;progress=profile.progress();combat=new KnightCombat(catalog,modifiers);combat.equipment(profile);health.equipment(profile,scene.town);combat.attacks(attack->{health.breakCloak();if(attack.activity()>=KnightCombat.INCOMPLETE&&combat.state().weapon()!=null)health.curseItem(combat.state().weapon().id(),attack.started());if(health.alive())scene.weapons.attack(appearance.id(),attack);});movement.combat(combat);}
    }
    public boolean sit(int sceneTime,long now) {
        synchronized(scene){check();return entered&&portalOpen()&&movement.sit(sceneTime,now);}
    }
    public boolean emote(PlayerEmotes.Descriptor emote,int sceneTime,long now) {
        synchronized(scene){check();return entered&&portalOpen()&&movement.emote(emote,sceneTime,now);}
    }
    public boolean canOpenBox(){synchronized(scene){check();return entered&&portalOpen()&&health.alive()&&lift==0&&pendingItem==0&&movement.canOpenBox();}}
    /** Called only with committed rewards; no storage or network work under the scene lock. */
    public void openBox(List<org.tortodrone.core.character.StarterCatalog.Model> references){
        synchronized(scene){check();if(!canOpenBox())return;
            scene.weapons.cancelAttacks(appearance.id());movement.openBox(references,SceneClock.now());}
    }
    /** Animate an already-committed persistent consumable in this scene only. */
    public void confetti(String config){synchronized(scene){check();if(!canOpenBox())return;
        scene.weapons.cancelAttacks(appearance.id());movement.confetti(config,SceneClock.now());}}
    String pendingAnimated;
    /** Animate a committed consumable; the scene owns its delayed effect and interruption. */
    public boolean animatedAction(String config){synchronized(scene){
        check();if(!canOpenBox())return false;
        int stamp=SceneClock.now();
        if(!movement.animatedItem(config,stamp))return false;
        scene.weapons.cancelAttacks(appearance.id());
        pendingAnimated=config;
        return true;
    }}
    void updateLife(int stamp){
        if(health.alive()){if(movement.dead()){
            movement.resurrect(stamp);
            if(!health.takeQuietRevival()){
                scene.weapons.effect(new org.tortodrone.core.character.StarterCatalog.Model("Player/Emergency Rez",Map.of()),actor(),stamp);
                scene.entities.reviveImpulse(actor(),stamp);
            }
        }return;}
        cancelElevator(stamp);activeFloorAdvance=pendingFloorAdvance=null;
        if(movement.dead())return;
        scene.weapons.cancelAttacks(appearance.id());
        scene.entities.cancelClaims(appearance.id());
        if(lift!=0)scene.entities.dropLift(lift,actor(),stamp);
        lift=dropAt=interactionTarget=0;pendingItem=0;itemSpec=null;pendingAnimated=null;
        movement.die(stamp);
        if((health.state().reviveFlags()&8)!=0)scene.weapons.effect(new org.tortodrone.core.character.StarterCatalog.Model("Player/Pre Emergency Rez",Map.of()),actor(),stamp);
    }
    public void appearance(ActorState actor) {
        synchronized(scene){check();if(actor.id()!=appearance.id()||actor.clientOid()!=appearance.clientOid())throw new IllegalArgumentException("Actor ownership changed");appearance=actor;}
    }
    ActorState snapshotActor() {return actor();}
    ScenePortals.Trigger portalTrigger(){return scene.portals.trigger(appearance.id());}
    boolean portalOpen(){return scene.portals.open(appearance.id());}
    SceneHealth.State healthState(){return health.state();}
    ActorState petSnapshot(){return pet.actor();}
    void advancePet(int stamp){pet.advance(stamp);}
    ActorState actor() {
        var a=appearance;
        return new ActorState(a.id(),a.clientOid(),a.name(),a.color(),a.created(),movement.x(),movement.y(),
            movement.rotation(),movement.direction(),movement.flags()|(!scene.instance.map().town()?SceneSimulation.TIER_ENABLED_FLAG:0)
                |(!scene.instance.map().town()&&health.invulnerable(SceneClock.now())?SceneHealth.INVULNERABLE_FLAG:0)|(health.cloaked()?SceneSimulation.HIDDEN_FOR_DIALOG_FLAG:0),movement.activity(),movement.activityStarted(),a.config(),a.gameMaster(),movement.combatState(),null,null,
                !health.alive()||movement.activity()==TownMovement.RESURRECT?null:scene.entities.interaction(movement.x(),movement.y(),movement.rotation(),appearance.id(),lift),new SceneKnightState(movement.activityData(),lift,health.status(),progress,sprite,harness,scene.instance.map().town()||sprite==null?0:pet.id,pet.knightSkill,health.hitTime()));
    }
    SceneSimulation.ConversationRequest pendingConversation;
    final ArrayDeque<SceneSimulation.MemoryRequest> pendingMemories=new ArrayDeque<>();
    final ArrayDeque<SceneSimulation.ObjectiveRequest> pendingObjectives=new ArrayDeque<>();
    public SceneSimulation.StoryRequests takeStory(){synchronized(scene){check();var result=new SceneSimulation.StoryRequests(List.copyOf(pendingMemories),List.copyOf(pendingObjectives));pendingMemories.clear();pendingObjectives.clear();return result;}}
    public List<SceneSimulation.MemoryRequest> takeMemories(){synchronized(scene){check();var result=List.copyOf(pendingMemories);pendingMemories.clear();return result;}}
    FloorAdvanceRequest activeFloorAdvance,pendingFloorAdvance;
    String elevatorEntry="";
    Set<Integer> elevatorMembers=Set.of();
    SceneEntities.ElevatorTrip elevatorTrip;
    boolean elevatorSettled;
    boolean elevatorPayoutSettled;
    /** Server-only exit grant after party/route preflight; no native request accepts a key. */
    public boolean authorizeElevator(String key){synchronized(scene){
        return authorizeElevator(key,scene.members.values().stream().filter(m->!m.detached&&m.entered&&m.health.alive()&&m.movement.activity()!=TownMovement.RESURRECT).map(m->m.appearance.id()).collect(java.util.stream.Collectors.toSet()));
    }}
    public boolean authorizeElevator(String key,Set<Integer> expectedMembers){synchronized(scene){
        check();if(elevatorTrip!=null||activeFloorAdvance!=null||!scene.instance.map().kind().equals("level")||!scene.entities.elevatorEntry(key))return false;
        if(expectedMembers==null||expectedMembers.isEmpty()||expectedMembers.size()>PartyView.CAPACITY||!expectedMembers.contains(appearance.id())||expectedMembers.stream().anyMatch(id->id==null||id<=0))return false;
        elevatorEntry=key;elevatorMembers=Set.copyOf(expectedMembers);return true;
    }}
    public void revokeElevator(){synchronized(scene){check();cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();}}
    void cancelElevator(int stamp){if(elevatorTrip!=null)scene.entities.cancelElevator(elevatorTrip,stamp);}
    public boolean dropElevator(FloorAdvanceRequest request,boolean returning){synchronized(scene){
        return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()&&scene.entities.dropElevator(elevatorTrip,returning,SceneClock.now());
    }}
    public boolean elevatorReady(FloorAdvanceRequest request){synchronized(scene){
        return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()&&scene.entities.elevatorReady(elevatorTrip,SceneClock.now());
    }}
    public SceneElevator elevatorConfiguration(FloorAdvanceRequest request){synchronized(scene){
        return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()?elevatorTrip.configuration():null;
    }}
    /** Native payout occurs at NextFloor, before the report and drop. Preserve the
     * owned request for those later phases, while forbidding a repeated transaction. */
    public <T> T settleSoloFloor(FloorAdvanceRequest request,SceneSimulation.RevivePayment<T> commit)throws java.io.IOException {synchronized(scene){
        if(!ownsFloorAdvance(request)||elevatorTrip==null||request.source()!=elevatorTrip.source()||elevatorTrip.members().size()!=1||elevatorPayoutSettled||elevatorSettled)throw new java.io.IOException("Solo floor settlement is not current");
        T result=Objects.requireNonNull(commit).commit();elevatorPayoutSettled=true;return result;
    }}
    /** Pin the exact live ride through the SQLite transaction. No publication or
     * socket work is allowed in commit. A failure leaves the request retryable. */
    public <T> T settleSoloElevator(FloorAdvanceRequest request,SceneSimulation.RevivePayment<T> commit)throws java.io.IOException {synchronized(scene){
        if(!elevatorReady(request)||elevatorTrip.members().size()!=1)throw new java.io.IOException("Solo elevator settlement is not ready");
        T result=Objects.requireNonNull(commit).commit();
        // Retire before releasing the scene lock: a competing callback cannot
        // reuse a successful settlement while the session prepares its travel.
        activeFloorAdvance=pendingFloorAdvance=null;elevatorSettled=true;elevatorEntry="";elevatorMembers=Set.of();
        return result;
    }}
    long floorSequence;
    public FloorAdvanceRequest takeFloorAdvance(){synchronized(scene){check();var request=pendingFloorAdvance;pendingFloorAdvance=null;return request;}}
    /** Revalidate the exact request after session-thread preflight; never trust a client token. */
    public boolean ownsFloorAdvance(FloorAdvanceRequest request){synchronized(scene){
        return !detached&&scene.members.get(appearance.id())==this&&entered&&health.alive()&&portalOpen()&&request!=null&&activeFloorAdvance==request;
    }}
    public boolean cancelFloorAdvance(FloorAdvanceRequest request){synchronized(scene){
        if(!ownsFloorAdvance(request))return false;cancelElevator(SceneClock.now());activeFloorAdvance=pendingFloorAdvance=null;return true;
    }}
    public SceneSimulation.ConversationRequest takeConversation(){synchronized(scene){check();var request=pendingConversation;pendingConversation=null;return request;}}
    public boolean conversationResponse(SceneConversation definition,String node,int choice,ActorState source){synchronized(scene){
        check();return entered&&scene.entities.conversationResponse(definition,node,choice,source,appearance.id(),SceneClock.now());
    }}
    public boolean conversationValid(SceneConversation definition,String node,int choice,ActorState source){synchronized(scene){check();return entered&&health.alive()&&portalOpen()&&scene.entities.conversationValid(definition,node,choice,source,appearance.id(),SceneClock.now());}}
    public boolean conversationStart(SceneConversation definition,ActorState source){synchronized(scene){check();return entered&&health.alive()&&portalOpen()&&scene.entities.conversationStart(definition,source,appearance.id(),SceneClock.now());}}
    void check(){if(detached)throw new IllegalStateException("Scene membership is closed");}
    @Override public void close(){synchronized(scene){if(detached)return;cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();detached=true;pendingConversation=null;pendingMemories.clear();pendingObjectives.clear();activeFloorAdvance=pendingFloorAdvance=null;scene.weapons.cancel(appearance.id());pet.cancel();scene.entities.cancelClaims(appearance.id());scene.entities.releaseLifts(appearance.id(),SceneClock.now());scene.portals.clear(appearance.id());scene.members.remove(appearance.id(),this);scene.stopIfIdle();}}
}
