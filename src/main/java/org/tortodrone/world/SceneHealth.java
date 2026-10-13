package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Party health is instance state; it is never written to the account database. */
public final class SceneHealth {
    public record State(int current,int maximum,int bonus) { }
    private static final Set<Integer> GEAR=Set.of(0,1,2,5,10,11,12);
    private final SceneContent content;
    private final SceneStatus status;
    private SceneContent.Spec defense=new SceneContent.Spec(Map.of());
    private int current,maximum,bonus;
    public SceneHealth(SceneContent content){this.content=content;status=new SceneStatus(content,false);current=maximum=Math.round(content.stats().getOrDefault("player.base-health",1f));}
    public SceneStatus.State status(){return status.state();}
    public void equipment(WorldProfile profile){
        int next=0;var defenses=new HashMap<String,String>();
        for(var item:profile.items())if(item.equippedSlot()!=null&&GEAR.contains(item.equippedSlot())){
            String prefix="gear."+item.config()+".";
            next+=Math.round(content.stats().getOrDefault(prefix+"health-bonus",0f));
            if(item.equippedSlot()!=2)for(String type:List.of("normal","piercing","elemental","shadow")){
                String key="defense."+type;float value=Float.parseFloat(defenses.getOrDefault(key,"0"));
                defenses.put(key,Float.toString(value+content.stats().getOrDefault(prefix+key,0f)));
            }
        }
        int difference=next-bonus;maximum=Math.min(3200,Math.round(content.stats().getOrDefault("player.base-health",1f))+next);current=Math.min(maximum,current+Math.max(0,difference));bonus=next;
        defenses.replaceAll((key,value)->Integer.toString((int)Math.ceil(Float.parseFloat(value))));
        defense=new SceneContent.Spec(defenses);
    }
    public State state(){return new State(current,maximum,bonus);}
    public boolean damage(Object damage,int stamp){
        if(current==0)return false;
        damage=SceneDamage.prune(damage,Math.round(content.stats().getOrDefault("player.damage-mask",13f)));
        var fields=new HashMap<>(defense.fields());fields.replaceAll((key,value)->Float.toString(Float.parseFloat(value)*status.state().modifiers().defense()));
        int amount=(int)Math.ceil(SceneDamage.amount(damage,content,new SceneContent.Spec(fields)));
        status.damaged(amount,stamp);status.apply(damage,stamp);return rawDamage(amount);
    }
    private boolean rawDamage(int amount){if(amount<=0||current==0)return false;current=Math.max(0,current-amount);return true;}
    public boolean heal(int amount){amount=(int)Math.ceil(amount*status.state().modifiers().heal());if(amount<=0||current==0||current==maximum)return false;current=Math.min(maximum,current+amount);return true;}
    public void advance(int stamp,int elapsed,boolean moving){status.advance(stamp,elapsed,moving,amount->rawDamage((int)Math.ceil(amount)),amount->heal((int)Math.ceil(amount)));}
}
