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
        var table=new com.threerings.projectx.dungeon.config.SpawnTableConfig.Original();
        var leaf=new com.threerings.projectx.dungeon.config.SpawnTableConfig.ActorEntry();leaf.actor=new com.threerings.config.ConfigReference<>("Fixture/Monster","Wave Tag","(Wave Tag)");table.root=leaf;
        var wave=new com.threerings.config.Parameter.Direct();wave.name="Wave";wave.paths=new String[]{"root.actor[\"Wave Tag\"]"};table.parameters=new com.threerings.config.Parameter[]{wave};
        var bound=SceneContentExport.singleTableActor(table,Map.of("Wave","boss support 1"));
        if(!bound.kW().get("Wave Tag").equals("boss support 1")||!leaf.actor.kW().get("Wave Tag").equals("(Wave Tag)"))throw new AssertionError("Actor table lost argument binding or mutated native config");
        wave.paths=new String[]{"root.actor[\"Wave Tag\"]","root.unimplemented"};
        try{SceneContentExport.singleTableActor(table,Map.of("Wave","bad"));throw new AssertionError("Unsupported table parameter sibling ignored");}catch(IOException expected){}
        table.root=new com.threerings.projectx.dungeon.config.SpawnTableConfig.RandomEntry();
        try{SceneContentExport.singleTableActor(table,Map.of());throw new AssertionError("Random table sampled at export time");}catch(IOException expected){}
        var target=new TargetConfig.Tagged();target.tag="door end";
        var signal=new ActionConfig.Signal();signal.name="open";signal.target=target;
        var delayed=new ActionConfig.Delayed();delayed.delay=5000;delayed.variance=0;delayed.action=signal;
        var compound=new ActionConfig.Compound();compound.stopOnFailure=true;compound.actions=new ActionConfig[]{delayed};
        var action=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(compound,0)));
        var events=new ArrayList<String>();
        var runtime=new SceneActions(List.of(new SceneActions.Rule("3306","removed","wave miniboss","",false,action)),
            (tag,name,stamp)->{events.add(tag+":"+name+":"+stamp);return true;});
        runtime.removed(Set.of("wave miniboss"),tag->false,100);runtime.advance(100);runtime.advance(5099);
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
        var emptySpawn=new ActionConfig.SpawnActor();emptySpawn.actor=null;
        if(!(SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(emptySpawn,0))) instanceof SceneActions.Sequence empty)||!empty.actions().isEmpty())throw new AssertionError("Null spawn should succeed without an actor");
        emptySpawn.actor=new com.threerings.config.ConfigReference<>("Fixture/Monster");emptySpawn.location=new TargetConfig.Tagged();
        try{SceneContentExport.sceneAction(emptySpawn,0);throw new AssertionError("Unsupported spawn location accepted");}catch(IOException expected){}
        var destroy=new ActionConfig.DestroyActor();destroy.target=target;destroy.endScene=false;
        var destruction=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(destroy,0)));
        if(!(destruction instanceof SceneActions.Destroy d)||!d.tag().equals("door end"))throw new AssertionError("Native destruction target lost");
        destroy.endScene=true;
        try{SceneContentExport.sceneAction(destroy,0);throw new AssertionError("Unsupported shutdown mode accepted");}catch(IOException expected){}
        destroy.endScene=false;destroy.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(destroy,0);throw new AssertionError("Unsupported destruction target accepted");}catch(IOException expected){}
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
        compound.actions=new ActionConfig[]{delayed,new ActionConfig.Signal()};
        try{SceneContentExport.sceneAction(compound,0);throw new AssertionError("Unsupported sibling was silently dropped");}catch(IOException expected){}
        var cosmetic=new com.threerings.tudey.config.EffectConfig();
        if(!SceneContentExport.cosmeticEffect(cosmetic))throw new AssertionError("Native empty effect action rejected");
        cosmetic.action=signal;
        if(SceneContentExport.cosmeticEffect(cosmetic))throw new AssertionError("Gameplay effect accepted without executing its action");
        delayed.variance=50;
        if(!(SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(delayed,0))) instanceof SceneActions.Delay varying)||varying.variance()!=50)throw new AssertionError("Native variance field lost");
        signal.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target accepted");}catch(IOException expected){}
        if(args.length>1){
            var folder=java.nio.file.Path.of(args[1]);var properties=new Properties();
            try(var input=java.nio.file.Files.newInputStream(folder.resolve("map.properties"))){properties.load(input);}
            var content=org.tortodrone.world.SceneContent.load(folder,properties.getProperty("scene-sha256"));
            for(String key:List.of("496","497","511","2845","2846","2897","3321","3367","3381","3382","3383","3385")){
                var entry=content.entries().stream().filter(e->e.key().equals(key)&&e.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
                var operation=SceneActions.rule(entry).action();
                if(!(operation instanceof SceneActions.Limited single)||!(single.action() instanceof SceneActions.Spawn spawn)||!spawn.activate())throw new AssertionError("Native single-actor activation missing at "+key);
                String expected=Set.of("496","497","511","2846").contains(key)?"Character/NPC/Monster/Slime/Jelly Cube (Intro)":Set.of("2845","2897").contains(key)?"Character/NPC/Monster/Beast/Chromalisk/Chromalisk Whelp (Intro)":"Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)";
                if(!spawn.fields().get("config").equals(expected))throw new AssertionError("Native single-actor table changed at "+key);
                if(expected.contains("Gun Puppy")){
                    String waveTag=Set.of("3321","3367").contains(key)?"boss support 1":"boss support 2";
                    if(!Arrays.asList(spawn.fields().get("tags").split(",")).contains(waveTag)||!spawn.fields().get("config.argument.Wave Tag").equals("string|"+waveTag))throw new AssertionError("Derived actor/table wave tag lost at "+key);
                }
            }
            System.out.println("PASS: all 12 native early/rocket actor-table activations preserve concrete actor identity; Gun Puppy wave tags survive table and derived actor parameters");
            var trigger=content.entries().stream().filter(entry->entry.key().equals("3265")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            var rule=SceneActions.rule(trigger);events.clear();
            var exported=new SceneActions(List.of(rule),(tag,name,stamp)->{events.add(tag+":"+name);return true;});
            exported.signal("start tut_miniboss","activate",100,"entry:3280");exported.advance(100);exported.advance(150);
            exported.signal("start tut_miniboss","activate",200,"entry:3280");exported.advance(200);
            if(!events.equals(List.of("spawn boss:activate","boss camera:activate","boss music:activate")))throw new AssertionError("Captured entry 3265 did not dispatch its authored targets once: "+events);
            var boss=content.entries().stream().filter(entry->entry.key().equals("3267")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            events.clear();var encounter=new SceneActions(List.of(SceneActions.rule(boss)),(tag,name,stamp,source)->{events.add(tag+":"+name+":"+stamp);return true;},
                new Random(){@Override public int nextInt(int bound){if(bound!=1000)throw new AssertionError("Unexpected captured variance");return bound/2;}});
            encounter.signal("spawn boss","activate",100,"entry:3265");encounter.advance(100);encounter.advance(3599);
            if(!events.equals(List.of("rocket 1:activate:100")))throw new AssertionError("Captured encounter advanced early");
            encounter.advance(3600);encounter.advance(6100);encounter.advance(8100);
            if(!events.equals(List.of("rocket 1:activate:100","rocket 2:activate:3600","miniboss:activate:6100","door hidden:open:8100","door miniboss:close:8100","start player:activate:8100","replace knightsa:activate:8100")))throw new AssertionError("Captured encounter signal timing differs: "+events);
            encounter.signal("spawn boss","activate",8200,"entry:3265");encounter.advance(8200);encounter.advance(20000);
            if(events.size()!=7)throw new AssertionError("Captured encounter replayed");
            var stopEntry=content.entries().stream().filter(entry->entry.key().equals("3263")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            var releaseEntry=content.entries().stream().filter(entry->entry.key().equals("3317")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            var operations=new ArrayList<SceneActions.PawnStatus>();
            var controls=new SceneActions(List.of(SceneActions.rule(stopEntry),SceneActions.rule(releaseEntry)),(tag,name,stamp,source)->true,new Random(1),(tag,stamp,source)->false,(actor,stamp,source)->false,
                (operation,stamp,source)->{operations.add(operation);return true;});
            controls.signal("stop player","activate",100,"entry:3362");controls.advance(100);
            controls.signal("start player","activate",200,"entry:3267");controls.advance(200);
            if(operations.size()!=2||operations.getFirst().clear()||!operations.getFirst().condition().name().equals("Scripted/Stop")||!operations.getLast().clear()||operations.getLast().clearMask()!=Integer.MAX_VALUE)throw new AssertionError("Captured player control actions lost");
            var forbiddenTarget=new com.threerings.tudey.config.TargetConfig.InstanceOf();forbiddenTarget.logicClass="java.lang.Runtime";
            var wrongTarget=new com.threerings.projectx.dungeon.config.DungeonActionConfig.ClearStatus();wrongTarget.target=forbiddenTarget;wrongTarget.mask=Integer.MAX_VALUE;
            try{SceneContentExport.sceneAction(wrongTarget,0);throw new AssertionError("Arbitrary class target accepted");}catch(IOException expected){}
            System.out.println("PASS: captured tutorial stop/release graphs retain Scripted/Stop and native clear mask; arbitrary target classes refused");
            var miniboss=content.entries().stream().filter(entry->entry.key().equals("3392")&&entry.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            var spawns=new ArrayList<SceneActions.Spawn>();var spawnSources=new ArrayList<String>();
            var activation=new SceneActions(List.of(SceneActions.rule(miniboss)),(tag,name,stamp,source)->true,new Random(1),(tag,stamp,source)->false,
                (actor,stamp,source)->{spawns.add(actor);spawnSources.add(source);return true;});
            activation.signal("miniboss","activate",100,"entry:3267");activation.advance(100);activation.advance(150);
            activation.signal("miniboss","activate",200,"entry:3267");activation.advance(200);
            if(spawns.size()!=1||!spawnSources.equals(List.of("entry:3392")))throw new AssertionError("Captured monster activation duplicated or lost source");
            var spawned=spawns.getFirst();
            if(!spawned.activate()||!spawned.fields().get("config").equals("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant")||!spawned.fields().get("tags").contains("wave miniboss")||!spawned.fields().get("health").equals("1440"))throw new AssertionError("Captured miniboss identity, wave tag or native health lost");
            System.out.println("PASS: captured entry 3392 emits one prepared Ironwood Giant with wave tag and native health; AI/playthrough not covered");
            System.out.println("PASS: captured tutorial entry 3267 -> seven authored timed signals, bounded variance, no replay");
            System.out.println("PASS: captured tutorial entry 3265 -> authoritative scheduler -> three authored signals, no replay");
        }
        System.out.println("PASS: stock action fields, delayed signal timing, Limit condition, signal transitions, inherited/recovered tags, compound targets, destruction guards, full-compound refusal, variance and target guards");
    }
}
