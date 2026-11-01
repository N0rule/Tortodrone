package org.tortodrone.world;

import java.util.*;

/** Native attack-owned ItemImpulse, validated before an actor is instantiated. */
public record SceneAttackMotion(int delay,int duration,float x,float y) {
    private static final java.util.regex.Pattern FIELD=java.util.regex.Pattern.compile("attack\\.([0-9]+)\\.impulse-(delay|duration|x|y)");
    public SceneAttackMotion {
        if(delay<0||delay>3_600_000||duration<0||duration>3_600_000||duration==0&&(x!=0||y!=0)
                ||!Float.isFinite(x)||!Float.isFinite(y)||Math.abs(x)>64||Math.abs(y)>64)throw new IllegalArgumentException("Invalid native attack motion");
    }
    public static Map<Integer,SceneAttackMotion> readAll(SceneContent.Spec spec){
        var result=new TreeMap<Integer,SceneAttackMotion>();
        for(String key:spec.fields().keySet()){
            var match=FIELD.matcher(key);if(!match.matches())continue;
            int slot=Integer.parseInt(match.group(1));if(slot>255)throw new IllegalArgumentException("Invalid native motion attack");
            String prefix="attack."+slot+".impulse-";
            for(String suffix:List.of("delay","duration","x","y"))if(!spec.fields().containsKey(prefix+suffix))throw new IllegalArgumentException("Incomplete native attack motion");
            result.putIfAbsent(slot,new SceneAttackMotion(spec.integer(prefix+"delay",-1),spec.integer(prefix+"duration",-1),spec.number(prefix+"x",Float.NaN),spec.number(prefix+"y",Float.NaN)));
        }
        return Map.copyOf(result);
    }
}
