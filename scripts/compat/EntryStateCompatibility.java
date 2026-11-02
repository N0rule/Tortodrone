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
        var next=actor(1,200);var delta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,200,(short)50,List.of(),List.of(next),new int[0],Map.of(first.id(),first),true));
        var changed=delta.aek()[0].apply(nativeActor);
        check(FireCompatibility.field(changed,"_key").equals(2930)&&(int)FireCompatibility.field(changed,"_state")==1&&(int)FireCompatibility.field(changed,"_stateEntered")==200&&(int)FireCompatibility.field(changed,"_id")==first.id(),"native state delta preserves final entry and actor identities");
        System.out.println("PASS: stock EntryState actor and applied mutable state/time delta, original elevator entry key and exact stream framing (serialization only; elevator interaction/travel incomplete)");
    }
    private static ActorState actor(int state,int stamp){
        var entity=new SceneEntityState("EntryState",Integer.MAX_VALUE,0,0,-1,state,stamp,0,0,"2930");
        return new ActorState(1_000_000_000,0,"",0,100,136.5f,197.5f,-(float)Math.PI/2,0,0,0,100,new StarterCatalog.Model("Dynamic/Elevator/Next Floor",Map.of()),false,null,null,entity);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
