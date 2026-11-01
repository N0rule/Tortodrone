package org.tortodrone.world;

/** Typed single-head native Targeting/PathAttack policy; no executable client classes. */
public record SceneTargeting(float expansion,int evaluation,int attack,int interval,float range,float turnSnap) {
    public SceneTargeting {
        if(!Float.isFinite(expansion)||expansion<0||expansion>4096||evaluation<1||evaluation>3_600_000
                ||attack<0||attack>255||interval<0||interval>3_600_000||!Float.isFinite(range)||range<0||range>4096
                ||!Float.isFinite(turnSnap)||turnSnap<0||turnSnap>Math.PI)throw new IllegalArgumentException("Invalid native targeting policy");
    }
    public static SceneTargeting read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("targeting.kind"))return null;
        if(!spec.text("targeting.kind","").equals("single-path-attack"))throw new IllegalArgumentException("Unsupported native targeting policy");
        var policy=new SceneTargeting(spec.number("targeting.expansion",-1),spec.integer("targeting.evaluation",-1),spec.integer("targeting.attack",-1),spec.integer("targeting.interval",-1),spec.number("targeting.range",-1),spec.number("targeting.turn-snap",-1));
        if(!spec.fields().containsKey("attack."+policy.attack()+".clear"))throw new IllegalArgumentException("Native targeting attack is unavailable");
        if(!Float.isFinite(spec.number("turn-rate",-1))||spec.number("turn-rate",-1)<0)throw new IllegalArgumentException("Native targeting turn rate is unavailable");
        return policy;
    }
}
