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
        compound.actions=new ActionConfig[]{delayed,new ActionConfig.FireEffect()};
        try{SceneContentExport.sceneAction(compound,0);throw new AssertionError("Unsupported sibling was silently dropped");}catch(IOException expected){}
        delayed.variance=50;
        try{SceneContentExport.sceneAction(delayed,0);throw new AssertionError("Unimplemented variance accepted");}catch(IOException expected){}
        signal.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target accepted");}catch(IOException expected){}
        System.out.println("PASS: stock action fields, delayed signal timing, full-compound refusal, variance and target guards");
    }
}
