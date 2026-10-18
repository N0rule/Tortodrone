package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Instance-local status conditions and the native modifier/expiry snapshots. */
public final class SceneStatus {
    public record Modifiers(float speed,float attackSpeed,float attackDamage,float defense,float heal,
            boolean canMove,boolean canRotate,boolean canAttack,boolean asleep,boolean shocked,boolean canDefend,boolean canBeTargeted,float relativeDamage) {
        public Modifiers(float speed,float attackSpeed,float attackDamage,float defense,float heal,boolean canMove,boolean canRotate,boolean canAttack,boolean asleep,boolean shocked){this(speed,attackSpeed,attackDamage,defense,heal,canMove,canRotate,canAttack,asleep,shocked,true,true,1);}
        public static final Modifiers NORMAL=new Modifiers(1,1,1,1,1,true,true,true,false,false);
    }
    public record State(List<StarterCatalog.Model> conditions,List<Integer> expires,Modifiers modifiers,Set<Long> cursedItems){
        public State(List<StarterCatalog.Model> conditions,List<Integer> expires,Modifiers modifiers){this(conditions,expires,modifiers,Set.of());}
        public State { conditions=List.copyOf(conditions);expires=List.copyOf(expires);cursedItems=Set.copyOf(cursedItems); }
        public static final State NORMAL=new State(List.of(),List.of(),Modifiers.NORMAL);
    }
    private static final class Active {
        final StarterCatalog.Model reference;final SceneContent.Spec spec;final Object data;final float strength;
        final int started;int expires,nextTick,nextShock,shockUntil,damage,nextSignal,shield;float power;final Set<Long> cursedItems=new HashSet<>();
        Active(StarterCatalog.Model reference,SceneContent.Spec spec,Object data,float strength,int stamp,boolean monster){
            this.reference=reference;this.spec=spec;this.data=data;this.strength=strength;started=nextTick=nextShock=stamp;
            float duration=lerp(spec.number("min-duration",0),spec.number("max-duration",0),strength);
            Object override=reference.arguments().get("Duration");if(override instanceof Number n)duration=n.floatValue();
            else if(override instanceof String value)try{duration=Float.parseFloat(value);}catch(NumberFormatException ignored){}
            expires=stamp+(int)(duration*(monster?spec.number("monster-duration-multiplier",1):1));
            if(spec.text("class","").equals("Sleep"))nextTick=stamp+(int)(SceneDamage.number(data,"healthRegenInterval",2000)+SceneDamage.number(data,"healthRegenDelay",4000));
            nextSignal=stamp+Math.max(1,(int)SceneDamage.number(data,"signalInterval",0));
        }
    }
    private final SceneContent content;private final boolean monster;
    private final Map<String,Active> active=new LinkedHashMap<>();
    private final Map<Integer,Integer> shieldCooloff=new HashMap<>();
    private org.tortodrone.character.WorldProfile equipment;
    public void equipment(org.tortodrone.character.WorldProfile profile){equipment=profile;}
    public record Pulse(Object damage,float radius,int mask) { }
    private final ArrayDeque<Pulse> pulses=new ArrayDeque<>();
    private final ArrayDeque<FireCatalog.Action> actions=new ArrayDeque<>();
    public List<FireCatalog.Action> takeActions(){var result=List.copyOf(actions);actions.clear();return result;}
    public List<Pulse> takePulses(){var result=List.copyOf(pulses);pulses.clear();return result;}
    public SceneStatus(SceneContent content,boolean monster){this.content=content;this.monster=monster;}
    public void clear(){active.clear();pulses.clear();actions.clear();}
    /** Native status hits charge shield health at most once per flag each second. */
    public int shieldDamage(Object damage,int stamp){
        if(!(damage instanceof FireCatalog.DataValue value))return 0;
        if(value.type().endsWith("$Compound")){int amount=0;if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())amount+=shieldDamage(child,stamp);return amount;}
        return SceneDamage.status(damage)&&SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model reference?shieldDamage(reference,stamp,0):0;
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
    public void applyResolved(StarterCatalog.Model reference,Object data,int stamp){
        applyResolved(reference,data,stamp,false);
    }
    private void applyResolved(StarterCatalog.Model reference,Object data,int stamp,boolean checkChance){
        if(!(data instanceof FireCatalog.DataValue value))return;
        String kind=value.type().substring(value.type().lastIndexOf('$')+1);
        if(kind.equals("DepthScaled")){
            var selected=depthCondition(data);
            if(selected!=null){var args=new HashMap<>(selected.arguments());args.putAll(reference.arguments());apply(new StarterCatalog.Model(selected.name(),args),stamp,0,checkChance);}
            return;
        }
        var fields=new HashMap<String,String>();fields.put("class",kind);
        fields.put("min-duration",Float.toString(SceneDamage.number(data,"minDuration",SceneDamage.number(data,"duration",0))));
        fields.put("max-duration",Float.toString(SceneDamage.number(data,"maxDuration",SceneDamage.number(data,"duration",0))));
        fields.put("monster-duration-multiplier",Float.toString(SceneDamage.number(data,"monsterDurationMultiplier",1)));
        fields.put("flags",Integer.toString((int)SceneDamage.number(data,"flags",0)));fields.put("mask",Integer.toString((int)SceneDamage.number(data,"mask",0)));
        float chance=resolvedParameter(reference,data,"Chance","chance","Status Chance","statusChance","status-chance.",1);
        float power=resolvedParameter(reference,data,"Power","power","Status Power","statusPower","status-power.",0);
        if(checkChance&&Math.random()>=chance)return;
        var prepared=content.statuses().get(reference.name());if(prepared!=null)prepared.fields().forEach((key,value2)->{if(key.endsWith("-action")||key.startsWith("scale-"))fields.put(key,value2);});
        add(reference,new SceneContent.Spec(fields),data,power,stamp);
    }
    private static StarterCatalog.Model depthCondition(Object data){
        StarterCatalog.Model selected=null;float highest=-1;
        if(SceneDamage.field(data,"conditions") instanceof FireCatalog.ListValue list)for(Object choice:list.values()){
            float at=SceneDamage.number(choice,"depth",0);
            if(at<=0&&at>=highest&&SceneDamage.field(choice,"statusCondition") instanceof StarterCatalog.Model model){selected=model;highest=at;}
        }
        return selected;
    }
    public boolean cloaked(){return active.containsKey("Cloak");}
    public void breakCloak(){active.remove("Cloak");}
    public void apply(Object damage,int stamp){
        if(!(damage instanceof FireCatalog.DataValue value))return;
        if(value.type().endsWith("$Compound")){
            if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())apply(child,stamp);return;
        }
        if(!SceneDamage.status(damage)||!(SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model reference))return;
        if(SceneDamage.field(damage,"statusData") instanceof FireCatalog.DataValue data)applyResolved(reference,data,stamp,true);else apply(reference,stamp,0,true);
    }
    private void apply(StarterCatalog.Model reference,int stamp,int depth){
        apply(reference,stamp,depth,false);
    }
    private void apply(StarterCatalog.Model reference,int stamp,int depth,boolean checkChance){
        if(depth>8)return;var spec=content.statuses().get(reference.name());if(spec==null)return;
        Object data=spec.model("data").arguments().get("data");
        if(spec.text("class","").equals("DepthScaled")){
            Object conditions=SceneDamage.field(data,"conditions");StarterCatalog.Model selected=null;float highest=-1;
            if(conditions instanceof FireCatalog.ListValue list)for(Object choice:list.values()){
                float at=SceneDamage.number(choice,"depth",0);if(at<=0&&at>=highest&&SceneDamage.field(choice,"statusCondition") instanceof StarterCatalog.Model model){selected=model;highest=at;}
            }
            if(selected!=null){var args=new HashMap<>(selected.arguments());args.putAll(reference.arguments());apply(new StarterCatalog.Model(selected.name(),args),stamp,depth+1,checkChance);}return;
        }
        float chance=parameter(reference,"Chance",spec.number("effective-chance",1),"Status Chance","status-chance.");
        if(checkChance&&Math.random()>=chance)return;
        float power=parameter(reference,"Power",spec.number("effective-power",0),"Status Power","status-power.");
        add(reference,spec,data,power,stamp);
    }
    private void add(StarterCatalog.Model reference,SceneContent.Spec spec,Object data,float power,int stamp){
        if(reference.arguments().get("Mask") instanceof Number mask){var fields=new HashMap<>(spec.fields());fields.put("mask",Integer.toString(mask.intValue()));spec=new SceneContent.Spec(fields);}
        int flags=spec.integer("flags",0),mask=spec.integer("mask",flags),resist=0;
        for(var a:active.values())if(a.spec.text("class","").equals("Resist")&&(a.spec.integer("mask",0)&flags)!=0)resist+=(int)parameter(a.reference,"Power",SceneDamage.number(a.data,"power",0),"Status Power","status-power.");
        // Shield resistance belongs to active blocking, never passive knight defense.
        if(equipment!=null)for(var item:equipment.items())if(item.equippedSlot()!=null&&Set.of(0,1,5,10,11,12).contains(item.equippedSlot())){
            int base=Math.round(content.stats().getOrDefault("gear."+item.config()+".status-resist."+flags,0f)),extra=0;
            for(var variant:item.variants()){
                String prefix="gear.variant."+variant.name()+".status-resist.";
                if((Math.round(content.stats().getOrDefault(prefix+"mask",0f))&flags)==0)continue;
                float value=content.stats().getOrDefault(prefix+"value",0f);extra+=(int)Math.ceil(value*(content.stats().getOrDefault(prefix+"relative",0f)!=0?Math.min(1,1-base/100f):1));
            }
            resist+=base+extra;
        }
        float strength=relativePower(power,resist);if(strength<=0)return;
        String kind=spec.text("class","");String key=kind.equals("Buff")?kind+":"+reference.arguments().getOrDefault("Source",SceneDamage.field(data,"source")):kind;
        if(active.values().stream().anyMatch(a->a.spec.text("class","").equals("Shield")))return;
        var prior=active.get(key);
        if(prior!=null){
            if(kind.equals("Sleep")||kind.equals("Grab"))return;
            if(!Boolean.FALSE.equals(SceneDamage.field(prior.data,"cooloff"))&&stamp-prior.started<1000)return;
            if(prior.power>power)return;
        }
        for(var a:active.values())if(a!=prior&&!(kind.equals("Buff")&&a.spec.text("class","").equals("Buff"))&&(a.spec.integer("flags",0)&mask)!=0&&a.power>power)return;
        active.entrySet().removeIf(e->!e.getKey().equals(key)&&!(kind.equals("Buff")&&e.getValue().spec.text("class","").equals("Buff"))&&(e.getValue().spec.integer("flags",0)&mask)!=0);
        var next=new Active(reference,spec,data,strength,stamp,monster);
        next.power=power;
        if(kind.equals("Shield"))next.shield=(int)Math.ceil(scale(next,"health"));
        if(prior!=null&&Boolean.TRUE.equals(SceneDamage.field(data,"additiveDuration")))next.expires+=Math.max(0,prior.expires-stamp);
        if(kind.equals("Curse")&&equipment!=null){
            var weapons=new ArrayList<Long>();var pickups=new ArrayList<Long>();
            for(var item:equipment.items())if(item.equippedSlot()!=null&&item.equippedSlot()>=6&&item.equippedSlot()<=9)weapons.add(item.id());else if(item.id()<0&&item.config().startsWith("Pickup/"))pickups.add(item.id());
            Collections.shuffle(weapons);Collections.shuffle(pickups);
            next.cursedItems.addAll(weapons.subList(0,Math.min(weapons.size(),(int)parameter(next,"minCurseWeapons","maxCurseWeapons"))));
            next.cursedItems.addAll(pickups.subList(0,Math.min(pickups.size(),(int)parameter(next,"minCursePickups","maxCursePickups"))));
        }
        active.put(key,next);
    }
    private float resolvedParameter(StarterCatalog.Model reference,Object data,String argument,String field,String selector,String configSelector,String prefix,float fallback){
        var args=new HashMap<>(reference.arguments());args.putIfAbsent(argument,SceneDamage.number(data,field,fallback));
        Object choice=SceneDamage.field(data,configSelector);if(choice!=null)args.putIfAbsent(selector,choice);
        return parameter(new StarterCatalog.Model(reference.name(),args),argument,fallback,selector,prefix);
    }
    private float parameter(StarterCatalog.Model reference,String key,float fallback,String selector,String prefix){
        Object raw=reference.arguments().get(key);float value=raw instanceof Number n?n.floatValue():fallback;
        if(reference.arguments().get(selector) instanceof FireCatalog.EnumValue e&&!e.name().equals("CUSTOM"))value=content.stats().getOrDefault(prefix+e.name(),value);
        return value;
    }
    public State state(){
        if(active.isEmpty())return State.NORMAL;
        float speed=1,attackSpeed=1,attackDamage=1,defense=1,heal=1,relativeDamage=1;boolean move=true,rotate=true,attack=true,asleep=false,shocked=false,defend=true,targeted=true;
        var cursed=new HashSet<Long>();
        var refs=new ArrayList<StarterCatalog.Model>();var expires=new ArrayList<Integer>();
        for(var entry:active.entrySet()){
            var a=entry.getValue();refs.add(a.reference);expires.add(a.expires);
            cursed.addAll(a.cursedItems);
            switch(a.spec.text("class","")){
                case "Buff" -> {speed*=1+buff(a,"Speed Modifier","speedModifier");attackSpeed*=1+buff(a,"Attack Speed Modifier","attackSpeedModifier");attackDamage*=1+buff(a,"Damage Modifier","damageModifier");defense*=1+buff(a,"Defense Modifier","defenseModifier");relativeDamage*=1+buff(a,"Relative Damage Modifier","relativeDamageModifier");}
                case "Stun" -> {float modifier=1-parameter(a,"minSpeedReduction","maxSpeedReduction");speed*=modifier;attackSpeed*=modifier;}
                case "Poison" -> {attackDamage*=1-parameter(a,"minAttackReduction","maxAttackReduction");defense*=1-parameter(a,"minDefenseReduction","maxDefenseReduction");heal*=monster?parameter(a,"minHealModifier","maxHealModifier"):0;}
                case "Freeze" -> {move=false;rotate=false;}
                case "Grab" -> {move=false;rotate=false;defend=false;}
                case "Stop" -> {move=false;rotate=false;defend=false;attack=false;}
                case "Shield" -> {move=false;defense*=1000;}
                case "Cloak" -> targeted=false;
                case "Sleep" -> {asleep=true;move=false;rotate=false;attack=false;}
                case "Shock" -> {if(a.shockUntil>0){shocked=true;move=false;rotate=false;defense*=1-parameter(a,"minDefenseDebuff","maxDefenseDebuff");}}
                default -> { }
            }
        }
        return new State(refs,expires,new Modifiers(Math.max(0,speed),Math.max(0,attackSpeed),Math.max(0,attackDamage),Math.max(0,defense),Math.max(0,heal),move,rotate,attack,asleep,shocked,defend,targeted,Math.max(0,relativeDamage)),cursed);
    }
    public float damaged(float damage,int stamp){
        var shield=active.get("Shield");if(shield!=null&&damage>0){shield.shield-=(int)Math.ceil(damage);queueAction(shield,"damage-action");if(shield.shield<=0)active.remove("Shield");}
        float extra=0;var freeze=active.get("Freeze");if(freeze!=null&&stamp-freeze.started>=250&&damage>0){active.remove("Freeze");extra=lerp(scale(freeze,"damageStart"),scale(freeze,"damageEnd"),freeze.strength);}
        var sleep=active.get("Sleep");if(sleep!=null){sleep.damage+=(int)Math.ceil(damage);if(sleep.damage>lerp(scale(sleep,"wakeDamageStart"),scale(sleep,"wakeDamageEnd"),sleep.strength))sleep.expires=stamp;}
        return extra;
    }
    public float used(long item){var curse=active.get("Curse");return curse!=null&&(monster&&Math.random()<curse.strength||curse.cursedItems.contains(item))?lerp(scale(curse,"damageStart"),scale(curse,"damageEnd"),curse.strength):0;}
    public void advance(int stamp,int elapsed,boolean moving,java.util.function.DoubleConsumer damage,java.util.function.DoubleConsumer heal){
        if(active.isEmpty())return;
        var finished=new ArrayList<StarterCatalog.Model>();
        for(var iterator=active.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();var a=entry.getValue();
            if(entry.getKey().equals("Fire")&&moving)a.expires-=(int)(elapsed*.25f);
            if(stamp>=a.expires){iterator.remove();queueAction(a,"finished-action");if(entry.getKey().equals("Sleep")&&SceneDamage.field(a.data,"finishedStatus") instanceof StarterCatalog.Model ref)finished.add(ref);if(monster&&entry.getKey().equals("Freeze"))damage.accept(lerp(scale(a,"damageStart"),scale(a,"damageEnd"),a.strength));continue;}
            if(SceneDamage.number(a.data,"signalInterval",0)>0&&stamp>=a.nextSignal){queueAction(a,"signal-action");a.nextSignal=stamp+Math.max(1,(int)SceneDamage.number(a.data,"signalInterval",0));}
            if(entry.getKey().equals("Shield")&&stamp>=a.nextTick){a.shield+=(int)Math.ceil(scale(a,"healScale"));a.nextTick=stamp+Math.max(1,(int)SceneDamage.number(a.data,"healthRegenInterval",500));}
            if(entry.getKey().equals("Fire")&&stamp>=a.nextTick){damage.accept(lerp(scale(a,"damageStart"),scale(a,"damageEnd"),a.strength));a.nextTick=stamp+Math.max(1,(int)SceneDamage.number(a.data,"damageInterval",2000));
                if(Math.random()<.3f+a.strength&&SceneDamage.field(a.data,"fireSpread") instanceof FireCatalog.DataValue spread&&SceneDamage.field(spread,"statusCondition") instanceof StarterCatalog.Model ref){
                    var args=new HashMap<>(ref.arguments());args.put("Power",parameter(a.reference,"Power",a.spec.number("effective-power",0),"Status Power","status-power.")*.75f);
                    var fields=new ArrayList<FireCatalog.FieldValue>();for(var f:spread.fields())fields.add(f.name().equals("statusCondition")?new FireCatalog.FieldValue(f.name(),f.type(),new StarterCatalog.Model(ref.name(),args)):f);
                    pulses.add(new Pulse(new FireCatalog.DataValue(spread.type(),fields),.5f,129));
                }}
            if(entry.getKey().equals("Sleep")&&stamp>=a.nextTick){heal.accept(monster?parameter(a,"minHealthRegen","maxHealthRegen"):parameter(a,"minPlayerHealthRegen","maxPlayerHealthRegen"));a.nextTick=stamp+Math.max(1,(int)SceneDamage.number(a.data,"healthRegenInterval",2000));}
            if(entry.getKey().equals("Shock")){
                if(a.shockUntil>0&&stamp>=a.shockUntil){a.shockUntil=0;a.nextShock=stamp+(int)lerp(SceneDamage.number(a.data,"minSpasmInterval",1000),SceneDamage.number(a.data,"maxSpasmInterval",4000),(float)Math.random());}
                if(a.shockUntil==0&&stamp>=a.nextShock){a.shockUntil=stamp+Math.max(1,(int)parameter(a,"minSpasmDuration","maxSpasmDuration"));a.nextShock=a.shockUntil;
                    Object arc=SceneDamage.field(a.data,monster?"monsterArcDamage":"arcDamage");if(arc instanceof FireCatalog.DataValue)pulses.add(new Pulse(arc,SceneDamage.number(a.data,"arcExpand",1),(int)SceneDamage.number(a.data,"arcCollisionMask",1)));}
            }
        }
        for(var reference:finished)apply(reference,stamp,0);
    }
    private static float buff(Active a,String argument,String field){Object value=a.reference.arguments().get(argument);return value instanceof Number n?n.floatValue():SceneDamage.number(a.data,field,0);}
    private void queueAction(Active a,String field){String json=a.spec.text(field,"");if(json.isEmpty()||actions.size()>=128)return;try{actions.add(FireCatalog.actionJson(json));}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared status action",invalid);}}
    private float scale(Active a,String field){if(a.spec.fields().containsKey("scale-"+field))return a.spec.number("scale-"+field,0);Object value=SceneDamage.field(a.data,field);return value instanceof StarterCatalog.Model model?content.scales().getOrDefault(model.name(),0f):0;}
    private static float parameter(Active a,String min,String max){return lerp(SceneDamage.number(a.data,min,0),SceneDamage.number(a.data,max,0),a.strength);}
    private static float lerp(float min,float max,float t){return min+(max-min)*t;}
    private static float relativePower(float power,int resist){int diff=(int)Math.floor(power-resist);return diff<=-50?0:diff>=100?1:Math.abs(diff)<=50?(diff+50)/200f:(diff-50)/100f+.5f;}
}
