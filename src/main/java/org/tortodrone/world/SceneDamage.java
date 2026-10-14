package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Stock numeric damage summaries; geometry and mutable targets belong to the scene. */
public final class SceneDamage {
    private SceneDamage() { }
    public static Object field(Object data,String name){
        if(data instanceof FireCatalog.DataValue value)for(var f:value.fields())if(f.name().equals(name))return f.value();
        return null;
    }
    public static float number(Object data,String name,float fallback){Object f=field(data,name);return f instanceof Number n?n.floatValue():fallback;}
    public static Object prune(Object damage,int mask){
        if(!(damage instanceof FireCatalog.DataValue value))return FireCatalog.NullValue.INSTANCE;
        if(value.type().endsWith("$Compound")){
            var fields=new java.util.ArrayList<FireCatalog.FieldValue>();
            for(var f:value.fields())if(f.name().equals("damages")&&f.value() instanceof FireCatalog.ListValue list){
                var children=list.values().stream().map(child->prune(child,mask)).filter(child->child!=FireCatalog.NullValue.INSTANCE).toList();
                fields.add(new FireCatalog.FieldValue(f.name(),f.type(),new FireCatalog.ListValue(list.componentType(),children)));
            }else fields.add(f);
            return new FireCatalog.DataValue(value.type(),fields);
        }
        return (((int)number(value,"flags",1))&mask)!=0?value:FireCatalog.NullValue.INSTANCE;
    }
    public static float amount(Object damage,SceneContent content,SceneContent.Spec target){
        if(!(damage instanceof FireCatalog.DataValue value))return 0;
        if(value.type().endsWith("$Compound")){
            Object raw=field(damage,"damages");float sum=0;
            if(raw instanceof FireCatalog.ListValue list)for(Object child:list.values())sum+=amount(child,content,target);
            return sum;
        }
        float amount;
        if(value.type().endsWith("$DepthScale")||value.type().endsWith("$DepthScaleMulti")){
            Object scale=field(damage,"depthScale");
            if(!(scale instanceof StarterCatalog.Model model))return 0;
            amount=content.scales().getOrDefault(model.name(),0f);
        }else if(value.type().endsWith("$Normal")||value.type().endsWith("$Unblockable"))amount=number(damage,"amount",0);
        else return 0; // Status conditions never become invented direct damage.
        String type=field(damage,"type") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):"normal";
        if(value.type().endsWith("$Unblockable"))return amount;
        if(value.type().endsWith("$DepthScaleMulti")){
            Object split=field(damage,"splitDepth");float fraction=split instanceof StarterCatalog.Model model?content.scales().getOrDefault(model.name(),.5f):.5f;
            String second=field(damage,"type2") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):"normal";
            float offset=fraction>0&&fraction<1?.02f:0;
            return mitigate(modified(amount,Math.min(1,fraction+offset)),target.number("defense."+type,0))+mitigate(modified(amount,Math.min(1,1-fraction+offset)),target.number("defense."+second,0));
        }
        float defense=target.number("defense."+type,0);
        return mitigate(amount,defense);
    }
    /** The native knight HUD packs four damage-type pip counts below the HP total. */
    public static int playerFeedback(Object damage,SceneContent content,SceneContent.Spec defense,int amount) {
        var parts=new java.util.HashMap<String,Float>();components(damage,content,defense,parts);
        int packed=amount<<20;
        int normal=pips(parts.getOrDefault("normal",0f))+pips(parts.getOrDefault("unblockable",0f));
        packed+=normal;
        int shift=5;
        for(String type:java.util.List.of("piercing","elemental","shadow")){
            packed+=pips(parts.getOrDefault(type,0f))<<shift;shift+=5;
        }
        return packed;
    }
    private static int pips(float amount){return (int)Math.ceil(amount/40);}
    private static void components(Object damage,SceneContent content,SceneContent.Spec defense,java.util.Map<String,Float> parts) {
        if(!(damage instanceof FireCatalog.DataValue value))return;
        if(value.type().endsWith("$Compound")){
            if(field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())components(child,content,defense,parts);
            return;
        }
        String type=field(damage,"type") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):"normal";
        if(value.type().endsWith("$DepthScaleMulti")){
            Object scale=field(damage,"depthScale");if(!(scale instanceof StarterCatalog.Model model))return;
            float base=content.scales().getOrDefault(model.name(),0f);
            Object split=field(damage,"splitDepth");float fraction=split instanceof StarterCatalog.Model ref?content.scales().getOrDefault(ref.name(),.5f):.5f;
            float offset=fraction>0&&fraction<1?.02f:0;
            String second=field(damage,"type2") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):"normal";
            parts.merge(type,mitigate(modified(base,Math.min(1,fraction+offset)),defense.number("defense."+type,0)),Float::sum);
            parts.merge(second,mitigate(modified(base,Math.min(1,1-fraction+offset)),defense.number("defense."+second,0)),Float::sum);
        }else parts.merge(value.type().endsWith("$Unblockable")?"unblockable":type,amount(damage,content,defense),Float::sum);
    }
    private static float modified(float amount,float multiplier){
        if(amount<=0||multiplier<=0)return 0;if(multiplier==1)return amount;
        if(multiplier>1)return amount*(multiplier+1)/2;
        int goal=(int)(amount*multiplier/2);float step=amount/2,current=amount-step;
        for(int i=0;i<30;i++){
            step/=2;int actual=(int)mitigate(current,amount);if(actual==goal)break;
            current+=actual>goal?-step:step;
        }
        return current;
    }
    private static float mitigate(float amount,float defense){
        if(amount<=0)return 0;if(defense<=0)return amount;
        // Native DamageSummary.getActualDamage: equal stats halve damage; stronger
        // defense follows its logarithmic window rather than subtracting hit points.
        float scale=.5f;
        if(amount>defense)scale-=(amount-defense)/(2*amount);
        else if(amount<defense)scale+=.19f*(float)Math.log10((defense-amount)/15+1);
        return scale>0?amount*Math.max(0,1-scale):amount;
    }
}
