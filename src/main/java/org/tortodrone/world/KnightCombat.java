package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Stock 2026 interior input state. Peaceful town Knights never instantiate this controller. */
public final class KnightCombat {
    public static final int HURT_DEFENSE=277, CHARGE=283, DEFENSE=284, DODGE=286, BASH=287,
        INCOMPLETE=289, CHARGED=290, COOLDOWN=291, NORMAL=292;
    public record Impulse(int started,int duration,float x,float y) {
        public float fraction(int time) {
            float t=Math.clamp((time-started)/(float)duration,0,1);
            return 2*t-t*t;
        }
    }
    public record State(WorldProfile.Item weapon,String shield,String shieldCostume,int shieldMaximum,
            int chainCount,int chainReset,int nextDodge,Impulse impulse,float shieldHealth,int shieldHit,int shieldRegenStart,
            EquipmentCatalog.Modifiers equipmentModifiers,EquipmentCatalog.Modifiers weaponModifiers) {
        public State(WorldProfile.Item weapon,String shield,String shieldCostume,int shieldMaximum,int chainCount,int chainReset,int nextDodge,Impulse impulse,float shieldHealth,int shieldHit,int shieldRegenStart) {
            this(weapon,shield,shieldCostume,shieldMaximum,chainCount,chainReset,nextDodge,impulse,shieldHealth,shieldHit,shieldRegenStart,EquipmentCatalog.Modifiers.ZERO,EquipmentCatalog.Modifiers.ZERO);
        }
        public State(WorldProfile.Item weapon,String shield,String shieldCostume,int shieldMaximum,int chainCount,int chainReset,int nextDodge,Impulse impulse) {
            this(weapon,shield,shieldCostume,shieldMaximum,chainCount,chainReset,nextDodge,impulse,shieldMaximum,-300000,0);
        }
    }
    private final WeaponCatalog catalog;
    private final EquipmentCatalog modifiers;
    private WorldProfile profile;
    private EquipmentCatalog.Modifiers equipmentModifiers=EquipmentCatalog.Modifiers.ZERO,weaponModifiers=EquipmentCatalog.Modifiers.ZERO;
    private List<WorldProfile.Item> equipment=List.of();
    private WorldProfile.Item weapon;
    private String shield,shieldCostume;
    private WeaponCatalog.Weapon definition;
    private WeaponCatalog.Shield shieldDefinition;
    private WeaponCatalog.Attack attack;
    private int activity,started,chainCount,chainReset,nextDodge;
    private Impulse impulse;
    private boolean impulseApplied;
    private int dodgeCooldown;
    private float shieldHealth;
    private int shieldHit=-300000,shieldRegenStart;
    private boolean bashSpent;
    private SceneStatus.Modifiers status=SceneStatus.Modifiers.NORMAL;
    public void status(SceneStatus.Modifiers value){status=Objects.requireNonNull(value);if(!value.canMove())impulse=null;if(!value.canDefend()&&(activity==DEFENSE||activity==HURT_DEFENSE))clear(started);if(!value.canAttack()&&activity!=DEFENSE&&activity!=HURT_DEFENSE)clear(started);}
    public record AttackStart(String config,int activity,int started,int land,int clear,float attackSpeed) {
        public AttackStart(String config,int activity,int started,int land,int clear) {
            this(config,activity,started,land,clear,1f);
        }
        public AttackStart {
            if(!Float.isFinite(attackSpeed)||attackSpeed<=0)throw new IllegalArgumentException("Invalid attack speed");
        }
    }
    private java.util.function.Consumer<AttackStart> attackSink=ignored->{};
    public void attacks(java.util.function.Consumer<AttackStart> sink){attackSink=Objects.requireNonNull(sink);}

    public KnightCombat(WeaponCatalog catalog) { this(catalog,EquipmentCatalog.empty()); }
    public KnightCombat(WeaponCatalog catalog,EquipmentCatalog modifiers) { this.catalog=Objects.requireNonNull(catalog);this.modifiers=Objects.requireNonNull(modifiers);dodgeCooldown=catalog.dodgeCooldown(); }
    public void equipment(WorldProfile profile) {
        this.profile=profile;
        equipment=profile.items().stream().filter(i->i.equippedSlot()!=null).toList();
        String previousShield=shield;
        shield=equipped(2)==null?null:equipped(2).config();
        shieldCostume=equipped(15)==null?null:equipped(15).config();
        shieldDefinition=shield==null?null:catalog.findShield(shield);
        if(!Objects.equals(previousShield,shield)){shieldHealth=shieldDefinition==null?0:shieldDefinition.health();shieldHit=-300000;shieldRegenStart=0;}
        int slot=weapon==null?6:equipment.stream().filter(i->i.id()==weapon.id()).map(WorldProfile.Item::equippedSlot).findFirst().orElse(6);
        select(slot);
        updateModifiers();
    }
    private WorldProfile.Item equipped(int slot) { return equipment.stream().filter(i->i.equippedSlot()==slot).findFirst().orElse(null); }
    private void select(int slot) {
        var item=equipped(slot);
        if(Objects.equals(item,weapon))return;
        weapon=item;definition=item==null?null:catalog.find(item.config());chainCount=chainReset=0;
        updateModifiers();
        // Re-equipping cannot leave an old weapon's charge or attack running.
        if(activity!=DEFENSE)clear(started);
    }
    public void reset(int time) { clear(time);chainCount=chainReset=0;impulse=null; }
    /** A soft hit interrupts attacks but retains the independently applied damage impulse. */
    public void interrupt(int time) { clear(time);chainCount=chainReset=0; }
    private void clear(int time) { activity=0;started=time;attack=null;impulseApplied=false; }
    public int activity(){return activity;}
    public int flags(){return impulseApplied?128:0;}
    public int started(){return started;}
    public boolean canOverride(){return activity==0||activity==DEFENSE;}
    public boolean allowsMovement(){return activity==0||activity==CHARGE||activity==DEFENSE||attack!=null&&attack.blended()&&definition!=null&&definition.blended();}
    public boolean allowsRotation(int time){return allowsMovement()||attack!=null&&time-started>=attack.rearm();}
    private void updateModifiers(){if(profile==null)return;try{var pair=modifiers.forKnight(profile,weapon);equipmentModifiers=pair.equipment();weaponModifiers=pair.weapon();
            // Older deployments may have only the weapon catalogue. Preserve its
            // verified shield slowdown until the full equipment export is supplied.
            if(modifiers.isEmpty()&&shieldDefinition!=null)equipmentModifiers=new EquipmentCatalog.Modifiers(0,0,0,shieldDefinition.defendingSpeed());}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("Equipped item has no verified control metadata",invalid);}}
    private float attackSpeed(){return Math.max(.01f,EquipmentCatalog.attackSpeedMultiplier(equipmentModifiers,weaponModifiers)*status.attackSpeed());}
    private WeaponCatalog.Attack scaled(WeaponCatalog.Attack attack){if(attack==null)return null;float speed=attackSpeed();
        return new WeaponCatalog.Attack((int)(attack.land()/speed),(int)(attack.rearm()/speed),(int)(attack.clear()/speed),attack.blended(),
            attack.impulseDelay()<0?-1:(int)(attack.impulseDelay()/speed),attack.impulseDuration(),attack.impulseX(),attack.impulseY());}
    public float movementScale(){float base=EquipmentCatalog.speedMultiplier(equipmentModifiers,weaponModifiers);
        return base*(activity==DEFENSE?EquipmentCatalog.defenseSpeedMultiplier(equipmentModifiers):activity==CHARGE&&definition!=null?definition.chargingSpeed():activity>=INCOMPLETE&&definition!=null?definition.attackingSpeed():1);}
    public State state(){return new State(weapon,shield,shieldCostume,shieldDefinition==null?0:shieldDefinition.health(),chainCount,chainReset,nextDodge,impulse,shieldHealth,shieldHit,shieldRegenStart,equipmentModifiers,weaponModifiers);}
    private int currentShieldHealth(int time){return shieldDefinition==null?0:Math.min(shieldDefinition.health(),(int)Math.ceil(Math.max(shieldHealth,shieldHealth+(time-shieldHit-shieldRegenStart)*(float)shieldDefinition.health()/shieldDefinition.regenTime())));}
    public boolean defending(int stamp){return activity==DEFENSE&&currentShieldHealth(stamp)>0;}
    public int shieldHealth(int stamp){return currentShieldHealth(stamp);}
    /** Native blockable spill fraction; unblockable damage bypasses shield health. */
    public float block(int amount,int stamp){
        if(!defending(stamp)||amount<=0)return 1;
        return block(amount,0,stamp);
    }
    public float block(int amount,int statusAmount,int stamp){
        if(!defending(stamp))return 1;
        int hp=currentShieldHealth(stamp);boolean survives=hp>(long)amount+statusAmount;shieldHit=stamp;shieldHealth=survives?hp-amount-statusAmount:0;
        shieldRegenStart=survives?shieldDefinition.regenDelay():shieldDefinition.breakDelay();
        if(survives){activity=HURT_DEFENSE;started=stamp;attack=null;impulse=null;return 0;}
        return amount>0?Math.max(0,(amount-hp)/(float)amount):1;
    }
    public void input(int flags,int changed,int time,float facing,float direction,boolean baseAllows) {
        advance(time,facing);
        if(!baseAllows){if(canOverride()){int index=(flags&56)>>>3;if(index<2)select(6+index);}return;}
        if(activity==CHARGE&&(flags&4)==0) {
            if(definition!=null)startAttack(time-started>=EquipmentCatalog.chargeTimeMillis(definition.chargeTime(),equipmentModifiers,weaponModifiers)?CHARGED:nextNormal(time),time);
            else clear(time);
        }
        if(activity==DEFENSE&&(flags&64)==0)clear(time);
        // ActiveAdvancer updates the current attack before Dungeoneer evaluates a
        // new attack. A held press at rearm must charge instead of chaining again.
        if(attack!=null&&canCharge()&&time-started>=attack.rearm()&&(flags&4)!=0&&changed<=started&&activity!=COOLDOWN) {
            started+=attack.rearm();activity=CHARGE;attack=null;
        }
        boolean rearmed=attack!=null&&time-started>=attack.rearm()&&activity!=COOLDOWN;
        if(status.canMove()&&(flags&1024)!=0&&time>nextDodge&&activity!=CHARGE&&activity!=DEFENSE&&attack==null&&activity!=DODGE) {
            activity=DODGE;started=time;nextDodge=time+dodgeCooldown;attack=null;
            float angle=(flags&1)!=0?direction:facing;
            impulse=new Impulse(time,625,(float)Math.cos(angle)*5,(float)Math.sin(angle)*5);impulseApplied=true;
        }
        if((flags&4)!=0&&definition!=null&&(activity==0||rearmed)
                &&(definition.maxChain()==0||count(time)<definition.maxChain())) {
            if(changed>=started)startAttack(definition.attacks().isEmpty()?CHARGE:activity>=NORMAL?nextNormal(time):NORMAL,time);
            else if(canCharge()&&changed+350<started){activity=CHARGE;started=time;attack=null;}
        }
        if(status.canDefend()&&(flags&64)!=0&&shieldDefinition!=null&&(activity==0||rearmed)) {activity=DEFENSE;started=time;attack=null;
            attackSink.accept(new AttackStart(shield,DEFENSE,started,0,600000,1));}
        if(status.canMove()&&status.canDefend()&&(flags&2048)!=0&&shieldDefinition!=null&&shieldDefinition.bash()!=null&&currentShieldHealth(time)==shieldDefinition.health()&&(activity==0||activity==DEFENSE||rearmed)) {
            activity=BASH;started=time;attack=scaled(shieldDefinition.bash());impulseApplied=false;bashSpent=false;
            attackSink.accept(new AttackStart(shield,activity,started,attack.land(),attack.clear(),attackSpeed()));
        }
        if(canOverride()) {
            int index=(flags&56)>>>3;
            // The current server owns two unlocked native weapon slots, 6 and 7.
            if(index<2)select(6+index);
        }
    }
    private boolean canCharge(){return definition!=null&&definition.charged()!=null&&definition.incomplete()!=null;}
    private int count(int time){return time>=chainReset?0:chainCount;}
    private int nextNormal(int time) {
        if(definition.attacks().isEmpty())return INCOMPLETE;
        int idx=count(time);return NORMAL+(definition.cycle()?idx%definition.attacks().size():Math.min(idx,definition.attacks().size()-1));
    }
    private void startAttack(int value,int time) {
        if(value==CHARGE){if(canCharge()){activity=CHARGE;started=time;attack=null;}return;}
        WeaponCatalog.Attack selected=switch(value){case INCOMPLETE->definition.incomplete();case CHARGED->definition.charged();case COOLDOWN->definition.cooldown();default->value>=NORMAL&&value-NORMAL<definition.attacks().size()?definition.attacks().get(value-NORMAL):null;};
        if(selected==null){clear(time);return;}
        selected=scaled(selected);
        chainCount=value==COOLDOWN?definition.maxChain()+1:count(time)+1;
        chainReset=time+(value!=COOLDOWN&&definition.chainCooldown()==0?selected.clear():selected.land()+definition.chainCooldown());
        activity=value;started=time;attack=selected;impulseApplied=false;
        attackSink.accept(new AttackStart(weapon.config(),activity,started,attack.land(),attack.clear(),attackSpeed()));
    }
    public void advance(int time,float facing) {
        if(attack!=null) {
            if(activity==BASH&&!bashSpent&&shieldDefinition!=null&&time>=started+attack.land()) {
                shieldHealth=Math.max(0,(int)(currentShieldHealth(time)-shieldDefinition.bashHealthPct()*shieldDefinition.health()));
                shieldHit=time;shieldRegenStart=shieldDefinition.regenDelay();bashSpent=true;
            }
            if(!impulseApplied&&attack.impulseDuration()>0&&time>=started+attack.impulseDelay()) {
                float cos=(float)Math.cos(facing),sin=(float)Math.sin(facing);
                if(status.canMove())impulse=new Impulse(started+attack.impulseDelay(),attack.impulseDuration(),attack.impulseX()*cos-attack.impulseY()*sin,attack.impulseX()*sin+attack.impulseY()*cos);impulseApplied=true;
            }
            if(definition!=null&&activity!=BASH&&activity!=COOLDOWN&&definition.cooldown()!=null&&definition.maxChain()>0&&chainCount==definition.maxChain()&&time>=started+attack.rearm()) {
                int end=started+attack.rearm();startAttack(COOLDOWN,end);
            }
            if(attack!=null&&time>=started+attack.clear())clear(started+attack.clear());
        }else if(activity==DODGE&&time>=started+625)clear(started+625);
        else if(activity==HURT_DEFENSE&&time>=started+411)clear(started+411);
        // Keep a completed impulse through its final integration interval; movement retires it.
    }
    public void retireImpulse(int time){if(impulse!=null&&time>=impulse.started()+impulse.duration())impulse=null;}
    public void impulse(Impulse value){if(value!=null&&status.canMove())impulse=SceneDamage.combine(value,impulse);}
}
