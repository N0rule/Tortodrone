import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.world.*;

/** Installed reader oracle for an authored entry's actor and mutable state, not elevator travel. */
public final class EntryStateCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");var first=actor(0,100);
        var added=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(first),List.of(),new int[0],Map.of(),true));
        var nativeActor=added.aej()[0];check(nativeActor.getClass()==com.threerings.tudey.data.actor.EntryState.class,"exact native EntryState class");
        check(FireCompatibility.field(nativeActor,"_key").equals(2930)&&(int)FireCompatibility.field(nativeActor,"_state")==0&&(int)FireCompatibility.field(nativeActor,"_stateEntered")==100,"original entry identity and initial state");
        for(int state=1;state<=4;state++){
            var next=actor(state,200);var delta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,200,(short)50,List.of(),List.of(next),new int[0],Map.of(first.id(),first),true));
            var changed=delta.aek()[0].apply(nativeActor);
            check(FireCompatibility.field(changed,"_key").equals(2930)&&(int)FireCompatibility.field(changed,"_state")==state&&(int)FireCompatibility.field(changed,"_stateEntered")==200&&(int)FireCompatibility.field(changed,"_id")==first.id(),"native state delta preserves final entry and actor identities");
        }
        var pawn=new ActorState(219,1001,"Fixture",0,100,136.5f,197.5f,0,0,0,TownMovement.IDLE,200,new StarterCatalog.Model("Character/PC/Level",Map.of()));
        var frozen=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,200,(short)50,List.of(pawn),List.of(),new int[0],Map.of(),true)).aej()[0];
        check(frozen.getClass()==com.threerings.projectx.dungeon.data.actor.Dungeoneer.class&&(int)FireCompatibility.field(frozen,"_activity")==285&&(int)FireCompatibility.field(frozen,"_activityStarted")==200,"current native frozen elevator pawn activity and timestamp");
        check(new com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp().colorId==99,"installed native neutral territory color");
        var light=light(100,0.5f);var lightFrame=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(light),List.of(),new int[0],Map.of(),true));
        var nativeLight=lightFrame.aej()[0];check(nativeLight.getClass()==com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp.class&&((com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp)nativeLight).colorId==99,"native lighting class and full color byte");
        var movedLight=light(200,1.5f);var lightDelta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,200,(short)50,List.of(),List.of(movedLight),new int[0],Map.of(light.id(),light),true));
        var updatedLight=(com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp)lightDelta.aek()[0].apply(nativeLight);
        check(updatedLight.colorId==99&&((com.threerings.math.Vector2f)FireCompatibility.field(updatedLight,"_translation")).x==1.5f,"native territory color delta bit and following stream fields");
        System.out.println("PASS: stock TerritoryProp constructor, full neutral-light actor and applied color/position delta framing (no PvP control or graphical lighting claim)");
        System.out.println("PASS: stock EntryState phases 0-4, applied state/time deltas, original entry key, Dungeoneer transit activity 285 and exact stream framing (serialization fixtures; no rendered ride or world travel)");
    }
    private static ActorState light(int stamp,float x){
        var entity=new SceneEntityState("TerritoryProp",Integer.MAX_VALUE,0,0,0,99,stamp,0,0,"751");
        return new ActorState(1_000_000_001,0,"",0,100,x,.5f,-(float)Math.PI/2,0,0,0,100,new StarterCatalog.Model("Static/PvP/Territory Props/Lamps and Lighting/Lights/Spot Lights/24 x 24",Map.of("Intensity","Strong")),false,null,null,entity);
    }
    private static ActorState actor(int state,int stamp){
        var entity=new SceneEntityState("EntryState",Integer.MAX_VALUE,0,0,-1,state,stamp,0,0,"2930");
        return new ActorState(1_000_000_000,0,"",0,100,136.5f,197.5f,-(float)Math.PI/2,0,0,0,100,new StarterCatalog.Model("Dynamic/Elevator/Next Floor",Map.of()),false,null,null,entity);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
