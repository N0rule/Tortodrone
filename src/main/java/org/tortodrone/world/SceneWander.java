package org.tortodrone.world;

/** Native Wander with constant evaluation, uniform full-circle turns and zero pauses. */
public record SceneWander(float radius,int evaluation,float turnRate) {
    public SceneWander {
        if(!Float.isFinite(radius)||radius<=0||radius>4096||evaluation<1||evaluation>3_600_000||!Float.isFinite(turnRate)||turnRate<=0)throw new IllegalArgumentException("Invalid native wander policy");
    }
    public static SceneWander read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("wander.kind"))return null;
        if(!spec.text("wander.kind","").equals("uniform-circle"))throw new IllegalArgumentException("Unsupported native wander policy");
        return new SceneWander(spec.number("wander.radius",-1),spec.integer("wander.evaluation",-1),spec.number("turn-rate",-1));
    }
}
