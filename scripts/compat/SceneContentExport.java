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
    private SceneContentExport() { }

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

        importReport.clear();
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
        collectTriggers(cm,scene,values,indexes);
        values.put("entries", String.join(",", indexes));
        writeDialogues(cm,values,bindings);
        Files.writeString(target, propertiesText(values), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE_NEW);
        importReport.add(0,"Supported extracted actor bindings: "+bindings.size());
        Files.writeString(output.resolve("import-source-report.txt"),String.join("\n",importReport)+"\n",StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static List<Binding> collectBindings(ConfigManager cm, TudeySceneModel scene)
        throws Exception {
        List<Binding> result = new ArrayList<>();
        var state=new com.threerings.projectx.dungeon.config.SpawnTableConfig.a();
        state.depth=0;state.difficulty=com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM;
        if(scene.adD() instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig level)state.bkg=level;
        for (Object raw : scene.adH()) {
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
                collectStartup(cm, result, sourceKey, hi, x, y, rotation, startup.action,sourceArgs,state,0);
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
        if (action instanceof ActionConfig.SpawnActor spawn) {
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
        } else importReport.add("Unsupported startup action at "+sourceKey+" / "+handlerIndex+": "+action.getClass().getName());
    }

    /** Whole-graph extraction only: an unsupported sibling must never disappear from a compound. */
    private static void collectTriggers(ConfigManager cm,TudeySceneModel scene,Map<String,String> values,List<String> indexes)throws Exception {
        for(Object raw:scene.adH())if(raw instanceof TudeySceneModel.PlaceableEntry entry){
            var config=entry.placeable.a(cm,PlaceableConfig.class);
            var original=config.q(cm);
            if(original.handlers==null)continue;
            for(int hi=0;hi<original.handlers.length;hi++){
                var handler=restoreSceneHandler(entry.placeable.getName(),original.handlers[hi]);
                if(!sceneHandler(handler))continue;
                if(handler!=original.handlers[hi])importReport.add("Restored archived One Time Action signal/Limit at "+key(entry.getKey())+" / "+hi);
                try{
                    var fields=new TreeMap<String,String>();
                    fields.put("kind","SceneTrigger");fields.put("config",entry.placeable.getName());fields.put("source-key",key(entry.getKey()));fields.put("handler-index",Integer.toString(hi));
                    fields.put("state-key",key(entry.getKey())+":"+hi);
                    var point=entry.au(cm);fields.put("x",Float.toString(finite(point.x,"trigger x")));fields.put("y",Float.toString(finite(point.y,"trigger y")));fields.put("rotation","0");
                    List<String> tags;
                    if(handler instanceof HandlerConfig.ActorRemoved removed){
                        if(!(removed.target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged))throw new IOException("ActorRemoved target is not Tagged");
                        fields.put("event","removed");fields.put("all",Boolean.toString(removed.all));tags=List.of(tagged.tag);
                    }else{
                        fields.putAll(sceneSignal(handler));
                        tags=sceneTags(config,entry.placeable.kW(),original.tags);
                        if(tags.isEmpty())throw new IOException("Signal handler has no resolved tags");
                    }
                    fields.put("action",org.tortodrone.resources.Json.stringify(sceneAction(((HandlerConfig.ActionHandlerConfig)handler).action,0)));
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
    /** Exact stripped footprint of the archived One Time Action; do not generalize empty guards. */
    static HandlerConfig restoreSceneHandler(String config,HandlerConfig handler){
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
    private static boolean sceneHandler(HandlerConfig handler){return handler instanceof HandlerConfig.ActorRemoved||handler instanceof HandlerConfig.Signal||handler instanceof HandlerConfig.SignalStart||handler instanceof HandlerConfig.SignalStop;}
    static Map<String,String> sceneSignal(HandlerConfig handler)throws IOException {
        if(handler instanceof HandlerConfig.Signal signal){
            if(signal.refractoryPeriod!=0)throw new IOException("Signal refractory period is not implemented");
            return Map.of("event","signal","signal",signal.name);
        }
        if(handler instanceof HandlerConfig.SignalStart signal)return Map.of("event","signal-start","signal",signal.name);
        if(handler instanceof HandlerConfig.SignalStop signal)return Map.of("event","signal-stop","signal",signal.name);
        throw new IOException("Unsupported signal handler");
    }
    static Map<String,Object> sceneAction(ActionConfig action,int depth)throws IOException {
        if(depth>16)throw new IOException("Scene action graph is too deep");
        if(action instanceof ActionConfig.Signal signal)return sceneSignalAction(signal.target,signal.name,depth);
        if(action instanceof ActionConfig.Delayed delay){
            if(delay.variance<0||delay.variance>3_600_000||delay.delay<0||delay.delay>3_600_000)throw new IOException("Unsupported scene action delay/variance");
            return Map.of("kind","delay","millis",delay.delay,"variance",delay.variance,"action",sceneAction(delay.action,depth+1));
        }
        if(action instanceof ActionConfig.Compound compound){
            if(compound.actions==null||compound.actions.length>256)throw new IOException("Invalid scene compound");
            var children=new ArrayList<Object>();for(var child:compound.actions)children.add(sceneAction(child,depth+1));
            return Map.of("kind","sequence","stopOnFailure",compound.stopOnFailure,"actions",children);
        }
        if(action instanceof ActionConfig.Conditional conditional&&conditional.condition instanceof com.threerings.tudey.config.ConditionConfig.Limit limit){
            if(limit.limit<0||limit.limit>1_000_000)throw new IOException("Unsupported condition limit");
            return Map.of("kind","limited","limit",limit.limit,"action",sceneAction(conditional.action,depth+1),"otherwise",
                conditional.elseAction==null?Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()):sceneAction(conditional.elseAction,depth+1));
        }
        throw new IOException("Unsupported scene action "+(action==null?"null":action.getClass().getName()));
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
        if(nativeTags.derived!=null)result.addAll(Arrays.asList(nativeTags.derived.adx()));
        for(String tag:result)if(tag==null||tag.length()>256||tag.contains(",")||tag.chars().anyMatch(Character::isISOControl))throw new IOException("Unrepresentable native scene tag");
        return result.stream().filter(tag->!tag.isBlank()).distinct().toList();
    }
    private static Map<String,Object> sceneSignalAction(com.threerings.tudey.config.TargetConfig target,String name,int depth)throws IOException {
        if(depth>16)throw new IOException("Scene signal target graph is too deep");
        if(target instanceof com.threerings.tudey.config.TargetConfig.Tagged tagged)return Map.of("kind","signal","tag",tagged.tag,"name",name);
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
            var bounds=knight.interactionShape.acP().aeu();
            putFloat(out,prefix+"interaction-radius",Math.min(bounds.tu().x-bounds.tt().x,bounds.tu().y-bounds.tt().y)/2);
            putFloat(out,prefix+"interaction-offset-x",(bounds.tu().x+bounds.tt().x)/2);
            putFloat(out,prefix+"interaction-offset-y",(bounds.tu().y+bounds.tt().y)/2);
        }
        if(field(sprite,"tooltipItem") instanceof ConfigReference<?> item){
            put(out,prefix+"pickup-item",item.getName());
            @SuppressWarnings("unchecked") var itemRef=(ConfigReference<com.threerings.projectx.item.config.ItemConfig>)item;
            var nativeItem=itemRef.a(cm,com.threerings.projectx.item.config.ItemConfig.class).w(cm);
            put(out,prefix+"pickup-transient",Boolean.toString(nativeItem.Qm()));
        }
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Pickup&&reference.kW().get("Dungeon Item") instanceof ConfigReference<?> item){
            out.put(prefix+"pickup-item",item.getName());
            @SuppressWarnings("unchecked") var nativeItem=((ConfigReference<com.threerings.projectx.item.config.ItemConfig>)item).a(cm,com.threerings.projectx.item.config.ItemConfig.class).w(cm);
            out.put(prefix+"pickup-transient",Boolean.toString(nativeItem.Qm()));
            int slot=nativeItem.UZ();
            if(slot<0||!nativeItem.h(slot,false))throw new IOException("Unsupported prepared dungeon equipment slot");
            out.put(prefix+"pickup-equip-slot",Integer.toString(slot));
        }
        writeTags(out, prefix, field(original, "tags"));
        if(original instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster monster){
            var death=new ArrayList<org.tortodrone.world.FireCatalog.Action>();
            deathActions(cm,monster.handlers,death,0);
            if(!death.isEmpty()){
                var action=new org.tortodrone.world.FireCatalog.Action(org.tortodrone.world.FireCatalog.Kind.COMPOUND,null,null,"Source","","",0,0,0,0,true,0,0,0,0,false,death,"");
                put(out,prefix+"death-action",org.tortodrone.world.FireCatalog.actionJson(action));
            }
        }
        if(reference.kW().get("Tag") instanceof String tag&&!tag.isBlank()&&!tag.startsWith("("))out.put(prefix+"tags",tag);
        writeArguments(out, prefix + "config.argument.", reference);

        if (isKind(original, "Monster")) writeMonster(cm, out, prefix, original);
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
            addOptionalNumber(out,prefix+"door-speed",field(original,"doorSpeed"));
            Object startOpen = field(original, "startOpen");
            if (startOpen == null) startOpen = field(original, "open");
            addOptionalString(out, prefix + "start-open", startOpen);
        }
        if (field(original, "actor") instanceof ConfigReference<?> && field(original, "delay") != null
            && field(original, "warning") != null) writeGenerator(cm, out, prefix, original);
        // Client exports strip server actions. Only explicitly sourced config bindings
        // in the checked-in rule data may restore those missing fields.
        String rulePrefix="actor."+reference.getName()+".";
        for(String key:rules.stringPropertyNames())if(key.startsWith(rulePrefix))out.put(prefix+key.substring(rulePrefix.length()),rules.getProperty(key));
        String appended=out.get(prefix+"append-tags");
        if(appended!=null)out.put(prefix+"tags",out.getOrDefault(prefix+"tags","")+(out.getOrDefault(prefix+"tags","").isBlank()?"":",")+appended);
        writeMechanism(cm,out,prefix,reference,original);
    }

    private static void deathActions(ConfigManager cm,HandlerConfig[] handlers,
            List<org.tortodrone.world.FireCatalog.Action> actions,int depth)throws Exception {
        if(handlers==null)return;
        if(depth>16)throw new IOException("Cyclic monster death handler references");
        for(var handler:handlers){
            if(handler instanceof HandlerConfig.Shutdown shutdown)actions.add(FireCatalogExport.readAction(cm,shutdown.action));
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
        for(String name:List.of("canThrow","throwTime","fallSpeed","thrownCollisionFlags","thrownCollisionMask","fuse","explodeOnImpact","snapTag"))addOptional(out,prefix+kebab(name),field(lift,name));
        if(lift.description!=null)out.put(prefix+"description",lift.description.getName());
        for(var handler:lift.handlers)if(handler instanceof HandlerConfig.Signal signal&&signal.action!=null){
            out.put(prefix+"land-action",org.tortodrone.world.FireCatalog.actionJson(FireCatalogExport.readAction(cm,signal.action)));break;
        }
    }

    private static void writeMonster(ConfigManager cm, Map<String, String> out, String prefix,
                                     Object original) throws Exception {
        SceneMonsterExport.export(cm,out,prefix,(com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster)original);
        for (String name : List.of("showHealth", "minReactPercent", "reactTime", "deathTime", "activateTime")) {
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

    private static void writeMechanism(ConfigManager cm,Map<String,String> out,String prefix,ConfigReference<ActorConfig> reference,Object original)throws Exception {
        var args=reference.kW();String mode=out.get(prefix+"switch-mode");
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
                String name=mode.equals("button")?(state==0?"Off":"On"):"State "+state;
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
            var bounds=config.acP().aeu();
            putFloat(out,prefix+"bounds-min-x",bounds.tt().x);putFloat(out,prefix+"bounds-min-y",bounds.tt().y);
            putFloat(out,prefix+"bounds-max-x",bounds.tu().x);putFloat(out,prefix+"bounds-max-y",bounds.tu().y);
        }
    }
    private static void writeDialogues(ConfigManager cm,Map<String,String> out,List<Binding> bindings)throws Exception {
        String ids=rules.getProperty("dialogues","");var selected=new ArrayList<String>();
        for(String id:ids.split(",")){
            if(id.isBlank())continue;String prefix="dialogue."+id+".";
            String actorName=rules.getProperty(prefix+"actor");
            if(bindings.stream().noneMatch(b->b.actorReference().getName().equals(actorName)))continue;
            selected.add(id);
            String reference=rules.getProperty(prefix+"reference");
            var conversation=cm.d(com.threerings.projectx.config.ConversationConfig.class,reference);
            if(conversation==null)throw new IOException("Unresolved authored conversation binding");
            var actor=cm.d(ActorConfig.class,actorName).ai(cm);
            Object model=field(actor.sprite,"model");
            put(out,prefix+"actor",actorName);put(out,prefix+"name",rules.getProperty(prefix+"name"));
            put(out,prefix+"initial",rules.getProperty(prefix+"initial"));put(out,prefix+"bundle",conversation.bundle);
            if(model instanceof ConfigReference<?> r)put(out,prefix+"model",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(r)));
            List<String> nodes=new ArrayList<>();
            for(int i=0;i<conversation.nodes.length;i++){
                if(!(conversation.nodes[i] instanceof com.threerings.projectx.config.ConversationConfig.SimpleNode node))throw new IOException("Unsupported conversation node");
                String np=prefix+"node."+i+".";nodes.add(Integer.toString(i));
                if(!node.name.equals(rules.getProperty(np+"name"))||!node.message.equals(rules.getProperty(np+"message")))throw new IOException("Authored conversation node changed");
                put(out,np+"name",node.name);put(out,np+"message",node.message);put(out,np+"responses",Integer.toString(node.responses.length));
                if(node.animation!=null)put(out,np+"animation",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(node.animation)));
                if(node.sourceTransient!=null)put(out,np+"transient",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(node.sourceTransient)));
                for(int j=0;j<node.responses.length;j++){
                    if(!(node.responses[j] instanceof com.threerings.projectx.config.ConversationConfig.SimpleResponse response))throw new IOException("Unsupported conversation response");
                    String rp=np+"response."+j+".";
                    if(!response.text.equals(rules.getProperty(rp+"text")))throw new IOException("Authored conversation response changed");
                    put(out,rp+"text",response.text);put(out,rp+"destination",rules.getProperty(rp+"destination",""));
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
