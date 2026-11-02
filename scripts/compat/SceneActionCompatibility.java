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
        tagWire(FireCatalogExport.argumentValue(tags),new String[]{"direct","inherited"});
        var manyTags=new com.threerings.tudey.config.TagConfig();manyTags.tags=new String[]{"a","b","c","d","e","f","g","h","i"};tagWire(FireCatalogExport.argumentValue(manyTags),manyTags.tags);
        var legacy=new org.tortodrone.character.StarterCatalog.Model("Fixture",Map.of("Tags",new org.tortodrone.world.FireCatalog.ListValue("com.threerings.tudey.config.TagConfig",List.of("direct","inherited"))));
        var restoredTags=org.tortodrone.world.FireCatalog.modelJson(org.tortodrone.world.FireCatalog.modelJson(legacy)).arguments().get("Tags");
        tagWire(restoredTags,new String[]{"direct","inherited"});
        System.out.println("PASS: stock reader accepts native iterable TagConfig and legacy imported tags as TagConfig, including inherited String arrays");
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
        var destructionTargets=new TargetConfig.Compound();var absent=new TargetConfig.Tagged();absent.tag="missing";
        destructionTargets.targets=new TargetConfig[]{absent,target,target};destroy.target=destructionTargets;
        var many=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(destroy,0)));
        if(!(many instanceof SceneActions.DestroyMany group)||!group.tags().equals(List.of("missing","door end","door end")))throw new AssertionError("Compound destruction lost order/multiplicity");
        destructionTargets.targets=new TargetConfig[]{target,new TargetConfig.Source()};
        try{SceneContentExport.sceneAction(destroy,0);throw new AssertionError("Unsupported destruction sibling dropped");}catch(IOException expected){}
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
        if(!(SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(new ActionConfig.None(),0))) instanceof SceneActions.Sequence noop)||!noop.actions().isEmpty())throw new AssertionError("Native None must succeed without an effect");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(com.threerings.config.ConfigManager)com.threerings.config.ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var nativeChoice=new ActionConfig.Random();var option=new ActionConfig.WeightedAction();option.weight=2.5f;
        var blockSpawn=new ActionConfig.SpawnActor();blockSpawn.actor=new com.threerings.config.ConfigReference<>("Block/Breakable/Clockworks, base");option.action=blockSpawn;
        var zero=new ActionConfig.WeightedAction();zero.weight=0;zero.action=new ActionConfig.None();nativeChoice.actions=new ActionConfig.WeightedAction[]{option,zero};
        var preparedChoice=SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(nativeChoice,0,cm)));
        if(!(preparedChoice instanceof SceneActions.Choice weights)||!weights.weights().equals(List.of(2.5f,0f))||!(weights.actions().getFirst() instanceof SceneActions.Spawn block)||!block.fields().get("damage-action").equals("destroy"))throw new AssertionError("Native weighted block spawn lost weights, branch or native damage action");
        var unsupportedSibling=new ActionConfig.Signal();unsupportedSibling.target=new TargetConfig.Source();unsupportedSibling.name="unsupported";zero.action=unsupportedSibling;
        try{SceneContentExport.sceneAction(nativeChoice,0,cm);throw new AssertionError("Unsupported zero-weight sibling dropped");}catch(IOException expected){}
        System.out.println("PASS: native Random/WeightedAction -> all prepared branches and exact weights; Clockworks block destruction; unsupported sibling refused even at zero weight");
        var recursive=new ActionConfig.SpawnActor();recursive.actor=new com.threerings.config.ConfigReference<>("Character/NPC/Companions/Knights/Immortal/Sword Knight/Tier 1");
        var squadSpawn=new ActionConfig.SpawnActor();squadSpawn.actor=new com.threerings.config.ConfigReference<>("Character/Scripted/Missions/Tutorial/Final Test/Knight Squad/Spiral Knight");
        squadSpawn.actor.kW().put("Goal","waypoint_sk1");squadSpawn.actor.kW().put("Wait",500);squadSpawn.actor.kW().put("End Orientation",(float)Math.PI/2);
        var squadAction=(SceneActions.Spawn)SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(squadSpawn,0,cm)));
        var movement=org.tortodrone.world.SceneMovementScript.read(new org.tortodrone.world.SceneContent.Spec(squadAction.fields()));
        if(movement==null||!movement.goal().equals("waypoint_sk1")||movement.waitMillis()!=500||movement.rotation()!=(float)Math.PI/2)throw new AssertionError("Native nested Goal/Wait/End Orientation script bindings were lost");
        System.out.println("PASS: original squad script preserves nested Goal, Wait and final rotation without fabricating a combat policy");
        try{FireCatalogExport.readSceneAction(cm,recursive);throw new AssertionError("Recursive companion graph was embedded into projectile metadata");}catch(IOException expected){if(!expected.getMessage().contains("scene actor graph"))throw expected;}
        if(FireCatalogExport.readSceneAction(cm,new ActionConfig.SpawnActor()).kind()!=org.tortodrone.world.FireCatalog.Kind.COMPOUND)throw new AssertionError("Refusal poisoned later action preparation");
        delayed.variance=50;
        if(!(SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(delayed,0))) instanceof SceneActions.Delay varying)||varying.variance()!=50)throw new AssertionError("Native variance field lost");
        signal.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(signal,0);throw new AssertionError("Unsupported target accepted");}catch(IOException expected){}
        var close=new com.threerings.projectx.config.ProjectXActionConfig.ClearDialog();close.target=new TargetConfig.Activator();
        SceneContentExport.verifyConversationResponse(close,"part 2a",null,"clear-dialog-activator");
        SceneContentExport.verifyConversationResponse(close,"",null,"");
        try{SceneContentExport.verifyConversationResponse(close,"part 2a",null,"");throw new AssertionError("Unsourced closing response converted into a jump");}catch(IOException expected){}
        close.target=new TargetConfig.Source();
        try{SceneContentExport.verifyConversationResponse(close,"part 2a",null,"clear-dialog-activator");throw new AssertionError("Changed native response target accepted");}catch(IOException expected){}
        var objective=new com.threerings.projectx.config.ProjectXActionConfig.SetObjective();
        objective.name="findRhendon";objective.persistence=com.threerings.projectx.config.Persistence.KNIGHT;
        objective.objective=new com.threerings.projectx.config.ObjectiveConfig.Simple();
        ((com.threerings.projectx.config.ObjectiveConfig.Simple)objective.objective).text="m.find_rhendon";
        if(!SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(objective,0,null,true))).equals(new SceneActions.Objective(new org.tortodrone.character.KnightObjective("findRhendon","m.find_rhendon"))))throw new AssertionError("Native KNIGHT objective lost its message");
        objective.objective=null;
        if(!SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(objective,0,null,true))).equals(new SceneActions.Objective(new org.tortodrone.character.KnightObjective("findRhendon",null))))throw new AssertionError("Null objective config with KNIGHT persistence must erase the objective");
        objective.persistence=com.threerings.projectx.config.Persistence.SCENE;
        try{SceneContentExport.sceneAction(objective,0,null,true);throw new AssertionError("Unimplemented objective scope accepted");}catch(IOException expected){}
        objective.persistence=com.threerings.projectx.config.Persistence.KNIGHT;objective.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(objective,0,null,true);throw new AssertionError("Arbitrary objective target accepted");}catch(IOException expected){}
        System.out.println("PASS: native SetObjective KNIGHT set/erase, including retained KNIGHT persistence with null config; unsupported scope/target refused");
        var progress=new com.threerings.projectx.dungeon.config.DungeonActionConfig.ProgressEvent();progress.level="tutorial";progress.progress="10_Move";
        if(!SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(progress,0))).equals(new SceneActions.ProgressEvent("tutorial","10_Move")))throw new AssertionError("Native analytics vocabulary lost");
        progress.level="";
        try{SceneContentExport.sceneAction(progress,0);throw new AssertionError("Unnamed progress event accepted");}catch(IOException expected){}
        var nextFloor=new com.threerings.projectx.dungeon.config.DungeonActionConfig.NextFloor();
        if(!(nextFloor.target instanceof TargetConfig.Activator)||!SceneActions.parse(Json.stringify(SceneContentExport.sceneAction(nextFloor,0))).equals(new SceneActions.NextFloor()))throw new AssertionError("Native NextFloor target/shape changed");
        var strippedPortal=new ActionConfig.Conditional();var blank=new com.threerings.tudey.config.ConditionConfig.Tagged();blank.tag="";blank.target=new TargetConfig.Source();strippedPortal.condition=blank;strippedPortal.action=nextFloor;strippedPortal.elseAction=null;
        var restoredPortal=(ActionConfig.Conditional)SceneContentExport.restoreNextFloorGuard(strippedPortal);
        if(restoredPortal==strippedPortal||!(restoredPortal.condition instanceof com.threerings.tudey.config.ConditionConfig.InstanceOf pawn)||!pawn.logicClass.equals("com.threerings.tudey.server.logic.PawnLogic")||!(pawn.target instanceof TargetConfig.Activator)||strippedPortal.condition!=blank)throw new AssertionError("Exact authored portal guard was not restored independently");
        blank.tag="real guard";if(SceneContentExport.restoreNextFloorGuard(strippedPortal)!=strippedPortal)throw new AssertionError("Non-stripped portal condition overwritten");blank.tag="";
        strippedPortal.elseAction=new ActionConfig.None();if(SceneContentExport.restoreNextFloorGuard(strippedPortal)!=strippedPortal)throw new AssertionError("Portal else branch dropped");strippedPortal.elseAction=null;
        nextFloor.target=new TargetConfig.Source();
        try{SceneContentExport.sceneAction(nextFloor,0);throw new AssertionError("Unsupported NextFloor target accepted");}catch(IOException expected){}
        if(args.length>1){
            var folder=java.nio.file.Path.of(args[1]);var properties=new Properties();
            try(var input=java.nio.file.Files.newInputStream(folder.resolve("map.properties"))){properties.load(input);}
            var content=org.tortodrone.world.SceneContent.load(folder,properties.getProperty("scene-sha256"));
            var finalPortal=content.entries().stream().filter(e->e.key().equals("1943")&&e.spec().flag("scene-sensor",false)).findFirst().orElseThrow();
            if(!SceneActions.rule(finalPortal).action().equals(new SceneActions.NextFloor()))throw new AssertionError("Original portal no longer performs NextFloor");
            System.out.println("PASS: original area 1943 retains its pawn intersection and native Activator NextFloor action; no destination is embedded in its graph");
            for(String name:List.of("Intro","Meetup")){
                var definition=content.conversations().get("@conversation/Tutorial/Rhendon "+name);
                if(definition==null||!definition.initial().equals("part 1a")||!definition.nodes().get("part 1a").responses().getFirst().destination().equals("part 2a")
                        ||!definition.nodes().get("part 2a").responses().getFirst().destination().isEmpty()||definition.nodes().get("part 2a").responses().getFirst().action()!=null)throw new AssertionError("Captured Rhendon dialogue missing or altered: "+name);
            }
            for(String key:List.of("3393","3481","2955"))if(content.entries().stream().noneMatch(e->e.key().equals(key)&&e.spec().text("kind","").equals("SceneTrigger")))throw new AssertionError("Complete opening handler missing: "+key);
            var button=content.entries().stream().filter(e->e.key().equals("2207")&&e.spec().text("kind","").equals("Switch")).findFirst().orElseThrow().spec();
            if(!button.text("switch-mode","").equals("button-once")||!button.text("state.1.tag","").equals("door 1")||!button.text("state.1.signal","").equals("open"))throw new AssertionError("Native one-time button lost its authored gate action");
            var shrubs=content.entries().stream().filter(e->e.spec().text("config","").equals("Block/Breakable/Haven")).toList();
            if(shrubs.isEmpty()||shrubs.stream().anyMatch(e->!e.spec().text("damage-action","").equals("destroy")))throw new AssertionError("Native tutorial shrub destruction missing");
            System.out.println("PASS: both capture-backed Rhendon dialogue edges and all three original opening/music handlers survive export");
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
    private static void tagWire(Object tags,String[] expected)throws Exception {
        var model=new org.tortodrone.character.StarterCatalog.Model("Fixture",Map.of("Tags",tags));
        var codec=new org.tortodrone.protocol.SessionCodec();var bytes=new java.io.ByteArrayOutputStream();
        var delta=codec.sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(),List.of(),new int[0],Map.of(),true,List.of(new org.tortodrone.world.SceneEffect(1,model,100,0,0,0,1001)));
        for(var frame:List.of(org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"fixture","Fixture"),delta))bytes.write(frame,4,frame.length-4);
        var reader=new com.threerings.io.v(new java.io.ByteArrayInputStream(bytes.toByteArray()));reader.qe();reader.qe();
        var event=(com.threerings.tudey.dobj.SceneDeltaEvent)((com.threerings.presents.net.EventNotification)reader.qe()).yj();
        Object effect=event.aem()[0];
        var field=effect.getClass().getDeclaredField("_config");field.setAccessible(true);
        var ref=(com.threerings.config.ConfigReference<?>)field.get(effect);
        if(!(ref.kW().get("Tags") instanceof com.threerings.tudey.config.TagConfig actual)||!Arrays.equals(actual.adx(),expected)||reader.available()!=0)throw new AssertionError("TagConfig stock wire round trip changed");
    }

}
