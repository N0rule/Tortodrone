package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.town.TownCatalog;

/** Numeric stock level tables and variants, evaluated against the authoritative loadout. */
public final class GearStats {
    private GearStats() { }
    private static final Set<Integer> GEAR=Set.of(0,1,2,5,10,11,12);
    public static void validate(Map<String,Float> stats){
        for(var entry:stats.entrySet())if(entry.getKey().startsWith("gear.")&&entry.getKey().endsWith(".table")){
            float table=entry.getValue();
            if(table!=Math.rint(table)||table<1||table>900000||!stats.containsKey("gear.table."+Math.round(table)+".levels.0.health-bonus"))
                throw new IllegalArgumentException("Missing or invalid equipment level table: "+entry.getKey());
        }
    }
    private static String prefix(Map<String,Float> stats,WorldProfile.Item item){
        String p="gear."+item.config()+".";Float table=stats.get(p+"table");
        return table==null?p:"gear.table."+Math.round(table)+".";
    }
    public static float value(Map<String,Float> stats,WorldProfile.Item item,String field){
        String p="gear."+item.config()+".";
        return stats.getOrDefault(prefix(stats,item)+"levels."+item.level()+"."+field,stats.getOrDefault(p+field,0f));
    }
    public static float defense(Map<String,Float> stats,WorldProfile.Item item,String type,TownCatalog town){
        float value=value(stats,item,"defense."+type);int rarity=Math.round(stats.getOrDefault("gear."+item.config()+".rarity",0f));
        for(var variant:item.variants())value+=stats.getOrDefault("gear.variant."+variant.name()+".defense."+rarity+"."+type,0f);
        return value;
    }
    public static EquipmentCatalog.Modifiers modifiers(Map<String,Float> stats,WorldProfile.Item item,EquipmentCatalog.WeaponClass weapon){
        String p=prefix(stats,item)+"levels."+item.level()+".modifiers."+weapon+".";
        return new EquipmentCatalog.Modifiers(stats.getOrDefault(p+"chargeTime",0f),stats.getOrDefault(p+"speed",0f),stats.getOrDefault(p+"attackSpeed",0f),0);
    }
    public record Step(float value,boolean relative){ }
    /** Captured at attack start; changing equipment cannot rewrite an in-flight projectile. */
    public record Damage(List<Step> steps,float capped,Map<String,Float> tagged){
        public static final Damage NONE=new Damage(List.of(),0,Map.of());
        public Damage { steps=List.copyOf(steps);tagged=Map.copyOf(tagged); }
        public float apply(float amount,String tags){
            for(var step:steps)amount=step.relative()?SceneDamage.modified(amount,1+step.value()):amount*Math.max(0,1+step.value());
            float bonus=capped;for(String tag:tags.split(","))bonus+=tagged.getOrDefault(tag,0f);
            return SceneDamage.modified(amount,1+Math.clamp(bonus,-.48f,.48f));
        }
    }
    public static Damage damage(Map<String,Float> stats,WorldProfile profile,WorldProfile.Item weapon,TownCatalog town){
        return damage(stats,profile,weapon,town,damageTags(stats));
    }
    public static Map<String,Map<String,Float>> damageTags(Map<String,Float> stats){
        var result=new HashMap<String,Map<String,Float>>();
        stats.forEach((key,value)->{int at=key.indexOf(".tagged.");if(at>=0)result.computeIfAbsent(key.substring(0,at),ignored->new HashMap<>()).put(key.substring(at+8),value);});
        result.replaceAll((key,value)->Map.copyOf(value));return Map.copyOf(result);
    }
    public static Damage damage(Map<String,Float> stats,WorldProfile profile,WorldProfile.Item weapon,TownCatalog town,Map<String,Map<String,Float>> tags){
        if(profile==null||weapon==null)return Damage.NONE;
        String cls=switch(weapon.slot()){case SWORD->"SWORD";case GUN->"HANDGUN";case BOMB->"BOMB";default->"NONE";};
        var steps=new ArrayList<Step>();var tagged=new HashMap<String,Float>();float capped=0;
        int depth=Math.round(stats.getOrDefault("player.depth",0f));
        for(var item:profile.items())if(item.id()==weapon.id()||item.equippedSlot()!=null&&GEAR.contains(item.equippedSlot())){
            String p=prefix(stats,item)+"levels."+item.level()+".damage."+cls+".";
            int count=Math.round(stats.getOrDefault(p+"count",0f));
            for(int i=0;i<count;i++){
                String r=p+i+".";if(depth<stats.getOrDefault(r+"depth",0f))continue;
                float value=stats.getOrDefault(r+"value",0f);int kind=Math.round(stats.getOrDefault(r+"kind",0f));
                if(kind==2)capped+=value;
                else if(kind==3)tags(tags,p+i,tagged);
                // Uncapped level damage belongs to the selected weapon, as in the native gear modifier path.
                else if(kind==0||kind==1&&item.id()==weapon.id())steps.add(new Step(value,kind==1));
            }
            for(var variant:item.variants())tags(tags,"gear.variant."+variant.name(),tagged);
        }
        for(String variant:town.spriteVariants(profile))tags(tags,"gear.variant."+variant,tagged);
        if(steps.isEmpty()&&capped==0&&tagged.isEmpty())return Damage.NONE;
        return new Damage(steps,capped,tagged);
    }
    private static void tags(Map<String,Map<String,Float>> tags,String key,Map<String,Float> out){
        tags.getOrDefault(key,Map.of()).forEach((tag,value)->out.merge(tag,value,Float::sum));
    }
}
