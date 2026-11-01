package org.tortodrone.world;

import java.util.*;
import java.util.random.RandomGenerator;
import org.tortodrone.resources.Json;

/** Allowlisted native Targeting with single or distance-weighted PathAttack choices. */
public record SceneTargeting(float expansion,int evaluation,int interval,List<Mode> modes,int idleAttack) {
    public record Mode(int attack,float minimum,float maximum,float range,float weight,boolean faces,float turnSnap) {
        public Mode {
            if(attack<0||attack>255||!Float.isFinite(minimum)||!Float.isFinite(maximum)||minimum<0||maximum<minimum
                    ||!Float.isFinite(range)||range<0||range>4096||!Float.isFinite(weight)||weight<0||weight>1_000_000
                    ||!Float.isFinite(turnSnap)||turnSnap<0||turnSnap>Math.PI)throw new IllegalArgumentException("Invalid native targeting mode");
        }
        boolean eligible(float distance){return distance>=minimum&&distance<=maximum;}
    }
    public SceneTargeting(float expansion,int evaluation,int interval,List<Mode> modes){this(expansion,evaluation,interval,modes,-1);}
    public SceneTargeting(float expansion,int evaluation,int attack,int interval,float range,float turnSnap){
        this(expansion,evaluation,interval,List.of(new Mode(attack,0,Float.MAX_VALUE,range,1,true,turnSnap)));
    }
    public SceneTargeting {
        modes=List.copyOf(modes);
        if(!Float.isFinite(expansion)||expansion<0||expansion>4096||evaluation<1||evaluation>3_600_000||interval<0||interval>3_600_000
                ||idleAttack< -1||idleAttack>255||modes.isEmpty()||modes.size()>64||modes.stream().noneMatch(m->m.weight()>0))throw new IllegalArgumentException("Invalid native targeting policy");
    }
    // Single-head adapters retained for the simple policy and its native-data oracle.
    public int attack(){return modes.getFirst().attack();}
    public float range(){return modes.getFirst().range();}
    public float turnSnap(){return modes.getFirst().turnSnap();}
    public Mode choose(float distance,RandomGenerator random){
        double total=0;for(var mode:modes)if(mode.eligible(distance))total+=mode.weight();
        if(total<=0)return null;
        double choice=random.nextDouble()*total;
        for(var mode:modes)if(mode.eligible(distance)&&mode.weight()>0){choice-=mode.weight();if(choice<0)return mode;}
        throw new IllegalStateException("Invalid native targeting draw");
    }
    public static SceneTargeting read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("targeting.kind"))return null;
        SceneTargeting policy;
        switch(spec.text("targeting.kind","")){
            case "single-path-attack" -> policy=new SceneTargeting(spec.number("targeting.expansion",-1),spec.integer("targeting.evaluation",-1),spec.integer("targeting.attack",-1),spec.integer("targeting.interval",-1),spec.number("targeting.range",-1),spec.number("targeting.turn-snap",-1));
            case "weighted-path-attacks" -> {
                var modes=new ArrayList<Mode>();
                try {
                    Object value=Json.parse(spec.text("targeting.modes",""),16);
                    if(!(value instanceof List<?> rows)||rows.isEmpty()||rows.size()>64)throw new IllegalArgumentException("Invalid targeting mode list");
                    for(Object row:rows){
                        if(!(row instanceof Map<?,?> fields)||!fields.keySet().equals(Set.of("attack","minimum","maximum","range","weight","faces","turn-snap"))||!(fields.get("faces") instanceof Boolean faces))throw new IllegalArgumentException("Unsupported targeting mode fields");
                        float attack=number(fields,"attack");if(attack!=(int)attack)throw new IllegalArgumentException("Invalid targeting attack index");
                        modes.add(new Mode((int)attack,number(fields,"minimum"),number(fields,"maximum"),number(fields,"range"),number(fields,"weight"),faces,number(fields,"turn-snap")));
                    }
                }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid native targeting metadata",invalid);}
                policy=new SceneTargeting(spec.number("targeting.expansion",-1),spec.integer("targeting.evaluation",-1),spec.integer("targeting.interval",-1),modes,spec.integer("targeting.idle-attack",-1));
            }
            default -> throw new IllegalArgumentException("Unsupported native targeting policy");
        }
        if(policy.idleAttack()>=0&&!spec.fields().containsKey("attack."+policy.idleAttack()+".clear"))throw new IllegalArgumentException("Native idle attack is unavailable");
        for(var mode:policy.modes())if(!spec.fields().containsKey("attack."+mode.attack()+".clear"))throw new IllegalArgumentException("Native targeting attack is unavailable");
        if(!Float.isFinite(spec.number("turn-rate",-1))||spec.number("turn-rate",-1)<0)throw new IllegalArgumentException("Native targeting turn rate is unavailable");
        return policy;
    }
    private static float number(Map<?,?> fields,String name){if(!(fields.get(name) instanceof Number value)||!Float.isFinite(value.floatValue()))throw new IllegalArgumentException("Invalid targeting number");return value.floatValue();}
}
