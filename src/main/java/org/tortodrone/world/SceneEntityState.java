package org.tortodrone.world;

/** Immutable native scene entity fields; these are never authenticated player occupants. */
public record SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,
        int state,int stateEntered,int count,int hitTime,String key,SceneLiftState lift,SceneStatus.State status,int affectedType,int affectedTime,boolean entrySource,ScenePickupState pickup) {
    public SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,int state,int stateEntered,int count,int hitTime,String key,SceneLiftState lift,SceneStatus.State status,int affectedType,int affectedTime,boolean entrySource){this(kind,destroyed,speed,maxStep,health,state,stateEntered,count,hitTime,key,lift,status,affectedType,affectedTime,entrySource,null);}
    public SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,int state,int stateEntered,int count,int hitTime,String key,SceneLiftState lift,SceneStatus.State status,int affectedType,int affectedTime){this(kind,destroyed,speed,maxStep,health,state,stateEntered,count,hitTime,key,lift,status,affectedType,affectedTime,false);}
    public SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,int state,int stateEntered,int count,int hitTime,String key,SceneLiftState lift,SceneStatus.State status){this(kind,destroyed,speed,maxStep,health,state,stateEntered,count,hitTime,key,lift,status,0,0);}
    public SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,int state,int stateEntered,int count,int hitTime,String key,SceneLiftState lift){this(kind,destroyed,speed,maxStep,health,state,stateEntered,count,hitTime,key,lift,SceneStatus.State.NORMAL);}
    public SceneEntityState(String kind,int destroyed,float speed,float maxStep,float health,int state,int stateEntered,int count,int hitTime,String key){this(kind,destroyed,speed,maxStep,health,state,stateEntered,count,hitTime,key,null);}
    public String nativeClass(){return switch(kind){
        case "Actor" -> "com.threerings.tudey.data.actor.Actor";
        case "Agent" -> "com.threerings.tudey.data.actor.Agent";
        case "Stranger" -> "com.threerings.projectx.data.actor.Stranger";
        case "Monster","Generator","Door","Switch","Block","Block$Stateful","Pickup","Trap","Bomb","DungeonLift" -> "com.threerings.projectx.dungeon.data.actor."+kind;
        default -> throw new IllegalArgumentException("Unsupported scene actor: "+kind);
    };}
    public boolean active(){return kind.equals("Monster")||kind.equals("Agent")||kind.equals("Stranger");}
}
