package org.tortodrone.core.world;

import java.util.Set;

/** Pure validation of prepared inventory item uses and their fire graphs. */
public final class ItemUseRules {
    private static final Set<String> SUPPORTED=Set.of("Actor","Agent","Stranger","Monster","Generator","Door","Switch","EntryState","Block","Block$Stateful","Pickup","Trap","Bomb","DungeonLift","Lift","TerritoryProp");
    private static final int ORBITAL_FALLBACK_FUSE=600000;
    private ItemUseRules() { }
    private static boolean liftKind(String kind){return kind.equals("DungeonLift")||kind.equals("Lift");}
    public static void validate(SceneContent.Spec spec)throws java.io.IOException{
        switch(spec.text("kind","")){
            case "heal" -> {int heal=spec.integer("heal",0);if(heal<=0||heal>1_000_000)throw new java.io.IOException("Invalid prepared item heal");}
            case "lethal" -> {
                String damage=spec.text("damage","");if(damage.isBlank())throw new java.io.IOException("Missing prepared item damage");
                try{FireCatalog.valueJson(damage);}catch(java.io.IOException invalid){throw new java.io.IOException("Invalid prepared item damage",invalid);}
            }
            case "spawn-actor" -> {
                String type=spec.text("actor.native-type","");String simple=type.substring(type.lastIndexOf('.')+1);
                if(!SUPPORTED.contains(simple)||spec.text("actor.config","").isBlank())throw new java.io.IOException("Unsupported prepared item actor: "+type);
            }
            case "lift" -> {
                String type=spec.text("lift.native-type","");String simple=type.substring(type.lastIndexOf('.')+1);
                if(!liftKind(simple)||!spec.flag("lift.can-throw",false)||spec.text("lift.config-json",spec.text("lift.config","")).isBlank())
                    throw new java.io.IOException("Unsupported prepared item lift: "+type);
            }
            default -> throw new java.io.IOException("Unsupported prepared item use: "+spec.text("kind",""));
        }
    }
    public static boolean usable(FireCatalog.Action action){
        return supported(action)&&meaningful(action);
    }
    private static boolean supported(FireCatalog.Action action){
        if(action==null||!sourceTarget(action.targetType())||!action.facingType().isEmpty())return false;
        return switch(action.kind()){
            case COMPOUND,MULTI_PRESPAWNED,RANDOM_EQUAL->action.children().stream().allMatch(ItemUseRules::supported);
            case HEAL,CLEAR_STATUS,APPLY_STATUS,FIRE_EFFECT,DAMAGE_REGION,SWEEP_DAMAGE,IMPULSE_REGION,DESTROY_SOURCE->true;
            case EMIT_PROJECTILE->supportedProjectile(action.actor());
            default->false;
        };
    }
    private static boolean supportedProjectile(FireCatalog.ActorSpec actor){
        if(actor==null||!Set.of("BULLET","BOMB","LASER","ORBITAL","ORBITAL_BULLET","COMPOUND_ORBITAL_BULLET").contains(actor.nativeType()))return false;
        if(actor.nativeType().equals("ORBITAL")&&actor.fuse()==ORBITAL_FALLBACK_FUSE)return false;
        if(actor.detonation()!=null&&!supported(actor.detonation())||actor.shutdown()!=null&&!supported(actor.shutdown()))return false;
        for(var timer:actor.timers())if(!supported(timer.action())||timer.endAction()!=null&&!supported(timer.endAction()))return false;
        return actor.detonation()!=null&&meaningful(actor.detonation())
            ||actor.timers().stream().anyMatch(t->meaningful(t.action()));
    }
    private static boolean meaningful(FireCatalog.Action action){
        return switch(action.kind()){
            case COMPOUND,MULTI_PRESPAWNED,RANDOM_EQUAL->action.children().stream().anyMatch(ItemUseRules::meaningful);
            case FIRE_EFFECT->action.config()!=null;
            default->true;
        };
    }
    private static boolean sourceTarget(String type){return type.isEmpty()||type.equals("Source")||type.equals("Activator");}
}
