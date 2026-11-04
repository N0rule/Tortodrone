import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.projectx.dungeon.config.DepthScaleConfig;
import com.threerings.projectx.dungeon.config.SpawnTableConfig;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.config.HandlerConfig;
import com.threerings.tudey.config.PlaceableConfig;
import com.threerings.tudey.config.TileConfig;
import com.threerings.tudey.data.TudeySceneModel;

/** Exports numeric and config-reference content for a captured native scene. */
public final class SceneContentExport {
    private static Properties rules;
    private static final List<String> importReport=new ArrayList<>();
    private record Startup(String key,int handler,float x,float y,float rotation,Map<String,Object> action) { }
    private static final List<Startup> startups=new ArrayList<>();
    private static final Map<String,String> actorTemplateIds=new java.util.LinkedHashMap<>();
    private static final List<ConfigReference<ActorConfig>> actorTemplates=new ArrayList<>();
    private static com.threerings.projectx.dungeon.data.LevelSceneConfig levelContext;
    private static final Map<String,com.threerings.projectx.config.ProjectXActionConfig.StartConversation> scriptedConversations=new TreeMap<>();
    private SceneContentExport() { }
    static boolean scriptedActor(String name){return rules!=null&&Boolean.parseBoolean(rules.getProperty("actor."+name+".script.restore-program","false"));}

    /** Writes deterministic content metadata next to an already-staged scene.dat. */
    public static void export(Path game, ConfigManager cm, TudeySceneModel scene, Path output)
        throws Exception {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(cm, "config manager");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(output, "output");
        Path layout = output.resolve("scene.dat");
        if (!Files.isRegularFile(layout)) throw new IOException("Missing staged scene.dat");
        Path target = output.resolve("content.properties");
        if (Files.exists(target)) throw new IOException("Refusing to replace content.properties");

        importReport.clear();scriptedConversations.clear();startups.clear();actorTemplateIds.clear();actorTemplates.clear();
        levelContext=scene.adG() instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig level?level:null;
        TreeMap<String, String> values = new TreeMap<>();
        rules=new Properties();
        try(var in=Files.newInputStream(Path.of("content/_internal/provenance/actor-rules.properties"))){rules.load(in);}
        if(!"1".equals(rules.getProperty("format")))throw new IOException("Unsupported authored actor rules");
        values.put("format", "1");
        values.put("layout-sha256", sha256(layout));
        addDepthScales(cm, values);
        ScenePlayerStatsExport.export(cm, values);
        SceneItemExport.export(cm,values);
        SceneStatusExport.export(cm,values);
        writeCycles(values);

        List<Binding> bindings = collectBindings(cm, scene);
        bindings.sort(Comparator.comparing(Binding::sourceKey).thenComparingInt(Binding::handlerIndex));
        List<String> indexes = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            String n = Integer.toString(i);
            Binding binding = bindings.get(i);
            String prefix = "entry." + n + ".";
            indexes.add(n);
            put(values, prefix + "source-key", binding.sourceKey());
            put(values, prefix + "handler-index", Integer.toString(binding.handlerIndex()));
            putFloat(values, prefix + "x", binding.x());
            putFloat(values, prefix + "y", binding.y());
            putFloat(values, prefix + "rotation", binding.rotation());
            put(values, prefix + "config", binding.actorReference().getName());
            writeActor(cm, values, prefix, binding.actorReference());
        }
        for(var startup:startups){
            String index=Integer.toString(indexes.size()),p="entry."+index+".";indexes.add(index);
            var fields=Map.ofEntries(Map.entry("kind","SceneTrigger"),Map.entry("config","@startup"),Map.entry("source-key",startup.key()),Map.entry("state-key",startup.key()+":startup:"+startup.handler()),Map.entry("event","signal-start"),Map.entry("watch-tag","startup:"+startup.key()+":"+startup.handler()),Map.entry("signal","startup"),Map.entry("scene-startup","true"),Map.entry("action",org.tortodrone.resources.Json.stringify(startup.action())));
            fields.forEach((k,v)->values.put(p+k,v));values.put(p+"x",Float.toString(startup.x()));values.put(p+"y",Float.toString(startup.y()));values.put(p+"rotation",Float.toString(startup.rotation()));
        }
        collectTriggers(cm,scene,values,indexes);
        collectAreaTriggers(cm,scene,values,indexes);
        collectAnchors(cm,scene,values,indexes);
        values.put("entries", String.join(",", indexes));
        writeActorTemplates(cm,values);
        writeDialogues(cm,values,bindings);
        Files.writeString(target, propertiesText(values), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE_NEW);
        importReport.add(0,"Supported extracted actor bindings: "+bindings.size());
        Files.writeString(output.resolve("import-source-report.txt"),String.join("\n",importReport)+"\n",StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    static void writeActorTemplates(ConfigManager cm,Map<String,String> values)throws Exception {
        for(int i=0;i<actorTemplates.size();i++){
            var fields=new TreeMap<String,String>();var reference=actorTemplates.get(i);
            fields.put("config",reference.getName());
            writeActor(cm,fields,"",reference);
            new org.tortodrone.world.SceneActions.Spawn(fields);
            for(var field:fields.entrySet())values.put("actor-template.spawn_"+i+"."+field.getKey(),field.getValue());
            // Do not imply parity merely because a cyclic config can now be represented.
            importReport.add("Deferred scene actor template spawn_"+i+": "+reference.getName()+"; retained death graph, other actor handlers still require separate verification");
        }
        values.put("actor-templates",String.join(",",java.util.stream.IntStream.range(0,actorTemplates.size()).mapToObj(i->"spawn_"+i).toList()));
        var prepared=new Properties();prepared.setProperty("format","1");prepared.setProperty("layout-sha256","template-validation");
        values.forEach((key,value)->{if(key.equals("actor-templates")||key.startsWith("actor-template."))prepared.setProperty(key,value);});
        org.tortodrone.world.SceneContent.load(prepared,"template-validation");
    }

    /** Reserve identity before expanding definitions so legitimate respawn cycles stay finite. */
    static String deferSceneActor(ConfigReference<ActorConfig> actor)throws IOException {
        String key=org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(actor));
        var existing=actorTemplateIds.get(key);if(existing!=null)return existing;
        if(actorTemplates.size()>=128)throw new IOException("Too many deferred scene actors");
        String id="spawn_"+actorTemplates.size();actorTemplateIds.put(key,id);
        var copy=new ConfigReference<ActorConfig>(actor.getName());copy.kW().putAll(actor.kW());actorTemplates.add(copy);return id;
    }

    private static List<Binding> collectBindings(ConfigManager cm, TudeySceneModel scene)
        throws Exception {
        List<Binding> result = new ArrayList<>();
        var state=new com.threerings.projectx.dungeon.config.SpawnTableConfig.a();
        state.depth=0;state.difficulty=com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM;
        if(scene.adG() instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig level)state.bkh=level;
        for (Object raw : scene.adK()) {
            HandlerConfig[] handlers;
            String sourceKey;
            float x, y, rotation;
            Map<String,Object> sourceArgs;
            if (raw instanceof TudeySceneModel.PlaceableEntry entry) {
                sourceKey = key(entry.getKey());
                if (entry.placeable == null) throw new IOException("Placeable reference missing at " + sourceKey);
                sourceArgs=entry.placeable.kW();
                PlaceableConfig config = entry.placeable.a(cm, PlaceableConfig.class);
                if (config == null) throw new IOException("Unresolved placeable at " + sourceKey);
                Object implementation = config.q(cm);
                Object rawHandlers = field(implementation, "handlers");
                handlers = rawHandlers == null ? null : (HandlerConfig[])rawHandlers;
                var point = entry.au(cm);
                x = finite(point.x, "x at " + sourceKey);
                y = finite(point.y, "y at " + sourceKey);
                var q=entry.transform.tE(); rotation=(float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI);
                if(implementation instanceof PlaceableConfig.StatefulProp prop&&prop.actor!=null)
                    result.add(new Binding(sourceKey,-1,x,y,rotation,bindArguments(prop.actor,sourceArgs)));
            } else if (raw instanceof TudeySceneModel.TileEntry entry) {
                sourceKey = key(entry.getKey());
                sourceArgs=entry.tile.kW();
                if (entry.tile == null) throw new IOException("Tile reference missing at " + sourceKey);
                var config = entry.tile.a(cm, TileConfig.class).an(cm);
                if (config == null) throw new IOException("Unresolved tile at " + sourceKey);
                Object rawHandlers = field(config, "handlers");
                handlers = rawHandlers == null ? null : (HandlerConfig[])rawHandlers;
                var point = entry.au(cm);
                x = finite(point.x, "x at " + sourceKey);
                y = finite(point.y, "y at " + sourceKey);
                rotation=(float)Math.IEEEremainder((entry.rotation-1)*Math.PI/2,2*Math.PI);
            } else {
                continue;
            }
            if (handlers == null) continue;
            for (int hi = 0; hi < handlers.length; hi++) {
                HandlerConfig handler = handlers[hi];
                if (!(handler instanceof HandlerConfig.Startup startup)) {if(!(raw instanceof TudeySceneModel.PlaceableEntry)||!sceneHandler(handler))importReport.add("Unsupported handler at "+sourceKey+" / "+hi+": "+handler.getClass().getName());continue;}
                var staged=new ArrayList<Binding>();int startupCount=startups.size();
                try{collectStartup(cm,staged,sourceKey,hi,x,y,rotation,startup.action,sourceArgs,state,0);result.addAll(staged);}
                catch(IOException unsupported){startups.subList(startupCount,startups.size()).clear();importReport.add("Unsupported complete startup at "+sourceKey+" / "+hi+": "+unsupported.getMessage());}
            }
        }
        return result;
    }

    /** Expand only native Startup graphs whose leaf is SpawnActor; preserve their authored path. */
    private static void collectStartup(ConfigManager cm, List<Binding> result, String sourceKey,
                                       int handlerIndex, float x, float y, float rotation,
                                       ActionConfig action,Map<String,Object> sourceArgs,com.threerings.projectx.dungeon.config.SpawnTableConfig.a state,int depth) throws Exception {
        if (action == null) return;
        if (depth > 32) throw new IOException("Startup compound nesting exceeds limit at " + sourceKey);
        if(action instanceof ActionConfig.Random){
            var graph=sceneAction(action,0,cm);org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(graph));
            startups.add(new Startup(sourceKey,handlerIndex,x,y,rotation,graph));return;
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction wrapper){
            var fire=wrapper.fireAction==null?null:cm.a(com.threerings.projectx.dungeon.config.FireActionConfig.class,wrapper.fireAction);
            if(fire==null)throw new IOException("Unresolved startup fire action: "+wrapper.fireAction);
            collectStartup(cm,result,sourceKey,handlerIndex,x,y,rotation,fire.action,sourceArgs,state,depth+1);return;
        }
        if (action instanceof ActionConfig.SpawnActor spawn) {
            if(!(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Unsupported startup spawn location");
            // Native SpawnActor explicitly succeeds without spawning when its reference is null.
            // For example scene 398's pipe valves select this branch with Spawn with Valve?=No.
            if (spawn.actor == null) {importReport.add("Native null startup actor (no spawn) at "+sourceKey);return;}
            ActorConfig actor = spawn.actor.a(cm, ActorConfig.class);
            if (actor == null) throw new IOException("Unresolved startup actor at " + sourceKey);
            result.add(new Binding(sourceKey, handlerIndex, x, y, rotation, bindArguments(spawn.actor,sourceArgs)));
        } else if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.TableSpawn table){
            var config=cm.a(com.threerings.projectx.dungeon.config.SpawnTableConfig.class,table.spawnTable);
            if(config==null)throw new IOException("Unresolved startup spawn table: "+table.spawnTable);
            var actors=new ArrayList<ConfigReference<ActorConfig>>();config.a(cm,state,null,null,actors::add);
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            for(int i=0;i<actors.size();i++)result.add(new Binding(sourceKey+"/table."+i,handlerIndex,
                x+table.translation.x*c-table.translation.y*s,y+table.translation.x*s+table.translation.y*c,
                (table.relative?rotation:0)+table.rotation,bindArguments(actors.get(i),sourceArgs)));
        } else if (action instanceof ActionConfig.Compound compound) {
            if (compound.actions == null) return;
            for (int i = 0; i < compound.actions.length; i++)
                collectStartup(cm, result, sourceKey + "/startup." + handlerIndex + ".action." + i,
                    handlerIndex, x, y, rotation, compound.actions[i],sourceArgs,state,depth + 1);
        } else throw new IOException("Unsupported startup action "+action.getClass().getName());
    }


    /** Whole-graph extraction only: an unsupported sibling must never disappear from a compound. */
    private static void collectTriggers(ConfigManager cm,TudeySceneModel scene,Map<String,String> values,List<String> indexes)throws Exception {
        for(Object raw:scene.adK())if(raw instanceof TudeySceneModel.PlaceableEntry entry){
            var reference=resolvedPlaceable(cm,entry.placeable);
            var config=reference.a(cm,PlaceableConfig.class);
            var original=config.q(cm);
            if(original.handlers==null)continue;
            if(reference.getName().equals("Trigger/Actor Removed (2 Tags)")){
                String source=key(entry.getKey());
                try{
                    var group=twoTagRemoval(cm,reference,config,original);
                    var point=entry.au(cm);var q=entry.transform.tE();
                    float rotation=(float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI);
                    for(int i=0;i<2;i++){
                        var fields=new TreeMap<String,String>();
                        fields.put("kind","SceneTrigger");fields.put("config",reference.getName());fields.put("source-key",source);fields.put("handler-index",Integer.toString(i));
                        fields.put("state-key",source+":removed-group");fields.put("event","removed");fields.put("all","true");fields.put("watch-tag",group.tags().get(i));fields.put("removed-group",String.join(",",group.tags()));
                        fields.put("x",Float.toString(finite(point.x,"trigger x")));fields.put("y",Float.toString(finite(point.y,"trigger y")));fields.put("rotation",Float.toString(finite(rotation,"trigger rotation")));
                        fields.put("action",org.tortodrone.resources.Json.stringify(group.action()));
                        org.tortodrone.world.SceneActions.rule(new org.tortodrone.world.SceneContent.Entry(source,point.x,point.y,rotation,new org.tortodrone.world.SceneContent.Spec(fields)));
                        String index=Integer.toString(indexes.size());indexes.add(index);fields.forEach((k,v)->values.put("entry."+index+"."+k,v));
                    }
                    importReport.remove("Unsupported handler at "+source+" / 2: "+HandlerConfig.VariableChanged.class.getName());
                    importReport.add("Restored native two-tag removal counter/Limit/VariableChanged at "+source);
                }catch(IOException|IllegalArgumentException unsupported){importReport.add("Unsupported complete two-tag removal at "+source+": "+unsupported.getMessage());}
                continue;
            }
            Map<String,Object> counterGraph=null;
            if(reference.getName().equals("Trigger/Multi Trigger Action"))try{counterGraph=multiTrigger(cm,reference,config,original);}
            catch(IOException unsupported){importReport.add("Unsupported complete multi-trigger at "+key(entry.getKey())+": "+unsupported.getMessage());continue;}
            for(int hi=0;hi<(counterGraph==null?original.handlers.length:1);hi++){
                var handler=restoreSceneHandler(reference.getName(),original.handlers[hi]);
                if(counterGraph!=null){var signal=new HandlerConfig.Signal();signal.name="increment";handler=signal;}
                if(!sceneHandler(handler))continue;
                if(handler!=original.handlers[hi])importReport.add("Restored archived "+reference.getName()+" handler at "+key(entry.getKey())+" / "+hi);
                try{
                    var fields=new TreeMap<String,String>();
                    fields.put("kind","SceneTrigger");fields.put("config",entry.placeable.getName());fields.put("source-key",key(entry.getKey()));fields.put("handler-index",Integer.toString(hi));
                    fields.put("state-key",key(entry.getKey())+":"+hi);
                    var point=entry.au(cm);fields.put("x",Float.toString(finite(point.x,"trigger x")));fields.put("y",Float.toString(finite(point.y,"trigger y")));var q=entry.transform.tE();
                    fields.put("rotation",Float.toString((float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI)));
                    List<String> tags;
                    if(handler instanceof HandlerConfig.ActorRemoved removed){
                        if(!(removed.target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged))throw new IOException("ActorRemoved target is not Tagged");
                        fields.put("event","removed");fields.put("all",Boolean.toString(removed.all));tags=List.of(tagged.tag);
                    }else if(handler.getClass()==HandlerConfig.Timer.class){
                        fields.put("event","signal-start");fields.put("signal","startup");fields.put("scene-startup","true");
                        tags=List.of("startup:"+key(entry.getKey())+":"+hi);
                    }else{
                        fields.putAll(sceneSignal(handler));
                        tags=sceneTags(config,reference.kW(),original.tags);
                        if(tags.isEmpty())throw new IOException("Signal handler has no resolved tags");
                    }
                    var graph=counterGraph==null?sceneAction(((HandlerConfig.ActionHandlerConfig)handler).action,0,cm):counterGraph;
                    if(handler.getClass()==HandlerConfig.Timer.class){var timer=(HandlerConfig.Timer)handler;graph=timerAction(timer,graph);}
                    var parsed=org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(graph));
                    try{for(String tag:tags)new org.tortodrone.world.SceneActions.Rule(key(entry.getKey())+":"+hi,fields.get("event"),tag,fields.getOrDefault("signal",""),Boolean.parseBoolean(fields.getOrDefault("all","false")),parsed,fields.get("state-key"),"entry:"+key(entry.getKey()));}
                    catch(IllegalArgumentException invalid){throw new IOException("Invalid complete native handler: "+invalid.getMessage(),invalid);}
                    fields.put("action",org.tortodrone.resources.Json.stringify(graph));
                    for(int ti=0;ti<tags.size();ti++){
                        if(tags.get(ti).isBlank())throw new IOException("Handler tag is empty");
                        String index=Integer.toString(indexes.size());indexes.add(index);
                        for(var field:fields.entrySet())values.put("entry."+index+"."+field.getKey(),field.getValue());
                        values.put("entry."+index+".handler-index",hi+".tag."+ti);values.put("entry."+index+".watch-tag",tags.get(ti));
                    }
                }catch(IOException unsupported){importReport.add("Unsupported complete scene handler at "+key(entry.getKey())+" / "+hi+": "+unsupported.getMessage());}
            }
        }
    }
    record RemovedGroup(List<String> tags,Map<String,Object> action) { }
    /** Capture 37268/37439 and 37657/38737: increment first, act only on equality.
     * The stripped reverse branch has no recoverable policy; only an authored no-op is allowed. */
    static Map<String,Object> multiTrigger(ConfigManager cm,ConfigReference<PlaceableConfig> reference,PlaceableConfig config,PlaceableConfig.Original original)throws IOException {
        if(!reference.getName().equals("Trigger/Multi Trigger Action")||original.handlers.length!=2)throw new IOException("Multi-trigger footprint changed");
        counterFootprint(original.handlers);
        requirePaths(config,"Action","implementation.handlers[0].action.actions[1].action");
        requirePaths(config,"Reverse Action","implementation.handlers[1].action.actions[1].action");
        requirePaths(config,"Triggers","implementation.handlers[0].action.actions[1].condition.expression.second_operand.value","implementation.handlers[1].action.actions[1].condition.expression.second_operand.value");
        if(!(reference.kW().get("Action") instanceof ActionConfig action))throw new IOException("Missing authored counter action");
        Object reverse=reference.kW().get("Reverse Action");
        if(reverse!=null&&(!(reverse instanceof ActionConfig reverseAction)||!noAction(reverseAction)))throw new IOException("Multi-trigger reverse policy is not recovered");
        return Map.of("kind","count","threshold",counterThreshold(reference.kW().get("Triggers")),"action",sceneAction(action,0,cm));
    }
    private static boolean noAction(ActionConfig action){return action instanceof ActionConfig.None||action!=null&&action.getClass()==ActionConfig.SpawnActor.class&&((ActionConfig.SpawnActor)action).actor==null;}
    private static int counterThreshold(Object value)throws IOException {
        try{String text=String.valueOf(value);if(!text.matches("[1-9][0-9]{0,6}"))throw new NumberFormatException();int result=Integer.parseInt(text);if(result>1_000_000)throw new NumberFormatException();return result;}
        catch(NumberFormatException invalid){throw new IOException("Invalid or missing native counter threshold",invalid);}
    }
    private static void requirePaths(com.threerings.config.ParameterizedConfig config,String name,String... paths)throws IOException {
        if(!(config.getParameter(name) instanceof com.threerings.config.Parameter.Direct direct)||!Arrays.equals(direct.paths,paths))throw new IOException("Native counter parameter changed: "+name);
    }
    private static void counterFootprint(HandlerConfig[] handlers)throws IOException {
        if(handlers.length!=2)throw new IOException("Counter handler count changed");
        for(var handler:handlers)if(handler.getClass()!=HandlerConfig.Signal.class||!((HandlerConfig.Signal)handler).name.isEmpty()||((HandlerConfig.Signal)handler).refractoryPeriod!=0
            ||!(((HandlerConfig.Signal)handler).action instanceof ActionConfig.Compound compound)||compound.actions.length!=1||!strippedGuard(compound.actions[0])
            ||!noAction(((ActionConfig.Conditional)compound.actions[0]).action))throw new IOException("Stripped counter handler footprint changed");
    }
    /** Native parameter paths survive; the archived marker retains the stripped counter=2 graph. */
    static RemovedGroup twoTagRemoval(ConfigManager cm,ConfigReference<PlaceableConfig> reference,PlaceableConfig config,PlaceableConfig.Original original)throws IOException {
        if(!reference.getName().equals("Trigger/Actor Removed (2 Tags)")||original.handlers.length!=3
            ||!(original.handlers[2] instanceof HandlerConfig.VariableChanged changed)||!strippedGuard(changed.action))throw new IOException("Two-tag removal footprint changed");
        for(var binding:Map.of("Actor Tag","implementation.handlers[0].target.tag","Actor Tag 2","implementation.handlers[1].target.tag","Action","implementation.handlers[2].action.action").entrySet()){
            if(!(config.getParameter(binding.getKey()) instanceof com.threerings.config.Parameter.Direct direct)||direct.paths.length!=1||!direct.paths[0].equals(binding.getValue()))throw new IOException("Two-tag removal parameter path changed");
        }
        var tags=new ArrayList<String>();
        for(int i=0;i<2;i++){
            if(!(original.handlers[i] instanceof HandlerConfig.ActorRemoved removed)||!removed.all||!(removed.target instanceof com.threerings.tudey.config.TargetConfig.Tagged target)
                ||target.tag.isBlank()||target.tag.contains(",")||!strippedGuard(removed.action)||!(((ActionConfig.Conditional)removed.action).action instanceof ActionConfig.SpawnActor spawn)
                ||spawn.actor!=null||!(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Two-tag removal latch footprint changed");
            tags.add(target.tag);
        }
        var action=((ActionConfig.Conditional)changed.action).action;
        if(!(reference.kW().get("Action") instanceof ActionConfig supplied)||!Objects.equals(supplied,action))throw new IOException("Two-tag removal action binding changed");
        var graph=sceneAction(action,0,cm);org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(graph));
        return new RemovedGroup(List.copyOf(tags),graph);
    }
    private static boolean strippedGuard(ActionConfig action){
        return action instanceof ActionConfig.Conditional gate&&gate.elseAction==null&&gate.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tag
            &&tag.tag.isEmpty()&&!tag.all&&tag.target instanceof com.threerings.tudey.config.TargetConfig.Source;
    }
    private static void collectAreaTriggers(ConfigManager cm,TudeySceneModel scene,Map<String,String> values,List<String> indexes)throws Exception {
        for(var entry:scene.ci(TudeySceneModel.AreaEntry.class)){
            var reference=entry.area;
            for(int depth=0;depth<16;depth++){
                var config=reference.a(cm,com.threerings.tudey.config.AreaConfig.class);
                if(config==null)throw new IOException("Unresolved native area");
                if(!(config.implementation instanceof com.threerings.tudey.config.AreaConfig.Derived derived))break;
                if(derived.area==null)throw new IOException("Missing derived native area");
                var next=new ConfigReference<com.threerings.tudey.config.AreaConfig>(derived.area.getName());next.kW().putAll(derived.area.kW());
                for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct&&reference.kW().containsKey(parameter.name))for(String path:direct.paths){
                    var match=java.util.regex.Pattern.compile("implementation\\.area\\[\"([^\"]+)\"\\]").matcher(path);
                    if(match.matches())next.kW().put(match.group(1),reference.kW().get(parameter.name));
                }
                reference=next;if(depth==15)throw new IOException("Area derivation exceeds limit");
            }
            var original=reference.a(cm,com.threerings.tudey.config.AreaConfig.class).aj(cm);
            if(original.handlers==null)continue;
            for(int hi=0;hi<original.handlers.length;hi++)try{
                var handler=original.handlers[hi];if(!(handler instanceof HandlerConfig.BaseIntersection sensor))throw new IOException("Unsupported area handler "+handler.getClass().getName());
                if(!(sensor.shape instanceof HandlerConfig.DefaultShape shape)||shape.expansion!=0)throw new IOException("Unsupported area intersection shape/expansion");
                String event;int mask;
                if(sensor instanceof HandlerConfig.IntersectionStart start){event="signal-start";mask=start.mask;}
                else if(sensor instanceof HandlerConfig.IntersectionStop stop){event="signal-stop";mask=stop.mask;}
                else if(sensor instanceof HandlerConfig.Intersection each&&each.refractoryPeriod==0){event="signal";mask=each.mask;}
                else throw new IOException("Unsupported area intersection timing");
                ActionConfig action=sensor.action;
                if(reference.getName().equals("Portals/Next Floor")){
                    var restored=restoreNextFloorGuard(action);
                    if(restored!=action)importReport.add("Restored archived NextFloor PawnLogic guard at "+entry.getKey()+" / "+hi);
                    action=restored;
                }
                if(reference.getName().equals("Player Enter/Base")&&action instanceof ActionConfig.Conditional conditional&&conditional.elseAction==null
                        &&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tagged&&tagged.tag.isEmpty()&&!tagged.all&&tagged.target instanceof com.threerings.tudey.config.TargetConfig.Source){
                    Object extra=reference.kW().get("Trigger Condition");
                    if(extra!=null&&(!(extra instanceof com.threerings.tudey.config.ConditionConfig[] conditions)||conditions.length!=0))throw new IOException("Unimplemented area trigger condition");
                    action=conditional.action;importReport.add("Restored archived PawnLogic area guard at "+entry.getKey()+" / "+hi);
                }
                var point=entry.au(cm);var polygon=new com.threerings.tudey.shape.config.ShapeConfig.Polygon();
                if(entry.vertices==null||entry.vertices.length<3||entry.vertices.length>256)throw new IOException("Invalid native area vertices");
                polygon.vertices=new com.threerings.tudey.shape.config.ShapeConfig.Vertex[entry.vertices.length];
                for(int v=0;v<entry.vertices.length;v++){polygon.vertices[v]=new com.threerings.tudey.shape.config.ShapeConfig.Vertex();polygon.vertices[v].x=finite(entry.vertices[v].x-point.x,"area x");polygon.vertices[v].y=finite(entry.vertices[v].y-point.y,"area y");}
                String index=Integer.toString(indexes.size()),prefix="entry."+index+".";
                var fields=new TreeMap<String,String>();fields.put("kind","SceneTrigger");fields.put("config",entry.area.getName());fields.put("source-key",key(entry.getKey()));fields.put("handler-index",Integer.toString(hi));fields.put("state-key",key(entry.getKey())+":"+hi);
                fields.put("x",Float.toString(point.x));fields.put("y",Float.toString(point.y));fields.put("rotation","0");fields.put("event",event);fields.put("watch-tag","area:"+entry.getKey()+":"+hi);fields.put("signal","intersection");
                fields.put("scene-sensor","true");fields.put("sensor-mask",Integer.toString(mask));fields.put("interaction-area","true");fields.put("source-shape",org.tortodrone.world.FireCatalog.modelJson(new org.tortodrone.character.StarterCatalog.Model("shape",Map.of("value",FireCatalogExport.argumentValue(polygon)))));
                fields.put("action",org.tortodrone.resources.Json.stringify(sceneAction(action,0,cm,true)));
                indexes.add(index);for(var field:fields.entrySet())values.put(prefix+field.getKey(),field.getValue());
            }catch(IOException unsupported){importReport.add("Unsupported complete area handler at "+entry.getKey()+" / "+hi+": "+unsupported.getMessage());}
        }
    }
    private static boolean pawnCondition(com.threerings.tudey.config.ConditionConfig condition,int depth){
        if(depth>16)return false;
        if(condition instanceof com.threerings.tudey.config.ConditionConfig.Always)return true;
        if(condition instanceof com.threerings.tudey.config.ConditionConfig.InstanceOf instance)return instance.target instanceof com.threerings.tudey.config.TargetConfig.Activator&&instance.logicClass.equals("com.threerings.tudey.server.logic.PawnLogic");
        if(condition instanceof com.threerings.tudey.config.ConditionConfig.All all){if(all.conditions==null)return false;for(var child:all.conditions)if(!pawnCondition(child,depth+1))return false;return true;}
        return false;
    }
    /** area.xml's exact stripped Next Floor guard: InstanceOf(PawnLogic, Activator). */
    static ActionConfig restoreNextFloorGuard(ActionConfig action){
        if(action instanceof ActionConfig.Conditional conditional&&conditional.elseAction==null
                &&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tagged&&tagged.tag.isEmpty()&&!tagged.all
                &&tagged.target instanceof com.threerings.tudey.config.TargetConfig.Source
                &&conditional.action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.NextFloor next
                &&next.target instanceof com.threerings.tudey.config.TargetConfig.Activator){
            var guard=new com.threerings.tudey.config.ConditionConfig.InstanceOf();guard.logicClass="com.threerings.tudey.server.logic.PawnLogic";guard.target=new com.threerings.tudey.config.TargetConfig.Activator();
            var restored=new ActionConfig.Conditional();restored.condition=guard;restored.action=next;restored.elseAction=null;return restored;
        }
        return action;
    }
    /** Static tagged entry logics are target locations, never synthetic server actors. */
    private static void collectAnchors(ConfigManager cm,TudeySceneModel scene,Map<String,String> values,List<String> indexes)throws Exception {
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)){
            var reference=resolvedPlaceable(cm,entry.placeable);var config=reference.a(cm,PlaceableConfig.class);var original=config.q(cm);
            if(original.tags==null)continue;
            var tags=sceneTags(config,reference.kW(),original.tags);if(tags.isEmpty())continue;
            String index=Integer.toString(indexes.size()),prefix="entry."+index+".";indexes.add(index);
            var point=entry.au(cm);var q=entry.transform.tE();
            put(values,prefix+"kind","SceneAnchor");put(values,prefix+"config",entry.placeable.getName());put(values,prefix+"source-key",key(entry.getKey()));put(values,prefix+"handler-index","anchor");
            put(values,prefix+"source-tags",String.join(",",tags));putFloat(values,prefix+"x",finite(point.x,"anchor x"));putFloat(values,prefix+"y",finite(point.y,"anchor y"));
            putFloat(values,prefix+"rotation",(float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI));
        }
    }
    private static ConfigReference<PlaceableConfig> resolvedPlaceable(ConfigManager cm,ConfigReference<PlaceableConfig> reference)throws IOException {
        for(int depth=0;depth<16;depth++){
            var config=reference.a(cm,PlaceableConfig.class);
            if(config==null)throw new IOException("Unresolved scene placeable "+reference.getName());
            if(!(config.implementation instanceof PlaceableConfig.Derived derived))return reference;
            if(derived.placeable==null)throw new IOException("Missing derived scene placeable");
            var next=new ConfigReference<PlaceableConfig>(derived.placeable.getName());next.kW().putAll(derived.placeable.kW());
            // Recover preserved argument-to-argument paths without mutating the native reference.
            for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct&&reference.kW().containsKey(parameter.name))
                for(String path:direct.paths){
                    var match=java.util.regex.Pattern.compile("implementation\\.placeable\\[\"([^\"]+)\"\\]").matcher(path);
                    if(match.matches())next.kW().put(match.group(1),reference.kW().get(parameter.name));
                }
            reference=next;
        }
        throw new IOException("Scene placeable derivation exceeds limit");
    }
    /** Exact stripped footprint of the archived One Time Action; do not generalize empty guards. */
    static HandlerConfig restoreSceneHandler(String config,HandlerConfig handler){
        // The archive preserves this name and the direct Action parameter path.
        // Preserve the bound graph and edge-trigger semantics; no implicit Limit.
        if(config.equals("Trigger/Repeat Action")&&handler instanceof HandlerConfig.SignalStart start&&start.name.isEmpty()){
            var result=new HandlerConfig.SignalStart();result.name="activate";result.action=start.action;return result;
        }
        // reference inputs placeable.xml: Trigger/One Time Action -> SignalStart("activate"),
        // Conditional(Limit(1)). Scene args retain its authored Action; no native config is mutated.
        if(config.equals("Trigger/One Time Action")&&handler instanceof HandlerConfig.SignalStart start
                &&start.name.isEmpty()&&start.action instanceof ActionConfig.Conditional conditional
                &&conditional.elseAction==null&&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tagged
                &&tagged.tag.isEmpty()&&!tagged.all&&tagged.target instanceof com.threerings.tudey.config.TargetConfig.Source){
            var result=new HandlerConfig.SignalStart();result.name="activate";
            var action=new ActionConfig.Conditional();var limit=new com.threerings.tudey.config.ConditionConfig.Limit();limit.limit=1;
            action.condition=limit;action.action=conditional.action;action.elseAction=null;result.action=action;return result;
        }
        return handler;
    }
    private static boolean sceneHandler(HandlerConfig handler){return handler instanceof HandlerConfig.ActorRemoved||handler instanceof HandlerConfig.Signal||handler instanceof HandlerConfig.SignalStart||handler instanceof HandlerConfig.SignalStop||handler.getClass()==HandlerConfig.Timer.class;}
    static Map<String,Object> timerAction(HandlerConfig.Timer timer,Map<String,Object> action)throws IOException {
        if(!Float.isFinite(timer.interval)||!Float.isFinite(timer.offset)||timer.interval<.001f||timer.interval>3600||Math.abs(timer.offset)>3600||timer.limit<0||timer.limit>1_000_000)throw new IOException("Unsupported native timer bounds");
        // Native casts the float seconds to milliseconds. Preserve combined initial rounding.
        int interval=(int)(timer.interval*1000f),initial=(int)(Math.max(0,timer.interval+timer.offset)*1000f);
        return Map.of("kind","timer","interval",interval,"offset",initial-interval,"limit",timer.limit,"action",action);
    }
    static Map<String,String> sceneSignal(HandlerConfig handler)throws IOException {
        if(handler instanceof HandlerConfig.Signal signal){
            if(signal.refractoryPeriod!=0)throw new IOException("Signal refractory period is not implemented");
            if(signal.name.isBlank())throw new IOException("Native signal name is stripped or empty");
            return Map.of("event","signal","signal",signal.name);
        }
        if(handler instanceof HandlerConfig.SignalStart signal){if(signal.name.isBlank())throw new IOException("Native signal name is stripped or empty");return Map.of("event","signal-start","signal",signal.name);}
        if(handler instanceof HandlerConfig.SignalStop signal){if(signal.name.isBlank())throw new IOException("Native signal name is stripped or empty");return Map.of("event","signal-stop","signal",signal.name);}
        throw new IOException("Unsupported signal handler");
    }
    static Map<String,Object> sceneAction(ActionConfig action,int depth)throws IOException {return sceneAction(action,depth,null);}
    static Map<String,Object> sceneAction(ActionConfig action,int depth,ConfigManager cm)throws IOException {return sceneAction(action,depth,cm,false);}
    static Map<String,Object> sceneAction(ActionConfig action,int depth,ConfigManager cm,boolean pawnActivator)throws IOException {
        if(depth>16)throw new IOException("Scene action graph is too deep");
        if(action instanceof ActionConfig.None)return Map.of("kind","sequence","stopOnFailure",false,"actions",List.of());
        if(action instanceof ActionConfig.TargetedAction targeted)return Map.of("kind","targeted","target",logicTarget(targeted.target),"action",sceneAction(targeted.action,depth+1,cm,pawnActivator));
        if(action instanceof ActionConfig.WarpActor warp&&warp.location instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&tagged.tag.isEmpty())return Map.of("kind","failure");
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.OverrideDefaultEntrance entrance)return Map.of("kind","default-entrance","target",logicTarget(entrance.target));
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction wrapper){
            if(cm==null||wrapper.fireAction==null)throw new IOException("Unresolved scene fire action");
            var fire=cm.a(com.threerings.projectx.dungeon.config.FireActionConfig.class,wrapper.fireAction);
            if(fire==null||fire.getClass()!=com.threerings.projectx.dungeon.config.FireActionConfig.class||fire.refractory!=0||fire.delay<0||fire.delay>3_600_000)throw new IOException("Unsupported scene fire timing/subtype");
            var graph=sceneAction(fire.action,depth+1,cm,pawnActivator);return fire.delay==0?graph:Map.of("kind","delay","millis",fire.delay,"variance",0,"action",graph);
        }
        if(action instanceof ActionConfig.SetVariable variable){
            if(!(variable.value instanceof com.threerings.tudey.config.ExpressionConfig.Constant constant)
                    ||!(constant.value.equalsIgnoreCase("true")||constant.value.equalsIgnoreCase("false")))throw new IOException("Unsupported native variable expression");
            return Map.of("kind","set-variable","name",variable.name,"value",Boolean.parseBoolean(constant.value),"target",logicTarget(variable.target));
        }
        if(action instanceof com.threerings.projectx.config.ProjectXActionConfig.ClearDialog clear){
            boolean only=clear.target instanceof com.threerings.tudey.config.TargetConfig.Activator;
            if(!only&&!pawnTarget(clear.target))throw new IOException("Unsupported native ClearDialog target");
            return Map.of("kind","clear-dialog","activator",only);
        }
        if(action instanceof ActionConfig.Random choice){
            if(choice.actions==null||choice.actions.length==0||choice.actions.length>256)throw new IOException("Invalid native weighted choice");
            var actions=new ArrayList<Object>();var weights=new ArrayList<Float>();
            for(var option:choice.actions){if(option==null||!Float.isFinite(option.weight)||option.weight<0)throw new IOException("Invalid native choice weight");actions.add(sceneAction(option.action,depth+1,cm,pawnActivator));weights.add(option.weight);}
            return Map.of("kind","weighted-choice","actions",actions,"weights",weights);
        }
        if(action instanceof ActionConfig.Signal signal)return sceneSignalAction(signal.target,signal.name,depth);
        if(action!=null&&action.getClass()==ActionConfig.FireEffect.class){
            var effect=(ActionConfig.FireEffect)action;
            if(effect.effect==null){importReport.add("Native null scene effect (no fire); presentation data may be stripped");return Map.of("kind","sequence","stopOnFailure",false,"actions",List.of());}
            if(cm==null)throw new IOException("Scene effect requires native preparation");
            var config=effect.effect.a(cm,com.threerings.tudey.config.EffectConfig.class);
            if(config==null||config.a(effect.effect,0,new com.threerings.tudey.data.EntityKey.Entry(1),new com.threerings.math.Vector2f(),0).getClass()!=com.threerings.tudey.data.effect.Effect.class)throw new IOException("Unsupported native scene effect subtype");
            String model=org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(effect.effect));
            if(!cosmeticEffect(config)){
                String tag;
                if(effect.location instanceof com.threerings.tudey.config.TargetConfig.Source)tag="";
                else if(effect.location instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&!tagged.tag.isBlank())tag=tagged.tag;
                else throw new IOException("Unsupported gameplay effect location");
                return Map.of("kind","gameplay-effect","config",model,"tag",tag,"action",sceneEffectAction(config.action,depth+1,cm,config.shape));
            }
            if(effect.location instanceof com.threerings.tudey.config.TargetConfig.Source)return Map.of("kind","source-effect","config",model);
            if(effect.location instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&!tagged.tag.isBlank())return Map.of("kind","tagged-effect","config",model,"tag",tagged.tag);
            throw new IOException("Unsupported scene effect location");
        }
        if(action!=null&&action.getClass()==ActionConfig.SpawnActor.class){
            var spawn=(ActionConfig.SpawnActor)action;
            if(spawn.actor==null)return Map.of("kind","sequence","stopOnFailure",false,"actions",List.of());
            if(cm==null)throw new IOException("Scene spawn requires native actor preparation");
            var prepared=new TreeMap<String,Object>(preparedSpawn(cm,spawn.actor,false));
            if(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source)return prepared;
            if(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Activator){prepared.put("activator",true);return prepared;}
            if(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&!tagged.tag.isBlank()){prepared.put("tag",tagged.tag);return prepared;}
            throw new IOException("Unsupported scene spawn location");
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ActivateMonster spawn){
            if(cm==null||spawn.spawnTable==null
                    ||spawn.warp||spawn.topLevel||spawn.rotation!=0||!spawn.relative||spawn.translation.x!=0||spawn.translation.y!=0
                    ||!(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Unsupported monster activation table or transform");
            var table=spawn.spawnTable.a(cm,com.threerings.projectx.dungeon.config.SpawnTableConfig.class);
            if(table==null)throw new IOException("Missing monster activation table");
            return preparedSpawn(cm,singleTableActor(table,spawn.spawnTable.kW()),true);
        }
        if(action!=null&&action.getClass()==com.threerings.projectx.dungeon.config.DungeonActionConfig.TableSpawn.class){
            var spawn=(com.threerings.projectx.dungeon.config.DungeonActionConfig.TableSpawn)action;
            if(cm==null||spawn.spawnTable==null||spawn.warp||spawn.topLevel||spawn.rotation!=0||!spawn.relative||spawn.translation.x!=0||spawn.translation.y!=0
                ||!(spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source||spawn.location instanceof com.threerings.tudey.config.TargetConfig.Tagged))throw new IOException("Unsupported plain table spawn transform");
            // A native table draws independently for each resolved location. Keep
            // the Choice inside TargetedAction, never one export-time random draw
            // or one runtime draw shared by all tagged locations.
            try{return Map.of("kind","targeted","target",logicTarget(spawn.location),"action",sceneTableAction(cm,spawn.spawnTable,0));}
            catch(IOException unsupported){throw new IOException("Unsupported scene table "+spawn.spawnTable.getName()+": "+unsupported.getMessage(),unsupported);}
        }

        if(action instanceof com.threerings.projectx.config.ProjectXActionConfig.StartConversation start){
            boolean party=start.target instanceof com.threerings.projectx.dungeon.config.DungeonTargetConfig.PartyMembers members&&members.target instanceof com.threerings.tudey.config.TargetConfig.Source&&members.filter==com.threerings.projectx.dungeon.config.DungeonTargetConfig.PartyMembers.Filter.ALL;
            if(cm==null||start.conversation==null||!(pawnTarget(start.target)||party||pawnActivator&&start.target instanceof com.threerings.tudey.config.TargetConfig.Activator)||rules==null)throw new IOException("Unsupported scripted conversation source/target");
            for(String id:rules.getProperty("dialogues","").split(","))if(start.conversation.getName().equals(rules.getProperty("dialogue."+id+".reference"))
                    &&rules.getProperty("dialogue."+id+".actor","").startsWith("@conversation/")){
                if(!start.conversation.kW().isEmpty())throw new IOException("Parameterized scripted conversation is not implemented");
                var previous=scriptedConversations.putIfAbsent(id,start);
                if(previous!=null&&(!Objects.equals(previous.name,start.name)||!Objects.equals(previous.model,start.model)||!Objects.equals(previous.music,start.music)||!Objects.equals(previous.sourceCloseAnimation,start.sourceCloseAnimation)))throw new IOException("Conflicting scripted conversation presentation");
                return Map.of("kind","conversation","id",rules.getProperty("dialogue."+id+".actor"),"activator",start.target instanceof com.threerings.tudey.config.TargetConfig.Activator);
            }
            throw new IOException("Unverified scripted conversation: "+start.conversation.getName());
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ProgressEvent event){
            var fields=Map.<String,Object>of("kind","progress-event","level",event.level,"progress",event.progress);
            org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(fields));return fields;
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.NextFloor next){
            if(!(next.target instanceof com.threerings.tudey.config.TargetConfig.Activator))throw new IOException("Unsupported NextFloor target");
            return Map.of("kind","next-floor");
        }
        if(action instanceof com.threerings.projectx.config.ProjectXActionConfig.SetMemory memory){
            if(!pawnActivator||!(memory.target instanceof com.threerings.tudey.config.TargetConfig.Activator)
                    ||memory.persistence!=null&&memory.persistence!=com.threerings.projectx.config.Persistence.KNIGHT)throw new IOException("Unsupported scene memory scope/target");
            var fields=Map.<String,Object>of("kind","knight-memory","name",memory.name,"remember",memory.persistence!=null);
            org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(fields));return fields;
        }
        if(action instanceof com.threerings.projectx.config.ProjectXActionConfig.SetObjective objective){
            if(!pawnActivator||!(objective.target instanceof com.threerings.tudey.config.TargetConfig.Activator)
                ||objective.persistence!=null&&objective.persistence!=com.threerings.projectx.config.Persistence.KNIGHT)throw new IOException("Unsupported objective scope/target");
            String text=null;
            if(objective.objective!=null){
                if(objective.persistence==null||!(objective.objective instanceof com.threerings.projectx.config.ObjectiveConfig.Simple simple)||simple.userInterface!=null
                    ||simple.childX!=.5f||simple.childY!=.5f||simple.parentX!=.5f||simple.parentY!=.5f)throw new IOException("Unsupported objective configuration");
                text=simple.text;
            }
            var fields=new LinkedHashMap<String,Object>();fields.put("kind","knight-objective");fields.put("name",objective.name);fields.put("text",text);
            org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(fields));return fields;
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ApplyStatus status){
            if(cm==null||!pawnTarget(status.target))throw new IOException("Unsupported scripted status target");
            org.tortodrone.world.FireCatalog.Action prepared;
            try{prepared=FireCatalogExport.readAction(cm,status);}catch(Exception invalid){throw new IOException("Unresolved scripted status",invalid);}
            if(prepared.kind()!=org.tortodrone.world.FireCatalog.Kind.APPLY_STATUS)throw new IOException("Unresolved scripted status");
            var fields=Map.<String,Object>of("kind","pawn-status","config",org.tortodrone.world.FireCatalog.modelJson(prepared.config()));
            org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(fields));return fields;
        }
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ClearStatus status){
            if(!pawnTarget(status.target)||status.mask<0)throw new IOException("Unsupported scripted status clear target/mask");
            return Map.of("kind","pawn-clear","mask",status.mask);
        }
        if(action instanceof ActionConfig.DestroyActor destruction){
            if(destruction.endScene)throw new IOException("Scene-ending destruction is not implemented");
            if(destruction.target instanceof com.threerings.tudey.config.TargetConfig.Source)return Map.of("kind","destroy-source");
            if(destruction.target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged)return Map.of("kind","destroy","tag",tagged.tag);
            if(destruction.target instanceof com.threerings.tudey.config.TargetConfig.Compound){var tags=new ArrayList<String>();destructionTags(destruction.target,tags,0);return Map.of("kind","destroy-many","tags",tags);}
            throw new IOException("Unsupported actor destruction target");
        }
        if(action instanceof ActionConfig.Delayed delay){
            if(delay.variance<0||delay.variance>3_600_000||delay.delay<0||delay.delay>3_600_000)throw new IOException("Unsupported scene action delay/variance");
            return Map.of("kind","delay","millis",delay.delay,"variance",delay.variance,"action",sceneAction(delay.action,depth+1,cm,pawnActivator));
        }
        if(action instanceof ActionConfig.Compound compound){
            if(compound.actions==null||compound.actions.length>256)throw new IOException("Invalid scene compound");
            var children=new ArrayList<Object>();for(var child:compound.actions)children.add(sceneAction(child,depth+1,cm,pawnActivator));
            return Map.of("kind","sequence","stopOnFailure",compound.stopOnFailure,"actions",children);
        }
        if(pawnActivator&&action instanceof ActionConfig.Conditional conditional&&pawnCondition(conditional.condition,0))return sceneAction(conditional.action,depth+1,cm,true);
        if(action instanceof ActionConfig.Conditional conditional&&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Limit limit){
            if(limit.limit<0||limit.limit>1_000_000)throw new IOException("Unsupported condition limit");
            return Map.of("kind","limited","limit",limit.limit,"action",sceneAction(conditional.action,depth+1,cm,pawnActivator),"otherwise",
                conditional.elseAction==null?Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()):sceneAction(conditional.elseAction,depth+1,cm,pawnActivator));
        }
        if(action instanceof ActionConfig.Conditional conditional&&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Evaluate evaluate
                &&evaluate.expression instanceof com.threerings.tudey.config.ExpressionConfig.Reference reference){
            return Map.of("kind","variable-condition","name",reference.name,"target",logicTarget(reference.target),"action",sceneAction(conditional.action,depth+1,cm,pawnActivator),"otherwise",
                conditional.elseAction==null?Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()):sceneAction(conditional.elseAction,depth+1,cm,pawnActivator));
        }
        throw new IOException("Unsupported scene action "+(action==null?"null":action.getClass().getName()));
    }

    private static boolean pawnTarget(com.threerings.tudey.config.TargetConfig target){
        return target instanceof com.threerings.tudey.config.TargetConfig.InstanceOf instance
            &&instance.logicClass.equals("com.threerings.tudey.server.logic.PawnLogic");
    }
    private static Map<String,String> logicTarget(com.threerings.tudey.config.TargetConfig target)throws IOException {
        if(target instanceof com.threerings.tudey.config.TargetConfig.Source)return Map.of("mode","source","tag","");
        if(target instanceof com.threerings.tudey.config.TargetConfig.Activator)return Map.of("mode","activator","tag","");
        if(target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&!tagged.tag.isBlank())return Map.of("mode","tagged","tag",tagged.tag);
        throw new IOException("Unsupported native variable target");
    }
    private static void destructionTags(com.threerings.tudey.config.TargetConfig target,List<String> tags,int depth)throws IOException {
        if(depth>16||tags.size()>256)throw new IOException("Scene destruction target graph exceeds bounds");
        if(target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged&&!tagged.tag.isBlank()){if(tags.size()>=256)throw new IOException("Too many scene destruction targets");tags.add(tagged.tag);return;}
        if(target instanceof com.threerings.tudey.config.TargetConfig.Compound compound&&compound.targets!=null&&compound.targets.length<=256){for(var child:compound.targets)destructionTags(child,tags,depth+1);return;}
        throw new IOException("Unsupported scene destruction target sibling");
    }
    static boolean cosmeticEffect(com.threerings.tudey.config.EffectConfig config){
        var action=config.action;
        if(action!=null&&!(action.getClass()==ActionConfig.SpawnActor.class&&((ActionConfig.SpawnActor)action).actor==null)
                &&!(action instanceof ActionConfig.Compound compound&&compound.actions!=null&&compound.actions.length==0))return false;
        return config.a(new ConfigReference<>("Basic"),0,new com.threerings.tudey.data.EntityKey.Entry(1),new com.threerings.math.Vector2f(),0).getClass()==com.threerings.tudey.data.effect.Effect.class;
    }
    /** Retain every gameplay sibling and original delay. Default regions use this
     * effect logic's authored shape, not a player-sized replacement. */
    private static Map<String,Object> sceneEffectAction(ActionConfig action,int depth,ConfigManager cm,com.threerings.tudey.shape.config.ShapeConfig shape)throws IOException {
        if(depth>16)throw new IOException("Effect action graph is too deep");
        if(action instanceof ActionConfig.Compound compound){
            if(compound.actions==null||compound.actions.length>256)throw new IOException("Invalid effect compound");
            var children=new ArrayList<Object>();for(var child:compound.actions)children.add(sceneEffectAction(child,depth+1,cm,shape));
            return Map.of("kind","sequence","stopOnFailure",compound.stopOnFailure,"actions",children);
        }
        if(action instanceof ActionConfig.Delayed delay)return Map.of("kind","delay","millis",delay.delay,"variance",delay.variance,"action",sceneEffectAction(delay.action,depth+1,cm,shape));
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ImpartImpulse impulse){
            if(!(impulse.location instanceof com.threerings.tudey.config.TargetConfig.Source)||!(impulse.region instanceof com.threerings.tudey.config.RegionConfig.Default)
                    ||!(shape instanceof com.threerings.tudey.shape.config.ShapeConfig.Point))throw new IOException("Unsupported effect impulse region/location");
            try{
                var prepared=FireCatalogExport.readAction(cm,impulse);var data=new TreeMap<>(prepared.config().arguments());data.put("shape",FireCatalogExport.argumentValue(shape));
                var model=new org.tortodrone.character.StarterCatalog.Model("ImpartImpulse",data);new org.tortodrone.world.SceneActions.Impulse(model);
                return Map.of("kind","source-impulse","config",org.tortodrone.world.FireCatalog.modelJson(model));
            }catch(Exception invalid){throw new IOException("Unsupported native effect impulse",invalid);}
        }
        if(action instanceof ActionConfig.None||action!=null&&action.getClass()==ActionConfig.SpawnActor.class
                ||action!=null&&action.getClass()==com.threerings.projectx.dungeon.config.DungeonActionConfig.TableSpawn.class)return sceneAction(action,depth,cm,false);
        throw new IOException("Unsupported complete gameplay effect action: "+(action==null?"null":action.getClass().getName()));
    }
    private static Map<String,Object> preparedSpawn(ConfigManager cm,ConfigReference<ActorConfig> actor,boolean activate)throws IOException {
        var fields=new TreeMap<String,String>();fields.put("config",actor.getName());
        try{
            if(rules==null){rules=new Properties();try(var input=Files.newInputStream(Path.of("content/_internal/provenance/actor-rules.properties"))){rules.load(input);}}
            writeActor(cm,fields,"",actor);
            var resolved=resolvedActor(cm,actor);var config=resolved.a(cm,ActorConfig.class);var tags=(com.threerings.tudey.config.TagConfig)field(config.ai(cm),"tags");
            if(fields.get("kind").equals("Block")){
                var original=config.ai(cm);
                if(original.getClass()!=com.threerings.projectx.dungeon.config.DungeonActorConfig.Block.class||original.handlers!=null&&original.handlers.length!=0)throw new IOException("Unimplemented block handlers");
                var damage=((com.threerings.projectx.dungeon.config.DungeonActorConfig.Block)original).damageAction;
                if(damage!=null){
                    if(!(damage instanceof ActionConfig.DestroyActor destroy)||destroy.endScene||!(destroy.target instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Unimplemented block damage action");
                    fields.put("damage-action","destroy");
                }
            }
            if(tags!=null)fields.put("tags",String.join(",",sceneTags(config,resolved.kW(),tags)));
            new org.tortodrone.world.SceneActions.Spawn(fields,activate);
        }catch(Exception invalid){throw new IOException("Unsupported scene spawn actor "+actor.getName()+": "+invalid.getMessage(),invalid);}
        return Map.of("kind","spawn","fields",fields,"activate",activate);
    }

    /** Actor-only tables are deterministic; never sample random/conditional tables at export time. */
    static ConfigReference<ActorConfig> singleTableActor(com.threerings.projectx.dungeon.config.SpawnTableConfig table,Map<String,Object> arguments)throws IOException {
        if(!(table instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.Original original)
                ||original.root==null||original.root.getClass()!=com.threerings.projectx.dungeon.config.SpawnTableConfig.ActorEntry.class
                ||((com.threerings.projectx.dungeon.config.SpawnTableConfig.ActorEntry)original.root).actor==null)throw new IOException("Unsupported monster activation table root");
        var actor=((com.threerings.projectx.dungeon.config.SpawnTableConfig.ActorEntry)original.root).actor;
        var result=new ConfigReference<ActorConfig>(actor.getName());result.kW().putAll(actor.kW());
        for(var parameter:table.parameters)if(arguments.containsKey(parameter.name)){
            if(!(parameter instanceof com.threerings.config.Parameter.Direct direct)||direct.paths==null||direct.paths.length==0)throw new IOException("Unsupported actor table parameter");
            for(String path:direct.paths){
                if(path.equals("root.actor")||path.equals("implementation.root.actor")){
                    Object value=arguments.get(parameter.name);
                    if(!(value instanceof ConfigReference<?> reference)||reference.getName()==null)throw new IOException("Invalid actor table reference");
                    result=new ConfigReference<>(reference.getName());result.kW().putAll(reference.kW());
                }else{
                    var match=java.util.regex.Pattern.compile("(?:implementation\\.)?root\\.actor\\[\"([^\"]+)\"\\]").matcher(path);
                    if(!match.matches())throw new IOException("Unsupported actor table parameter path");
                    result.kW().put(match.group(1),arguments.get(parameter.name));
                }
            }
        }
        return result;
    }
    private static ConfigReference<ActorConfig> singleSceneTableActor(ConfigManager cm,com.threerings.projectx.dungeon.config.SpawnTableConfig table,Map<String,Object> arguments,int depth)throws IOException {
        if(depth>16)throw new IOException("Recursive scene table");
        if(table instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.Prefab prefab){
            if(levelContext!=null){
                Object types=levelContext.bkJ?levelContext.bkH:levelContext.bkG;
                if(types instanceof Map<?,?> map&&!map.isEmpty()||types instanceof java.util.Set<?> set&&!set.isEmpty())throw new IOException("Themed prefab selection needs runtime type handling");
            }
            if(prefab.root==null||prefab.root.spawnTable==null||prefab.root.getClass()!=com.threerings.projectx.dungeon.config.SpawnTableConfig.SpawnTypeListEntry.class)throw new IOException("Unsupported prefab root");
            var reference=prefab.root.spawnTable;var next=reference.a(cm,com.threerings.projectx.dungeon.config.SpawnTableConfig.class);
            return singleSceneTableActor(cm,next,reference.kW(),depth+1);
        }
        return singleTableActor(table,arguments);
    }

    /** Depth-zero preparation, matching the existing scene stat/scale export context.
     * Random branches remain typed runtime choices; no table is sampled by this exporter. */
    private static Map<String,Object> sceneTableAction(ConfigManager cm,ConfigReference<SpawnTableConfig> reference,int depth)throws IOException {
        if(depth>16||reference==null)throw new IOException("Recursive or missing scene table");
        var table=reference.a(cm,SpawnTableConfig.class);if(table==null)throw new IOException("Missing referenced scene table");
        if(table instanceof SpawnTableConfig.Original original&&original.root instanceof SpawnTableConfig.RandomEntry random){
            if(!reference.kW().isEmpty()||table.parameters.length!=0)throw new IOException("Unimplemented random table parameters");
            return sceneTableEntry(cm,random,depth+1);
        }
        if(reference.getName().equals("Treasure/Heat/Scaled Ember")){
            if(!(table instanceof SpawnTableConfig.Original original)||!(original.root instanceof SpawnTableConfig.DepthListEntry list)
                ||!reference.kW().isEmpty()||table.parameters.length!=0||list.offset!=0||list.bottomLimit!=.25f||list.topLimit!=.25f||list.entries.length!=6)throw new IOException("Scaled Ember depth-zero footprint changed");
            int[] depths={0,6,11,16,21,26};
            for(int i=0;i<depths.length;i++)if(list.entries[i]==null||list.entries[i].depth!=depths[i]||!(list.entries[i].entry instanceof SpawnTableConfig.TableEntry child)||child.getClass()!=SpawnTableConfig.TableEntry.class
                ||child.spawnTable==null||!child.spawnTable.kW().isEmpty()||!child.spawnTable.getName().equals("Treasure/Heat/Random Heat/Random Heat "+(i+1)))throw new IOException("Scaled Ember depth bands changed");
            // At zero the first band has weight one; every other band's lower
            // bound is strictly positive. This selection is deterministic.
            importReport.add("Prepared Scaled Ember for the scene export's depth-zero context; higher-depth selection is not implemented");
            return sceneTableEntry(cm,list.entries[0].entry,depth+1);
        }
        var prepared=new TreeMap<String,Object>(preparedSpawn(cm,singleSceneTableActor(cm,table,reference.kW(),depth+1),false));prepared.put("activator",true);return prepared;
    }
    private static Map<String,Object> sceneTableEntry(ConfigManager cm,SpawnTableConfig.Entry entry,int depth)throws IOException {
        if(depth>16||entry==null)throw new IOException("Recursive or missing scene table entry");
        if(entry.getClass()==SpawnTableConfig.TableEntry.class)return sceneTableAction(cm,((SpawnTableConfig.TableEntry)entry).spawnTable,depth+1);
        if(entry.getClass()==SpawnTableConfig.ActorEntry.class){
            var actor=((SpawnTableConfig.ActorEntry)entry).actor;if(actor==null)return Map.of("kind","sequence","actions",List.of(),"stopOnFailure",false);
            var prepared=new TreeMap<String,Object>(preparedSpawn(cm,actor,false));prepared.put("activator",true);return prepared;
        }
        if(entry.getClass()==SpawnTableConfig.RandomEntry.class){
            var random=(SpawnTableConfig.RandomEntry)entry;
            if(random.entries==null||random.entries.length==0||random.entries.length>256)throw new IOException("Invalid random table size");
            var actions=new ArrayList<Object>();var weights=new ArrayList<Float>();
            for(var child:random.entries){
                if(child==null||!Float.isFinite(child.weight)||child.weight<0||!child.dropModifierTag.isEmpty())throw new IOException("Unimplemented random table weight modifier");
                actions.add(sceneTableEntry(cm,child.entry,depth+1));weights.add(child.weight);
            }
            var result=Map.<String,Object>of("kind","weighted-choice","actions",actions,"weights",weights);org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(result));return result;
        }
        throw new IOException("Unsupported complete scene table entry: "+entry.getClass().getName());
    }

    private static ConfigReference<ActorConfig> resolvedActor(ConfigManager cm,ConfigReference<ActorConfig> reference)throws IOException {
        for(int depth=0;depth<16;depth++){
            var config=reference.a(cm,ActorConfig.class);
            if(config==null)throw new IOException("Unresolved scene actor");
            if(!(config.implementation instanceof ActorConfig.Derived derived))return reference;
            if(derived.actor==null)throw new IOException("Missing derived scene actor");
            var next=new ConfigReference<ActorConfig>(derived.actor.getName());next.kW().putAll(derived.actor.kW());
            for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct&&reference.kW().containsKey(parameter.name))for(String path:direct.paths){
                var match=java.util.regex.Pattern.compile("implementation\\.actor\\[\"([^\"]+)\"\\]").matcher(path);
                if(match.matches())next.kW().put(match.group(1),reference.kW().get(parameter.name));
            }
            reference=next;
        }
        throw new IOException("Actor derivation exceeds limit");
    }

    static int doorCounter(ConfigManager cm,ConfigReference<ActorConfig> reference)throws IOException {
        for(int depth=0;depth<16;depth++){
            var config=reference.a(cm,ActorConfig.class);if(config==null)throw new IOException("Unresolved door counter");
            if(!(config.implementation instanceof ActorConfig.Derived derived))return 0;
            if(derived.actor==null)throw new IOException("Missing derived door counter");
            if(reference.getName().equals("Door/Multi Trigger Base")){
                if(!derived.actor.getName().equals("Door/Base")||!(derived.actor.kW().get("Handlers") instanceof HandlerConfig[] handlers))throw new IOException("Door counter base changed");
                counterFootprint(handlers);
                requirePaths(config,"Triggers","implementation.actor[\"Handlers\"][0].action.actions[1].condition.expression.second_operand.value","implementation.actor[\"Handlers\"][1].action.actions[1].condition.expression.second_operand.value");
                return counterThreshold(reference.kW().get("Triggers"));
            }
            var next=new ConfigReference<ActorConfig>(derived.actor.getName());next.kW().putAll(derived.actor.kW());
            for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct&&reference.kW().containsKey(parameter.name))for(String path:direct.paths){
                var match=java.util.regex.Pattern.compile("implementation\\.actor\\[\"([^\"]+)\"\\]").matcher(path);
                if(match.matches())next.kW().put(match.group(1),reference.kW().get(parameter.name));
            }
            reference=next;
        }
        throw new IOException("Door counter derivation exceeds limit");
    }
    static boolean goldDoor(ConfigManager cm,ConfigReference<ActorConfig> reference)throws IOException {
        var resolved=resolvedActor(cm,reference);
        if(!(resolved.kW().get("Action Desc") instanceof com.threerings.projectx.config.ActionDesc.UnlockGold))return false;
        if(!resolved.getName().equals("Door/Base")||!"gold_unlock".equals(resolved.kW().get("Actionable Signal"))
            ||!(resolved.kW().get("Handlers") instanceof HandlerConfig[] handlers)||handlers.length!=2
            ||!(handlers[0] instanceof HandlerConfig.SignalStart start)||!start.name.isEmpty()||!strippedGuard(start.action)
            ||!(((ActionConfig.Conditional)start.action).action instanceof ActionConfig.Compound compound)||compound.stopOnFailure||compound.actions.length!=1
            ||!(compound.actions[0] instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.ClearMinimap)
            ||!(handlers[1] instanceof com.threerings.projectx.config.ProjectXHandlerConfig.MinimapIcon))throw new IOException("Native gold door footprint changed");
        return true;
    }
    /** Recover only explicit preserved scalar tag paths; never infer a binding from argument names. */
    static List<String> sceneTags(com.threerings.config.ParameterizedConfig config,Map<String,Object> arguments,
                                  com.threerings.tudey.config.TagConfig nativeTags)throws IOException {
        var slots=new TreeMap<Integer,String>();
        for(int i=0;i<nativeTags.tags.length;i++)slots.put(i,nativeTags.tags[i]);
        for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct){
            if(!arguments.containsKey(parameter.name))continue;
            for(String path:direct.paths){
                var match=java.util.regex.Pattern.compile("implementation\\.tags\\.tags\\[([0-9]+)\\]").matcher(path);
                if(!match.matches())continue;
                int index;
                try{index=Integer.parseInt(match.group(1));}catch(NumberFormatException invalid){throw new IOException("Invalid native tag slot",invalid);}
                if(index>255)throw new IOException("Native tag slot exceeds bound");
                if(!(arguments.get(parameter.name) instanceof String value))throw new IOException("Native tag argument is not text");
                slots.put(index,value);
            }
        }
        var result=new ArrayList<String>(slots.values());
        if(nativeTags.derived!=null)nativeTags.derived.forEach(result::add);
        for(String tag:result)if(tag==null||tag.length()>256||tag.contains(",")||tag.chars().anyMatch(Character::isISOControl))throw new IOException("Unrepresentable native scene tag");
        return result.stream().filter(tag->!tag.isBlank()).distinct().toList();
    }
    private static Map<String,Object> sceneSignalAction(com.threerings.tudey.config.TargetConfig target,String name,int depth)throws IOException {
        if(depth>16)throw new IOException("Scene signal target graph is too deep");
        if(name==null||name.isBlank()||name.length()>256||name.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid or stripped native signal name");
        if(target instanceof com.threerings.tudey.config.TargetConfig.Source)return Map.of("kind","source-signal","name",name);
        if(target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged)return Map.of("kind","signal","tag",tagged.tag,"name",name);
        if(target instanceof com.threerings.tudey.config.TargetConfig.NearestSubset nearest){
            if(!(nearest.location instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Unsupported nearest signal reference location");
            var selection=nearest.target;boolean exclude=false;
            if(selection instanceof com.threerings.tudey.config.TargetConfig.Excluding excluding){
                if(!(excluding.excluding instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Unsupported nearest signal exclusion");
                exclude=true;selection=excluding.target;
            }
            if(!(selection instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged))throw new IOException("Unsupported nearest signal candidate selection");
            new org.tortodrone.world.SceneActions.NearestSignal(tagged.tag,name,nearest.size,exclude);
            return Map.of("kind","nearest-signal","tag",tagged.tag,"name",name,"count",nearest.size,"excludeSource",exclude);
        }
        if(target instanceof com.threerings.tudey.config.TargetConfig.Compound compound){
            if(compound.targets==null||compound.targets.length>256)throw new IOException("Invalid scene signal targets");
            var actions=new ArrayList<Object>();
            for(var child:compound.targets)actions.add(sceneSignalAction(child,name,depth+1));
            return Map.of("kind","sequence","stopOnFailure",false,"actions",actions);
        }
        throw new IOException("Unsupported scene signal target "+(target==null?"null":target.getClass().getName()));
    }

    private static ConfigReference<ActorConfig> bindArguments(ConfigReference<ActorConfig> actor,Map<String,Object> source){
        var result=new ConfigReference<ActorConfig>(actor.getName());result.kW().putAll(actor.kW());
        // Stripped startup parameter paths leave their authored placeholder defaults
        // intact. Bind preserved scene arguments without changing native asset data.
        for(var argument:actor.kW().entrySet())if(argument.getValue() instanceof String value&&value.startsWith("(")&&value.endsWith(")")){
            String label=value.substring(1,value.length()-1);Object bound=source.get(label);
            if(bound==null)bound=source.get(argument.getKey());if(bound!=null)result.kW().put(argument.getKey(),bound);
        }
        return result;
    }

    private static void writeActor(ConfigManager cm, Map<String, String> out, String prefix,
                                   ConfigReference<ActorConfig> reference) throws Exception {
        ActorConfig config = reference.a(cm, ActorConfig.class);
        if (config == null) throw new IOException("Unresolved actor " + reference.getName());
        Object original = config.ai(cm);
        if (original == null) throw new IOException("Actor has no original config: " + reference.getName());
        if(original instanceof com.threerings.projectx.dungeon.arena.config.ArenaActorConfig.TerritoryProp){
            if(((ActorConfig.Original)original).handlers.length!=0)throw new IOException("Territory lighting handlers require implementation");
            var nativeLight=(com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp)((ActorConfig.Original)original).a(reference,1,0,new com.threerings.math.Vector2f(),0);
            out.put(prefix+"territory-color",Byte.toString(nativeLight.colorId));
        }
        put(out, prefix + "kind", original.getClass().getSimpleName());
        put(out,prefix+"native-type",((ActorConfig.Original)original).a(reference,1,0,new com.threerings.math.Vector2f(),0).getClass().getName());
        addOptionalString(out, prefix + "is-static", field(original, "isStatic"));
        writeGeometry(out, prefix, field(original, "shape"));
        addOptionalNumber(out, prefix + "speed", field(original, "speed"));
        addOptionalNumber(out, prefix + "collision-flags", field(original, "collisionFlags"));
        addOptionalNumber(out, prefix + "collision-mask", field(original, "collisionMask"));
        for(String name:List.of("damageMask","blockDamage","vacuumExpansion","vacuumSpeed","targetedSpeed","lifespan"))addOptional(out,prefix+kebab(name),field(original,name));
        Object sprite=field(original,"sprite");Object description=field(sprite,"description");
        if(description instanceof ConfigReference<?> r)put(out,prefix+"description",r.getName());
        if(original instanceof com.threerings.projectx.config.ProjectXActorConfig.Stranger){
            var knight=(com.threerings.projectx.config.ProjectXActorConfig.Knight)cm.d(ActorConfig.class,"Character/PC/Default").ai(cm);
            var bounds=knight.interactionShape.acS().aex();
            putFloat(out,prefix+"interaction-radius",Math.min(bounds.tu().x-bounds.tt().x,bounds.tu().y-bounds.tt().y)/2);
            putFloat(out,prefix+"interaction-offset-x",(bounds.tu().x+bounds.tt().x)/2);
            putFloat(out,prefix+"interaction-offset-y",(bounds.tu().y+bounds.tt().y)/2);
        }
        if(field(sprite,"tooltipItem") instanceof ConfigReference<?> item){
            put(out,prefix+"pickup-item",item.getName());
            @SuppressWarnings("unchecked") var itemRef=(ConfigReference<com.threerings.projectx.item.config.ItemConfig>)item;
            var nativeItem=itemRef.a(cm,com.threerings.projectx.item.config.ItemConfig.class).w(cm);
            put(out,prefix+"pickup-transient",Boolean.toString(nativeItem.Qp()));
        }
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Pickup&&reference.kW().get("Dungeon Item") instanceof ConfigReference<?> item){
            out.put(prefix+"pickup-item",item.getName());
            @SuppressWarnings("unchecked") var nativeItem=((ConfigReference<com.threerings.projectx.item.config.ItemConfig>)item).a(cm,com.threerings.projectx.item.config.ItemConfig.class).w(cm);
            out.put(prefix+"pickup-transient",Boolean.toString(nativeItem.Qp()));
            int slot=nativeItem.Vc();
            if(slot<0||!nativeItem.h(slot,false))throw new IOException("Unsupported prepared dungeon equipment slot");
            out.put(prefix+"pickup-equip-slot",Integer.toString(slot));
        }
        writeTags(out, prefix, field(original, "tags"));
        if(field(original,"tags") instanceof com.threerings.tudey.config.TagConfig tags){
            var resolved=resolvedActor(cm,reference);var resolvedConfig=resolved.a(cm,ActorConfig.class);
            out.put(prefix+"tags",String.join(",",sceneTags(resolvedConfig,resolved.kW(),tags)));
        }
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster monster){
            String shutdownMode=rules.getProperty("actor."+reference.getName()+".script.shutdown-mode","scene");
            if(!java.util.Set.of("scene","fire").contains(shutdownMode))throw new IOException("Unsupported script shutdown mode");
            if(scriptedActor(reference.getName())&&shutdownMode.equals("scene")){
                var shutdown=new ArrayList<Object>();sceneShutdown(cm,monster.handlers,shutdown,scriptShutdownArguments(config,reference),0);
                if(!shutdown.isEmpty())put(out,prefix+"scene-shutdown-action",org.tortodrone.resources.Json.stringify(Map.of("kind","sequence","actions",shutdown,"stopOnFailure",false)));
            }else{
                var death=new ArrayList<org.tortodrone.world.FireCatalog.Action>();deathActions(cm,monster.handlers,death,0);
                if(!death.isEmpty()){
                    var action=new org.tortodrone.world.FireCatalog.Action(org.tortodrone.world.FireCatalog.Kind.COMPOUND,null,null,"Source","","",0,0,0,0,true,0,0,0,0,false,death,"");
                    put(out,prefix+"death-action",org.tortodrone.world.FireCatalog.actionJson(action));
                }
            }
        }
        if(reference.kW().get("Tag") instanceof String tag&&!tag.isBlank()&&!tag.startsWith("("))out.put(prefix+"tags",tag);
        writeArguments(out, prefix + "config.argument.", reference);

        if (isKind(original, "Monster")) writeMonster(cm, out, prefix, original);
        if(scriptedActor(reference.getName()))writeSceneScript(cm,config,reference,original,out,prefix);
        else writeMovementScript(config,reference,original,out,prefix);
        if (isKind(original, "Trap")) writeTrap(out, prefix, original);
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Block){
            for(String field:List.of("fuse","color","hits"))addOptionalNumber(out,prefix+"block-"+field,field(original,field));
            if(field(original,"detonationAction") instanceof ActionConfig action)out.put(prefix+"detonation-action",org.tortodrone.world.FireCatalog.actionJson(FireCatalogExport.readAction(cm,action)));
        }
        if(original instanceof com.threerings.projectx.config.ProjectXActorConfig.Lift lift)writeLift(cm,out,prefix,reference,lift,false);
        if (isKind(original, "Switch") || isKind(original, "DumbSwitch")) {
            addOptionalNumber(out, prefix + "state-time", field(original, "stateTime"));
            addOptionalNumber(out, prefix + "delay", field(original, "delay"));
        }
        if (isKind(original, "Door")) {
            int increments=doorCounter(cm,reference);if(increments>0)out.put(prefix+"door-increments",Integer.toString(increments));
            if(goldDoor(cm,reference)){
                out.put(prefix+"door-lock","gold");
                importReport.add("Gold door "+reference.getName()+": physical key/drop handling restored; native ClearMinimap icon removal is not implemented");
            }
            addOptionalNumber(out,prefix+"door-speed",field(original,"doorSpeed"));
            Object startOpen = field(original, "startOpen");
            if (startOpen == null) startOpen = field(original, "open");
            addOptionalString(out, prefix + "start-open", startOpen);
        }
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Elevator elevator){
            if(elevator.splitParty||elevator.pushGate==null||!elevator.pushGate.isEmpty()||elevator.popGate||elevator.newFloor!=0||elevator.logicalFloor!=(elevator.dissolveParty?26:29)||elevator.returnFloorOffset!=1)
                importReport.add("Unsupported elevator routing variant retained idle: "+reference.getName());
            else{
                out.put(prefix+"elevator-mode","standard");out.put(prefix+"elevator-advance",elevator.dissolveParty?"-1":"1");out.put(prefix+"elevator-completes",Boolean.toString(!elevator.noComplete));
                var fields=new TreeMap<String,String>();out.forEach((key,value)->{if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),value);});org.tortodrone.world.SceneElevator.read(new org.tortodrone.world.SceneContent.Spec(fields));
            }
        }
        if (field(original, "actor") instanceof ConfigReference<?> && field(original, "delay") != null
            && field(original, "warning") != null) writeGenerator(cm, out, prefix, original);
        // Client exports strip server actions. Only explicitly sourced config bindings
        // in the checked-in rule data may restore those missing fields.
        String rulePrefix="actor."+reference.getName()+".";
        if(reference.getName().equals("Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (No Loot)")&&rules.containsKey(rulePrefix+"script.program")){
            if(!(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster sentry)||sentry.attacks.length!=1||sentry.attacks[0]==null||sentry.attacks[0].ref==null
                ||!sentry.attacks[0].ref.getName().equals("Character/NPC/Monster/Construct/Gun Puppy")||Float.parseFloat(out.getOrDefault(prefix+"speed","NaN"))!=0||Float.parseFloat(out.getOrDefault(prefix+"targeted-speed","NaN"))!=0
                ||!out.getOrDefault(prefix+"attack.0.clear","").equals("1852")||!out.getOrDefault(prefix+"attack.0.land","").equals("769"))throw new IOException("No-loot sentry native stationary/attack footprint changed: speed="+out.get(prefix+"speed")+", targeted-speed="+out.get(prefix+"targeted-speed")+", clear="+out.get(prefix+"attack.0.clear")+", land="+out.get(prefix+"attack.0.land")+", heads="+(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster m?Arrays.stream(m.attacks).map(a->a==null||a.ref==null?"none":a.ref.getName()).toList():List.of()));
        }
        for(String key:rules.stringPropertyNames())if(key.startsWith(rulePrefix))out.put(prefix+key.substring(rulePrefix.length()),rules.getProperty(key));
        String appended=out.get(prefix+"append-tags");
        if(appended!=null)out.put(prefix+"tags",out.getOrDefault(prefix+"tags","")+(out.getOrDefault(prefix+"tags","").isBlank()?"":",")+appended);
        writeMechanism(cm,out,prefix,reference,original);
    }

    /** Recover nested script bindings only from the preserved native parameter paths. */
    private static void writeSceneScript(ConfigManager cm,ActorConfig config,ConfigReference<ActorConfig> reference,Object original,Map<String,String> out,String prefix)throws IOException {
        if(!(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster)||!(config.implementation instanceof ActorConfig.Derived derived)
                ||!(derived.actor.kW().get("Behavior Script") instanceof com.threerings.tudey.config.ScriptConfig[] nativeSteps))throw new IOException("Native script footprint changed");
        String rp="actor."+reference.getName()+".script.";var steps=new ArrayList<Object>();
        int expected=Integer.parseInt(rules.getProperty(rp+"steps","-1"));if(nativeSteps.length!=expected)throw new IOException("Native script step count changed");
        for(int i=0;i<nativeSteps.length;i++){
            var step=nativeSteps[i];Map<String,Object> value;
            String base=rp+"step."+i+".";
            if(step instanceof com.threerings.tudey.config.ScriptConfig.Wait wait)value=Map.of("kind","wait","millis",wait.wait);
            else if(step instanceof com.threerings.tudey.config.ScriptConfig.Move move){
                String goal=rules.getProperty(base+"goal","");
                if(!(move.target instanceof com.threerings.tudey.config.TargetConfig.Source)||goal.isBlank())throw new IOException("Unverified script Move target at "+i);
                value=Map.of("kind","move","tag",goal,"maxPath",move.maxPathLength);
            }else if(step instanceof com.threerings.tudey.config.ScriptConfig.Goto jump)value=Map.of("kind","goto","step",jump.step);
            else if(step instanceof com.threerings.tudey.config.ScriptConfig.Rotate rotate)value=Map.of("kind","rotate","direction",rotate.direction,"variance",rotate.rotationVariance,"relative",rotate.relative);
            else if(step.getClass()==com.threerings.tudey.config.ScriptConfig.Condition.class){
                var condition=(com.threerings.tudey.config.ScriptConfig.Condition)step;
                if(!(condition.condition instanceof com.threerings.tudey.config.ConditionConfig.Always)||i!=0||!rules.getProperty(base+"condition","").equals("activate"))throw new IOException("Unverified script Condition at "+i);
                if(Arrays.stream(((ActorConfig.Original)original).handlers).noneMatch(h->h instanceof HandlerConfig.SignalStart signal&&signal.name.isEmpty()&&signal.action instanceof ActionConfig.SpawnActor spawn&&spawn.actor==null&&spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Native activation handler footprint changed");
                value=Map.of("kind","condition","name","activate");out.put(prefix+"script.activate-signal","activate");
            }else if(step.getClass()==com.threerings.projectx.dungeon.config.DungeonScriptConfig.Attack.class)value=Map.of("kind","attack","index",((com.threerings.projectx.dungeon.config.DungeonScriptConfig.Attack)step).attack);
            else if(step instanceof com.threerings.tudey.config.ScriptConfig.Action action){
                if(!(action.action instanceof ActionConfig.SetVariable variable)||!variable.name.isEmpty()||!(variable.target instanceof com.threerings.tudey.config.TargetConfig.Source)||!(variable.value instanceof com.threerings.tudey.config.ExpressionConfig.Constant constant)||!constant.value.isEmpty())throw new IOException("Native stripped script Action footprint changed");
                String restored=rules.getProperty(base+"action");if(restored==null)throw new IOException("Unverified stripped script Action at "+i);
                org.tortodrone.world.SceneActions.parse(restored);value=Map.of("kind","action","action",org.tortodrone.resources.Json.parse(restored));
            }else throw new IOException("Unsupported complete native script step "+step.getClass().getName());
            steps.add(value);
        }
        out.put(prefix+"script.program",org.tortodrone.resources.Json.stringify(steps));
        var fields=new TreeMap<String,String>();out.forEach((key,value)->{if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),value);});org.tortodrone.world.SceneScript.read(new org.tortodrone.world.SceneContent.Spec(fields));
    }
    static Map<String,Object> scriptShutdownArguments(ActorConfig config,ConfigReference<ActorConfig> reference)throws IOException {
        var bindings=new LinkedHashMap<String,Object>();String start="implementation.actor[\"Shutdown Action\"].actor[\"";
        for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct&&reference.kW().containsKey(parameter.name))for(String path:direct.paths){
            if(!path.startsWith(start)||!path.endsWith("\"]"))continue;
            if(direct.paths.length!=1)throw new IOException("Unsupported shutdown parameter siblings");
            String argument=path.substring(start.length(),path.length()-2);Object value=reference.kW().get(parameter.name);
            if(argument.isBlank()||argument.length()>256||value==null||bindings.putIfAbsent(argument,value)!=null)throw new IOException("Invalid or ambiguous shutdown argument binding");
        }return Map.copyOf(bindings);
    }
    private static void sceneShutdown(ConfigManager cm,HandlerConfig[] handlers,List<Object> actions,Map<String,Object> bindings,int depth)throws IOException {
        if(depth>16)throw new IOException("Scene shutdown handler references exceed limit");
        for(var handler:handlers){
            if(handler instanceof HandlerConfig.Shutdown shutdown){
                var action=shutdown.action;
                if(!bindings.isEmpty()){
                    if(action==null||action.getClass()!=ActionConfig.SpawnActor.class||((ActionConfig.SpawnActor)action).actor==null)throw new IOException("Bound native shutdown SpawnActor footprint changed");
                    var spawn=(ActionConfig.SpawnActor)action;var restored=(ActionConfig.SpawnActor)spawn.clone();var actor=new ConfigReference<ActorConfig>(spawn.actor.getName());actor.kW().putAll(spawn.actor.kW());actor.kW().putAll(bindings);restored.actor=actor;action=restored;
                }
                actions.add(sceneAction(action,0,cm));
            }else if(handler instanceof HandlerConfig.Reference reference&&reference.handler!=null){var config=cm.a(com.threerings.tudey.config.ParameterizedHandlerConfig.class,reference.handler);if(config==null)throw new IOException("Missing scene shutdown reference");sceneShutdown(cm,config.handlers,actions,bindings,depth+1);}
        }
    }
    private static void writeMovementScript(ActorConfig config,ConfigReference<ActorConfig> reference,Object original,Map<String,String> out,String prefix)throws IOException {
        if(!(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster monster)
                ||!(config.implementation instanceof ActorConfig.Derived derived)
                ||!(derived.actor.kW().get("Behavior Script") instanceof com.threerings.tudey.config.ScriptConfig[] steps)
                ||!scriptParameter(config,"Goal","implementation.actor[\"Behavior Script\"][2].target.tag"))return;
        if(steps.length!=4||!(steps[0] instanceof com.threerings.tudey.config.ScriptConfig.Condition condition)||!(condition.condition instanceof com.threerings.tudey.config.ConditionConfig.Always)
                ||!(steps[1] instanceof com.threerings.tudey.config.ScriptConfig.Wait wait)||!(steps[2] instanceof com.threerings.tudey.config.ScriptConfig.Move move)
                ||!(move.target instanceof com.threerings.tudey.config.TargetConfig.Source||move.target instanceof com.threerings.tudey.config.TargetConfig.Tagged)
                ||!(steps[3] instanceof com.threerings.tudey.config.ScriptConfig.Rotate rotate)||rotate.relative||rotate.rotationVariance!=0
                ||!scriptParameter(config,"Wait","implementation.actor[\"Behavior Script\"][1].wait")
                ||config.getParameter("End Orientation")!=null&&!scriptParameter(config,"End Orientation","implementation.actor[\"Behavior Script\"][3].direction"))throw new IOException("Unsupported complete native movement script: "+reference.getName());
        Object goal=reference.kW().getOrDefault("Goal",move.target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged?tagged.tag:"");
        Object delay=reference.kW().getOrDefault("Wait",wait.wait),direction=config.getParameter("End Orientation")==null?rotate.direction:reference.kW().getOrDefault("End Orientation",rotate.direction);
        if(!(goal instanceof String tag)||!(delay instanceof Number millis)||millis.doubleValue()!=millis.intValue()||!(direction instanceof Number angle))throw new IOException("Invalid native movement script parameters");
        var script=new org.tortodrone.world.SceneMovementScript(millis.intValue(),tag,move.maxPathLength,angle.floatValue(),monster.turnRate);
        out.put(prefix+"script.kind","wait-move-rotate");out.put(prefix+"script.wait",Integer.toString(script.waitMillis()));out.put(prefix+"script.goal",script.goal());out.put(prefix+"script.max-path",Float.toString(script.maxPath()));out.put(prefix+"script.rotation",Float.toString(script.rotation()));out.put(prefix+"script.turn-rate",Float.toString(script.turnRate()));
    }
    private static boolean scriptParameter(ActorConfig config,String name,String path){
        var parameter=config.getParameter(name);return parameter instanceof com.threerings.config.Parameter.Direct direct&&direct.paths.length==1&&direct.paths[0].equals(path);
    }
    private static void deathActions(ConfigManager cm,HandlerConfig[] handlers,
            List<org.tortodrone.world.FireCatalog.Action> actions,int depth)throws Exception {
        if(handlers==null)return;
        if(depth>16)throw new IOException("Cyclic monster death handler references");
        for(var handler:handlers){
            if(handler instanceof HandlerConfig.Shutdown shutdown)actions.add(FireCatalogExport.readSceneAction(cm,shutdown.action));
            else if(handler instanceof HandlerConfig.Reference reference&&reference.handler!=null){
                var config=cm.a(com.threerings.tudey.config.ParameterizedHandlerConfig.class,reference.handler);
                if(config==null)throw new IOException("Unresolved monster death handler: "+reference.handler.getName());
                deathActions(cm,config.handlers,actions,depth+1);
            }
        }
    }

    static void writeLift(ConfigManager cm,Map<String,String> out,String prefix,ConfigReference<ActorConfig> reference,
            com.threerings.projectx.config.ProjectXActorConfig.Lift lift,boolean geometry)throws Exception {
        if(geometry){
            out.put(prefix+"config",reference.getName());out.put(prefix+"config-json",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(reference)));
            out.put(prefix+"native-type",lift.a(reference,1,0,new com.threerings.math.Vector2f(),0).getClass().getName());
            out.put(prefix+"kind",lift.getClass().getSimpleName());writeGeometry(out,prefix,lift.shape);
            out.put(prefix+"speed",Float.toString(lift.speed));
        }
        for(String name:List.of("canThrow","throwTime","fallSpeed","thrownCollisionFlags","thrownCollisionMask","fuse","explodeOnImpact","snapTag","actionableSignal"))addOptional(out,prefix+kebab(name),field(lift,name));
        if(lift.description!=null)out.put(prefix+"description",lift.description.getName());
        for(var handler:lift.handlers)if(handler instanceof HandlerConfig.Signal signal&&signal.action!=null){
            out.put(prefix+"land-action",org.tortodrone.world.FireCatalog.actionJson(FireCatalogExport.readAction(cm,signal.action)));break;
        }
    }

    private static void writeMonster(ConfigManager cm, Map<String, String> out, String prefix,
                                     Object original) throws Exception {
        SceneMonsterExport.export(cm,out,prefix,(com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster)original,
            scriptedActor(out.get(prefix+"config"))?start->sceneAction(start,0,cm):null);
        for (String name : List.of("showHealth", "minReactPercent", "reactTime", "deathTime", "activateTime", "turnRate")) {
            addOptional(out, prefix + kebab(name), field(original, name));
        }
        Object healthScale = field(original, "healthScale");
        Object health = field(original, "health");
        if (healthScale != null) {
            String scaleName = referenceName(healthScale);
            put(out, prefix + "health-scale", scaleName);
        }
        if (health instanceof Number number && number.doubleValue() > 0d) {
            putNumber(out, prefix + "health", number);
        } else if (healthScale != null) {
            Object scale = resolveReference(cm, healthScale, DepthScaleConfig.class);
            if (scale == null) throw new IOException("Unresolved health scale " + referenceName(healthScale));
            putFloat(out, prefix + "health", evaluateScale(cm, scale));
        }
        Object defense = field(original, "defense");
        if (defense != null) writeDefense(cm, out, prefix, defense);
    }

    private static void writeCycles(Map<String,String> out){
        for(var cycle:com.threerings.projectx.dungeon.config.DungeonActorConfig.Trap.Cycle.values()){
            String p="cycle."+cycle.name()+".";
            out.put(p+"down",Float.toString(cycle.downTime));out.put(p+"warn",Float.toString(cycle.warnTime));out.put(p+"up",Float.toString(cycle.upTime));
            out.put(p+"offset",Float.toString(cycle.offset));out.put(p+"inverse",cycle.invert().name());
        }
    }

    static org.tortodrone.world.SceneActions.Signal oneTimeLever(ConfigManager cm,ConfigReference<ActorConfig> reference,Object original)throws IOException {
        // The installed export stripped the entire Damage action and state 1.
        // Archive actor.xml preserves Limit(1) -> Signal(next) and state 1's
        // Tagged signal; check_multi_trigger.py measures both original levers.
        if(!reference.getName().equals("Dynamic/Switch/Lever/One-Time")||!(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Switch lever)
            ||lever.handlers.length!=1||lever.stateActions.length!=1||!(lever.handlers[0] instanceof com.threerings.projectx.dungeon.config.DungeonHandlerConfig.Damage hit)||hit.mask!=2
            ||hit.action.getClass()!=ActionConfig.SpawnActor.class||!noAction(hit.action)||!(((ActionConfig.SpawnActor)hit.action).location instanceof com.threerings.tudey.config.TargetConfig.Source)
            ||lever.stateActions[0].getClass()!=ActionConfig.SpawnActor.class||!noAction(lever.stateActions[0]))throw new IOException("Native one-time lever footprint changed");
        requirePaths(reference.a(cm,ActorConfig.class),"(Activate Signal)","implementation.state_actions[1].name");
        requirePaths(reference.a(cm,ActorConfig.class),"(Activate Tag)","implementation.state_actions[1].target.tag");
        if(!(reference.kW().get("(Activate Signal)") instanceof String name)||!(reference.kW().get("(Activate Tag)") instanceof String tag))throw new IOException("Missing authored lever target");
        try{return new org.tortodrone.world.SceneActions.Signal(tag,name);}catch(IllegalArgumentException invalid){throw new IOException("Invalid lever signal",invalid);}
    }
    private static void writeMechanism(ConfigManager cm,Map<String,String> out,String prefix,ConfigReference<ActorConfig> reference,Object original)throws Exception {
        var args=reference.kW();String mode=out.get(prefix+"switch-mode");
        if(reference.getName().equals("Dynamic/Switch/Lever/One-Time")){
            var operation=oneTimeLever(cm,reference,original);mode="damage-once";
            out.put(prefix+"switch-mode",mode);out.put(prefix+"switch-damage-mask","2");
            out.put(prefix+"state.1.signal",operation.name());out.put(prefix+"state.1.tag",operation.tag());return;
        }
        if(reference.getName().equals("Dynamic/Switch/Party Platform/Base")){
            if(!(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Switch platform)||platform.handlers.length!=3||platform.stateActions.length!=2
                ||!(platform.handlers[0] instanceof HandlerConfig.IntersectionStop)||!(platform.handlers[1] instanceof HandlerConfig.IntersectionStart)
                ||!(platform.handlers[2] instanceof com.threerings.projectx.dungeon.config.DungeonHandlerConfig.PartyIntersection party)||(party.mask!=4&&party.mask!=65535)
                ||!(party.condition instanceof com.threerings.tudey.config.ConditionConfig.Always||party.condition instanceof com.threerings.tudey.config.ConditionConfig.InstanceOf stripped&&(stripped.logicClass.isEmpty()||stripped.logicClass.equals("com.threerings.tudey.server.logic.PawnLogic"))&&!stripped.all&&stripped.target instanceof com.threerings.tudey.config.TargetConfig.Source)
                ||!(platform.stateActions[1] instanceof ActionConfig.Compound state)||state.actions.length!=2
                ||!(state.actions[1] instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.OverrideDefaultEntrance)
                ||!(args.get("Action") instanceof ActionConfig supplied))throw new IOException("Native party platform footprint changed");
            if(!(state.actions[0] instanceof ActionConfig.Compound placeholder&&placeholder.actions.length==0)&&!state.actions[0].equals(supplied))throw new IOException("Native platform action slot changed");
            var parameter=reference.a(cm,ActorConfig.class).getParameter("Action");
            if(!(parameter instanceof com.threerings.config.Parameter.Direct direct)||!Arrays.equals(direct.paths,new String[]{"implementation.state_actions[1].actions[0]"}))throw new IOException("Native platform action binding changed");
            var pressed=(ActionConfig.Compound)state.clone();pressed.actions=state.actions.clone();pressed.actions[0]=supplied;
            if(!(party.action instanceof ActionConfig.Compound outer)||outer.actions.length!=1||!(outer.actions[0] instanceof ActionConfig.Conditional guard)||guard.elseAction!=null
                ||!(guard.condition instanceof com.threerings.tudey.config.ConditionConfig.Limit||guard.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tagged&&tagged.tag.isEmpty()&&!tagged.all&&tagged.target instanceof com.threerings.tudey.config.TargetConfig.Source))throw new IOException("Native party intersection guard footprint changed");
            ActionConfig.Compound inner;
            if(guard.action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction retained&&retained.fireAction!=null
                &&retained.fireAction.getName().equals("Base/Warp (with Poof Cloud)")&&retained.fireAction.kW().size()==1&&"White".equals(retained.fireAction.kW().get("Cloud FX Variant"))){
                inner=new ActionConfig.Compound();var next=new ActionConfig.Signal();next.name="next";inner.actions=new ActionConfig[]{retained,next};
            }else if(guard.action instanceof ActionConfig.Compound compound&&compound.actions.length==2)inner=compound;
            else throw new IOException("Native party intersection action footprint changed");
            if(!(inner.actions[0] instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction)||!(inner.actions[1] instanceof ActionConfig.Signal next)
                ||!(next.target instanceof com.threerings.tudey.config.TargetConfig.Source)||!java.util.Set.of("","next").contains(next.name))throw new IOException("Native party next-state signal changed");
            var restoredInner=(ActionConfig.Compound)inner.clone();restoredInner.actions=inner.actions.clone();var restoredNext=(ActionConfig.Signal)next.clone();restoredNext.name="next";restoredInner.actions[1]=restoredNext;
            var restoredGuard=(ActionConfig.Conditional)guard.clone();var limit=new com.threerings.tudey.config.ConditionConfig.Limit();limit.limit=1;restoredGuard.condition=limit;restoredGuard.action=restoredInner;
            var restoredOuter=(ActionConfig.Compound)outer.clone();restoredOuter.actions=outer.actions.clone();restoredOuter.actions[0]=restoredGuard;
            out.put(prefix+"switch-mode","party");out.put(prefix+"party-intersection-action",org.tortodrone.resources.Json.stringify(sceneAction(restoredOuter,0,cm)));
            out.put(prefix+"state.1.scene-action",org.tortodrone.resources.Json.stringify(sceneAction(pressed,0,cm)));
        }
        if(reference.getName().startsWith("Dynamic/Breakable Objects/")&&original instanceof ActorConfig.Original actor){
            for(var handler:actor.handlers)if(handler instanceof com.threerings.projectx.dungeon.config.DungeonHandlerConfig.Damage damage){
                out.put(prefix+"damage-mask",Integer.toString(damage.mask));out.put(prefix+"damage-action","destroy");
            }
        }
        if(reference.getName().equals("Dynamic/Monster Objects/Beast Bell/Beast Bell")){
            if(!(args.get("Cooldown") instanceof Number cooldown))throw new IOException("Beast Bell cooldown argument missing");
            out.put(prefix+"switch-mode","damage-timed");out.put(prefix+"switch-damage-mask","2");out.put(prefix+"switch-delay",Integer.toString(cooldown.intValue()));
            out.put(prefix+"state.1.effect","Objects/Monster Objects/Beast Bell/Ring bell, beast stun");
            var effect=cm.d(com.threerings.tudey.config.EffectConfig.class,"Objects/Monster Objects/Beast Bell/Ring bell, beast stun");
            var status=bellStatus(effect.action);
            if(status==null||!(effect.shape instanceof com.threerings.tudey.shape.config.ShapeConfig.Circle circle))throw new IOException("Beast Bell effect layout changed");
            out.put(prefix+"state.1.status",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(status)));
            out.put(prefix+"state.1.status-radius",Float.toString(circle.radius));out.put(prefix+"state.1.status-tag","fam_beast");
        }
        if(mode!=null){
            if(mode.equals("pressure")){
                out.put(prefix+"state.0.signal",Objects.toString(args.get("(Off Signal)"),""));out.put(prefix+"state.0.tag",Objects.toString(args.get("(Target Tag)"),""));
                out.put(prefix+"state.2.signal",Objects.toString(args.get("(On Signal)"),""));out.put(prefix+"state.2.tag",Objects.toString(args.get("(Target Tag)"),""));
            }else for(int state=0;state<2;state++){
                String name=(mode.equals("button")||mode.equals("button-once"))?(state==0?"Off":"On"):"State "+state;
                Object signal=args.get("("+name+" Signal)");if(signal==null)signal=args.get(name+" Signal");if(state==1&&signal==null)signal=args.get("Start 1 Signal");
                Object tag=args.get("("+name+" Tag)");if(tag==null)tag=args.get(name+" Tag");
                out.put(prefix+"state."+state+".signal",Objects.toString(signal,""));out.put(prefix+"state."+state+".tag",Objects.toString(tag,""));
            }
            if(args.get("Delay") instanceof Number delay)out.put(prefix+"switch-delay",delay.toString());
        }
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Trap trap){
            for(var handler:trap.handlers)if(handler instanceof HandlerConfig.Intersection intersection&&intersection.action instanceof ActionConfig.Conditional conditional
                &&conditional.action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction fire){
                Object size=args.get("Warning and Damage Size");
                var parameter=reference.a(cm,ActorConfig.class).getParameter("Warning and Damage Size");
                if(parameter instanceof com.threerings.config.Parameter.Choice choice){
                    for(var option:choice.options)if(option.name.equals(size)){
                        // Native Choice values survive even though the handler's shape field is stripped.
                        Object values=field(option,"_arguments");if(values instanceof Map<?,?> map&&map.get("Intersect Shape")!=null)
                            out.put(prefix+"sensor",org.tortodrone.world.FireCatalog.modelJson(new org.tortodrone.character.StarterCatalog.Model("metadata",Map.of("value",FireCatalogExport.argumentValue(map.get("Intersect Shape"))))));
                    }
                }
                out.put(prefix+"hazard-action",org.tortodrone.world.FireCatalog.actionJson(FireCatalogExport.readAction(cm,fire)));
            }
        }
        if(out.containsKey(prefix+"heal-pad")){
            var config=reference.a(cm,ActorConfig.class);var parameter=config.getParameter("Heart");
            if(parameter instanceof com.threerings.config.Parameter.Choice choice)for(var option:choice.options)if(option.name.equals(Objects.toString(args.get("Heart"),choice.choice))){
                Object values=field(option,"_arguments");if(values instanceof Map<?,?> map&&map.get("Health") instanceof Number amount)out.put(prefix+"heal",amount.toString());
            }
            out.put(prefix+"heal-interval",Objects.toString(args.get("Interval"),"1.0"));
            if(field(original,"handlers") instanceof HandlerConfig[] handlers)for(var handler:handlers)if(handler instanceof HandlerConfig.Timer timer&&timer.action instanceof ActionConfig.Conditional conditional)
                out.put(prefix+"heal-action",org.tortodrone.world.FireCatalog.actionJson(FireCatalogExport.readAction(cm,conditional.action)));
        }
    }
    private static ConfigReference<com.threerings.projectx.dungeon.config.StatusConditionConfig> bellStatus(ActionConfig action){
        if(action instanceof ActionConfig.TargetedAction targeted)return bellStatus(targeted.action);
        if(action instanceof ActionConfig.Compound compound)for(var child:compound.actions){var found=bellStatus(child);if(found!=null)return found;}
        if(action instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.DamageTarget target&&target.damage instanceof com.threerings.projectx.dungeon.config.DamageConfig.Status status)return status.statusCondition;
        return null;
    }

    private static void writeDefense(ConfigManager cm, Map<String, String> out, String prefix,
                                     Object defense) throws Exception {
        for (String type : List.of("normal", "piercing", "elemental", "shadow")) {
            Object scaleReference = field(defense, type);
            if (scaleReference == null) continue;
            String name = referenceName(scaleReference);
            Object scale = resolveReference(cm, scaleReference, DepthScaleConfig.class);
            if (scale == null) throw new IOException("Unresolved defense scale " + name);
            putFloat(out, prefix + "defense." + type, evaluateScale(cm, scale));
        }
    }

    private static void writeTrap(Map<String, String> out, String prefix, Object original)
        throws Exception {
        Object cycle = field(original, "cycle");
        if (cycle == null) return;
        put(out, prefix + "cycle", enumValue(cycle));
        for (String name : List.of("downTime", "warnTime", "upTime", "offset")) {
            addOptionalNumber(out, prefix + kebab(name), field(cycle, name));
        }
    }

    private static void writeGenerator(ConfigManager cm, Map<String, String> out, String prefix,
                                       Object generator) throws Exception {
        addOptionalNumber(out, prefix + "delay", field(generator, "delay"));
        addOptionalNumber(out, prefix + "warning", field(generator, "warning"));
        Object damage=field(generator,"damage");
        if(damage!=null)out.put(prefix+"generated-damage",org.tortodrone.world.FireCatalog.modelJson(new org.tortodrone.character.StarterCatalog.Model("metadata",Map.of("value",FireCatalogExport.argumentValue(damage)))));
        Object actorReference = field(generator, "actor");
        if (!(actorReference instanceof ConfigReference<?> reference)) {
            throw new IOException("Generator has no actor reference");
        }
        String childPrefix = prefix + "child.";
        put(out, childPrefix + "config", reference.getName());
        @SuppressWarnings("unchecked") var childRef=(ConfigReference<ActorConfig>)reference;
        writeActor(cm,out,childPrefix,childRef);
    }

    private static void writeGeometry(Map<String, String> out, String prefix, Object shape)
        throws Exception {
        if (shape == null) return;
        put(out, prefix + "shape", shape.getClass().getSimpleName());
        put(out,prefix+"source-shape",org.tortodrone.world.FireCatalog.modelJson(new org.tortodrone.character.StarterCatalog.Model("shape",Map.of("value",FireCatalogExport.argumentValue(shape)))));
        addOptionalNumber(out, prefix + "radius", field(shape, "radius"));
        addOptionalNumber(out, prefix + "width", field(shape, "width"));
        addOptionalNumber(out, prefix + "height", field(shape, "height"));
        if(shape instanceof com.threerings.tudey.shape.config.ShapeConfig config){
            var bounds=config.acS().aex();
            putFloat(out,prefix+"bounds-min-x",bounds.tt().x);putFloat(out,prefix+"bounds-min-y",bounds.tt().y);
            putFloat(out,prefix+"bounds-max-x",bounds.tu().x);putFloat(out,prefix+"bounds-max-y",bounds.tu().y);
        }
    }
    private static void writeDialogues(ConfigManager cm,Map<String,String> out,List<Binding> bindings)throws Exception {
        String ids=rules.getProperty("dialogues","");var selected=new ArrayList<String>();
        for(String id:ids.split(",")){
            if(id.isBlank())continue;String prefix="dialogue."+id+".";
            String actorName=rules.getProperty(prefix+"actor");
            var script=scriptedConversations.get(id);
            if(script==null&&bindings.stream().noneMatch(b->b.actorReference().getName().equals(actorName)))continue;
            selected.add(id);
            String reference=rules.getProperty(prefix+"reference");
            var conversation=cm.d(com.threerings.projectx.config.ConversationConfig.class,reference);
            if(conversation==null)throw new IOException("Unresolved authored conversation binding");
            boolean verify=Boolean.parseBoolean(rules.getProperty(prefix+"verify-actions","false"));
            if(verify)verifyConversationInitial(conversation.initialAction,rules.getProperty(prefix+"initial"),rules.getProperty(prefix+"initial-footprint",""));
            if(rules.containsKey(prefix+"initial-action")){
                if(!rules.getProperty(prefix+"initial-footprint","").equals("rhendon-rc-intro"))throw new IOException("Unverified conversation initial action");
                String initialAction=rules.getProperty(prefix+"initial-action");
                var original=((ActionConfig.Compound)((ActionConfig.Conditional)conversation.initialAction).elseAction).actions[0];
                if(!org.tortodrone.world.SceneActions.parse(initialAction).equals(org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(sceneAction(original,0,cm,true)))))throw new IOException("Conversation initial memory changed");
                put(out,prefix+"initial-action",initialAction);
            }
            Object model=script==null?field(cm.d(ActorConfig.class,actorName).ai(cm).sprite,"model"):script.model;
            String speaker=script==null?rules.getProperty(prefix+"name"):script.name.isBlank()?conversation.speakerName:script.name;
            if(speaker!=null&&speaker.isBlank())speaker=rules.getProperty(prefix+"name");
            if(speaker==null||speaker.isBlank())throw new IOException("Missing authored conversation speaker");
            put(out,prefix+"actor",actorName);put(out,prefix+"name",speaker);
            if(script!=null){
                verifyConversationInitial(conversation.initialAction,rules.getProperty(prefix+"initial"));
                if(script.sourceCloseAnimation!=null)put(out,prefix+"source-close-animation",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(script.sourceCloseAnimation)));
                if(script.music!=null)put(out,prefix+"music",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(script.music)));
            }
            put(out,prefix+"initial",rules.getProperty(prefix+"initial"));put(out,prefix+"bundle",conversation.bundle);
            if(model instanceof ConfigReference<?> r)put(out,prefix+"model",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(r)));
            List<String> nodes=new ArrayList<>();
            for(int i=0;i<conversation.nodes.length;i++){
                if(!(conversation.nodes[i] instanceof com.threerings.projectx.config.ConversationConfig.SimpleNode node))throw new IOException("Unsupported conversation node");
                String np=prefix+"node."+i+".";nodes.add(Integer.toString(i));
                if(!node.name.equals(rules.getProperty(np+"name"))||!node.message.equals(rules.getProperty(np+"message")))throw new IOException("Authored conversation node changed");
                put(out,np+"name",node.name);put(out,np+"message",node.message);put(out,np+"responses",Integer.toString(node.responses.length));
                if(node.animation!=null)put(out,np+"animation",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(node.animation)));
                if(node.userInterface!=null)put(out,np+"interface",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(node.userInterface)));
                if(node.sourceTransient!=null)put(out,np+"transient",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(node.sourceTransient)));
                for(int j=0;j<node.responses.length;j++){
                    if(!(node.responses[j] instanceof com.threerings.projectx.config.ConversationConfig.SimpleResponse response))throw new IOException("Unsupported conversation response");
                    String rp=np+"response."+j+".";
                    if(!response.text.equals(rules.getProperty(rp+"text")))throw new IOException("Authored conversation response changed");
                    put(out,rp+"text",response.text);put(out,rp+"destination",rules.getProperty(rp+"destination",""));
                    if(script!=null||verify){
                        String restored=rules.getProperty(rp+"action");String destination=rules.getProperty(rp+"destination","");
                        try{verifyConversationResponse(response.action,destination,restored,rules.getProperty(rp+"stripped-action",""));}catch(IOException invalid){throw new IOException("Conversation "+reference+" / "+node.name+" / "+j+": "+invalid.getMessage(),invalid);}
                        if(restored!=null){org.tortodrone.world.SceneActions.parse(restored);put(out,rp+"action",restored);}
                    }
                }
            }
            put(out,prefix+"nodes",String.join(",",nodes));
        }
        put(out,"dialogues",String.join(",",selected));
        for(int i=0;i<bindings.size();i++)if("Stranger".equals(out.get("entry."+i+".kind"))) {
            String actor=bindings.get(i).actorReference().getName();
            if(selected.stream().noneMatch(id->actor.equals(rules.getProperty("dialogue."+id+".actor"))))
                importReport.add("Unresolved conversation for placed NPC "+bindings.get(i).sourceKey()+": "+actor);
        }
    }

    private static boolean strippedConversationAction(ActionConfig action){return action instanceof ActionConfig.Compound compound&&compound.actions!=null&&compound.actions.length==0;}
    private static void verifyConversationInitial(ActionConfig action,String initial)throws IOException {
        verifyConversationInitial(action,initial,"");
    }
    private static void verifyConversationInitial(ActionConfig action,String initial,String footprint)throws IOException {
        if(!footprint.isEmpty()){
            if(footprint.equals("rhendon-rc-intro")&&initial.equals("part 1a")&&action instanceof ActionConfig.Conditional conditional
                    &&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Tagged tagged&&tagged.tag.isEmpty()&&!tagged.all&&tagged.target instanceof com.threerings.tudey.config.TargetConfig.Source
                    &&conditional.action instanceof ActionConfig.SpawnActor spawn&&spawn.actor==null&&spawn.location instanceof com.threerings.tudey.config.TargetConfig.Source
                    &&conditional.elseAction instanceof ActionConfig.Compound compound&&!compound.stopOnFailure&&compound.actions!=null&&compound.actions.length==1
                    &&compound.actions[0] instanceof com.threerings.projectx.config.ProjectXActionConfig.SetMemory memory&&memory.name.equals("talkedRhendonRC")&&memory.persistence==com.threerings.projectx.config.Persistence.KNIGHT&&memory.target instanceof com.threerings.tudey.config.TargetConfig.Activator)return;
            throw new IOException("Conversation initial footprint changed");
        }
        if(strippedConversationAction(action))return;
        // The installed stripped config retains a blank Jump. The allowlisted rule's
        // destination is backed by the archive XML and the observed first panel;
        // never infer an opener from the order of the nodes.
        if(action instanceof com.threerings.projectx.config.ProjectXActionConfig.ConversationJump jump
                &&jump.target instanceof com.threerings.tudey.config.TargetConfig.Activator
                &&(jump.node.isEmpty()||jump.node.equals(initial)))return;
        throw new IOException("Scripted conversation initial action changed");
    }
    static void verifyConversationResponse(ActionConfig action,String destination,String restored,String footprint)throws IOException {
        if(!footprint.isEmpty()){
            if(footprint.equals("clear-dialog-activator")&&restored==null&&action instanceof com.threerings.projectx.config.ProjectXActionConfig.ClearDialog clear
                    &&clear.target instanceof com.threerings.tudey.config.TargetConfig.Activator)return;
            throw new IOException("Sourced conversation response footprint changed");
        }
        if(strippedConversationAction(action))return;
        // A terminal response already clears its own dialog through the production service.
        if(destination.isEmpty()&&restored==null&&action instanceof com.threerings.projectx.config.ProjectXActionConfig.ClearDialog clear
                &&clear.target instanceof com.threerings.tudey.config.TargetConfig.Activator)return;
        if(action instanceof ActionConfig.Compound compound&&compound.actions!=null&&compound.actions.length==1){verifyConversationResponse(compound.actions[0],destination,restored,"");return;}
        if(!destination.isEmpty()&&restored==null&&action instanceof com.threerings.projectx.config.ProjectXActionConfig.ConversationJump jump
                &&jump.target instanceof com.threerings.tudey.config.TargetConfig.Activator&&jump.node.equals(destination))return;
        if(destination.isEmpty()&&restored!=null&&org.tortodrone.world.SceneActions.parse(restored).equals(org.tortodrone.world.SceneActions.parse(org.tortodrone.resources.Json.stringify(sceneAction(action,0,null,true)))))return;
        throw new IOException("Scripted conversation response changed ("+(action==null?"null":action.getClass().getName())
            +(action instanceof com.threerings.projectx.config.ProjectXActionConfig.ConversationJump jump?", node="+jump.node+", target="+jump.target.getClass().getName():"")
            +(action instanceof com.threerings.projectx.config.ProjectXActionConfig.ClearDialog clear?", target="+clear.target.getClass().getName():"")+"); refusing to omit authored siblings");
    }

    static void writeTags(Map<String, String> out, String prefix, Object tagConfig)
        throws Exception {
        if (tagConfig == null) return;
        Object raw = callNoArg(tagConfig, List.of("adx", "getTags"));
        if (raw == null) return;
        List<String> tags = new ArrayList<>();
        if (raw instanceof String[] array) {
            for (String tag : array) if (tag != null && !tag.isBlank()) tags.add(tag);
        } else {
            throw new IOException("Unsupported actor tag representation");
        }
        put(out, prefix + "tags", String.join(",", tags));
    }

    private static void writeArguments(Map<String, String> out, String keyPrefix, Object reference)
        throws Exception {
        Object raw = callNoArg(reference, List.of("kW", "getArguments"));
        if (raw == null) return;
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw new IOException("Non-string config argument name");
                arguments.put(name, entry.getValue());
            }
        } else if (raw.getClass().isArray() || raw instanceof Iterable<?>) {
            Iterable<?> items = raw instanceof Iterable<?> iterable ? iterable : arrayIterable(raw);
            for (Object item : items) {
                Object name = field(item, "name");
                Object value = field(item, "value");
                if (!(name instanceof String string)) throw new IOException("Unsupported config argument record");
                arguments.put(string, value);
            }
        } else {
            throw new IOException("Unsupported config argument container: " + raw.getClass().getName());
        }
        for (Map.Entry<String, Object> entry : new TreeMap<>(arguments).entrySet()) {
            if (entry.getKey().isBlank()) throw new IOException("Empty config argument name");
            put(out, keyPrefix + entry.getKey(), typedValue(entry.getValue()));
        }
    }

    private static Iterable<?> arrayIterable(Object array) {
        List<Object> list = new ArrayList<>(Array.getLength(array));
        for (int i = 0; i < Array.getLength(array); i++) list.add(Array.get(array, i));
        return list;
    }

    private static String typedValue(Object value) throws IOException {
        if (value instanceof String string) return "string|" + string;
        if (value instanceof Boolean bool) return "boolean|" + bool;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return "int|" + value;
        }
        if (value instanceof Float number) return "float|" + safeFloat(number);
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IOException("Non-finite config argument");
            return "double|" + number;
        }
        if(value instanceof ConfigReference<?> r)return "model|"+org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(r));
        if (value instanceof Enum<?> enumeration) return enumValue(enumeration);
        return "data|"+org.tortodrone.world.FireCatalog.modelJson(new org.tortodrone.character.StarterCatalog.Model(
            "content-argument",Map.of("value",FireCatalogExport.argumentValue(value))));
    }

    private static String enumValue(Object value) throws IOException {
        if (!(value instanceof Enum<?> enumeration)) throw new IOException("Expected native enum value");
        return "enum|" + enumeration.getDeclaringClass().getName() + "|" + enumeration.ordinal()+"|"+enumeration.name();
    }

    private static void addDepthScales(ConfigManager cm, Map<String, String> out) throws Exception {
        Object group = cm.bd(DepthScaleConfig.class);
        if (group == null) throw new IOException("Native depth scale group is unavailable");
        Object raw = cm.bd(DepthScaleConfig.class).kK();
        Iterable<?> configs;
        if (raw instanceof Iterable<?> iterable) configs = iterable;
        else if (raw != null && raw.getClass().isArray()) configs = arrayIterable(raw);
        else throw new IOException("Depth scale group has no iterable config list");
        List<Object> sorted = new ArrayList<>();
        configs.forEach(sorted::add);
        sorted.sort(Comparator.comparing(c -> ((DepthScaleConfig)c).getName()));
        for (Object config : sorted) {
            String name = String.valueOf(invokeName(config));
            if (name.isBlank()) throw new IOException("Depth scale without native name");
            putFloat(out, "scale." + name, evaluateScale(cm, config));
        }
    }

    private static Object findGroup(ConfigManager cm, Class<?> configClass) throws Exception {
        List<Method> methods = Arrays.stream(cm.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == Class.class)
            .filter(m -> m.getReturnType().getName().contains("ConfigGroup"))
            .toList();
        if (methods.size() != 1) throw new IOException("Cannot identify native ConfigManager group accessor");
        return methods.getFirst().invoke(cm, configClass);
    }

    private static Object callNoArgByReturn(Object target, Class<?>... returnTypes) throws Exception {
        List<Method> methods = Arrays.stream(target.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> Arrays.stream(returnTypes).anyMatch(t -> t.isAssignableFrom(m.getReturnType())))
            .toList();
        if (methods.size() != 1) throw new IOException("Cannot identify native config-list accessor");
        return methods.getFirst().invoke(target);
    }

    private static Object invokeName(Object target) throws Exception {
        try { return target.getClass().getMethod("getName").invoke(target); }
        catch (NoSuchMethodException absent) {
            Object name = field(target, "name");
            if (name == null) throw new IOException("Config lacks native name");
            return name;
        }
    }

    private static Object resolveReference(ConfigManager cm, Object reference, Class<?> type)
        throws Exception {
        List<Method> methods = Arrays.stream(reference.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 2
                && m.getParameterTypes()[0].isAssignableFrom(cm.getClass())
                && m.getParameterTypes()[1] == Class.class
                && !Modifier.isStatic(m.getModifiers()))
            .toList();
        if (methods.size() != 1) throw new IOException("Cannot resolve native config reference");
        return methods.getFirst().invoke(reference, cm, type);
    }

    private static float evaluateScale(ConfigManager cm, Object scale) throws Exception {
        for (Method method : scale.getClass().getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 4 || !p[0].isAssignableFrom(cm.getClass())
                || p[1] != int.class || p[2] != int.class || !p[3].isEnum()
                || method.getReturnType() != float.class) continue;
            Object medium = Arrays.stream(p[3].getEnumConstants())
                .filter(c -> ((Enum<?>)c).name().equals("MEDIUM")).findFirst()
                .orElseThrow(() -> new IOException("Missing MEDIUM depth difficulty"));
            Object value = method.invoke(scale, cm, 0, 0, medium);
            return finite(((Number)value).floatValue(), "depth scale result");
        }
        throw new IOException("Cannot evaluate native depth scale at depth zero MEDIUM");
    }

    private static Object callNoArg(Object target, List<String> names) throws Exception {
        for (String name : names) {
            try { return target.getClass().getMethod(name).invoke(target); }
            catch (NoSuchMethodException ignored) { }
        }
        // ConfigReference's public argument accessor is obfuscated in some client builds.
        List<Method> candidates = Arrays.stream(target.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> Map.class.isAssignableFrom(m.getReturnType())
                || m.getReturnType().isArray() || Iterable.class.isAssignableFrom(m.getReturnType()))
            .toList();
        if (candidates.size() == 1) return candidates.getFirst().invoke(target);
        throw new IOException("Cannot read native config arguments");
    }

    private static Object field(Object target, String name) throws Exception {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private static void addOptional(Map<String, String> out, String key, Object value) throws Exception {
        if (value == null) return;
        if (value instanceof Number number) putNumber(out, key, number);
        else if (value instanceof Boolean || value instanceof String || value instanceof Enum<?>) {
            put(out, key, value instanceof Enum<?> ? enumValue(value) : String.valueOf(value));
        } else if (value instanceof ConfigReference<?> reference) {
            put(out, key, reference.getName());
        } else throw new IOException("Unsupported native field " + key + ": " + value.getClass().getName());
    }

    private static void addOptionalString(Map<String, String> out, String key, Object value)
        throws IOException {
        if (value != null) put(out, key, String.valueOf(value));
    }

    private static void addOptionalNumber(Map<String, String> out, String key, Object value)
        throws IOException {
        if (value instanceof Number number) putNumber(out, key, number);
    }

    private static void putNumber(Map<String, String> out, String key, Number number) throws IOException {
        if (number instanceof Float f) putFloat(out, key, f);
        else if (number instanceof Double d) {
            if (!Double.isFinite(d)) throw new IOException("Non-finite value: " + key);
            put(out, key, d.toString());
        } else put(out, key, number.toString());
    }

    private static void putFloat(Map<String, String> out, String key, float value) throws IOException {
        put(out, key, safeFloat(value));
    }

    private static String safeFloat(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Non-finite numeric value");
        return Float.toString(value);
    }

    private static float finite(float value, String field) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Non-finite " + field);
        return value;
    }

    private static String key(Object value) throws IOException {
        if (value == null) throw new IOException("Scene entry has no native key");
        String key = String.valueOf(value);
        if (key.isBlank() || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new IOException("Malformed native scene key");
        }
        return key;
    }

    private static String referenceName(Object reference) throws Exception {
        Object name = reference.getClass().getMethod("getName").invoke(reference);
        if (!(name instanceof String string) || string.isBlank()) throw new IOException("Missing config reference name");
        return string;
    }

    private static boolean isKind(Object object, String name) {
        return object.getClass().getSimpleName().equals(name);
    }

    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static void put(Map<String, String> out, String key, String value) throws IOException {
        if (key.isBlank() || value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Malformed content property: " + key);
        }
        if (out.putIfAbsent(key, value) != null) throw new IOException("Duplicate content property: " + key);
    }

    private static String propertiesText(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(values).forEach((key, value) -> text.append(escape(key, true)).append('=')
            .append(escape(value, false)).append('\n'));
        return text.toString();
    }

    private static String escape(String value, boolean key) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                case ' ', '=', ':', '#', '!' -> {
                    if (key || i == 0) escaped.append('\\');
                    escaped.append(c);
                }
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private record Binding(String sourceKey, int handlerIndex, float x, float y, float rotation,
                           ConfigReference<ActorConfig> actorReference) { }
}
