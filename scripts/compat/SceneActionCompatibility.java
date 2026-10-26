import java.io.IOException;
import java.util.*;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.TargetConfig;
import org.tortodrone.resources.Json;
import org.tortodrone.world.SceneActions;

/** Installed stock configuration classes -> allowlisted metadata -> authoritative scheduler. */
public final class SceneActionCompatibility {
    public static void main(String[] args)throws Exception {
        var tags=new com.threerings.tudey.config.TagConfig();tags.tags=new String[]{"direct"};
        tags.derived=new com.threerings.tudey.config.TagConfig();tags.derived.tags=new String[]{"inherited"};
        var tagFields=new TreeMap<String,String>();SceneContentExport.writeTags(tagFields,"",tags);
        if(!tagFields.get("tags").equals(String.join(",",tags.adx()))||!tagFields.get("tags").contains("inherited"))throw new AssertionError("Inherited native tags lost");
        var marker=new com.threerings.tudey.config.PlaceableConfig();
        var parameter=new com.threerings.config.Parameter.Direct();parameter.name="Tag";parameter.paths=new String[]{"implementation.tags.tags[0]"};marker.parameters=new com.threerings.config.Parameter[]{parameter};
        tags.tags=new String[0];
        if(!SceneContentExport.sceneTags(marker,Map.of("Tag","start tut_miniboss"),tags).equals(List.of("start tut_miniboss","inherited")))throw new AssertionError("Preserved native tag slot not recovered");
        parameter.paths=new String[]{"implementation.model"};
        if(!SceneContentExport.sceneTags(marker,Map.of("Tag","must not infer"),tags).equals(List.of("inherited")))throw new AssertionError("Tag name guessed without native path");
        parameter.paths=new String[]{"implementation.tags.tags[0]"};
        try{SceneContentExport.sceneTags(marker,Map.of("Tag",42),tags);throw new AssertionError("Non-text tag accepted");}catch(IOException expected){}
        parameter.paths=new String[]{"implementation.tags.tags[256]"};
        try{SceneContentExport.sceneTags(marker,Map.of("Tag","oversized"),tags);throw new AssertionError("Unbounded slot accepted");}catch(IOException expected){}
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
        var start=new com.threerings.tudey.config.HandlerConfig.SignalStart();start.name="activate";start.action=conditional;
        var startFields=SceneContentExport.sceneSignal(start);
        if(!startFields.equals(Map.of("event","signal-start","signal","activate")))throw new AssertionError("SignalStart fields changed");
        var startRuntime=new SceneActions(List.of(new SceneActions.Rule("start",startFields.get("event"),"start tut_miniboss",startFields.get("signal"),false,
            SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(start.action,0))))),(tag,name,stamp)->{events.add(name);return true;});
        events.clear();startRuntime.signal("start tut_miniboss","activate",1,"entry:3280");startRuntime.signal("start tut_miniboss","activate",1,"entry:3280");
        startRuntime.advance(1);startRuntime.advance(2);startRuntime.signal("start tut_miniboss","activate",3,"entry:3280");startRuntime.advance(3);
        if(!events.equals(List.of("open")))throw new AssertionError("Native one-time trigger replayed");
        var stop=new com.threerings.tudey.config.HandlerConfig.SignalStop();stop.name="activate";
        if(!SceneContentExport.sceneSignal(stop).equals(Map.of("event","signal-stop","signal","activate")))throw new AssertionError("SignalStop fields changed");
        var second=new TargetConfig.Tagged();second.tag="boss music";
        var targets=new TargetConfig.Compound();targets.targets=new TargetConfig[]{target,second,target};signal.target=targets;
        var multi=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(signal,0)));
        events.clear();var multiple=new SceneActions(List.of(new SceneActions.Rule("multi","signal","in","activate",false,multi)),
            (tag,name,stamp)->{events.add(tag);return true;});
        multiple.signal("in","activate",1);multiple.advance(1);
        if(!events.equals(List.of("door end","boss music","door end")))throw new AssertionError("Native compound target ordering or multiplicity lost");
        targets.targets=new TargetConfig[]{target,new TargetConfig.Source()};
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target sibling dropped");}catch(IOException expected){}
        signal.target=target;
        var stripped=new com.threerings.tudey.config.HandlerConfig.SignalStart();stripped.name="";
        var strippedCondition=new com.threerings.tudey.config.ConditionConfig.Tagged();strippedCondition.tag="";strippedCondition.all=false;strippedCondition.target=new TargetConfig.Source();
        var strippedAction=new ActionConfig.Conditional();strippedAction.condition=strippedCondition;strippedAction.action=signal;strippedAction.elseAction=null;stripped.action=strippedAction;
        var restored=(com.threerings.tudey.config.HandlerConfig.SignalStart)SceneContentExport.restoreSceneHandler("Trigger/One Time Action",stripped);
        if(!restored.name.equals("activate")||!stripped.name.isEmpty()||!(strippedAction.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged))throw new AssertionError("Invalid restoration or native config mutated");
        events.clear();var recovered=new SceneActions(List.of(new SceneActions.Rule("recovered","signal-start","start tut_miniboss",restored.name,false,
            SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(restored.action,0))))),(tag,name,stamp)->{events.add(tag);return true;});
        recovered.signal("start tut_miniboss","activate",1,"actor:1");recovered.advance(1);recovered.advance(2);
        recovered.signal("start tut_miniboss","activate",3,"actor:1");recovered.advance(3);
        if(!events.equals(List.of("door end")))throw new AssertionError("Recovered one-time handler did not fire exactly once");
        if(SceneContentExport.restoreSceneHandler("Unrelated",stripped)!=stripped)throw new AssertionError("Unrelated handler repaired");
        strippedCondition.tag="real condition";
        if(SceneContentExport.restoreSceneHandler("Trigger/One Time Action",stripped)!=stripped)throw new AssertionError("Non-stripped guard overwritten");
        compound.actions=new ActionConfig[]{delayed,new ActionConfig.FireEffect()};
        try{SceneContentExport.sceneAction(compound,0);throw new AssertionError("Unsupported sibling was silently dropped");}catch(IOException expected){}
        delayed.variance=50;
        try{SceneContentExport.sceneAction(delayed,0);throw new AssertionError("Unimplemented variance accepted");}catch(IOException expected){}
        signal.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target accepted");}catch(IOException expected){}
        if(args.length>1){
            var folder=java.nio.file.Path.of(args[1]);var properties=new Properties();
            try(var input=java.nio.file.Files.newInputStream(folder.resolve("map.properties"))){properties.load(input);}
            var content=org.tortodrone.world.SceneContent.load(folder,properties.getProperty("scene-sha256"));
            var trigger=content.entries().stream().filter(entry->entry.key().equals("3265")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            var rule=SceneActions.rule(trigger);events.clear();
            var exported=new SceneActions(List.of(rule),(tag,name,stamp)->{events.add(tag+":"+name);return true;});
            exported.signal("start tut_miniboss","activate",100,"entry:3280");exported.advance(100);exported.advance(150);
            exported.signal("start tut_miniboss","activate",200,"entry:3280");exported.advance(200);
            if(!events.equals(List.of("spawn boss:activate","boss camera:activate","boss music:activate")))throw new AssertionError("Captured entry 3265 did not dispatch its authored targets once: "+events);
            System.out.println("PASS: captured tutorial entry 3265 -> authoritative scheduler -> three authored signals, no replay");
        }
        System.out.println("PASS: stock action fields, delayed signal timing, Limit condition, signal transitions, inherited/recovered tags, compound targets, full-compound refusal, variance and target guards");
    }
}
