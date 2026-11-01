package org.tortodrone.world;

/** Allowlisted native Always -> Wait -> Move(Tagged/Source) -> Rotate script. */
public record SceneMovementScript(int waitMillis,String goal,float maxPath,float rotation,float turnRate) {
    public SceneMovementScript {
        if(waitMillis<0||waitMillis>3_600_000||goal==null||goal.length()>256||goal.chars().anyMatch(Character::isISOControl)
                ||!Float.isFinite(maxPath)||maxPath<=0||maxPath>4096||!Float.isFinite(rotation)||!Float.isFinite(turnRate)||turnRate<=0)throw new IllegalArgumentException("Invalid scene movement script");
    }
    public static SceneMovementScript read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("script.kind"))return null;
        if(!spec.text("script.kind","").equals("wait-move-rotate"))throw new IllegalArgumentException("Unsupported movement script");
        return new SceneMovementScript(spec.integer("script.wait",-1),spec.text("script.goal",""),spec.number("script.max-path",-1),spec.number("script.rotation",Float.NaN),spec.number("script.turn-rate",-1));
    }
}
