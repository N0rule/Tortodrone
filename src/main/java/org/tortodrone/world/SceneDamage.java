package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Stock numeric damage summaries; geometry and mutable targets belong to the scene. */
public final class SceneDamage {
    private SceneDamage() { }
    public static KnightCombat.Impulse impulse(Object config,float ox,float oy,float x,float y,int level,int stamp){
        if(!(config instanceof FireCatalog.DataValue))return null;
        int difference=level-(int)number(config,"level",1);if(difference>2)return null;
        float attenuation=difference>0?(float)Math.pow(2,-difference):1;
        Object translation=field(config,"translation");float angle=(float)Math.atan2(y-oy,x-ox),c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        float tx=number(translation,"x",0),ty=number(translation,"y",0);
        return new KnightCombat.Impulse(stamp,Math.max(1,(int)number(config,"duration",0)),(tx*c-ty*s)*attenuation,(tx*s+ty*c)*attenuation);
    }
    public static KnightCombat.Impulse combine(KnightCombat.Impulse next,KnightCombat.Impulse previous){
        if(previous==null||next.started()>=previous.started()+previous.duration())return next;
        float remaining=1-previous.fraction(next.started());
        return new KnightCombat.Impulse(next.started(),Math.max(next.duration(),previous.started()+previous.duration()-next.started()),next.x()+previous.x()*remaining,next.y()+previous.y()*remaining);
    }
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
    public static boolean hasStatus(Object damage){
        if(!(damage instanceof FireCatalog.DataValue value))return false;
        if(value.type().endsWith("$Status"))return true;
        if(value.type().endsWith("$Compound")&&field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())if(hasStatus(child))return true;
        return false;
    }
    public static float amount(Object damage,SceneContent content,SceneContent.Spec target){
        return amount(damage,content,target,1);
    }
    public static float amount(Object damage,SceneContent content,SceneContent.Spec target,float multiplier){
        return amount(damage,content,target,multiplier,1);
    }
    public static float amount(Object damage,SceneContent content,SceneContent.Spec target,float multiplier,float blockableScale){
        var parts=new java.util.HashMap<String,Float>();components(damage,content,target,parts,multiplier,blockableScale);
        float total=0;for(float part:parts.values())total+=part;return total;
    }
    /** The native knight HUD packs four damage-type pip counts below the HP total. */
    public static int playerFeedback(Object damage,SceneContent content,SceneContent.Spec defense,int amount) {
        return playerFeedback(damage,content,defense,amount,1);
    }
    public static int playerFeedback(Object damage,SceneContent content,SceneContent.Spec defense,int amount,float multiplier) {
        return playerFeedback(damage,content,defense,amount,multiplier,1);
    }
    public static int playerFeedback(Object damage,SceneContent content,SceneContent.Spec defense,int amount,float multiplier,float blockableScale) {
        var parts=new java.util.HashMap<String,Float>();components(damage,content,defense,parts,multiplier,blockableScale);
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
    private static void components(Object damage,SceneContent content,SceneContent.Spec defense,java.util.Map<String,Float> parts,float multiplier,float blockableScale) {
        var raw=new java.util.HashMap<String,Float>();rawParts(damage,content,raw,multiplier,blockableScale);
        raw.forEach((type,amount)->parts.merge(type,type.equals("unblockable")?amount:mitigate(amount,defense.number("defense."+type,0)),Float::sum));
    }
    private static void rawParts(Object damage,SceneContent content,java.util.Map<String,Float> parts,float multiplier,float blockableScale){
        if(!(damage instanceof FireCatalog.DataValue value))return;
        if(value.type().endsWith("$Compound")){
            if(field(damage,"damages") instanceof FireCatalog.ListValue list)for(Object child:list.values())rawParts(child,content,parts,multiplier,blockableScale);return;
        }
        String type=field(damage,"type") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):
            value.type().substring(value.type().lastIndexOf('$')+1).toLowerCase(java.util.Locale.ROOT);
        float amount;
        if(value.type().endsWith("$DepthScale")||value.type().endsWith("$DepthScaleMulti")){
            if(!(field(damage,"depthScale") instanceof StarterCatalog.Model model))return;
            amount=content.scales().getOrDefault(model.name(),0f);
        }else if(java.util.Set.of("normal","piercing","elemental","shadow","unblockable").contains(type))amount=number(damage,"amount",0);
        else return;
        amount*=Math.max(0,multiplier)*(type.equals("unblockable")?1:Math.max(0,blockableScale));
        if(value.type().endsWith("$DepthScaleMulti")){
            Object split=field(damage,"splitDepth");float fraction=split instanceof StarterCatalog.Model ref?content.scales().getOrDefault(ref.name(),.5f):.5f;
            String second=field(damage,"type2") instanceof FireCatalog.EnumValue e?e.name().toLowerCase(java.util.Locale.ROOT):"normal";
            float offset=fraction>0&&fraction<1?.02f:0;
            parts.merge(type,modified(amount,Math.min(1,fraction+offset)),Float::sum);
            parts.merge(second,modified(amount,Math.min(1,1-fraction+offset)),Float::sum);
        }else parts.merge(type,amount,Float::sum);
    }
    public static Object select(Object damage,boolean unblockable){
        if(!(damage instanceof FireCatalog.DataValue value))return FireCatalog.NullValue.INSTANCE;
        if(value.type().endsWith("$Compound")){
            var fields=new java.util.ArrayList<FireCatalog.FieldValue>();
            for(var field:value.fields()){
                if(field.name().equals("damages")&&field.value() instanceof FireCatalog.ListValue list)
                    fields.add(new FireCatalog.FieldValue(field.name(),field.type(),new FireCatalog.ListValue(list.componentType(),list.values().stream().map(child->select(child,unblockable)).filter(child->child!=FireCatalog.NullValue.INSTANCE).toList())));
                else fields.add(field);
            }
            return new FireCatalog.DataValue(value.type(),fields);
        }
        return value.type().endsWith("$Unblockable")==unblockable?value:FireCatalog.NullValue.INSTANCE;
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
