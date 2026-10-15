package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Party health is instance state; it is never written to the account database. */
public final class SceneHealth {
    public record State(int current,int maximum,int bonus) { }
    public record Hit(int amount,int feedback) { }
    private static final Set<Integer> GEAR=Set.of(0,1,2,5,10,11,12);
    private final SceneContent content;
    private final SceneStatus status;
    private SceneContent.Spec defense=new SceneContent.Spec(Map.of());
    private SceneContent.Spec shieldDefense=new SceneContent.Spec(Map.of());
    private int current,maximum,bonus;
    private int invulnerableUntil;
    private java.util.function.IntUnaryOperator spriteShield=amount->amount;
    public void spriteShield(java.util.function.IntUnaryOperator shield){spriteShield=java.util.Objects.requireNonNull(shield);}
    public SceneHealth(SceneContent content){this.content=content;status=new SceneStatus(content,false);current=maximum=Math.round(content.stats().getOrDefault("player.base-health",1f));}
    public SceneStatus.State status(){return status.state();}
    public void condition(org.tortodrone.character.StarterCatalog.Model ref,Object data,int stamp){status.applyResolved(ref,data,stamp);}
    public boolean cloaked(){return status.cloaked();}
    public void breakCloak(){status.breakCloak();}
    public void equipment(WorldProfile profile){equipment(profile,org.tortodrone.town.TownCatalog.empty());}
    public void equipment(WorldProfile profile,org.tortodrone.town.TownCatalog town){
        int next=town.equipmentBonuses(profile,"").health();var defenses=new HashMap<String,String>();var shield=new HashMap<String,String>();
        for(var item:profile.items())if(item.equippedSlot()!=null&&GEAR.contains(item.equippedSlot())){
            String prefix="gear."+item.config()+".";
            next+=Math.round(content.stats().getOrDefault(prefix+"health-bonus",0f));
            for(String type:List.of("normal","piercing","elemental","shadow")){
                var target=item.equippedSlot()==2?shield:defenses;
                String key="defense."+type;float value=Float.parseFloat(target.getOrDefault(key,"0"));
                target.put(key,Float.toString(value+content.stats().getOrDefault(prefix+key,0f)));
            }
        }
        int difference=next-bonus;maximum=Math.min(3200,Math.round(content.stats().getOrDefault("player.base-health",1f))+next);current=Math.min(maximum,current+(current>0?Math.max(0,difference):0));bonus=next;
        defenses.replaceAll((key,value)->Integer.toString((int)Math.ceil(Float.parseFloat(value))));
        defense=new SceneContent.Spec(defenses);
        shield.replaceAll((key,value)->Integer.toString((int)Math.ceil(Float.parseFloat(value))));shieldDefense=new SceneContent.Spec(shield);
    }
    public State state(){return new State(current,maximum,bonus);}
    public boolean alive(){return current>0;}
    public boolean invulnerable(int stamp){return stamp<invulnerableUntil;}
    /** Training revival is a separate operation: ordinary heals cannot raise the dead. */
    public boolean revive(int stamp){if(alive())return false;current=maximum;status.clear();invulnerableUntil=stamp+2000;return true;}
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
        damage=SceneDamage.prune(damage,Math.round(content.stats().getOrDefault("player.damage-mask",13f)));
        var fields=new HashMap<>(defense.fields());float blockableScale=1;
        if(combat!=null&&combat.defending(stamp)){
            int shieldDamage=(int)Math.ceil(SceneDamage.amount(SceneDamage.select(damage,false),content,shieldDefense,multiplier));
            int statusDamage=combat.shieldHealth(stamp)>shieldDamage?status.shieldDamage(damage,stamp):0;
            if(shieldDamage>0||SceneDamage.hasStatus(damage))blockableScale=combat.block(shieldDamage,statusDamage,stamp);
            shieldDefense.fields().forEach((key,value)->fields.merge(key,value,(a,b)->Float.toString(Float.parseFloat(a)+Float.parseFloat(b))));
            if(combat.activity()==KnightCombat.HURT_DEFENSE)damage=SceneDamage.select(damage,true);
        }
        fields.replaceAll((key,value)->Float.toString(Float.parseFloat(value)*status.state().modifiers().defense()));
        var mitigation=new SceneContent.Spec(fields);
        int amount=(int)Math.ceil(SceneDamage.amount(damage,content,mitigation,multiplier,blockableScale));
        amount=Math.max(0,spriteShield.applyAsInt(amount));
        status.damaged(amount,stamp);status.apply(damage,stamp);rawDamage(amount);if(!alive())status.clear();
        return new Hit(amount,SceneDamage.playerFeedback(damage,content,mitigation,amount,multiplier,blockableScale));
    }
    private boolean rawDamage(int amount){if(amount<=0||current==0)return false;current=Math.max(0,current-amount);return true;}
    public boolean heal(int amount){amount=(int)Math.ceil(amount*status.state().modifiers().heal());if(amount<=0||current==0||current==maximum)return false;current=Math.min(maximum,current+amount);return true;}
    public void advance(int stamp,int elapsed,boolean moving){
        if(!alive())return;
        status.advance(stamp,elapsed,moving,amount->{if(!invulnerable(stamp))rawDamage((int)Math.ceil(amount));},amount->heal((int)Math.ceil(amount)));
        if(!alive())status.clear();
    }
}
