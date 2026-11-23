package org.tortodrone.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.MovementRules;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.SceneStatus;

/** Native scene-actor records: occupants, knights, pets, projectiles, prepared actors,
 *  deltas and damage/effect entries. Kept out of the shared {@link WireWriter} base so a
 *  non-scene codec cannot grow a dependency on the scene record model. */
final class SceneActorWire extends WireWriter {
    private static final List<String> DAMAGE_PERFORMANCE=List.of(
        "WEAK","NORMAL","STRONG","WEAK_SMALL","WEAK_MEDIUM","WEAK_LARGE",
        "NORMAL_SMALL","NORMAL_MEDIUM","NORMAL_LARGE","STRONG_SMALL","STRONG_MEDIUM","STRONG_LARGE");

    private final ItemWire items;
    SceneActorWire(WireContext context,ItemCatalog catalog){super(context,catalog);items=new ItemWire(context,catalog);}

    protected void sceneDeltaHeader(DataOutputStream out,int oid,int sceneOid,int acknowledge,short ping,int reference,int timestamp,short elapsed)throws IOException {
        type(out,PREFIX+"EventNotification");out.writeShort(-1);type(out,"com.threerings.tudey.dobj.SceneDeltaEvent");out.writeInt(oid);
        out.writeInt(sceneOid);out.writeInt(acknowledge);out.writeShort(ping);out.writeInt(reference);out.writeInt(timestamp);out.writeShort(elapsed);
    }

    protected void sceneDeltaActors(DataOutputStream out,int observer,List<ActorState> added,List<ActorState> updated,int[] removed,Map<Integer,ActorState> baseline,boolean interior,List<SceneEffect> effects,List<SceneCombatEvent> combat)throws IOException {
        if(added.isEmpty())out.writeShort(0);else{type(out,WireTypes.ACTOR_ARRAY);out.writeInt(added.size());for(var a:added)actor(out,a,interior,observer);}
        if(updated.isEmpty())out.writeShort(0);else{type(out,"[Lcom.threerings.tudey.dobj.ActorDelta;");out.writeInt(updated.size());out.writeShort((updated.size()+7)/8);for(int i=0;i<updated.size();i+=8)out.writeByte((1<<Math.min(8,updated.size()-i))-1);for(var a:updated)actorDelta(out,a,baseline.get(a.id()),interior,observer);}
        out.writeBoolean(true);out.writeInt(removed.length);for(int id:removed)out.writeInt(id);
        if(effects.isEmpty()&&combat.isEmpty())out.writeShort(0);else {type(out,"[Lcom.threerings.tudey.data.effect.Effect;");out.writeInt(effects.size()+combat.size());for(var effect:effects)effect(out,effect);for(var damage:combat)effect(out,damage);}
    }

    protected void occupant(DataOutputStream out,ActorState actor)throws IOException {
        type(out,"com.threerings.projectx.data.ProjectXOccupantInfo");out.writeBoolean(true);out.writeInt(actor.clientOid());name(out,actor.name());out.writeByte(0);out.writeInt(actor.id());
        var progress=actor.sceneKnight()==null?org.tortodrone.core.character.KnightProgress.RECRUIT:actor.sceneKnight().progress();
        type(out,"com.threerings.projectx.data.PlayerObject$Type");out.writeByte(actor.gameMaster()?2:0);out.writeInt(actor.color());out.writeShort(0);type(out,"com.threerings.projectx.data.ProjectXCodes$PrestigeRank");out.writeByte(progress.prestigeRank());out.writeInt(progress.rank());
    }

    protected void actorSprite(DataOutputStream out,ActorState actor)throws IOException{
        var state=actor.sceneKnight();if(state==null||state.sprite()==null)out.writeShort(0);else sprite(out,actor.id(),state.sprite(),state.harness());
    }

    protected Object activityData(ActorState actor,int observer) {
        // Appearance references are shared animation data. Inventory IDs and the reward
        // callback still belong exclusively to the authenticated opening connection.
        return actor.sceneKnight()==null?null:actor.sceneKnight().activityData();
    }

    protected void actor(DataOutputStream out,ActorState a,boolean interior,int observer)throws IOException {
        if(a.pet()!=null){petActor(out,a);return;}
        if(a.projectile()!=null){projectile(out,a);return;}
        if(a.entity()!=null){sceneEntity(out,a);return;}
        type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(MovementRules.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());
        value(out,activityData(a,observer));interaction(out,a.interaction());string(out,null);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());actorSprite(out,a);
        if(interior) {
            var combat=a.combat();
            weapon(out,a.id(),combat);string(out,combat==null?null:combat.shield());string(out,combat==null?null:combat.shieldCostume());conditions(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
            out.writeInt(0);out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().hitTime());
            out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
            out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteActor());out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteAttack());out.writeInt(combat==null?0:combat.nextDodge());
            statusModifiers(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }

    protected void actorDelta(DataOutputStream out,ActorState a,ActorState baseline,boolean interior,int observer)throws IOException {
        // ActorDelta is final: its typed array omits a per-element type header.
        if(a.pet()!=null){petDelta(out,a);return;}
        if(a.projectile()!=null){projectileDelta(out,a);return;}
        if(a.entity()!=null){sceneEntityDelta(out,a);return;}
        out.writeInt(a.id());type(out,interior?"com.threerings.projectx.dungeon.data.actor.Dungeoneer":"com.threerings.projectx.data.actor.Knight");
        boolean configChanged = baseline == null || !a.config().equals(baseline.config());
        // Preserve the reference's config object on ordinary movement; update it for actual gear changes.
        // The stock controller predicts directly on its first snapshot. Inventory
        // corrections below must not leak local prediction into later baselines.
        boolean combatChanged=interior&&(a.combat()!=null||baseline!=null&&baseline.combat()!=null);
        boolean sceneData=a.sceneKnight()!=null||baseline!=null&&baseline.sceneKnight()!=null;
        boolean spriteChanged=baseline==null||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().sprite(),baseline.sceneKnight()==null?null:baseline.sceneKnight().sprite())||!java.util.Objects.equals(a.sceneKnight()==null?null:a.sceneKnight().harness(),baseline.sceneKnight()==null?null:baseline.sceneKnight().harness());
        // The stock advancer owns an emote's clock after its initial publication.
        // Passive movement/status updates must not reapply its activity to a locally
        // advanced baseline. Real activity transitions still replace both fields.
        boolean stableEmote=baseline!=null&&a.activity()>=PlayerEmotes.FIRST_ACTIVITY
            &&a.activity()<PlayerEmotes.FIRST_ACTIVITY+PlayerEmotes.ANIMATED_COUNT
            &&a.activity()==baseline.activity()&&a.activityStarted()==baseline.activityStarted();
        int second=a.interaction()!=null||baseline!=null&&baseline.interaction()!=null?0x1d:1;
        out.writeByte((configChanged ? 0xdd : 0xdc)&(stableEmote?0x7f:0xff));out.writeByte((second|(sceneData?0x42:0)|(spriteChanged?0x80:0))&(stableEmote?0xfe:0xff));
        // Mutable Dungeoneer fields: weapon 16, shields 17/18, health 22/23,
        // impulse 24, hit time 26, chain 27/28, dodge deadline 31 and modifiers 33/34. Id/created are DeltaFinal.
        if(interior){out.writeByte((combatChanged?0xf7:0)|(sceneData?8:0));out.writeByte((combatChanged?0x99:0)|(sceneData?0x64:0));out.writeByte((combatChanged?6:0)|(sceneData?1:0));}
        if (configChanged) value(out,a.config());
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());
        if(!stableEmote){out.writeInt(a.activity());out.writeInt(a.activityStarted());}
        if(sceneData)value(out,activityData(a,observer));
        if(a.interaction()!=null||baseline!=null&&baseline.interaction()!=null)interaction(out,a.interaction());
        if(sceneData)out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().lift());
        if(spriteChanged)actorSprite(out,a);
        if(combatChanged) {
            var combat=a.combat();weapon(out,a.id(),combat);value(out,combat==null?null:combat.shield());value(out,combat==null?null:combat.shieldCostume());
        }
        if(interior&&sceneData)conditions(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.State.NORMAL:a.sceneKnight().status());
        if(combatChanged) {
            var combat=a.combat();
            out.writeInt(combat==null?0:combat.shieldRegenStart());out.writeInt(combat==null?-300000:combat.shieldHit());out.writeFloat(combat==null?0:combat.shieldHealth());out.writeInt(combat==null?0:combat.shieldMaximum());impulse(out,combat==null?null:combat.impulse());
        }
        if(interior&&sceneData)out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().hitTime());
        if(combatChanged){var combat=a.combat();out.writeInt(combat==null?0:combat.chainCount());out.writeInt(combat==null?0:combat.chainReset());
        }
        if(interior&&sceneData){out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteActor());out.writeInt(a.sceneKnight()==null?0:a.sceneKnight().spriteAttack());}
        if(combatChanged){var combat=a.combat();out.writeInt(combat==null?0:combat.nextDodge());
        }
        if(interior&&sceneData)statusModifiers(out,a.sceneKnight()==null?org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL:a.sceneKnight().status().modifiers());
        if(combatChanged) {
            var combat=a.combat();
            equipmentModifiers(out,combat==null?null:combat.equipmentModifiers());equipmentModifiers(out,combat==null?null:combat.weaponModifiers());
        }
    }

    protected void equipmentModifiers(DataOutputStream out,org.tortodrone.core.world.EquipmentCatalog.Modifiers modifiers)throws IOException {
        var value=modifiers==null?org.tortodrone.core.world.EquipmentCatalog.Modifiers.ZERO:modifiers;
        type(out,"com.threerings.projectx.item.data.ImmutableEquipmentModifiers");
        out.writeFloat(value.chargeTime());out.writeFloat(value.speed());out.writeFloat(value.attackSpeed());out.writeFloat(value.defendingSpeed());
    }

    protected void interaction(DataOutputStream out,org.tortodrone.core.world.InteractionState interaction)throws IOException {
        if(interaction==null){out.writeShort(0);out.writeShort(0);out.writeShort(0);return;}
        if(!java.util.Set.of("Interact","Talk","Shop","Lift","Resurrect","UnlockGold").contains(interaction.action()))throw new IOException("Unsupported interaction action");
        type(out,"com.threerings.projectx.config.ActionDesc$"+interaction.action());
        if(interaction.entryKey()!=null){type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,Integer.parseInt(interaction.entryKey()));}
        else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(interaction.actorId());}
        value(out,interaction.description());
    }

    protected void petActor(DataOutputStream out,ActorState a)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");value(out,a.config());out.writeInt(a.id());out.writeInt(a.created());out.writeInt(Integer.MAX_VALUE);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(MovementRules.MAX_STEP);out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);
        petFields(out,a,false);
    }

    protected void petFields(DataOutputStream out,ActorState a,boolean delta)throws IOException {
        var pet=a.pet();sprite(out,pet.knight(),pet.sprite(),pet.harness());out.writeInt(pet.knight());out.writeBoolean(pet.follow());if(delta){type(out,"[I");out.writeInt(pet.nextSkill().size());for(int next:pet.nextSkill())out.writeInt(next);}else primitiveInts(out,pet.nextSkill().stream().mapToInt(Integer::intValue).toArray());
        out.writeInt(pet.shieldHealth());out.writeInt(pet.shieldMaximum());if(delta)value(out,pet.shieldPower());else string(out,pet.shieldPower());
    }

    protected void petDelta(DataOutputStream out,ActorState a)throws IOException {
        out.writeInt(a.id());type(out,"com.threerings.projectx.dungeon.data.actor.SpriteActor");
        // Actor/Active/Agent mutable field order, followed by the seven SpriteActor fields.
        int mask=0x1ffdc;out.writeByte(mask);out.writeByte(mask>>>8);out.writeByte(mask>>>16);
        vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(0);petFields(out,a,true);
    }

    protected void projectile(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        type(out,p.nativeType().equals("ACTOR")?WireTypes.ACTOR:"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
        value(out,p.config());out.writeInt(actor.id());out.writeInt(p.created());out.writeInt(p.destroyed());
        vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(p.orbit()!=null){var orbit=p.orbit();out.writeFloat(orbit.radius());out.writeInt(orbit.followId());
            vector(out,p.originX(),p.originY());vector(out,orbit.relativeX(),orbit.relativeY());out.writeInt(orbit.collided());
            if(!p.nativeType().equals("ORBITAL")){out.writeFloat(orbit.originalRotation());out.writeInt(p.ownerOid());out.writeInt(orbit.homeTargetId());out.writeInt(0);out.writeInt(p.faction());}return;}
        if(p.nativeType().equals("ACTOR"))return;
        if(p.nativeType().equals("LASER")){out.writeBoolean(!p.fizzle());vector(out,actor.x()+(float)Math.cos(actor.rotation())*p.speed(),actor.y()+(float)Math.sin(actor.rotation())*p.speed());return;}
        if(bullet){out.writeFloat(p.maxStep());out.writeFloat(actor.direction());vector(out,p.originX(),p.originY());out.writeFloat(p.z());}
        out.writeInt(p.ownerOid());out.writeInt(0);if(bullet)out.writeInt(p.faction());else out.writeFloat(p.z());
    }

    protected void sceneEntity(DataOutputStream out,ActorState a)throws IOException {
        var e=a.entity();type(out,e.nativeClass());value(out,a.config());out.writeInt(a.id());
        out.writeInt(a.created());out.writeInt(e.destroyed());vector(out,a.x(),a.y());
        out.writeFloat(a.rotation());out.writeInt(a.flags());
        if(e.active()){
            out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeInt(a.activity());
            out.writeInt(a.activityStarted());out.writeFloat(e.speed());
            if(e.kind().equals("Monster")){
                out.writeShort(0);out.writeInt(e.affectedType());out.writeInt(e.affectedTime());conditions(out,e.status());
                out.writeInt(0);out.writeInt(0);out.writeFloat(e.health());out.writeShort(0);
                statusModifiers(out,e.status().modifiers());
            }
        }else switch(e.kind()){
            case "Generator" -> out.writeInt(e.count());
            case "TerritoryProp" -> out.writeByte(e.state());
            case "Door" -> out.writeInt(e.stateEntered());
            case "Switch","EntryState" -> {value(out,sceneEntryKey(e.key()));out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());primitiveInts(out,e.lift()==null?null:e.lift().excluded());}
            case "Lift" -> {out.writeFloat(e.maxStep());out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());}
            case "Pickup" -> {primitiveInts(out,null);primitiveInts(out,e.pickup()==null?null:new int[]{e.pickup().playerOid(),e.pickup().activated()});}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Bomb" -> {out.writeInt(0);out.writeInt(0);out.writeFloat(org.tortodrone.core.world.ProjectileState.UNSPECIFIED_Z);}
            case "Actor" -> { }
            default -> throw new IOException("Unsupported prepared actor type");
        }
    }

    protected Object sceneEntryKey(String key)throws IOException {
        if(key.matches("spawn:[0-9]+"))return null; // Native dynamically spawned switches have no scene-entry key.
        try{return Integer.valueOf(key.split("/",2)[0]);}catch(NumberFormatException invalid){throw new IOException("Unsupported switch entry key: "+key,invalid);}
    }

    protected void sceneEntityDelta(DataOutputStream out,ActorState a)throws IOException {
        var e=a.entity();out.writeInt(a.id());type(out,e.nativeClass());
        // DeltaFinal id/created are absent from the stock mutable-field layout.
        int mask=0x1e;
        if(e.active()){
            mask|=(1<<6)|(1<<7)|(1<<8)|(1<<9);
            if(e.kind().equals("Monster"))mask|=(1<<11)|(1<<12)|(1<<13)|(1<<16)|(1<<18); // affected type/time, conditions, health and status
            out.writeByte(mask);out.writeByte(mask>>>8);
            if(e.kind().equals("Monster"))out.writeByte(mask>>>16);
        }else{
            mask|=(e.kind().equals("Switch")||e.kind().equals("EntryState"))?0xc0:(e.kind().equals("Actor")||e.kind().equals("Bomb"))?0:e.kind().equals("DungeonLift")?0x7c0:e.kind().equals("Lift")?0x1c0:e.kind().equals("Block$Stateful")?0xe0:0x20;
            if(e.kind().equals("Trap"))mask|=0x40;
            if(e.kind().equals("Pickup")&&e.pickup()!=null)mask|=0x40;
            out.writeByte(mask);if(e.kind().equals("DungeonLift")||e.kind().equals("Lift"))out.writeByte(mask>>>8);
        }
        out.writeInt(e.destroyed());vector(out,a.x(),a.y());out.writeFloat(a.rotation());out.writeInt(a.flags());
        if(e.active()){
            out.writeFloat(a.direction());out.writeInt(a.activity());out.writeInt(a.activityStarted());out.writeFloat(e.speed());
            if(e.kind().equals("Monster")){out.writeInt(e.affectedType());out.writeInt(e.affectedTime());conditions(out,e.status());out.writeFloat(e.health());statusModifiers(out,e.status().modifiers());}
        }else switch(e.kind()){
            case "Generator" -> out.writeInt(e.count());case "Door" -> out.writeInt(e.stateEntered());
            case "TerritoryProp" -> out.writeByte(e.state());
            case "Switch","EntryState" -> {out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "Block" -> out.writeInt(e.hitTime());case "Pickup" -> {out.writeShort(0);if(e.pickup()!=null){type(out,"[I");out.writeInt(2);out.writeInt(e.pickup().playerOid());out.writeInt(e.pickup().activated());}}
            case "Block$Stateful" -> {out.writeInt(e.hitTime());out.writeInt(e.state());out.writeInt(e.stateEntered());}
            case "DungeonLift" -> {out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());out.writeInt(e.lift()==null?0:e.lift().faction());
                int[] ids=e.lift()==null?null:e.lift().excluded();if(ids==null)out.writeShort(0);else{type(out,"[I");out.writeInt(ids.length);for(int id:ids)out.writeInt(id);}}
            case "Lift" -> {out.writeFloat(a.direction());out.writeFloat(e.lift()==null?0:e.lift().z());dropPoint(out,e.lift());}
            case "Trap" -> {type(out,"com.threerings.projectx.dungeon.data.actor.Trap$State");out.writeByte(e.state());out.writeInt(e.stateEntered());}
            case "Actor","Bomb" -> { }
            default -> throw new IOException("Unsupported prepared actor delta");
        }
    }

    protected void dropPoint(DataOutputStream out,org.tortodrone.core.world.SceneLiftState lift)throws IOException {
        if(lift==null||lift.dropPoint()==null)out.writeShort(0);else vector(out,lift.dropPoint().x(),lift.dropPoint().y());
    }

    protected void neutralStatus(DataOutputStream out)throws IOException {
        statusModifiers(out,org.tortodrone.core.world.SceneStatus.Modifiers.NORMAL);
    }

    protected void statusModifiers(DataOutputStream out,org.tortodrone.core.world.SceneStatus.Modifiers status)throws IOException {
        type(out,"com.threerings.projectx.dungeon.data.ImmutableStatusModifiers");
        out.writeFloat(status.speed());out.writeFloat(status.attackSpeed());out.writeFloat(status.attackDamage());out.writeFloat(status.relativeDamage());
        out.writeFloat(status.defense());out.writeFloat(status.heal());out.writeFloat(1);out.writeFloat(1);out.writeFloat(0);
        out.writeBoolean(status.canMove());out.writeBoolean(status.canRotate());out.writeBoolean(status.canDefend());out.writeBoolean(status.canAttack());
        out.writeBoolean(status.asleep());out.writeBoolean(status.shocked());out.writeBoolean(status.canBeTargeted());out.writeInt(0);
    }

    protected void effect(DataOutputStream out,SceneCombatEvent damage)throws IOException {
        int ordinal=DAMAGE_PERFORMANCE.indexOf(damage.performance());
        if(ordinal<0)throw new IOException("Unsupported damage performance: "+damage.performance());
        type(out,"com.threerings.projectx.dungeon.data.effect.DamageEffect");
        value(out,new StarterCatalog.Model("Feedback/Damage",Map.of()));
        out.writeInt(damage.timestamp());
        type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(damage.actor());
        vector(out,damage.x(),damage.y());out.writeFloat(damage.rotation());
        out.writeInt(0); // Client oid: a shared effect, never a private prefire.
        out.writeBoolean(false);out.writeInt(damage.amount());out.writeInt(damage.source());
        value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.dungeon.util.DamageSummary$Performance",ordinal));
    }

    protected void projectileDelta(DataOutputStream out,ActorState actor)throws IOException {
        var p=actor.projectile();boolean bullet=p.nativeType().equals("BULLET");
        out.writeInt(actor.id());type(out,p.nativeType().equals("ACTOR")?WireTypes.ACTOR:"com.threerings.projectx.dungeon.data.actor."+p.nativeClass());
        // Actor: destroyed, translation, rotation, flags. Origin, id and created are final.
        out.writeByte(p.nativeType().equals("LASER")?0x7e:0x1e);if(p.orbit()!=null)out.writeByte(3);else if(bullet)out.writeByte(0);
        out.writeInt(p.destroyed());vector(out,actor.x(),actor.y());out.writeFloat(actor.rotation());out.writeInt(actor.flags());
        if(p.orbit()!=null){vector(out,p.orbit().relativeX(),p.orbit().relativeY());out.writeInt(p.orbit().collided());}
        if(p.nativeType().equals("LASER")){out.writeBoolean(!p.fizzle());vector(out,actor.x()+(float)Math.cos(actor.rotation())*p.speed(),actor.y()+(float)Math.sin(actor.rotation())*p.speed());}
    }

    protected void effect(DataOutputStream out,SceneEffect effect)throws IOException {
        type(out,"com.threerings.tudey.data.effect.Effect");value(out,effect.config());out.writeInt(effect.timestamp());
        if(effect.targetEntry()!=null){type(out,"com.threerings.tudey.data.EntityKey$Entry");value(out,effect.targetEntry());}
        else if(effect.targetActor()==0)out.writeShort(0);else{type(out,"com.threerings.tudey.data.EntityKey$Actor");out.writeInt(effect.targetActor());}
        vector(out,effect.x(),effect.y());out.writeFloat(effect.rotation());out.writeInt(effect.ownerOid());
    }

    protected void weapon(DataOutputStream out,int bag,KnightCombat.State combat)throws IOException {
        if(combat==null||combat.weapon()==null)out.writeShort(0);else items.item(out,(String)null,bag,combat.weapon());
    }

    protected void impulse(DataOutputStream out,KnightCombat.Impulse impulse)throws IOException {
        if(impulse==null){out.writeShort(0);return;}
        type(out,"com.threerings.projectx.dungeon.data.actor.Impulse");out.writeInt(impulse.started());out.writeInt(impulse.duration());vector(out,impulse.x(),impulse.y());
    }
}
