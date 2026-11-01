package org.tortodrone.world;

/** Allowlisted nearest scene-party-member idle Follow, bounded by native path length 8. */
public record SceneFollow(int evaluation,float minimum,float maximum) {
    public SceneFollow {
        if(evaluation<1||evaluation>3_600_000||!Float.isFinite(minimum)||!Float.isFinite(maximum)||minimum<0||maximum<minimum||maximum>4096)throw new IllegalArgumentException("Invalid native follow routine");
    }
    public static SceneFollow read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("follow.kind"))return null;
        if(!spec.text("follow.kind","").equals("nearest-party-member"))throw new IllegalArgumentException("Unsupported native follow routine");
        return new SceneFollow(spec.integer("follow.evaluation",-1),spec.number("follow.minimum",-1),spec.number("follow.maximum",-1));
    }
}
