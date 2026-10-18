package org.tortodrone.resources;

import java.io.*;
import java.util.*;
import org.tortodrone.world.WeaponCatalog;
import static org.tortodrone.resources.Yaml.*;
import static org.tortodrone.resources.ItemFields.*;

/** Presentation adapter only: the validated native combat records and state machine stay unchanged. */
final class ReadableCombat {
    private ReadableCombat() { }
    static String sequence(String type){return type.equals("Sword")?"combo":type.contains("Bomb")?"deploymentSequence":"firingSequence";}
    static WeaponCatalog.Weapon weapon(String config,String type,Map<String,Object> m,String at)throws IOException {
        String seq=sequence(type);fields(m,at,"movement",seq,"charge","recoveryAction");
        var movement=child(m,"movement",at);fields(movement,at+" → movement","whileAttacking","whileCharging","allowMovementInput");
        var chain=child(m,seq,at);String cs=at+" → "+seq;fields(chain,cs,"maximumAttacks","repeatSequence","resetAfterEffectMs","attacks");
        var stages=child(chain,"attacks",cs);var ordered=new TreeMap<Integer,WeaponCatalog.Attack>();
        for(var e:stages.entrySet()) {
            String path=cs+" → attacks → "+e.getKey();if(!e.getKey().matches("[a-z][a-z0-9_]{0,79}"))throw new IOException(path+": invalid stage name");
            var stage=map(e.getValue(),path);if(!stage.containsKey("step"))throw new IOException(path+" → step: explicit stage position is required");
            int step=integer(stage,"step",path);if(ordered.putIfAbsent(step,attack(stage,path,true))!=null)throw new IOException(path+" → step: duplicate position "+step);
        }
        for(int i=1;i<=ordered.size();i++)if(!ordered.containsKey(i))throw new IOException(cs+" → attacks → step: positions must be contiguous from 1");
        var charge=child(m,"charge",at);String cc=at+" → charge";fields(charge,cc,"fullChargeAfterMs","fullChargeAction","incompleteChargeAction");
        return new WeaponCatalog.Weapon(config,integer(charge,"fullChargeAfterMs",cc),integer(chain,"maximumAttacks",cs),integer(chain,"resetAfterEffectMs",cs),bool(chain,"repeatSequence",cs),bool(movement,"allowMovementInput",at+" → movement"),number(movement,"whileAttacking",at+" → movement"),number(movement,"whileCharging",at+" → movement"),new ArrayList<>(ordered.values()),optional(charge,"incompleteChargeAction",cc),optional(charge,"fullChargeAction",cc),optional(m,"recoveryAction",at));
    }
    private static Map<String,Object> child(Map<String,Object> m,String key,String at)throws IOException{return map(m.getOrDefault(key,Map.of()),at+" → "+key);}
    private static WeaponCatalog.Attack optional(Map<String,Object> m,String key,String at)throws IOException{return m.containsKey(key)?attack(map(m.get(key),at+" → "+key),at+" → "+key,false):null;}
    private static WeaponCatalog.Attack attack(Map<String,Object> m,String at,boolean stage)throws IOException {
        fields(m,at,stage?new String[]{"step","timing","movement"}:new String[]{"timing","movement"});
        var t=child(m,"timing",at);String tt=at+" → timing";fields(t,tt,"effectStartsAfterMs","nextAttackAfterMs","actionEndsAfterMs");
        var v=child(m,"movement",at);String vv=at+" → movement";fields(v,vv,"allowMovementInput","startsAfterMs","durationMs","forwardImpulse","sidewaysImpulse");
        boolean impulse=v.containsKey("startsAfterMs")||v.containsKey("durationMs")||v.containsKey("forwardImpulse")||v.containsKey("sidewaysImpulse");
        return new WeaponCatalog.Attack(integer(t,"effectStartsAfterMs",tt),integer(t,"nextAttackAfterMs",tt),integer(t,"actionEndsAfterMs",tt),bool(v,"allowMovementInput",vv),impulse?integer(v,"startsAfterMs",vv):-1,impulse?integer(v,"durationMs",vv):-1,number(v,"forwardImpulse",vv),number(v,"sidewaysImpulse",vv));
    }
    static WeaponCatalog.Shield shield(String config,Map<String,Object> m,String at)throws IOException {
        fields(m,at,"health","regeneration","defendingSpeedAdjustment","bash");
        var r=child(m,"regeneration",at);String rr=at+" → regeneration";fields(r,rr,"fullHealthAfterMs","delayAfterHitMs","delayAfterBreakMs");
        var b=child(m,"bash",at);String bb=at+" → bash";fields(b,bb,"healthCostFraction","action");
        return new WeaponCatalog.Shield(config,integer(m,"health",at),integer(r,"fullHealthAfterMs",rr),integer(r,"delayAfterHitMs",rr),number(m,"defendingSpeedAdjustment",at),number(b,"healthCostFraction",bb),optional(b,"action",bb),integer(r,"delayAfterBreakMs",rr));
    }
    static Map<String,Object> weapon(WeaponCatalog.Weapon w,String type) {
        var out=new LinkedHashMap<String,Object>();var movement=new LinkedHashMap<String,Object>();
        put(movement,"whileAttacking",w.attackingSpeed());put(movement,"whileCharging",w.chargingSpeed());put(movement,"allowMovementInput",w.blended());if(!movement.isEmpty())out.put("movement",movement);
        var seq=new LinkedHashMap<String,Object>();put(seq,"maximumAttacks",w.maxChain());put(seq,"repeatSequence",w.cycle());put(seq,"resetAfterEffectMs",w.chainCooldown());
        var attacks=new LinkedHashMap<String,Object>();String noun=type.equals("Sword")?"swing":type.contains("Bomb")?"deployment":"shot";
        for(int i=0;i<w.attacks().size();i++) {var stage=new LinkedHashMap<String,Object>();stage.put("step",i+1);stage.putAll(attack(w.attacks().get(i)));attacks.put((i==0?"first":i==1?"second":i==2?"third":"stage_"+(i+1))+"_"+noun,stage);}
        if(!attacks.isEmpty())seq.put("attacks",attacks);if(!seq.isEmpty())out.put(sequence(type),seq);
        var charge=new LinkedHashMap<String,Object>();put(charge,"fullChargeAfterMs",w.chargeTime());if(w.charged()!=null)charge.put("fullChargeAction",attack(w.charged()));if(w.incomplete()!=null)charge.put("incompleteChargeAction",attack(w.incomplete()));if(!charge.isEmpty())out.put("charge",charge);
        if(w.cooldown()!=null)out.put("recoveryAction",attack(w.cooldown()));return out;
    }
    private static Map<String,Object> attack(WeaponCatalog.Attack a) {
        var out=new LinkedHashMap<String,Object>();var t=new LinkedHashMap<String,Object>();put(t,"effectStartsAfterMs",a.land());put(t,"nextAttackAfterMs",a.rearm());put(t,"actionEndsAfterMs",a.clear());if(!t.isEmpty())out.put("timing",t);
        var v=new LinkedHashMap<String,Object>();put(v,"allowMovementInput",a.blended());if(a.impulseDelay()!=-1) {v.put("startsAfterMs",a.impulseDelay());v.put("durationMs",a.impulseDuration());put(v,"forwardImpulse",a.impulseX());put(v,"sidewaysImpulse",a.impulseY());}if(!v.isEmpty())out.put("movement",v);return out;
    }
    static Map<String,Object> shield(WeaponCatalog.Shield s) {
        var out=new LinkedHashMap<String,Object>();put(out,"health",s.health());put(out,"defendingSpeedAdjustment",s.defendingSpeed());var r=new LinkedHashMap<String,Object>();put(r,"fullHealthAfterMs",s.regenTime());put(r,"delayAfterHitMs",s.regenDelay());put(r,"delayAfterBreakMs",s.breakDelay());if(!r.isEmpty())out.put("regeneration",r);
        var b=new LinkedHashMap<String,Object>();put(b,"healthCostFraction",s.bashHealthPct());if(s.bash()!=null)b.put("action",attack(s.bash()));if(!b.isEmpty())out.put("bash",b);return out;
    }
}
