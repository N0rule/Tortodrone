import java.io.IOException;
import java.util.*;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.TargetConfig;
import org.tortodrone.resources.Json;
import org.tortodrone.world.SceneActions;

/** Installed stock configuration classes -> allowlisted metadata -> authoritative scheduler. */
public final class SceneActionCompatibility {
    public static void main(String[] args)throws Exception {
        var target=new TargetConfig.Tagged();target.tag="door end";
        var signal=new ActionConfig.Signal();signal.name="open";signal.target=target;
        var delayed=new ActionConfig.Delayed();delayed.delay=5000;delayed.variance=0;delayed.action=signal;
        var compound=new ActionConfig.Compound();compound.stopOnFailure=true;compound.actions=new ActionConfig[]{delayed};
        var action=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(compound,0)));
        var events=new ArrayList<String>();
        var runtime=new SceneActions(List.of(new SceneActions.Rule("3306","removed","wave miniboss","",false,action)),
            (tag,name,stamp)->{events.add(tag+":"+name+":"+stamp);return true;});
        runtime.removed(Set.of("wave miniboss"),tag->false,100);runtime.advance(5099);
        if(!events.isEmpty())throw new AssertionError("Early exit signal");runtime.advance(5100);
        if(!events.equals(List.of("door end:open:5100")))throw new AssertionError("Native delay/signal fields changed");
        var limit=new com.threerings.tudey.config.ConditionConfig.Limit();limit.limit=1;
        var conditional=new ActionConfig.Conditional();conditional.condition=limit;conditional.action=signal;conditional.elseAction=null;
        var limited=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(conditional,0)));
        events.clear();var once=new SceneActions(List.of(new SceneActions.Rule("once","signal","start","activate",false,limited)),
            (tag,name,stamp)->{events.add(name);return true;});
        once.signal("start","activate",1);once.signal("start","activate",1);once.advance(1);
        if(!events.equals(List.of("open")))throw new AssertionError("Native Limit condition did not execute once");
        compound.actions=new ActionConfig[]{delayed,new ActionConfig.FireEffect()};
        try{SceneContentExport.sceneAction(compound,0);throw new AssertionError("Unsupported sibling was silently dropped");}catch(IOException expected){}
        delayed.variance=50;
        try{SceneContentExport.sceneAction(delayed,0);throw new AssertionError("Unimplemented variance accepted");}catch(IOException expected){}
        signal.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target accepted");}catch(IOException expected){}
        System.out.println("PASS: stock action fields, delayed signal timing, Limit condition, full-compound refusal, variance and target guards");
    }
}
