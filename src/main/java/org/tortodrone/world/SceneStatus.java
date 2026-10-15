package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Instance-local status conditions and the native modifier/expiry snapshots. */
public final class SceneStatus {
    public record Modifiers(float speed,float attackSpeed,float attackDamage,float defense,float heal,
            boolean canMove,boolean canRotate,boolean canAttack,boolean asleep,boolean shocked) {
        public static final Modifiers NORMAL=new Modifiers(1,1,1,1,1,true,true,true,false,false);
    }
    public record State(List<StarterCatalog.Model> conditions,List<Integer> expires,Modifiers modifiers){
        public State { conditions=List.copyOf(conditions);expires=List.copyOf(expires); }
        public static final State NORMAL=new State(List.of(),List.of(),Modifiers.NORMAL);
    }
    private static final class Active {
        final StarterCatalog.Model reference;final SceneContent.Spec spec;final Object data;final float strength;
        final int started;int expires,nextTick,nextShock,shockUntil,damage;
        Active(StarterCatalog.Model reference,SceneContent.Spec spec,Object data,float strength,int stamp,boolean monster){
            this.reference=reference;this.spec=spec;this.data=data;this.strength=strength;started=nextTick=nextShock=stamp;
            float duration=lerp(spec.number("min-duration",0),spec.number("max-duration",0),strength);
            expires=stamp+(int)(duration*(monster?spec.number("monster-duration-multiplier",1):1));
        }
    }
    private final SceneContent content;private final boolean monster;
    private final Map<String,Active> active=new LinkedHashMap<>();
    private final Map<Integer,Integer> shieldCooloff=new HashMap<>();
    public SceneStatus(SceneContent content,boolean monster){this.content=content;this.monster=monster;}
    public void clear(){active.clear();}
    /** Native status hits charge shield health at most once per flag each second. */
    public int shieldDamage(Object damage,int stamp){
        if(!(damage instanceof FireCatalog.DataValue value))return 0;
        if(value.type().endsWith("$Compound")){int amount=0;if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())amount+=shieldDamage(child,stamp);return amount;}
        return value.type().endsWith("$Status")&&SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model reference?shieldDamage(reference,stamp,0):0;
    }
    private int shieldDamage(StarterCatalog.Model reference,int stamp,int depth){
        if(depth>8)return 0;var spec=content.statuses().get(reference.name());if(spec==null)return 0;
        Object data=spec.model("data").arguments().get("data");
        if(spec.text("class","").equals("DepthScaled")){
            Object choices=SceneDamage.field(data,"conditions");StarterCatalog.Model selected=null;float highest=-1;
            if(choices instanceof FireCatalog.ListValue list)for(Object choice:list.values()){float at=SceneDamage.number(choice,"depth",0);if(at<=0&&at>=highest&&SceneDamage.field(choice,"statusCondition") instanceof StarterCatalog.Model model){selected=model;highest=at;}}
            if(selected==null)return 0;var args=new HashMap<>(selected.arguments());args.putAll(reference.arguments());return shieldDamage(new StarterCatalog.Model(selected.name(),args),stamp,depth+1);
        }
        int flags=spec.integer("flags",0);if(stamp<shieldCooloff.getOrDefault(flags,Integer.MIN_VALUE))return 0;
        Object scale=SceneDamage.field(data,"shieldDamage");if(!(scale instanceof StarterCatalog.Model model))return 0;
        if(Math.random()>=parameter(reference,"Chance",spec.number("effective-chance",0),"Status Chance","status-chance."))return 0;
        float power=parameter(reference,"Power",spec.number("effective-power",0),"Status Power","status-power.");
        shieldCooloff.put(flags,stamp+1000);
        return (int)Math.ceil(relativePower(power,0)*content.scales().getOrDefault(model.name(),0f));
    }
    public void apply(Object damage,int stamp){
        if(!(damage instanceof FireCatalog.DataValue value))return;
        if(value.type().endsWith("$Compound")){
            if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())apply(child,stamp);return;
        }
        if(!value.type().endsWith("$Status")||!(SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model reference))return;
        apply(reference,stamp,0);
    }
    private void apply(StarterCatalog.Model reference,int stamp,int depth){
        if(depth>8)return;var spec=content.statuses().get(reference.name());if(spec==null)return;
        Object data=spec.model("data").arguments().get("data");
        if(spec.text("class","").equals("DepthScaled")){
            Object conditions=SceneDamage.field(data,"conditions");StarterCatalog.Model selected=null;float highest=-1;
            if(conditions instanceof FireCatalog.ListValue list)for(Object choice:list.values()){
                float at=SceneDamage.number(choice,"depth",0);if(at<=0&&at>=highest&&SceneDamage.field(choice,"statusCondition") instanceof StarterCatalog.Model model){selected=model;highest=at;}
            }
            if(selected!=null){var args=new HashMap<>(selected.arguments());args.putAll(reference.arguments());apply(new StarterCatalog.Model(selected.name(),args),stamp,depth+1);}return;
        }
        float chance=parameter(reference,"Chance",spec.number("effective-chance",0),"Status Chance","status-chance.");
        if(Math.random()>=chance)return;
        float power=parameter(reference,"Power",spec.number("effective-power",0),"Status Power","status-power.");
        float strength=relativePower(power,0);if(strength<=0)return;
        String kind=spec.text("class","");var prior=active.get(kind);
        if(prior!=null&&prior.strength>strength)return;
        active.put(kind,new Active(reference,spec,data,strength,stamp,monster));
    }
    private float parameter(StarterCatalog.Model reference,String key,float fallback,String selector,String prefix){
        Object raw=reference.arguments().get(key);float value=raw instanceof Number n?n.floatValue():fallback;
        if(reference.arguments().get(selector) instanceof FireCatalog.EnumValue e&&!e.name().equals("CUSTOM"))value=content.stats().getOrDefault(prefix+e.name(),value);
        return value;
    }
    public State state(){
        float speed=1,attackSpeed=1,attackDamage=1,defense=1,heal=1;boolean move=true,rotate=true,attack=true,asleep=false,shocked=false;
        var refs=new ArrayList<StarterCatalog.Model>();var expires=new ArrayList<Integer>();
        for(var entry:active.entrySet()){
            var a=entry.getValue();refs.add(a.reference);expires.add(a.expires);
            switch(entry.getKey()){
                case "Stun" -> {float modifier=1-parameter(a,"minSpeedReduction","maxSpeedReduction");speed*=modifier;attackSpeed*=modifier;}
                case "Poison" -> {attackDamage*=1-parameter(a,"minAttackReduction","maxAttackReduction");defense*=1-parameter(a,"minDefenseReduction","maxDefenseReduction");heal*=monster?parameter(a,"minHealModifier","maxHealModifier"):0;}
                case "Freeze" -> {move=false;rotate=false;}
                case "Sleep" -> {asleep=true;move=false;rotate=false;attack=false;}
                case "Shock" -> {if(a.shockUntil>0){shocked=true;move=false;rotate=false;defense*=1-parameter(a,"minDefenseDebuff","maxDefenseDebuff");}}
                default -> { }
            }
        }
        return new State(refs,expires,new Modifiers(speed,attackSpeed,attackDamage,defense,heal,move,rotate,attack,asleep,shocked));
    }
    public void damaged(float damage,int stamp){
        var freeze=active.get("Freeze");if(freeze!=null&&stamp-freeze.started>=250&&damage>0)active.remove("Freeze");
        var sleep=active.get("Sleep");if(sleep!=null){sleep.damage+=(int)Math.ceil(damage);if(sleep.damage>lerp(scale(sleep,"wakeDamageStart"),scale(sleep,"wakeDamageEnd"),sleep.strength))active.remove("Sleep");}
    }
    public void advance(int stamp,int elapsed,boolean moving,java.util.function.DoubleConsumer damage,java.util.function.DoubleConsumer heal){
        for(var iterator=active.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();var a=entry.getValue();
            if(entry.getKey().equals("Fire")&&moving)a.expires-=(int)(elapsed*.25f);
            if(stamp>=a.expires){iterator.remove();continue;}
            if(entry.getKey().equals("Fire")&&stamp>=a.nextTick){damage.accept(lerp(scale(a,"damageStart"),scale(a,"damageEnd"),a.strength));a.nextTick=stamp+(int)SceneDamage.number(a.data,"damageInterval",0);}
            if(entry.getKey().equals("Sleep")&&stamp>=a.nextTick){heal.accept(parameter(a,"minHealthRegen","maxHealthRegen"));a.nextTick=stamp+(int)SceneDamage.number(a.data,"healthRegenInterval",0);}
            if(entry.getKey().equals("Shock")){
                if(stamp>=a.nextShock){a.shockUntil=stamp+(int)parameter(a,"minSpasmDuration","maxSpasmDuration");a.nextShock=stamp+(int)parameter(a,"minSpasmInterval","maxSpasmInterval");}
                if(stamp>=a.shockUntil)a.shockUntil=0;
            }
        }
    }
    private float scale(Active a,String field){Object value=SceneDamage.field(a.data,field);return value instanceof StarterCatalog.Model model?content.scales().getOrDefault(model.name(),0f):0;}
    private static float parameter(Active a,String min,String max){return lerp(SceneDamage.number(a.data,min,0),SceneDamage.number(a.data,max,0),a.strength);}
    private static float lerp(float min,float max,float t){return min+(max-min)*t;}
    private static float relativePower(float power,int resist){int diff=(int)Math.floor(power-resist);return diff<=-50?0:diff>=100?1:Math.abs(diff)<=50?(diff+50)/200f:(diff-50)/100f+.5f;}
}
