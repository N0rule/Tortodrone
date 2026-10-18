package org.tortodrone.resources;

import java.io.*;
import java.util.*;
import static org.tortodrone.resources.Yaml.*;

/** Single source for scalar defaults, ranges, diagnostics and the administrator field reference. */
public final class ItemFields {
    public record Field(String name,String meaning,String unit,Object fallback,double min,double max,String applies) { }
    private static final Map<String,Field> FIELDS=new LinkedHashMap<>();
    static {
        field("name","Administrator display label; never changes native client localization","characters","required",1,512,"all items");
        field("category","Administrator category; independent of native item class and slot","characters","required",1,512,"all items");
        field("subcategory","Optional administrator grouping, such as Aura","characters","absent",1,512,"all items");
        field("rarity","Native star rarity; -1 is unranked","stars",-1,-1,10,"all items");
        field("bindsWhenEquipped","Bind on equip using existing inventory rules","boolean",false,0,1,"all items");
        field("stackable","Use existing stack handling; does not add support for unsupported variants","boolean",false,0,1,"all items");
        field("permanent","Permanent items must remain locked","boolean",false,0,1,"all items");
        field("locked","Existing locked-item restriction","boolean",false,0,1,"all items");
        duration("effectStartsAfterMs","Effect activation offset; fire graph delays remain additional");
        duration("nextAttackAfterMs","Earliest rearm threshold, subject to input, charge and sequence rules");
        duration("actionEndsAfterMs","Attack state clears at this offset if uninterrupted");
        duration("fullChargeAfterMs","Required held charge duration before equipment charge modifiers");
        duration("resetAfterEffectMs","Chain resets this long after effect activation; zero uses action end except cooldown uses effect activation");
        duration("startsAfterMs","Movement impulse start offset; scaled by attack speed");
        duration("durationMs","Movement impulse duration; not scaled by attack speed");
        duration("delayAfterHitMs","Shield regeneration delay after a surviving hit or bash");
        field("delayAfterBreakMs","Shield regeneration delay after break","ms",7000,0,600000,"shield.regeneration");
        field("fullHealthAfterMs","Time to regenerate one full shield health pool after the delay","ms",1,1,600000,"shield.regeneration");
        field("maximumAttacks","Chain action limit; zero is unlimited, independent of number of named stages","count",0,0,32,"combat sequence");
        field("step","Explicit contiguous stage position starting at one","position",1,1,32,"sequence attacks");
        field("repeatSequence","Wrap normal stage selection; otherwise reuse final stage","boolean",false,0,1,"combat sequence");
        field("allowMovementInput","Weapon AND active stage must allow movement input","boolean",false,0,1,"combat.movement / attack.movement");
        field("whileAttacking","Base movement multiplier while an attack state is active","multiplier",0f,0,100,"combat.movement");
        field("whileCharging","Base movement multiplier while charging","multiplier",0f,0,100,"combat.movement");
        field("forwardImpulse","Facing-relative native impulse translation, integrated with 2t-t²; collisions can limit movement","game units",0f,-10000,10000,"attack.movement");
        field("sidewaysImpulse","Perpendicular native impulse translation; same integration as forwardImpulse","game units",0f,-10000,10000,"attack.movement");
        field("health","Shield health pool","health",0,0,100000,"shield");
        field("defendingSpeedAdjustment","Base shield movement adjustment: -0.5 means multiplier 0.5; supplies omitted defendingSpeed modifiers","fraction",0f,-1,0,"shield");
        field("healthCostFraction","Fraction of maximum shield health spent at bash effect time; full shield required to bash","fraction",0f,0,1,"shield.bash");
        field("chargeTime","Positive values reduce charge time: base × (1 − sum capped to ±0.48)","fraction",0f,-1000,1000,"modifiers per weapon class");
        field("speed","Movement multiplier is 1 + equipment/weapon sum capped to ±0.24","fraction",0f,-1000,1000,"modifiers per weapon class");
        field("attackSpeed","Timing divisor is 1 + equipment/weapon sum capped to ±0.24, then status scaling","fraction",0f,-1000,1000,"modifiers per weapon class");
        field("defendingSpeed","Defending movement multiplier is 1 + equipped gear sum; omitted shield values inherit its defendingSpeedAdjustment; selected weapon modifier is excluded","fraction",0f,-1000,1000,"modifiers per weapon class");
    }
    private static void duration(String name,String meaning){field(name,meaning,"ms",0,0,600000,"combat / shield action");}
    private static void field(String n,String m,String u,Object d,double min,double max,String a){FIELDS.put(n,new Field(n,m,u,d,min,max,a));}
    private ItemFields() { }
    public static String label(Object value,String key,String where)throws IOException {
        String s=text(value,where+" → "+key);var field=FIELDS.get(key);
        if(s.isBlank()||s.length()>field.max()||s.chars().anyMatch(c->c<32))throw new IOException(where+" → "+key+": must be a nonempty label of at most "+(int)field.max()+" characters");
        return s;
    }
    public static int integer(Map<String,Object> m,String key,String where)throws IOException {
        var field=FIELDS.get(key);int n=Yaml.integer(m.getOrDefault(key,field.fallback()),where+" → "+key);range(n,field,where);return n;
    }
    public static float number(Map<String,Object> m,String key,String where)throws IOException {
        var field=FIELDS.get(key);float n=decimal(m.getOrDefault(key,field.fallback()),where+" → "+key);range(n,field,where);return n;
    }
    public static boolean bool(Map<String,Object> m,String key,String where)throws IOException{return flag(m.getOrDefault(key,FIELDS.get(key).fallback()),where+" → "+key);}
    private static void range(double n,Field f,String where)throws IOException {
        if(n<f.min()||n>f.max())throw new IOException(where+" → "+f.name()+": must be "+(f.unit().equals("ms")?"a duration in milliseconds":"a value")+" from "+f.min()+" to "+f.max());
    }
    static void put(Map<String,Object> m,String key,Object value) {
        Object fallback=FIELDS.get(key).fallback();boolean same=value instanceof Number a&&fallback instanceof Number b?Double.compare(a.doubleValue(),b.doubleValue())==0:value.equals(fallback);
        if(!same)m.put(key,value);
    }
    public static String markdown() {
        var out=new StringBuilder("| Field | Meaning | Unit | Omitted default | Range | Applies to |\n|---|---|---|---|---|---|\n");
        for(var f:FIELDS.values())out.append("| `").append(f.name()).append("` | ").append(f.meaning()).append(" | ").append(f.unit()).append(" | ").append(f.name().equals("defendingSpeed")?"shield adjustment for shields; 0 otherwise":f.fallback()).append(" | ").append(f.min()).append("…").append(f.max()).append(" | ").append(f.applies()).append(" |\n");
        return out.toString();
    }
    public static void main(String[] args){System.out.print(markdown());}
}
