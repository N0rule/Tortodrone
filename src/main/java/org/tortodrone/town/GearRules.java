package org.tortodrone.town;

import java.io.IOException;
import java.util.*;

/** Numeric rules read from the unchanged client's resolved item/forge properties. */
public final class GearRules {
    public record Gear(int levels,int baseHeat,int maxHeat,String forge) {
        public Gear{if(levels<0||levels>100||baseHeat<0||maxHeat<0)throw new IllegalArgumentException("Invalid gear heat rules");Objects.requireNonNull(forge);}
        public boolean maximum(int level){return levels<2||level>=levels-1;}
        public float addHeat(int level,float progress,float amount){
            if(maximum(level)||progress>=1)return progress;
            float step=(2f*maxHeat/levels-2f*baseHeat)/(levels-1);
            int previous=(int)(level*(2f*baseHeat+(level-1)*step)/2f);
            int next=(int)((level+1)*(2f*baseHeat+level*step)/2f);
            return next>previous?Math.clamp(progress+amount/(next-previous),0,1):progress;
        }
    }
    public record Forge(List<Integer> crystals,List<Float> success) {
        public Forge{crystals=List.copyOf(crystals);success=List.copyOf(success);
            if(crystals.size()!=3||success.size()!=3||crystals.stream().anyMatch(v->v<0||v>9999)||success.stream().anyMatch(v->!Float.isFinite(v)||v<0||v>1)||crystals.get(0)>crystals.get(1)||crystals.get(1)>crystals.get(2))throw new IllegalArgumentException("Invalid forge tiers");
        }
        public int tier(int spent){return spent<crystals.get(0)?-1:spent<crystals.get(1)?0:spent<crystals.get(2)?1:2;}
        public float chance(int spent,boolean exact)throws IOException{int tier=tier(spent);if(tier<0||exact&&spent!=crystals.get(tier))throw new IOException("m.insufficient_crystals");return success.get(tier);}
    }
    private final Map<String,Gear> gear;
    private final Map<String,List<Forge>> forges;
    private GearRules(Map<String,Gear> gear,Map<String,List<Forge>> forges){this.gear=Map.copyOf(gear);this.forges=Map.copyOf(forges);}
    public static GearRules empty(){return new GearRules(Map.of(),Map.of());}
    public Gear gear(String config){return gear.get(config);}
    public Forge forge(Gear definition,int level){var rows=forges.get(definition.forge());return rows==null||level<0||level>=rows.size()?null:rows.get(level);}
    @SuppressWarnings("unchecked") static GearRules load(Map<String,Object> root){
        var gear=new HashMap<String,Gear>();var forges=new HashMap<String,List<Forge>>();
        for(var raw:(List<?>)root.getOrDefault("gearProgression",List.of())){var row=(Map<String,Object>)raw;gear.put((String)row.get("config"),new Gear(number(row,"levels"),number(row,"baseHeat"),number(row,"maxHeat"),(String)row.get("forge")));}
        for(var raw:(List<?>)root.getOrDefault("forgeTables",List.of())){var table=(Map<String,Object>)raw;var rows=new ArrayList<Forge>();for(var value:(List<?>)table.get("rows")){var row=(Map<String,Object>)value;rows.add(new Forge(((List<Number>)row.get("crystals")).stream().map(Number::intValue).toList(),((List<Number>)row.get("success")).stream().map(Number::floatValue).toList()));}forges.put((String)table.get("config"),List.copyOf(rows));}
        return new GearRules(gear,forges);
    }
    private static int number(Map<String,Object> row,String name){return ((Number)row.get(name)).intValue();}
}
