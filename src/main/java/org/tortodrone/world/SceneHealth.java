package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Party health is instance state; it is never written to the account database. */
public final class SceneHealth {
    /** Retail Dungeoneer.INVULNERABLE; its sprite flashes every 50 ms while set. */
    public static final int INVULNERABLE_FLAG=64, POST_HIT_MILLIS=1000, ENTRY_PROTECTION_MILLIS=3000;
    public record State(int current,int maximum,int bonus,int reviveFlags) {
        public State(int current,int maximum,int bonus){this(current,maximum,bonus,1);}
    }
    public record Hit(int amount,int feedback,boolean react) {
        public Hit(int amount,int feedback){this(amount,feedback,false);}
    }
    private static final Set<Integer> GEAR=Set.of(0,1,2,5,10,11,12);
    private final SceneContent content;
    private final SceneStatus status;
    private SceneContent.Spec defense=new SceneContent.Spec(Map.of());
    private SceneContent.Spec shieldDefense=new SceneContent.Spec(Map.of());
    private int current,maximum,bonus;
    private int invulnerableUntil,hitTime;
    public int hitTime(){return hitTime;}
    private boolean emergencyRevive=true;
    private int emergencyAt;
    /** Original tutorial parties never dissolve on defeat: Party.checkPartyDead heals every
     * dead tutorial member in place after a fixed delay instead. */
    public static final int TUTORIAL_RESPAWN_MILLIS=4000;
    private boolean tutorialRespawn;
    private int respawnAt;
    public void tutorialRespawn(boolean enabled){tutorialRespawn=enabled;if(!enabled)respawnAt=0;}
    public record SpriteBlock(float scale,boolean held){public static final SpriteBlock NONE=new SpriteBlock(1,false);}
    @FunctionalInterface public interface SpriteShield{SpriteBlock absorb(Object damage,int stamp,float multiplier);}
    private SpriteShield spriteShield=(damage,stamp,multiplier)->SpriteBlock.NONE;
    public void spriteShield(SpriteShield shield){spriteShield=java.util.Objects.requireNonNull(shield);}
    public SceneHealth(SceneContent content){this.content=content;status=new SceneStatus(content,false);current=maximum=Math.round(content.stats().getOrDefault("player.base-health",1f));}
    public SceneStatus.State status(){return status.state();}
    public List<SceneStatus.Pulse> takePulses(){return status.takePulses();}
    public List<FireCatalog.Action> takeActions(){return status.takeActions();}
    public void inventory(WorldProfile profile){status.equipment(profile);}
    public void curseItem(long item,int stamp){if(alive()){rawDamage((int)Math.ceil(status.used(item)),stamp);died(stamp);}}
    public void condition(org.tortodrone.character.StarterCatalog.Model ref,Object data,int stamp){status.applyResolved(ref,data,stamp);}
    public boolean applyCondition(org.tortodrone.character.StarterCatalog.Model ref,Object data,int stamp){return status.applyResolvedResult(ref,data,stamp);}
    public boolean clearStatus(int mask){return status.clear(mask);}
    public boolean cloaked(){return status.cloaked();}
    public void breakCloak(){status.breakCloak();}
    public void equipment(WorldProfile profile){equipment(profile,org.tortodrone.town.TownCatalog.empty());}
    public void equipment(WorldProfile profile,org.tortodrone.town.TownCatalog town){
        status.town(town);
        status.equipment(profile);
        int next=town.equipmentBonuses(profile,"").health();var defenses=new HashMap<String,String>();var shield=new HashMap<String,String>();
        for(var item:profile.items())if(item.equippedSlot()!=null&&GEAR.contains(item.equippedSlot())){
            next+=Math.round(GearStats.value(content.stats(),item,"health-bonus"));
            for(String type:List.of("normal","piercing","elemental","shadow")){
                var target=item.equippedSlot()==2?shield:defenses;
                String key="defense."+type;float value=Float.parseFloat(target.getOrDefault(key,"0"));
                target.put(key,Float.toString(value+GearStats.defense(content.stats(),item,type,town)));
            }
        }
        int difference=next-bonus;maximum=Math.min(MAX_PUBLISHED_HEALTH,Math.round(content.stats().getOrDefault("player.base-health",1f))+next);current=Math.min(maximum,current+(current>0?Math.max(0,difference):0));bonus=next;
        defenses.replaceAll((key,value)->Integer.toString((int)Math.ceil(Float.parseFloat(value))));
        defense=new SceneContent.Spec(defenses);
        shield.replaceAll((key,value)->Integer.toString((int)Math.ceil(Float.parseFloat(value))));shieldDefense=new SceneContent.Spec(shield);
    }
    public State state(){return new State(current,maximum,bonus,(emergencyRevive?1:0)|(emergencyAt!=0?8:0));}
    public boolean alive(){return current>0;}
    public boolean invulnerable(int stamp){return stamp<invulnerableUntil;}
    public void protectEntry(int stamp){if(alive())invulnerableUntil=Math.max(invulnerableUntil,stamp+ENTRY_PROTECTION_MILLIS);}
    /** Native DungeoneerLogic.INITIAL_INVULNERABILITY: five seconds of spawn protection
     * armed by finishedLoading when the client's first input ends the loading pose. */
    public static final int INITIAL_INVULNERABILITY_MILLIS = 5000;
    /** Hard ceiling on published knight health; keeps the pip bar scale bounded. */
    public static final int MAX_PUBLISHED_HEALTH = 3200;
    /** Spark-of-life and emergency revive protection windows, and the automatic
     * emergency revive delay after death. */
    public static final int SPARK_REVIVE_PROTECTION_MILLIS = 2000;
    public static final int EMERGENCY_REVIVE_PROTECTION_MILLIS = 5000;
    public static final int EMERGENCY_REVIVE_DELAY_MILLIS = 5000;
    public void protectLoading(int stamp){if(alive())invulnerableUntil=Math.max(invulnerableUntil,stamp+INITIAL_INVULNERABILITY_MILLIS);}
    /** Archive emergency revive: five-second delay and five seconds of invulnerability. */
    private void died(int stamp){if(!alive()){status.clear();if(emergencyRevive&&emergencyAt==0)emergencyAt=stamp+EMERGENCY_REVIVE_DELAY_MILLIS;
        else if(!emergencyRevive&&emergencyAt==0&&tutorialRespawn&&respawnAt==0)respawnAt=stamp+TUTORIAL_RESPAWN_MILLIS;}}
    public boolean canSparkRevive(){return !alive()&&!emergencyRevive&&emergencyAt==0;}
    public boolean revive(int stamp){if(alive())return false;
        // A manual Training resurrection can answer the pending automatic charge.
        // Both paths spend the same charge; neither a Spark nor equipment replenishes it.
        emergencyRevive=false;current=maximum;status.clear();emergencyAt=respawnAt=0;invulnerableUntil=stamp+SPARK_REVIVE_PROTECTION_MILLIS;return true;}
    public boolean damage(Object damage,int stamp){
        return hit(damage,stamp).amount()>0;
    }
    public Hit hit(Object damage,int stamp){
        return hit(damage,stamp,1);
    }
    public Hit hit(Object damage,int stamp,float multiplier){
        return hit(damage,stamp,multiplier,null);
    }
    public Hit hit(Object damage,int stamp,float multiplier,KnightCombat combat){
        if(current==0||invulnerable(stamp)||combat!=null&&combat.activity()==KnightCombat.HURT_DEFENSE)return new Hit(0,0);
        damage=SceneDamage.prune(damage,Math.round(content.stats().getOrDefault("player.damage-mask",SceneContent.DEFAULT_PLAYER_DAMAGE_MASK)));
        var spriteBlock=spriteShield.absorb(damage,stamp,multiplier);
        if(spriteBlock.held())damage=SceneDamage.select(damage,true);
        var fields=new HashMap<>(defense.fields());float blockableScale=spriteBlock.scale();
        if(!spriteBlock.held()&&combat!=null&&combat.defending(stamp)){
            int shieldDamage=(int)Math.ceil(SceneDamage.amount(SceneDamage.select(damage,false),content,shieldDefense,multiplier,blockableScale));
            int statusDamage=combat.shieldHealth(stamp)>shieldDamage?status.shieldDamage(damage,stamp):0;
            if(shieldDamage>0||SceneDamage.hasStatus(damage))blockableScale*=combat.block(shieldDamage,statusDamage,stamp);
            shieldDefense.fields().forEach((key,value)->fields.merge(key,value,(a,b)->Float.toString(Float.parseFloat(a)+Float.parseFloat(b))));
            if(combat.activity()==KnightCombat.HURT_DEFENSE)damage=SceneDamage.select(damage,true);
        }
        fields.replaceAll((key,value)->Float.toString(Float.parseFloat(value)*status.state().modifiers().defense()));
        var mitigation=new SceneContent.Spec(fields);
        int amount=(int)Math.ceil(SceneDamage.amount(damage,content,mitigation,multiplier,blockableScale));
        amount+=(int)Math.ceil(status.damaged(amount,stamp));
        if(blockableScale>0)status.apply(damage,stamp);rawDamage(amount,stamp);died(stamp);
        boolean react=amount>0&&SceneDamage.reacts(damage);
        if(react&&alive())invulnerableUntil=stamp+POST_HIT_MILLIS;
        return new Hit(amount,SceneDamage.playerFeedback(damage,content,mitigation,amount,multiplier,blockableScale),react);
    }
    private boolean rawDamage(int amount,int stamp){if(amount<=0||current==0)return false;current=Math.max(0,current-amount);hitTime=stamp;return true;}
    public boolean heal(int amount){amount=(int)Math.ceil(amount*status.state().modifiers().heal());if(amount<=0||current==0||current==maximum)return false;current=Math.min(maximum,current+amount);return true;}
    public void advance(int stamp,int elapsed,boolean moving){
        if(!alive()){
            if(emergencyAt!=0&&stamp>=emergencyAt){emergencyRevive=false;revive(stamp);invulnerableUntil=stamp+EMERGENCY_REVIVE_PROTECTION_MILLIS;}
            else if(respawnAt!=0&&stamp>=respawnAt)revive(stamp);
            return;
        }
        status.advance(stamp,elapsed,moving,amount->{if(!invulnerable(stamp))rawDamage((int)Math.ceil(amount),stamp);},amount->heal((int)Math.ceil(amount)));
        died(stamp);
    }
}
