package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One authoritative clock, movement owner and immutable publication per scene instance.
 * Session callbacks only offer publications to bounded mailboxes; no socket or storage work
 * runs on the simulation thread. Terrain and future world systems belong to this instance. */
public final class SceneSimulation {
    /** Native Dungeoneer TIER_ENABLED publication flag (256): served content has no gated subtown
     * segment, so live captures show it set on every Character/PC/Level pawn in every dungeon. */
    public static final int TIER_ENABLED_FLAG = 256;
    /** Native Knight.HIDDEN_FOR_DIALOG (32): published while the Cloak status
     * condition is active, hiding the knight from other clients' scene views.
     * Same value as Monster.CAMO, but this is the Knight/KnightData namespace. */
    public static final int HIDDEN_FOR_DIALOG_FLAG = 32;
    @FunctionalInterface public interface RevivePayment<T>{T commit()throws java.io.IOException;}
    /** Run on the requesting session thread. No network I/O occurs under this lock.
     * Holding the scene through commit makes departure and competing revives mutually
     * exclusive with payment; the health/effect change is published only after commit. */
    public synchronized <T> T revive(Membership payer,int targetWorld,RevivePayment<T> payment)throws java.io.IOException {
        var target=members.get(targetWorld);
        if(payer==null||members.get(payer.appearance.id())!=payer||!payer.entered||payer.portal!=null
                ||target==null||!target.entered||target.portal!=null||target.revivePending
                ||target!=payer&&!payer.health.alive()||!target.health.canSparkRevive())throw new java.io.IOException("m.no_revive");
        target.revivePending=true;
        try{
            T result=payment.commit();int stamp=SceneClock.now();
            target.health.revive(stamp);target.movement.resurrect(stamp);
            weapons.effect(new org.tortodrone.character.StarterCatalog.Model("Player/Spark Rez",Map.of()),target.actor(),stamp);
            entities.reviveImpulse(target.actor(),stamp);
            return result;
        }finally{target.revivePending=false;}
    }
    private org.tortodrone.town.TownCatalog town=org.tortodrone.town.TownCatalog.empty();
    private final String pickupEpoch=UUID.randomUUID().toString();
    public synchronized void town(org.tortodrone.town.TownCatalog rules){town=Objects.requireNonNull(rules);}
    public record Delta(List<ActorState> added, List<ActorState> updated, int[] removed,
            List<ActorState> changedRoles, Map<Integer,ActorState> baseline) {
        public Delta { removed=removed.clone(); }
        @Override public int[] removed(){return removed.length==0?removed:removed.clone();}
    }
    public static final class Snapshot {
        private final int timestamp;
        private final Object identity=new Object(), predecessor;
        private final List<ActorState> actors;
        private final List<ActorState> players;
        private final List<SceneEffect> effects;
        private final List<SceneCombatEvent> combatEvents;
        private final Map<Integer,ActorState> byId;
        private final Set<Integer> occupants;
        private final Map<Integer,Boolean> roles;
        private final Map<Integer,ScenePortals.Trigger> portals;
        private final Map<Integer,SceneHealth.State> health;
        private final Delta common, initial;
        private Snapshot(int stamp, List<ActorState> actors, List<SceneEffect> effects,List<SceneCombatEvent> combatEvents, Map<Integer,ScenePortals.Trigger> portals, Map<Integer,SceneHealth.State> health,Snapshot previous) {
            timestamp=stamp;this.actors=List.copyOf(actors);this.effects=List.copyOf(effects);
            players=this.actors.stream().filter(ActorState::player).toList();
            this.portals=Map.copyOf(portals);
            this.health=Map.copyOf(health);
            this.combatEvents=List.copyOf(combatEvents);
            var index=new LinkedHashMap<Integer,ActorState>();var permissions=new HashMap<Integer,Boolean>();
            for(var actor:actors){index.put(actor.id(),actor);if(actor.player())permissions.put(actor.id(),actor.gameMaster());}
            byId=Collections.unmodifiableMap(index);occupants=Set.copyOf(permissions.keySet());roles=Map.copyOf(permissions);
            predecessor=previous==null?null:previous.identity;
            initial=new Delta(this.actors,List.of(),new int[0],List.of(),Map.of());
            common=previous==null?initial:calculate(previous);
        }
        public int timestamp(){return timestamp;}
        public List<ActorState> actors(){return actors;}
        public List<ActorState> players(){return players;}
        public SceneHealth.State health(int actor){return health.get(actor);}
        public List<SceneEffect> effectsSince(Snapshot previous){long last=previous==null?Long.MAX_VALUE:previous.effects.stream().mapToLong(SceneEffect::sequence).max().orElse(0);return effects.stream().filter(e->e.sequence()>last).toList();}
        public List<SceneCombatEvent> combatEventsSince(Snapshot previous){long last=previous==null?Long.MAX_VALUE:previous.combatEvents.stream().mapToLong(SceneCombatEvent::sequence).max().orElse(0);return combatEvents.stream().filter(e->e.sequence()>last).toList();}
        public Set<Integer> occupants(){return occupants;}
        public Map<Integer,Boolean> roles(){return roles;}
        public ScenePortals.Trigger portal(int actorId){return portals.get(actorId);}
        /** Preserve these records when a recipient coalesces ordinary movement snapshots. */
        public boolean startsDataActivity(Snapshot previous){
            if(previous==null)return false;
            for(var actor:players){
                var data=actor.sceneKnight()==null?null:actor.sceneKnight().activityData();
                var old=previous.byId.get(actor.id());
                if(data!=null&&old!=null&&(actor.activity()!=old.activity()||actor.activityStarted()!=old.activityStarted()
                        ||!Objects.equals(data,old.sceneKnight()==null?null:old.sceneKnight().activityData())))return true;
            }
            return false;
        }
        public Delta delta(Snapshot previous) {
            if(previous==null)return initial;
            return previous.identity==predecessor?common:calculate(previous);
        }
        private Delta calculate(Snapshot previous) {
            var added=new ArrayList<ActorState>();var updated=new ArrayList<ActorState>();
            var changed=new ArrayList<ActorState>();
            for(var actor:actors) {
                var old=previous.byId.get(actor.id());
                if(old==null||actor.player()&&(old.clientOid()!=actor.clientOid()||old.created()!=actor.created()))added.add(actor);else {
                    // Stock prediction mutates activity on its reference: transmit every actor.
                    if(actor.player()||!actor.equals(old))updated.add(actor);if(actor.player()&&(!old.name().equals(actor.name())||old.gameMaster()!=actor.gameMaster()||!Objects.equals(old.sceneKnight()==null?null:old.sceneKnight().progress(),actor.sceneKnight()==null?null:actor.sceneKnight().progress())))changed.add(actor);
                }
            }
            int[] removed=previous.byId.keySet().stream().filter(id->!byId.containsKey(id)).mapToInt(Integer::intValue).toArray();
            return new Delta(List.copyOf(added),List.copyOf(updated),removed,List.copyOf(changed),previous.byId);
        }
    }
    /** A null definition is the native ClearDialog operation, delivered on the session thread. */
    public record ConversationRequest(SceneConversation definition,ActorState source,int timestamp) { }
    public record MemoryRequest(org.tortodrone.character.KnightMemory operation,ActorState source,int timestamp) { }
    public record ObjectiveRequest(org.tortodrone.character.KnightObjective operation,ActorState source,int timestamp) { }
    public record StoryRequests(List<MemoryRequest> memories,List<ObjectiveRequest> objectives) { }
    /** Membership-owned native NextFloor intent; this is not a completion receipt or travel grant. */
    public record FloorAdvanceRequest(long sequence,ActorState source,int timestamp) { }
    public final class Membership implements AutoCloseable {
        private ActorState appearance;
        private final TownMovement movement;
        private KnightCombat combat;
        private org.tortodrone.character.WorldProfile combatProfile;
        private final Consumer<Snapshot> initialPublish;
        private Consumer<Snapshot> publish;
        private boolean entered, detached, arrived;
        private ScenePortals.Trigger portal;
        private int interactionTarget;
        private int lift,dropAt;
        private final SceneHealth health=new SceneHealth(instance.map().content());
        private float floorHeat;
        private boolean revivePending;
        private org.tortodrone.town.TownState.Sprite sprite;
        private org.tortodrone.town.TownState.Harness harness;
        private final int petId=nextPetId++;
        private final int[] nextSkill=new int[3];
        private int petSkill,knightSkill,petStarted,petRearm,petClear,lastSkillInput,petShield,petShieldMaximum;
        private String petShieldPower;
        private SceneContent.Spec petShieldDefense=new SceneContent.Spec(Map.of());
        private float petX,petY,petRotation;
        private boolean petFollow;
        private int auraOwner;
        private ActorState petActor(){
            if(instance.map().town()||!entered||sprite==null)return null;
            var state=new ScenePetState(appearance.id(),sprite,harness,petFollow,java.util.Arrays.stream(nextSkill).boxed().toList(),petShield,petShieldMaximum,petShieldPower);
            return new ActorState(petId,0,"",0,appearance.created(),petSkill==0||petFollow?movement.x():petX,petSkill==0||petFollow?movement.y():petY,petSkill==0?movement.rotation():petRotation,0,0,petSkill,petStarted,new org.tortodrone.character.StarterCatalog.Model("Sprite/Base",Map.of()),false,null,null,null,null,null,state);
        }
        private void advancePet(int stamp){
            if(sprite==null||instance.map().town())return;
            if(petSkill!=0&&stamp>=petClear){petSkill=0;petStarted=petClear;}
            else if(petSkill!=0&&stamp>=petRearm)petFollow=true;
            int input=(movement.inputFlags()&InputBits.SPRITE_SKILL_MASK)>>>InputBits.SPRITE_SKILL_SHIFT;
            if(input==0){lastSkillInput=0;return;}
            if(input==lastSkillInput||petSkill!=0||!health.alive())return;
            lastSkillInput=input;int skill=input-1;
            if(!org.tortodrone.town.SpriteRules.unlocked(sprite.level(),skill)||stamp<nextSkill[skill])return;
            var def=town.sprite(sprite.config());if(def==null||skill>=def.skills().size())return;
            var ability=def.skills().get(skill);String name=ability.attacks().get(sprite.ultimates().get(skill));
            String damage=town.harnessDamage(harness==null?"Iron Harness":harness.config());if(damage==null)damage="NORMAL";
            var attack=petFires.find(name+"|"+sprite.powers().get(skill*3+1)+"|"+sprite.powers().get(skill*3+2)+"|"+damage,292);if(attack==null)return;
            int power=sprite.powers().get(skill*3),recharge=(int)(ability.recharge().get(power)*1000*town.harnessRecharge(harness==null?"Iron Harness":harness.config()));
            if(!movement.spriteCommand(stamp))return;
            // Archive live observations write the USED skill's recharge to all three slots.
            recharge=Math.max(recharge,ability.minimum().get(power)*1000);
            java.util.Arrays.fill(nextSkill,stamp+recharge);
            knightSkill=skill;petSkill=input;petStarted=stamp;petClear=stamp+Math.max(1,((Number)attack.attack().arguments().getOrDefault("clear",1000)).intValue());petFollow=ability.follow();petX=movement.x();petY=movement.y();petRotation=movement.rotation();
            petRearm=stamp+((Number)attack.attack().arguments().getOrDefault("rearm",petClear-stamp)).intValue();
            int land=((Number)attack.attack().arguments().getOrDefault("land",0)).intValue();
            weapons.attack(petId,new KnightCombat.AttackStart(attack.weaponConfig(),292,stamp,land,petClear-stamp),attack);
        }

        private org.tortodrone.character.KnightProgress progress=org.tortodrone.character.KnightProgress.RECRUIT;
        private long pendingItem;private int itemLand;private SceneContent.Spec itemSpec;
        private final ArrayDeque<Long> usedItems=new ArrayDeque<>();
        public List<Long> takeUsedItems(){synchronized(SceneSimulation.this){check();var used=List.copyOf(usedItems);usedItems.clear();return used;}}
        public boolean useItem(long id,SceneContent.Spec spec){synchronized(SceneSimulation.this){
            check();if(!entered||portal!=null||!health.alive()||pendingItem!=0||lift!=0||usedItems.contains(id))return false;
            if(spec.text("kind","").equals("vial")){
                int stamp=SceneClock.now();if(!movement.lift(stamp))return false;lift=entities.vial(actor(),spec,stamp);
                if(lift==0){movement.resetInput(stamp);return false;}usedItems.add(id);health.curseItem(id,stamp);updateLife(stamp);return true;
            }
            if(!spec.text("kind","").equals("capsule"))return false;
            if(spec.integer("heal",0)<=0)try{if(!SceneWeapons.usable(FireCatalog.actionJson(spec.text("use-action",""))))return false;}
            catch(java.io.IOException invalid){return false;}
            int stamp=SceneClock.now();if(!movement.useItem(new org.tortodrone.character.StarterCatalog.Model(spec.text("config",""),Map.of()),stamp))return false;
            // The native use activity is accepted even when a Health Capsule has
            // nothing to heal. This attempt has no item action or consumption.
            if(spec.integer("heal",0)>0&&health.state().current()>=health.state().maximum())return true;
            health.curseItem(id,stamp);if(!health.alive()){usedItems.add(id);updateLife(stamp);return true;}
            pendingItem=id;itemLand=stamp+spec.integer("delay",0);itemSpec=spec;return true;
        }}
        /** Server-only notification after a committed item/economy operation. */
        public void effect(org.tortodrone.character.StarterCatalog.Model effect){synchronized(SceneSimulation.this){
            check();if(entered&&portal==null)weapons.effect(effect,actor(),SceneClock.now());
        }}
        public SceneHealth.State health(){synchronized(SceneSimulation.this){check();return health.state();}}
        public boolean arsenalZone(){synchronized(SceneSimulation.this){check();return entered&&portal==null&&arsenalZones.stream().anyMatch(e->e.spec().contains(e.x(),e.y(),e.rotation(),movement.x(),movement.y(),ScenePortals.PAWN_RADIUS));}}
        public boolean equipmentAllowed(){synchronized(SceneSimulation.this){check();return entered&&portal==null&&health.alive()&&(!instance.map().kind().equals("level")||arsenalZone());}}
        private List<org.tortodrone.character.WorldProfile.Item> carriedItems=List.of();
        public void inventory(org.tortodrone.character.WorldProfile profile){synchronized(SceneSimulation.this){
            check();carriedItems=profile.items();health.inventory(profile);entities.reconsiderPickups(appearance.id());
        }}
        /**
         * Native transient-pickup capacity: consumables are stackable into a single Item
         * per distinct type (wire captures show repeated EntryUpdated, never a new slot),
         * the HUD belt has exactly 4 quickbar slots (interface_quickbar.dat slot0..3), and
         * every capsule/vial Pickup actor carries failureEffect=Player/Full Inventory.
         * The per-type cap of 3 matches stock-server behavior (wiki-corroborated); it is
         * not present as a client constant. So: at most 3 of a held type, at most 4 types.
         */
        private boolean canPickup(SceneContent.Spec spec){
            if(!spec.flag("pickup-transient",false))return true;
            String item=spec.text("pickup-item","");
            if(spec.fields().containsKey("pickup-equip-slot")){
                int slot=spec.integer("pickup-equip-slot",0);
                var equipped=carriedItems.stream().filter(i->Objects.equals(i.equippedSlot(),slot)).findFirst().orElse(null);
                var stats=instance.map().content().stats();
                return equipped==null||equipped.id()<0&&stats.getOrDefault("gear."+item+".health-bonus",0f)>stats.getOrDefault("gear."+equipped.config()+".health-bonus",0f);
            }
            var counts=new HashMap<String,Integer>();
            for(var carried:carriedItems)if(carried.id()<0&&carried.equippedSlot()==null)counts.merge(carried.config(),carried.count(),Integer::sum);
            // Claims reserve capacity until the session commits or refuses them.
            for(var reserved:entities.pendingPickups(appearance.id()))if(reserved.temporary()&&reserved.equippedSlot()==null)counts.merge(reserved.item(),1,Integer::sum);
            return counts.getOrDefault(item,0)<3&&(counts.containsKey(item)||counts.size()<4);
        }
        public SceneStatus.State status(){synchronized(SceneSimulation.this){check();return health.status();}}
        public boolean heal(int amount){synchronized(SceneSimulation.this){check();return heal(amount,SceneClock.now());}}
        private boolean heal(int amount,int stamp){
            if(!health.heal(amount))return false;
            var capsule=instance.map().content().items().get("Pickup/Capsule/Health Capsule");
            if(capsule!=null)weapons.effect(capsule.model("use-effect"),actor(),stamp);
            return true;
        }
        public int takeInteraction(){synchronized(SceneSimulation.this){check();int target=interactionTarget;interactionTarget=0;return target;}}
        public List<SceneEntities.Pickup> takePickups(){synchronized(SceneSimulation.this){check();return entities.takePickups(appearance.id());}}
        public float floorHeat(){synchronized(SceneSimulation.this){check();return floorHeat;}}
        /** Consume a scene-owned claim and credit the floor atomically, before publication. */
        public boolean collectHeat(int entity){synchronized(SceneSimulation.this){
            check();if(!entered||portal!=null||!health.alive())return false;
            int amount=entities.heatClaim(appearance.id(),entity);if(amount<=0)return false;
            var recipients=members.values().stream().filter(m->m.entered&&!m.detached).toList();
            if(recipients.stream().anyMatch(m->m.floorHeat>1_000_000-amount))return false;
            if(!entities.activateHeat(appearance.id(),entity))return false;
            for(var member:recipients)member.floorHeat+=amount;
            return true;
        }}
        public String pickupReceipt(int entity){return pickupEpoch+":"+entity;}
        public void completePickup(int entity,boolean success){synchronized(SceneSimulation.this){entities.completePickup(appearance.id(),entity,success);}}
        private Membership(ActorState actor,Consumer<Snapshot> publish) {
            appearance=actor;this.initialPublish=this.publish=publish;
            health.spriteShield((damage,stamp,multiplier)->{
                var shield=auraOwner==0?this:members.get(auraOwner);
                if(shield==null||!shield.entered||!shield.health.alive()||shield.sprite==null||shield.petShield<=0)return SceneHealth.SpriteBlock.NONE;
                int amount=(int)Math.ceil(SceneDamage.amount(SceneDamage.select(damage,false),instance.map().content(),shield.petShieldDefense,multiplier));
                int remaining=shield.petShield;shield.petShield=Math.max(0,remaining-amount);
                return new SceneHealth.SpriteBlock(amount==0?0:Math.max(0,(amount-remaining)/(float)amount),remaining>amount);
            });
            movement=new TownMovement(instance.map().collision(),actor.x(),actor.y(),actor.rotation(),instance.map().portals());
            movement.resetInput(actor.created());
            movement.beginLoading(actor.created());
            movement.dynamicPenetration((x,y)->entities.penetration(x,y,actor.id()));
            movement.staticCollision(instance.map().staticCollision(),Math.round(instance.map().content().stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK)));
            movement.interaction(stamp->{var prompt=entities.interaction(movement.x(),movement.y(),movement.rotation(),appearance.id(),lift);
                if(prompt==null)return false;
                if(prompt.action().equals("Lift")){
                    if(lift!=0||!movement.lift(stamp))return false;
                    if(!entities.carry(prompt.actorId(),actor(),stamp)){movement.resetInput(stamp);return false;}
                    lift=prompt.actorId();return true;
                }
                entities.openArsenal(prompt.actorId(),stamp);
                movement.interactionDuration(entities.interactionDuration(prompt.actorId()));interactionTarget=prompt.actorId();return true;});
            movement.throwing(stamp->{if(lift==0)return false;
                boolean snap=entities.prepareDrop(lift,actor());
                boolean drop=!entities.canThrow(lift)||snap;
                movement.thrown(drop,stamp);if(drop)dropAt=stamp+DROP_FLIGHT_MILLIS;else{entities.throwLift(lift,actor(),stamp);lift=0;}return true;});
        }
        public void enter(){enter(initialPublish);}
        public void enter(Consumer<Snapshot> publication){synchronized(SceneSimulation.this){check();
            publish=Objects.requireNonNull(publication);
            if(!arrived){arrived=true;if(!instance.map().town())health.protectEntry(SceneClock.now());}
            entered=true;start();}}
        public void pause(){synchronized(SceneSimulation.this){if(detached)return;cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();entered=false;pendingConversation=null;pendingMemories.clear();pendingObjectives.clear();activeFloorAdvance=pendingFloorAdvance=null;weapons.cancel(appearance.id());weapons.cancel(petId);entities.releaseLifts(appearance.id(),SceneClock.now());lift=dropAt=0;pendingItem=0;itemSpec=null;pendingAnimated=null;movement.resetInput(SceneClock.now());stopIfIdle();}}
        public void resetInput(){synchronized(SceneSimulation.this){check();pendingAnimated=null;weapons.cancelAttacks(appearance.id());movement.resetInput(SceneClock.now());}}
        public boolean teleport(float x,float y,float rotation){synchronized(SceneSimulation.this){
            check();if(!entered||portal!=null||lift!=0||pendingItem!=0||!health.alive())return false;
            if(!movement.teleport(x,y,rotation,SceneClock.now()))return false;
            interactionTarget=0;weapons.cancelAttacks(appearance.id());return true;
        }}
        public boolean input(int stamp,float facing,float heading,int flags,int sceneTime,long now) {
            return input(stamp,facing,heading,flags,stamp,sceneTime,now);
        }
        public boolean input(int stamp,float facing,float heading,int flags,int actionChanged,int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();
                boolean loading=movement.activity()==TownMovement.LOADING;
                boolean accepted=entered&&portal==null&&movement.accept(stamp,facing,heading,flags,actionChanged,sceneTime,now);
                // Native DungeoneerLogic.finishedLoading arms INITIAL_INVULNERABILITY when the
                // client's first input ends the loading pose; town knights receive none.
                if(accepted&&loading&&!instance.map().town())health.protectLoading(SceneClock.now());
                return accepted;}
        }
        public void equipment(org.tortodrone.character.WorldProfile profile) {
            synchronized(SceneSimulation.this){check();
                int priorSprite=sprite==null?0:sprite.id();
                combatProfile=profile;carriedItems=profile.items();progress=profile.progress();sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);
                if(priorSprite!=(sprite==null?0:sprite.id())){
                    weapons.cancel(petId);petSkill=petShield=petShieldMaximum=0;petShieldPower=null;petFollow=false;
                    // Preserve the recharge deadlines: swapping sprites cannot bypass cooldowns.
                }
                harness=sprite==null?null:profile.town().harnesses().stream().filter(h->h.id()==sprite.harness()).findFirst().orElse(new org.tortodrone.town.TownState.Harness(0,"Iron Harness"));if(combat!=null){var before=combat.state().weapon();combat.equipment(profile);
                if(!Objects.equals(before,combat.state().weapon()))weapons.cancelAttacks(appearance.id());}health.equipment(profile,town);}
        }
        public void combat(WeaponCatalog catalog,org.tortodrone.character.WorldProfile profile) {
            combat(catalog,EquipmentCatalog.empty(),profile);
        }
        public void combat(WeaponCatalog catalog,EquipmentCatalog modifiers,org.tortodrone.character.WorldProfile profile) {
            synchronized(SceneSimulation.this){check();if(instance.map().town())throw new IllegalStateException("Town Knights are peaceful");
                combatProfile=profile;progress=profile.progress();combat=new KnightCombat(catalog,modifiers);combat.equipment(profile);health.equipment(profile,town);combat.attacks(attack->{health.breakCloak();if(attack.activity()>=KnightCombat.INCOMPLETE&&combat.state().weapon()!=null)health.curseItem(combat.state().weapon().id(),attack.started());if(health.alive())weapons.attack(appearance.id(),attack);});movement.combat(combat);}
        }
        public boolean sit(int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&portal==null&&movement.sit(sceneTime,now);}
        }
        public boolean emote(PlayerEmotes.Descriptor emote,int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&portal==null&&movement.emote(emote,sceneTime,now);}
        }
        public boolean canOpenBox(){synchronized(SceneSimulation.this){check();return entered&&portal==null&&health.alive()&&lift==0&&pendingItem==0&&movement.canOpenBox();}}
        /** Called only with committed rewards; no storage or network work under the scene lock. */
        public void openBox(List<org.tortodrone.character.StarterCatalog.Model> references){
            synchronized(SceneSimulation.this){check();if(!canOpenBox())return;
                weapons.cancelAttacks(appearance.id());movement.openBox(references,SceneClock.now());}
        }
        /** Animate an already-committed persistent consumable in this scene only. */
        public void confetti(String config){synchronized(SceneSimulation.this){check();if(!canOpenBox())return;
            weapons.cancelAttacks(appearance.id());movement.confetti(config,SceneClock.now());}}
        private String pendingAnimated;
        /** Animate a committed consumable; the scene owns its delayed effect and interruption. */
        public boolean animatedAction(String config){synchronized(SceneSimulation.this){
            check();if(!canOpenBox())return false;
            int stamp=SceneClock.now();
            if(!movement.animatedItem(config,stamp))return false;
            weapons.cancelAttacks(appearance.id());
            pendingAnimated=config;
            return true;
        }}
        private void updateLife(int stamp){
            if(health.alive()){if(movement.dead()){
                movement.resurrect(stamp);
                weapons.effect(new org.tortodrone.character.StarterCatalog.Model("Player/Emergency Rez",Map.of()),actor(),stamp);
                entities.reviveImpulse(actor(),stamp);
            }return;}
            cancelElevator(stamp);activeFloorAdvance=pendingFloorAdvance=null;
            if(movement.dead())return;
            weapons.cancelAttacks(appearance.id());
            entities.cancelClaims(appearance.id());
            if(lift!=0)entities.dropLift(lift,actor(),stamp);
            lift=dropAt=interactionTarget=0;pendingItem=0;itemSpec=null;pendingAnimated=null;
            movement.die(stamp);
            if((health.state().reviveFlags()&8)!=0)weapons.effect(new org.tortodrone.character.StarterCatalog.Model("Player/Pre Emergency Rez",Map.of()),actor(),stamp);
        }
        public void appearance(ActorState actor) {
            synchronized(SceneSimulation.this){check();if(actor.id()!=appearance.id()||actor.clientOid()!=appearance.clientOid())throw new IllegalArgumentException("Actor ownership changed");appearance=actor;}
        }
        private ActorState actor() {
            var a=appearance;
            return new ActorState(a.id(),a.clientOid(),a.name(),a.color(),a.created(),movement.x(),movement.y(),
                movement.rotation(),movement.direction(),movement.flags()|(!instance.map().town()?TIER_ENABLED_FLAG:0)
                    |(!instance.map().town()&&health.invulnerable(SceneClock.now())?SceneHealth.INVULNERABLE_FLAG:0)|(health.cloaked()?HIDDEN_FOR_DIALOG_FLAG:0),movement.activity(),movement.activityStarted(),a.config(),a.gameMaster(),movement.combatState(),null,null,
                    !health.alive()||movement.activity()==TownMovement.RESURRECT?null:entities.interaction(movement.x(),movement.y(),movement.rotation(),appearance.id(),lift),new SceneKnightState(movement.activityData(),lift,health.status(),progress,sprite,harness,instance.map().town()||sprite==null?0:petId,knightSkill,health.hitTime()));
        }
        private ConversationRequest pendingConversation;
        private final ArrayDeque<MemoryRequest> pendingMemories=new ArrayDeque<>();
        private final ArrayDeque<ObjectiveRequest> pendingObjectives=new ArrayDeque<>();
        public StoryRequests takeStory(){synchronized(SceneSimulation.this){check();var result=new StoryRequests(List.copyOf(pendingMemories),List.copyOf(pendingObjectives));pendingMemories.clear();pendingObjectives.clear();return result;}}
        public List<MemoryRequest> takeMemories(){synchronized(SceneSimulation.this){check();var result=List.copyOf(pendingMemories);pendingMemories.clear();return result;}}
        private FloorAdvanceRequest activeFloorAdvance,pendingFloorAdvance;
        private String elevatorEntry="";
        private Set<Integer> elevatorMembers=Set.of();
        private SceneEntities.ElevatorTrip elevatorTrip;
        private boolean elevatorSettled;
        private boolean elevatorPayoutSettled;
        /** Server-only exit grant after party/route preflight; no native request accepts a key. */
        public boolean authorizeElevator(String key){synchronized(SceneSimulation.this){
            return authorizeElevator(key,members.values().stream().filter(m->!m.detached&&m.entered&&m.health.alive()&&m.movement.activity()!=TownMovement.RESURRECT).map(m->m.appearance.id()).collect(java.util.stream.Collectors.toSet()));
        }}
        public boolean authorizeElevator(String key,Set<Integer> expectedMembers){synchronized(SceneSimulation.this){
            check();if(elevatorTrip!=null||activeFloorAdvance!=null||!instance.map().kind().equals("level")||!entities.elevatorEntry(key))return false;
            if(expectedMembers==null||expectedMembers.isEmpty()||expectedMembers.size()>PartyRegistry.CAPACITY||!expectedMembers.contains(appearance.id())||expectedMembers.stream().anyMatch(id->id==null||id<=0))return false;
            elevatorEntry=key;elevatorMembers=Set.copyOf(expectedMembers);return true;
        }}
        public void revokeElevator(){synchronized(SceneSimulation.this){check();cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();}}
        private void cancelElevator(int stamp){if(elevatorTrip!=null)entities.cancelElevator(elevatorTrip,stamp);}
        public boolean dropElevator(FloorAdvanceRequest request,boolean returning){synchronized(SceneSimulation.this){
            return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()&&entities.dropElevator(elevatorTrip,returning,SceneClock.now());
        }}
        public boolean elevatorReady(FloorAdvanceRequest request){synchronized(SceneSimulation.this){
            return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()&&entities.elevatorReady(elevatorTrip,SceneClock.now());
        }}
        public SceneElevator elevatorConfiguration(FloorAdvanceRequest request){synchronized(SceneSimulation.this){
            return ownsFloorAdvance(request)&&elevatorTrip!=null&&request.source()==elevatorTrip.source()?elevatorTrip.configuration():null;
        }}
        /** Native payout occurs at NextFloor, before the report and drop. Preserve the
         * owned request for those later phases, while forbidding a repeated transaction. */
        public <T> T settleSoloFloor(FloorAdvanceRequest request,RevivePayment<T> commit)throws java.io.IOException {synchronized(SceneSimulation.this){
            if(!ownsFloorAdvance(request)||elevatorTrip==null||request.source()!=elevatorTrip.source()||elevatorTrip.members().size()!=1||elevatorPayoutSettled||elevatorSettled)throw new java.io.IOException("Solo floor settlement is not current");
            T result=Objects.requireNonNull(commit).commit();elevatorPayoutSettled=true;return result;
        }}
        /** Pin the exact live ride through the SQLite transaction. No publication or
         * socket work is allowed in commit. A failure leaves the request retryable. */
        public <T> T settleSoloElevator(FloorAdvanceRequest request,RevivePayment<T> commit)throws java.io.IOException {synchronized(SceneSimulation.this){
            if(!elevatorReady(request)||elevatorTrip.members().size()!=1)throw new java.io.IOException("Solo elevator settlement is not ready");
            T result=Objects.requireNonNull(commit).commit();
            // Retire before releasing the scene lock: a competing callback cannot
            // reuse a successful settlement while the session prepares its travel.
            activeFloorAdvance=pendingFloorAdvance=null;elevatorSettled=true;elevatorEntry="";elevatorMembers=Set.of();
            return result;
        }}
        private long floorSequence;
        public FloorAdvanceRequest takeFloorAdvance(){synchronized(SceneSimulation.this){check();var request=pendingFloorAdvance;pendingFloorAdvance=null;return request;}}
        /** Revalidate the exact request after session-thread preflight; never trust a client token. */
        public boolean ownsFloorAdvance(FloorAdvanceRequest request){synchronized(SceneSimulation.this){
            return !detached&&members.get(appearance.id())==this&&entered&&health.alive()&&portal==null&&request!=null&&activeFloorAdvance==request;
        }}
        public boolean cancelFloorAdvance(FloorAdvanceRequest request){synchronized(SceneSimulation.this){
            if(!ownsFloorAdvance(request))return false;cancelElevator(SceneClock.now());activeFloorAdvance=pendingFloorAdvance=null;return true;
        }}
        public ConversationRequest takeConversation(){synchronized(SceneSimulation.this){check();var request=pendingConversation;pendingConversation=null;return request;}}
        public boolean conversationResponse(SceneConversation definition,String node,int choice,ActorState source){synchronized(SceneSimulation.this){
            check();return entered&&entities.conversationResponse(definition,node,choice,source,appearance.id(),SceneClock.now());
        }}
        public boolean conversationValid(SceneConversation definition,String node,int choice,ActorState source){synchronized(SceneSimulation.this){check();return entered&&health.alive()&&portal==null&&entities.conversationValid(definition,node,choice,source,appearance.id(),SceneClock.now());}}
        public boolean conversationStart(SceneConversation definition,ActorState source){synchronized(SceneSimulation.this){check();return entered&&health.alive()&&portal==null&&entities.conversationStart(definition,source,appearance.id(),SceneClock.now());}}
        private void check(){if(detached)throw new IllegalStateException("Scene membership is closed");}
        @Override public void close(){synchronized(SceneSimulation.this){if(detached)return;cancelElevator(SceneClock.now());elevatorEntry="";elevatorMembers=Set.of();detached=true;pendingConversation=null;pendingMemories.clear();pendingObjectives.clear();activeFloorAdvance=pendingFloorAdvance=null;weapons.cancel(appearance.id());weapons.cancel(petId);entities.cancelClaims(appearance.id());entities.releaseLifts(appearance.id(),SceneClock.now());members.remove(appearance.id(),this);stopIfIdle();}}
    }
    private final SceneRegistry.Instance instance;
    private final Map<Integer,Membership> members=new LinkedHashMap<>();
    /** Battle-sprite actors use this fixed id base so their ids never collide with
     * client-visible pawn or entity oid ranges observed on the wire; server-chosen. */
    private static final int PET_ACTOR_ID_BASE = 1_700_000_000;
    /** Carried actors released by a drop land after the 400 ms tossed-flight window. */
    private static final int DROP_FLIGHT_MILLIS = 400;
    private int nextPetId=PET_ACTOR_ID_BASE;
    private FireCatalog petFires=FireCatalog.empty();
    public synchronized void petFires(FireCatalog catalog){petFires=Objects.requireNonNull(catalog);}
    private final SceneWeapons weapons;
    private final SceneEntities entities;
    private final ArrayDeque<SceneCombatEvent> combatEvents=new ArrayDeque<>();
    private long combatSequence;
    private void feedback(SceneCombatEvent event){
        if(combatEvents.size()>=1024)combatEvents.removeFirst();
        combatEvents.addLast(new SceneCombatEvent(++combatSequence,event.timestamp(),event.actor(),event.amount(),event.source(),event.performance()));
    }
    public synchronized void fires(FireCatalog catalog){weapons.catalog(catalog);}
    private ScheduledExecutorService timer;
    private Throwable tickFailure;
    private Snapshot previous;
    private long tickCount;
    private final List<SceneContent.Entry> arsenalZones;
    public synchronized boolean placement(float x,float y){
        var map=instance.map();
        return Float.isFinite(x)&&Float.isFinite(y)&&map.collision().walkable(x,y)
            &&(map.staticCollision().isEmpty()||map.staticCollision().floorSupported(x,y)
                &&!map.staticCollision().penetration(Math.round(map.content().stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK)),x,y,ScenePortals.PAWN_RADIUS).present())
            &&!entities.penetration(x,y,0).present()
            &&map.portals().triggers().stream().noneMatch(t->t.intersects(x,y));
    }
    public SceneSimulation(SceneRegistry.Instance instance){this.instance=Objects.requireNonNull(instance);
        arsenalZones=instance.map().content().entries().stream().filter(e->e.spec().flag("arsenal-zone",false)).toList();
        entities=new SceneEntities(instance.map().content(),instance.map().collision(),instance.map().staticCollision());
        weapons=new SceneWeapons(instance.map().collision());weapons.staticCollision(instance.map().staticCollision());weapons.targets(entities);
        var gearTags=GearStats.damageTags(instance.map().content().stats());
        weapons.damageModifiers((owner,attack)->{var member=members.get(owner);
            return member==null||member.combat==null||attack.activity()<KnightCombat.INCOMPLETE?GearStats.Damage.NONE:
                GearStats.damage(instance.map().content().stats(),member.combatProfile,member.combat.state().weapon(),town,gearTags);});
        entities.feedback(this::feedback);
        entities.playerActive(id->{var member=members.get(id);return member!=null&&member.entered&&member.health.alive()&&member.movement.activity()!=TownMovement.RESURRECT;});
        entities.pickupEligible((id,spec)->{var member=members.get(id);return member!=null&&member.canPickup(spec);});
        entities.playerTargetable(id->{var member=members.get(id);return member!=null&&member.health.status().modifiers().canBeTargeted();});
        entities.playerImpulse((id,impulse)->{var member=members.get(id);if(member!=null&&member.entered&&member.health.alive()&&member.combat!=null)member.combat.impulse(impulse);});
        entities.playerHits(hit->{var member=members.get(hit.actor());if(member!=null&&member.entered){
            var result=member.health.hit(hit.damage(),hit.timestamp(),hit.multiplier(),member.combat);
            if(result.react()&&member.health.alive()){
                weapons.cancelAttacks(hit.actor());
                if(member.lift!=0){entities.dropLift(member.lift,member.actor(),hit.timestamp());member.lift=member.dropAt=0;}
                member.movement.hurt(hit.timestamp());
            }
            member.updateLife(hit.timestamp());
            member.movement.status(member.health.status().modifiers());
            if(result.amount()>0)feedback(new SceneCombatEvent(0,hit.timestamp(),hit.actor(),result.feedback(),hit.source(),"NORMAL"));
        }});
        entities.conversations((definition,source,stamp,target)->{
            for(var member:members.values())if(member.entered&&(target==0||target==member.appearance.id()))member.pendingConversation=new ConversationRequest(definition,source,stamp);
            return true;
        });
        entities.dialogClears((target,stamp)->{
            for(var member:members.values())if(member.entered&&(target==0||target==member.appearance.id()))member.pendingConversation=new ConversationRequest(null,null,stamp);
            return true;
        });
        entities.floorAdvances((player,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||member.portal!=null||!instance.map().kind().equals("level"))return false;
            if(member.activeFloorAdvance==null){
                var request=new FloorAdvanceRequest(++member.floorSequence,source,stamp);
                member.activeFloorAdvance=member.pendingFloorAdvance=request;
            }
            return true;
        });
        entities.elevators(new SceneEntities.ElevatorControl(){
            private boolean allowed(Membership member,SceneEntities.ElevatorTrip trip){
                return member!=null&&!member.detached&&member.entered&&member.health.alive()&&member.portal==null
                    &&member.movement.activity()!=TownMovement.RESURRECT&&(member.elevatorSettled&&member.elevatorTrip==trip||member.elevatorEntry.equals(trip.source().entity().key())&&member.elevatorMembers.equals(new HashSet<>(trip.members())));
            }
            @Override public boolean begin(SceneEntities.ElevatorTrip trip,int stamp){
                if(!instance.map().kind().equals("level"))return false;
                for(int id:trip.members()){
                    var member=members.get(id);if(!allowed(member,trip)||member.elevatorTrip!=null||member.movement.inTransit()||member.activeFloorAdvance!=null)return false;
                }
                for(int id:trip.members()){
                    var member=members.get(id);member.elevatorTrip=trip;member.elevatorSettled=member.elevatorPayoutSettled=false;
                    if(!member.movement.transit(true,stamp)){cancel(trip,stamp);return false;}
                    weapons.cancelAttacks(id);
                    if(member.lift!=0){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                }
                return true;
            }
            @Override public boolean valid(SceneEntities.ElevatorTrip trip){
                var living=members.values().stream().filter(m->!m.detached&&m.entered&&m.health.alive()&&m.movement.activity()!=TownMovement.RESURRECT).map(m->m.appearance.id()).collect(java.util.stream.Collectors.toSet());
                return living.equals(new HashSet<>(trip.members()))&&trip.members().stream().allMatch(id->{var member=members.get(id);return allowed(member,trip)&&member.elevatorTrip==trip&&member.movement.inTransit();});
            }
            @Override public boolean advance(SceneEntities.ElevatorTrip trip,int stamp){
                if(!valid(trip)||trip.members().stream().anyMatch(id->members.get(id).activeFloorAdvance!=null))return false;
                for(int id:trip.members()){
                    var member=members.get(id);var request=new FloorAdvanceRequest(++member.floorSequence,trip.source(),stamp);
                    member.activeFloorAdvance=member.pendingFloorAdvance=request;
                }
                return true;
            }
            @Override public void cancel(SceneEntities.ElevatorTrip trip,int stamp){
                for(int id:trip.members()){
                    var member=members.get(id);if(member==null||member.elevatorTrip!=trip)continue;
                    member.elevatorTrip=null;member.elevatorSettled=member.elevatorPayoutSettled=false;member.movement.transit(false,stamp);
                    if(member.activeFloorAdvance!=null&&member.activeFloorAdvance.source()==trip.source())member.activeFloorAdvance=member.pendingFloorAdvance=null;
                }
            }
        });
        entities.memories((player,operation,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||member.portal!=null||member.pendingMemories.size()>=256)return false;
            member.pendingMemories.addLast(new MemoryRequest(operation,source,stamp));return true;
        });
        entities.objectives((player,operation,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||member.portal!=null||member.pendingObjectives.size()>=256)return false;
            member.pendingObjectives.addLast(new ObjectiveRequest(operation,source,stamp));return true;
        });
        entities.effects(weapons::effect);
        entities.pawnStatuses((id,operation,stamp)->{
            var member=members.get(id);if(member==null||!member.entered)return false;
            if(operation.clear()){member.health.clearStatus(operation.clearMask());return true;}
            return member.health.applyCondition(operation.condition(),operation.data(),stamp);
        });
        entities.playerHeal((actor,amount)->{var member=members.get(actor);return member!=null&&member.entered&&member.heal(amount,SceneClock.now());});
        weapons.petActions(action->{var member=members.get(action.owner());if(member==null||!member.entered)return;
            switch(action.kind()){
                case HEAL->member.health.heal(((Number)action.config().arguments().getOrDefault("current",0)).intValue());
                case CLEAR_STATUS->member.health.clearStatus(((Number)action.config().arguments().getOrDefault("mask",0)).intValue());
                case APPLY_STATUS->{if(action.config()!=null&&action.config().arguments().get("reference") instanceof org.tortodrone.character.StarterCatalog.Model ref){
                    Object data=action.config().arguments().get("data");float radius=((Number)action.config().arguments().getOrDefault("radius",0)).floatValue();
                    for(var target:members.values())if(target.entered&&(target==member||radius>0&&Math.hypot(target.movement.x()-member.movement.x(),target.movement.y()-member.movement.y())<=radius)){
                        target.health.condition(ref,data,action.timestamp());if(data instanceof FireCatalog.DataValue value&&value.type().endsWith("$SpriteShield"))target.auraOwner=member.appearance.id();
                    }
                }}
                case START_SPRITE_SHIELD->{
                    member.petShield=member.petShieldMaximum=((Number)action.config().arguments().getOrDefault("health",0)).intValue();member.petShieldPower=(String)action.config().arguments().get("power");
                    var defenses=new HashMap<String,String>();Object defense=action.config().arguments().get("defense");
                    for(String type:List.of("normal","piercing","elemental","shadow")){
                        Object scale=SceneDamage.field(defense,type);
                        if(scale instanceof org.tortodrone.character.StarterCatalog.Model model)defenses.put("defense."+type,Float.toString(instance.map().content().scales().getOrDefault(model.name(),0f)));
                        else if(scale instanceof Number n)defenses.put("defense."+type,n.toString());
                    }
                    member.petShieldDefense=new SceneContent.Spec(defenses);
                }
                case END_SPRITE_SHIELD->{member.petShield=member.petShieldMaximum=0;member.petShieldPower=null;}
                default->{}
            }
        });
        entities.attacks((actor,action)->weapons.fire(action,actor,SceneClock.now()));}
    public record InteractionSource(ActorState actor,SceneContent.Spec specification) { }
    public synchronized InteractionSource interactionSource(int id){return new InteractionSource(entities.actor(id),entities.specification(id));}
    public synchronized ActorState station(String kind){return entities.station(kind);}
    public synchronized Membership join(ActorState actor,Consumer<Snapshot> publish) {
        if(members.containsKey(actor.id()))throw new IllegalStateException("Actor is already in this scene");
        if(members.values().stream().anyMatch(m->m.appearance.clientOid()==actor.clientOid()))throw new IllegalStateException("Connection already owns a scene actor");
        var membership=new Membership(actor,Objects.requireNonNull(publish));members.put(actor.id(),membership);return membership;
    }
    /** Selection/subscription sees the same scene-owned entities without advancing movement. */
    public synchronized Snapshot snapshot(){return capture(SceneClock.now(),null);}
    /** Read one existing membership without ticking the scene or creating an instance. */
    public record Companion(int selectedSprite,ScenePetState deployed,int observedAt) { }
    public synchronized Companion companion(int knight) {
        var member=members.get(knight);
        if(member==null||member.detached)return null;
        var actor=member.petActor();
        return new Companion(member.sprite==null?0:member.sprite.id(),actor==null?null:actor.pet(),SceneClock.now());
    }
    public synchronized int playerCount(){return members.size();}
    public synchronized ActorState defaultEntrance(){return entities.defaultEntrance();}
    public synchronized long tickCount(){return tickCount;}
    /** Scheduled executors otherwise hide exceptions in their cancelled future. */
    public synchronized Throwable failure(){return tickFailure;}
    private Snapshot capture(int stamp,Snapshot baseline) {
        var portals=new HashMap<Integer,ScenePortals.Trigger>();
        for(var member:members.values())if(member.portal!=null)portals.put(member.appearance.id(),member.portal);
        var actors=new ArrayList<ActorState>();members.values().forEach(m->actors.add(m.actor()));for(var member:members.values()){var pet=member.petActor();if(pet!=null)actors.add(pet);}actors.addAll(entities.actors());actors.addAll(weapons.actors());
        var health=new HashMap<Integer,SceneHealth.State>();members.forEach((id,member)->health.put(id,member.health.state()));
        return new Snapshot(stamp,actors,weapons.effects(),List.copyOf(combatEvents),portals,health,baseline);
    }
    private void start() {
        if(timer!=null)return;
        tickFailure=null;
        timer=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"Scene-"+instance.oid());thread.setDaemon(true);return thread;});
        // Never burst missed ticks. Integration uses actual elapsed scene time.
        timer.scheduleWithFixedDelay(()->{
            try{tick();}
            catch(RuntimeException|Error failure){
                synchronized(this){tickFailure=failure;}
                System.getLogger(SceneSimulation.class.getName()).log(System.Logger.Level.ERROR,"Scene simulation stopped: template="+instance.map().id()+", instance="+instance.oid(),failure);
                throw failure;
            }
        },50,50,TimeUnit.MILLISECONDS);
    }
    private void stopIfIdle() {
        if(members.values().stream().anyMatch(m->m.entered))return;
        if(timer!=null){timer.shutdownNow();timer=null;}
        previous=null;
    }
    private void tick() {
        Snapshot publication;List<Consumer<Snapshot>> recipients;
        synchronized(this) {
            if(timer==null)return;
            int stamp=SceneClock.now();long now=System.nanoTime()/1_000_000L;
            if(previous!=null&&stamp-previous.timestamp()<=0)return;
            // All authoritative entities advance before capture. Future AI/combat/dynamic
            // collision stages run here once per instance, independent of observer count.
            for(var member:members.values())if(member.entered&&member.portal==null) {
                if(member.auraOwner!=0&&member.health.status().conditions().stream().noneMatch(c->c.name().contains("Angelic Aura")))member.auraOwner=0;
                member.health.advance(stamp,previous==null?0:stamp-previous.timestamp(),(member.movement.flags()&TownMovement.PUBLISHED_MOVING_FLAG)!=0);
                member.updateLife(stamp);
                member.movement.status(member.health.status().modifiers());
                if(member.health.status().modifiers().shocked()&&member.lift!=0){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                if(member.pendingItem!=0){
                    if(member.movement.activity()!=TownMovement.USE_ITEM){member.pendingItem=0;member.itemSpec=null;}
                    else if(stamp>=member.itemLand){
                        boolean used;
                        if(member.itemSpec.integer("heal",0)>0){
                            used=member.health.heal(member.itemSpec.integer("heal",0));
                            if(used)weapons.effect(member.itemSpec.model("use-effect"),member.actor(),member.itemLand);
                        }else try{used=weapons.fire(FireCatalog.actionJson(member.itemSpec.text("use-action","")),member.actor(),member.itemLand);}
                        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared item action",invalid);}
                        if(used)member.usedItems.add(member.pendingItem);
                        member.pendingItem=0;member.itemSpec=null;
                    }
                }
                // Resolve the delayed action before natural animation completion on a late tick.
                member.movement.preparePublication(stamp);
                if(member.pendingAnimated!=null){
                    String config=member.pendingAnimated;
                    if(member.movement.activity()!=TownMovement.ANIMATED_ITEM)member.pendingAnimated=null;
                    else if(stamp-member.movement.activityStarted()>=org.tortodrone.character.AnimatedItems.land(config)){
                        for(var effect:org.tortodrone.character.AnimatedItems.effects(config))weapons.effect(effect,member.actor(),stamp);
                        member.pendingAnimated=null;
                    }
                }
                member.movement.advance(stamp,now);
                if(member.dropAt>0&&stamp>=member.dropAt){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                member.portal=member.movement.portal();member.advancePet(stamp);
            }
            var players=members.values().stream().filter(m->m.entered).map(Membership::actor).toList();
            for(var member:members.values())if(member.entered)for(var pulse:member.health.takePulses())entities.statusPulse(member.appearance.id(),pulse,member.movement.x(),member.movement.y(),stamp);
            for(var member:members.values())if(member.entered)for(var action:member.health.takeActions())weapons.fire(action,member.actor(),stamp);
            entities.advance(stamp,players);var sources=new ArrayList<>(players);for(var member:members.values()){var pet=member.petActor();if(pet!=null)sources.add(pet);}weapons.advance(stamp,sources);
            combatEvents.removeIf(event->stamp-event.timestamp()>5000);
            publication=capture(stamp,previous);previous=publication;tickCount++;
            recipients=members.values().stream().filter(m->m.entered).map(m->m.publish).toList();
        }
        // No simulation lock is held across callbacks, including detach/travel races.
        for(var recipient:recipients)recipient.accept(publication);
    }
}
