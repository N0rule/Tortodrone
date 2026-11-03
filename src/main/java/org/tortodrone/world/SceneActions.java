package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;
import org.tortodrone.character.StarterCatalog;

/** Allowlisted scene handlers. Owned and advanced by one SceneEntities simulation, never a timer. */
public final class SceneActions {
    public sealed interface Action permits Signal,SourceSignal,Failure,Targeted,DefaultEntrance,NearestSignal,Destroy,DestroySource,DestroyMany,Spawn,PawnStatus,Conversation,ClearDialog,SetVariable,VariableCondition,Timer,SourceEffect,GameplayEffect,Impulse,ProgressEvent,NextFloor,Memory,Objective,Delay,Sequence,Limited,Choice { }
    public record SourceSignal(String name) implements Action {public SourceSignal{text(name);}}
    /** A natively resolved empty WarpActor location fails without moving anything. */
    public record Failure() implements Action { }
    public record Targeted(LogicTarget target,Action action) implements Action {public Targeted{Objects.requireNonNull(target);Objects.requireNonNull(action);}}
    public record DefaultEntrance(LogicTarget target) implements Action {public DefaultEntrance{Objects.requireNonNull(target);}}
    @FunctionalInterface public interface TargetResolution {List<String> resolve(LogicTarget target,int stamp,String source,String activator);}
    @FunctionalInterface public interface SourceSignals {boolean signal(String name,int stamp,String source);}
    @FunctionalInterface public interface Entrances {boolean set(LogicTarget target,int stamp,String source,String activator);}
    private TargetResolution targetResolution=(target,stamp,source,activator)->List.of();
    private SourceSignals sourceSignals=(name,stamp,source)->false;
    private Entrances entrances=(target,stamp,source,activator)->false;
    public void targets(TargetResolution sink){targetResolution=Objects.requireNonNull(sink);}
    public void sourceSignals(SourceSignals sink){sourceSignals=Objects.requireNonNull(sink);}
    public void entrances(Entrances sink){entrances=Objects.requireNonNull(sink);}
    public record DestroySource() implements Action { }
    @FunctionalInterface public interface SourceDestruction {boolean destroy(int stamp,String source);}
    private SourceDestruction sourceDestruction=(stamp,source)->false;
    public void sourceDestruction(SourceDestruction sink){sourceDestruction=Objects.requireNonNull(sink);}
    /** Native logic variables belong to Source, Activator or resolved Tagged logics, never a client object. */
    public record LogicTarget(String mode,String tag) {
        public LogicTarget {if(!Set.of("source","activator","tagged").contains(mode)||tag==null||mode.equals("tagged")==tag.isEmpty())throw new IllegalArgumentException("Invalid logic target");if(!tag.isEmpty())text(tag);}
    }
    public record SetVariable(String name,boolean value,LogicTarget target) implements Action {
        public SetVariable{text(name);Objects.requireNonNull(target);}
    }
    /** Evaluate(Reference), with native missing-reference=false behavior. */
    public record VariableCondition(String name,LogicTarget target,Action action,Action otherwise) implements Action {
        public VariableCondition{text(name);Objects.requireNonNull(target);Objects.requireNonNull(action);Objects.requireNonNull(otherwise);}
    }
    public record Timer(int interval,int offset,int limit,Action action) implements Action {
        public Timer {if(interval<1||interval>3_600_000||offset< -3_600_000||offset>3_600_000||limit<0||limit>1_000_000)throw new IllegalArgumentException("Invalid scene timer");Objects.requireNonNull(action);}
    }
    public record ClearDialog(boolean activatorOnly) implements Action { }
    @FunctionalInterface public interface Variables {boolean set(SetVariable operation,int stamp,String source,String activator);}
    @FunctionalInterface public interface VariableReads {boolean get(String name,LogicTarget target,int stamp,String source,String activator);}
    @FunctionalInterface public interface DialogClears {boolean clear(boolean activatorOnly,int stamp,String source,String activator);}
    private Variables variables=(operation,stamp,source,activator)->false;
    private VariableReads variableReads=(name,target,stamp,source,activator)->false;
    private DialogClears dialogClears=(only,stamp,source,activator)->false;
    public void variables(Variables writes,VariableReads reads){variables=Objects.requireNonNull(writes);variableReads=Objects.requireNonNull(reads);}
    public void dialogClears(DialogClears sink){dialogClears=Objects.requireNonNull(sink);}
    /** A native effect's server action has a distinct, frozen source logic at its fire location. */
    public record GameplayEffect(StarterCatalog.Model config,Action action,String locationTag) implements Action {
        public GameplayEffect{Objects.requireNonNull(config);text(config.name());Objects.requireNonNull(action);Objects.requireNonNull(locationTag);if(!locationTag.isEmpty())text(locationTag);}
    }
    public record Impulse(StarterCatalog.Model config) implements Action {
        public Impulse{
            Objects.requireNonNull(config);
            if(!config.name().equals("ImpartImpulse")||!config.arguments().keySet().equals(Set.of("region","impulse","impulse-origin","flags","shape"))
                    ||!(config.arguments().get("region") instanceof FireCatalog.DataValue region)||!region.type().equals("com.threerings.tudey.config.RegionConfig$Default")
                    ||!(config.arguments().get("shape") instanceof FireCatalog.DataValue shape)||!shape.type().equals("com.threerings.tudey.shape.config.ShapeConfig$Point")
                    ||!(config.arguments().get("flags") instanceof Number flags)||flags.doubleValue()!=flags.intValue()||flags.intValue()<0||flags.intValue()>65535
                    ||!(config.arguments().get("impulse") instanceof FireCatalog.DataValue impulse)||!impulse.type().equals("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse"))throw new IllegalArgumentException("Unsupported scripted point impulse");
            vector(SceneDamage.field(impulse,"translation"));vector(config.arguments().get("impulse-origin"));
            bounded(impulse,"duration",3_600_000,true);bounded(impulse,"level",1_000_000,true);bounded(region,"expansion",1_000_000,false);
        }
        private static void vector(Object value){
            if(!(value instanceof FireCatalog.DataValue data)||!data.type().equals("com.threerings.math.Vector2f"))throw new IllegalArgumentException("Invalid impulse vector");
            for(String axis:List.of("x","y"))if(!(SceneDamage.field(data,axis) instanceof Number n)||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>1_000_000)throw new IllegalArgumentException("Invalid impulse vector component");
        }
        private static void bounded(Object data,String field,int limit,boolean integral){
            if(!(SceneDamage.field(data,field) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>limit||integral&&n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Invalid impulse "+field);
        }
    }
    @FunctionalInterface public interface GameplayEffects {boolean apply(GameplayEffect effect,int stamp,String source);}
    @FunctionalInterface public interface Impulses {boolean apply(Impulse impulse,int stamp,String source);}
    private GameplayEffects gameplayEffects=(effect,stamp,source)->false;
    private Impulses impulses=(impulse,stamp,source)->false;
    public void gameplayEffects(GameplayEffects sink){gameplayEffects=Objects.requireNonNull(sink);}
    public void impulses(Impulses sink){impulses=Objects.requireNonNull(sink);}
    public record NearestSignal(String tag,String name,int count,boolean excludeSource) implements Action {
        public NearestSignal{text(tag);text(name);if(count<0||count>256)throw new IllegalArgumentException("Invalid signal subset size");}
    }
    @FunctionalInterface public interface NearestSignals {boolean apply(NearestSignal signal,int stamp,String source);}
    private NearestSignals nearestSignals=(signal,stamp,source)->false;
    public void nearestSignals(NearestSignals sink){nearestSignals=Objects.requireNonNull(sink);}
    public record Objective(org.tortodrone.character.KnightObjective mutation) implements Action {public Objective{Objects.requireNonNull(mutation);}}
    @FunctionalInterface public interface Objectives {boolean apply(org.tortodrone.character.KnightObjective operation,int stamp,String source,String activator);}
    private Objectives objectives=(operation,stamp,source,activator)->false;
    public void objectives(Objectives sink){objectives=Objects.requireNonNull(sink);}
    public record Choice(List<Action> actions,List<Float> weights) implements Action {
        public Choice {
            actions=List.copyOf(actions);weights=List.copyOf(weights);
            if(actions.isEmpty()||actions.size()>256||actions.size()!=weights.size())throw new IllegalArgumentException("Invalid weighted scene choice");
            double total=0;for(float weight:weights){if(!Float.isFinite(weight)||weight<0)throw new IllegalArgumentException("Invalid scene weight");total+=weight;}
            if(total<=0)throw new IllegalArgumentException("Empty scene choice weight");
        }
    }
    /** The native KNIGHT memory target is its living Activator, never a client-selected knight. */
    public record Memory(org.tortodrone.character.KnightMemory mutation) implements Action {public Memory{Objects.requireNonNull(mutation);}}
    /** Native PartyBase target Activator; routing and settlement belong to its server-owned party. */
    public record NextFloor() implements Action { }
    public record Signal(String tag,String name) implements Action {
        public Signal { text(tag);text(name); }
    }
    public record Destroy(String tag) implements Action {
        public Destroy { text(tag); }
    }
    /** Compound native targets retain order/multiplicity and succeed if any actor was found. */
    public record DestroyMany(List<String> tags) implements Action {
        public DestroyMany { tags=List.copyOf(tags);if(tags.size()>256)throw new IllegalArgumentException("Too many destruction targets");tags.forEach(SceneActions::text); }
    }
    /** Prepared Monster, cosmetic Block or sourced destroy-on-hit Block at an authored location. */
    public record Spawn(Map<String,String> fields,boolean activate,String locationTag,boolean activatorLocation) implements Action {
        public Spawn(Map<String,String> fields,boolean activate,String locationTag){this(fields,activate,locationTag,false);}
        public Spawn(Map<String,String> fields,boolean activate){this(fields,activate,"");}
        public Spawn(Map<String,String> fields){this(fields,false);}
        public Spawn {
            Objects.requireNonNull(locationTag);if(!locationTag.isEmpty())text(locationTag);
            if(activatorLocation&&!locationTag.isEmpty())throw new IllegalArgumentException("Conflicting scene spawn locations");
            fields=Map.copyOf(fields);
            boolean monster="com.threerings.projectx.dungeon.data.actor.Monster".equals(fields.get("native-type"))&&"Monster".equals(fields.get("kind"));
            boolean block="com.threerings.projectx.dungeon.data.actor.Block".equals(fields.get("native-type"))&&"Block".equals(fields.get("kind"))&&!activate;
            boolean cosmetic=block&&"Global".equals(fields.get("shape"));
            boolean breakable=block&&"destroy".equals(fields.get("damage-action"));
            boolean platform="com.threerings.projectx.dungeon.data.actor.Switch".equals(fields.get("native-type"))&&"Switch".equals(fields.get("kind"))&&"party".equals(fields.get("switch-mode"))&&!activate;
            if(fields.size()>1024||!(monster||cosmetic||breakable||platform)||fields.getOrDefault("config","").isBlank())throw new IllegalArgumentException("Unsupported scene spawn actor");
            for(var field:fields.entrySet())if(field.getKey().endsWith(".chain-unsupported")||field.getKey().length()>256||field.getValue().length()>262144)throw new IllegalArgumentException("Unsupported or oversized spawn metadata");
            var spec=new SceneContent.Spec(fields);
            if(platform)ScenePartyPlatform.read(spec);
            var script=SceneMovementScript.read(spec);
            var program=SceneScript.read(spec);
            var targeting=SceneTargeting.read(spec);var wander=SceneWander.read(spec);
            var follow=SceneFollow.read(spec);if(follow!=null&&(!monster||targeting==null||targeting.monsterMask()==0||script!=null||program!=null||wander!=null))throw new IllegalArgumentException("Conflicting native follow routine");
            if(!SceneAttackMotion.readAll(spec).isEmpty()&&!monster)throw new IllegalArgumentException("Attack motion requires a monster actor");
            if((targeting!=null||wander!=null)&&(!monster||script!=null||program!=null))throw new IllegalArgumentException("Conflicting native targeting actor");
            if((script!=null||program!=null)&&!monster)throw new IllegalArgumentException("Movement script requires a monster actor");
            if(cosmetic&&(spec.integer("collision-flags",0)!=0||spec.integer("collision-mask",0)!=0||!spec.text("damage-action","").isEmpty()))throw new IllegalArgumentException("Cosmetic scene block can collide or react to damage");
            for(String key:fields.keySet())if(key.endsWith(".chain-next")){
                int slot=spec.integer(key,-1),rearm=spec.integer(key.substring(0,key.length()-10)+"chain-rearm",-1);
                if(slot<0||slot>255||!fields.containsKey("attack."+slot+".clear")||rearm<0||rearm>3_600_000)throw new IllegalArgumentException("Invalid prepared attack chain");
            }
            for(String key:List.of("health","speed","radius","width","height","bounds-min-x","bounds-min-y","bounds-max-x","bounds-max-y"))if(!Float.isFinite(spec.number(key,0)))throw new IllegalArgumentException("Non-finite spawn actor value");
            if(monster&&(spec.number("health",0)<0||spec.number("health",0)==0&&script==null&&program==null)||spec.number("radius",0)<0||spec.number("speed",0)<0)throw new IllegalArgumentException("Invalid spawn actor values");
            spec.config();
            int collision=spec.integer("collision-flags",0);spec.integer("activate-time",0);
            var model=spec.model("source-shape");
            if(collision!=0)SceneCollision.pawnShape(spec,model==null?null:model.arguments().get("value"));
        }
    }
    /** Analytics vocabulary only: no mapping to knight progress/completion flags is established. */
    public record ProgressEvent(String level,String progress) implements Action {
        public ProgressEvent { text(level);text(progress); }
    }
    /** Native InstanceOf(PawnLogic) status operations over this scene's entered knights. */
    public record PawnStatus(StarterCatalog.Model condition,FireCatalog.DataValue data,int clearMask) implements Action {
        public PawnStatus {
            if(condition==null){if(data!=null||clearMask<0)throw new IllegalArgumentException("Invalid pawn status clear");}
            else{
                if(data==null||clearMask!=0||!data.type().equals("com.threerings.projectx.dungeon.config.StatusConditionConfig$Stop"))throw new IllegalArgumentException("Unsupported scripted pawn status");
                float min=SceneDamage.number(data,"minDuration",0),max=SceneDamage.number(data,"maxDuration",0);
                if(!Float.isFinite(min)||!Float.isFinite(max)||min<0||max<min||max>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid scripted status duration");
            }
        }
        public boolean clear(){return condition==null;}
    }
    public record Conversation(String id,boolean activatorOnly) implements Action {
        public Conversation(String id){this(id,false);}
        public Conversation { text(id); }
    }
    /** Cosmetic native effect at Source (empty tag) or authored Tagged locations. */
    public record SourceEffect(StarterCatalog.Model config,String locationTag) implements Action {
        public SourceEffect(StarterCatalog.Model config){this(config,"");}
        public SourceEffect { Objects.requireNonNull(config);text(config.name());Objects.requireNonNull(locationTag);if(!locationTag.isEmpty())text(locationTag); }
    }
    public record Delay(int millis,int variance,Action action) implements Action {
        public Delay(int millis,Action action){this(millis,0,action);}
        public Delay { if(millis<0||millis>3_600_000||variance<0||variance>3_600_000)throw new IllegalArgumentException("Invalid scene action delay");Objects.requireNonNull(action); }
    }
    public record Sequence(List<Action> actions,boolean stopOnFailure) implements Action {
        public Sequence { actions=List.copyOf(actions);if(actions.size()>256)throw new IllegalArgumentException("Too many compound actions"); }
    }
    /** Native Conditional(Limit): each evaluation consumes one success, even if its action fails. */
    public record Limited(int limit,Action action,Action otherwise) implements Action {
        public Limited { if(limit<0||limit>1_000_000)throw new IllegalArgumentException("Invalid condition limit");Objects.requireNonNull(action);Objects.requireNonNull(otherwise); }
    }
    public record Rule(String key,String event,String tag,String name,boolean all,Action action,String stateKey,String source) {
        public Rule(String key,String event,String tag,String name,boolean all,Action action){this(key,event,tag,name,all,action,key,key);}
        public Rule {
            text(key);text(tag);text(stateKey);text(source);Objects.requireNonNull(action);
            if(!Set.of("removed","signal","signal-start","signal-stop").contains(event))throw new IllegalArgumentException("Unsupported scene handler");
            if(!event.equals("removed"))text(name);
        }
    }
    @FunctionalInterface public interface Conversations { boolean apply(Conversation operation,int stamp,String source,String activator); }
    @FunctionalInterface public interface Effects { boolean apply(SourceEffect operation,int stamp,String source); }
    @FunctionalInterface public interface FloorAdvances { boolean apply(int stamp,String source,String activator); }
    @FunctionalInterface public interface Memories { boolean apply(org.tortodrone.character.KnightMemory operation,int stamp,String source,String activator); }
    @FunctionalInterface public interface PawnStatuses { boolean apply(PawnStatus operation,int stamp,String source); }
    @FunctionalInterface public interface SpawnActors { boolean apply(Spawn actor,int stamp,String source); }
    @FunctionalInterface public interface SourcedSpawnActors { boolean apply(Spawn actor,int stamp,String source,String activator); }
    @FunctionalInterface public interface DestroyActors { boolean apply(String tag,int stamp,String source); }
    @FunctionalInterface public interface Signals { boolean apply(String tag,String name,int stamp); }
    @FunctionalInterface public interface SourcedSignals { boolean apply(String tag,String name,int stamp,String source); }
    private static SourcedSpawnActors sourced(SpawnActors spawn){Objects.requireNonNull(spawn);return (actor,stamp,source,activator)->spawn.apply(actor,stamp,source);}
    private static final class Node {
        final Action action;final List<Node> children;int remaining;boolean started;
        Node(Action action){
            this.action=action;
            children=switch(action){
                case Delay delay->List.of(new Node(delay.action()));
                case Sequence sequence->sequence.actions().stream().map(Node::new).toList();
                case Choice choice->choice.actions().stream().map(Node::new).toList();
                case Limited limited->{remaining=limited.limit();yield List.of(new Node(limited.action()),new Node(limited.otherwise()));}
                case VariableCondition condition->List.of(new Node(condition.action()),new Node(condition.otherwise()));
                case Timer timer->{remaining=timer.limit();yield List.of(new Node(timer.action()));}
                case Targeted targeted->List.of(new Node(targeted.action()));
                default->List.of();
            };
        }
    }
    /** A native script step retains its action's condition counters across Goto loops. */
    public final class Prepared {
        private final Node root;
        private Prepared(Action action){root=new Node(action);}
        private SceneActions owner(){return SceneActions.this;}
    }
    public Prepared prepare(Action action){validate(action,0,new int[]{0});return new Prepared(action);}
    public boolean perform(Prepared prepared,int stamp,String source,String activator){
        text(source);text(activator);if(prepared.owner()!=this)throw new IllegalArgumentException("Foreign scene action");return enqueue(prepared.root,stamp,source,activator);
    }
    private static final class State {
        final Rule definition;final Node root;
        final Map<String,Boolean> active=new LinkedHashMap<>();
        State(Rule rule){definition=rule;root=new Node(rule.action());}
    }
    private record Compiled(Rule rule,State state) { }
    private record Pending(long due,long order,Node node,String source,String activator) { }
    private static final int MAX_PENDING=4096,MAX_PER_TICK=1024;
    private final List<Compiled> rules;
    private final List<State> states;
    private final SourcedSignals signals;
    private final DestroyActors destroy;
    private final SourcedSpawnActors spawn;
    private final PawnStatuses pawnStatuses;
    private final Conversations conversations;
    private final Effects effects;
    private final FloorAdvances floorAdvances;
    private final Memories memories;
    private final java.util.random.RandomGenerator random;
    private final PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingLong(Pending::due).thenComparingLong(Pending::order));
    private long order,rejected;
    private int activeSources;
    private boolean closed,advanced;
    private int lastAdvanced;
    public SceneActions(List<Rule> rules,Signals signals){
        this(rules,adapt(signals));
    }
    private static SourcedSignals adapt(Signals signals){Objects.requireNonNull(signals);return (tag,name,stamp,source)->signals.apply(tag,name,stamp);}
    public SceneActions(List<Rule> rules,SourcedSignals signals){this(rules,signals,new Random());}
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random){this(rules,signals,random,(tag,stamp,source)->false);}
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy){this(rules,signals,random,destroy,(actor,stamp,source)->false);}
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SpawnActors spawn){this(rules,signals,random,destroy,spawn,(operation,stamp,source)->false);}
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SpawnActors spawn,PawnStatuses pawnStatuses){this(rules,signals,random,destroy,spawn,pawnStatuses,(operation,stamp,source,activator)->false);}
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SpawnActors spawn,PawnStatuses pawnStatuses,Conversations conversations){
        this(rules,signals,random,destroy,spawn,pawnStatuses,conversations,(operation,stamp,source)->false);
    }
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SpawnActors spawn,PawnStatuses pawnStatuses,Conversations conversations,Effects effects){
        this(rules,signals,random,destroy,sourced(spawn),pawnStatuses,conversations,effects);
    }
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SourcedSpawnActors spawn,PawnStatuses pawnStatuses,Conversations conversations,Effects effects){
        this(rules,signals,random,destroy,spawn,pawnStatuses,conversations,effects,(stamp,source,activator)->false);
    }
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SourcedSpawnActors spawn,PawnStatuses pawnStatuses,Conversations conversations,Effects effects,FloorAdvances floorAdvances){
        this(rules,signals,random,destroy,spawn,pawnStatuses,conversations,effects,floorAdvances,(operation,stamp,source,activator)->false);
    }
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy,SourcedSpawnActors spawn,PawnStatuses pawnStatuses,Conversations conversations,Effects effects,FloorAdvances floorAdvances,Memories memories){
        this.memories=Objects.requireNonNull(memories);
        this.floorAdvances=Objects.requireNonNull(floorAdvances);
        this.effects=Objects.requireNonNull(effects);
        this.conversations=Objects.requireNonNull(conversations);
        this.pawnStatuses=Objects.requireNonNull(pawnStatuses);
        this.spawn=Objects.requireNonNull(spawn);
        this.destroy=Objects.requireNonNull(destroy);
        this.random=Objects.requireNonNull(random);
        this.signals=Objects.requireNonNull(signals);
        if(rules.size()>2048)throw new IllegalArgumentException("Too many scene handlers");
        var keys=new HashSet<String>();for(var rule:rules){if(!keys.add(rule.key()))throw new IllegalArgumentException("Duplicate scene handler");validate(rule.action(),0,new int[]{0});}
        var shared=new LinkedHashMap<String,State>();var compiled=new ArrayList<Compiled>();
        for(var rule:rules){
            var state=shared.computeIfAbsent(rule.stateKey(),key->new State(rule));var original=state.definition;
            if(!original.event().equals(rule.event())||!original.name().equals(rule.name())||original.all()!=rule.all()
                    ||!original.source().equals(rule.source())||!original.action().equals(rule.action()))throw new IllegalArgumentException("Conflicting scene handler aliases");
            compiled.add(new Compiled(rule,state));
        }
        this.rules=List.copyOf(compiled);states=List.copyOf(shared.values());
    }
    public void removed(Set<String> tags,java.util.function.Predicate<String> remaining,int stamp){removed(tags,remaining,stamp,"removed");}
    public void removed(Set<String> tags,java.util.function.Predicate<String> remaining,int stamp,String activator){
        if(closed)return;
        for(var compiled:rules){var rule=compiled.rule();if(rule.event().equals("removed")&&tags.contains(rule.tag())&&(!rule.all()||!remaining.test(rule.tag())))enqueue(compiled.state().root,stamp,rule.source(),activator);}
    }
    public void signal(String tag,String name,int stamp){
        signal(tag,name,stamp,"external");
    }
    public void signal(String tag,String name,int stamp,String source){
        signalTo(tag,name,stamp,source,null);
    }
    /** Native subset selection addresses a receiver logic, not every logic sharing its tag. */
    public void signalTo(String tag,String name,int stamp,String source,String receiver){
        if(closed)return;
        text(source);
        for(var compiled:rules){var rule=compiled.rule();var state=compiled.state();
            if(rule.event().equals("removed")||!rule.tag().equals(tag)||!rule.name().equals(name)||receiver!=null&&!rule.source().equals(receiver))continue;
            if(rule.event().equals("signal")){enqueue(state.root,stamp,rule.source(),source);continue;}
            boolean already=state.active.containsKey(source);
            if(!already&&activeSources>=MAX_PENDING){rejected++;continue;}
            if(!already)activeSources++;
            state.active.put(source,true);
            if(!already&&rule.event().equals("signal-start"))enqueue(state.root,stamp,rule.source(),source);
        }
    }
    /** Signal(Source) addresses each of the receiver's handlers once, regardless of tag aliases. */
    public void signalSource(String receiver,String name,int stamp,String source){
        if(closed)return;text(receiver);text(name);text(source);
        for(var state:states){var rule=state.definition;
            if(rule.event().equals("removed")||!rule.source().equals(receiver)||!rule.name().equals(name))continue;
            if(rule.event().equals("signal")){enqueue(state.root,stamp,rule.source(),source);continue;}
            boolean already=state.active.containsKey(source);
            if(!already&&activeSources>=MAX_PENDING){rejected++;continue;}
            if(!already)activeSources++;state.active.put(source,true);
            if(!already&&rule.event().equals("signal-start"))enqueue(state.root,stamp,rule.source(),source);
        }
    }
    /** Equal-time actions retain authored order; overdue work executes on the current scene tick. */
    public void advance(int stamp){
        if(closed)return;
        if(advanced&&stamp<lastAdvanced)throw new IllegalArgumentException("Scene action clock moved backwards");
        int[] budget={MAX_PER_TICK};
        drain(stamp,budget);
        if(!advanced||stamp!=lastAdvanced){
            for(var state:states)for(var iterator=state.active.entrySet().iterator();iterator.hasNext();){
                var entry=iterator.next();
                if(entry.getValue())entry.setValue(false);
                else{iterator.remove();activeSources--;if(state.definition.event().equals("signal-stop"))enqueue(state.root,stamp,state.definition.source(),entry.getKey());}
            }
            advanced=true;lastAdvanced=stamp;
            drain(stamp,budget);
        }
    }
    private void drain(int stamp,int[] budget){
        while(!pending.isEmpty()&&pending.peek().due()<=stamp&&budget[0]>0){
            var task=pending.remove();execute(task.node(),stamp,task.source(),task.activator(),budget);
        }
    }
    private boolean execute(Node node,long due,String source,String activator,int[] budget){
        if(--budget[0]<0){rejected++;return false;}
        return switch(node.action){
            case Signal signal -> signals.apply(signal.tag(),signal.name(),Math.toIntExact(due),source);
            case SourceSignal signal -> sourceSignals.signal(signal.name(),Math.toIntExact(due),source);
            case Failure ignored -> false;
            case DefaultEntrance entrance -> entrances.set(entrance.target(),Math.toIntExact(due),source,activator);
            case Targeted targeted -> {
                var targets=targetResolution.resolve(targeted.target(),Math.toIntExact(due),source,activator);
                if(targets.size()>256){rejected++;yield false;}
                // TargetedAction executes in target order, ignores each child's result
                // and succeeds even when its target set is empty (native ActionLogic).
                for(String target:targets){text(target);execute(node.children.getFirst(),due,source,target,budget);}
                yield true;
            }
            case NearestSignal signal -> nearestSignals.apply(signal,Math.toIntExact(due),source);
            case Conversation conversation -> conversations.apply(conversation,Math.toIntExact(due),source,activator);
            case ClearDialog clear -> dialogClears.clear(clear.activatorOnly(),Math.toIntExact(due),source,activator);
            case SetVariable variable -> variables.set(variable,Math.toIntExact(due),source,activator);
            case VariableCondition condition -> execute(node.children.get(variableReads.get(condition.name(),condition.target(),Math.toIntExact(due),source,activator)?0:1),due,source,activator,budget);
            case Timer timer -> {
                if(!node.started){node.started=true;yield enqueue(node,Math.addExact(due,Math.max(0,timer.interval()+timer.offset())),source,activator);}
                boolean success=execute(node.children.getFirst(),due,source,activator,budget);
                if(timer.limit()==0||--node.remaining>0)success=enqueue(node,Math.addExact(due,timer.interval()),source,activator)&&success;
                yield success;
            }
            case SourceEffect effect -> effects.apply(effect,Math.toIntExact(due),source);
            case GameplayEffect effect -> gameplayEffects.apply(effect,Math.toIntExact(due),source);
            case Impulse impulse -> impulses.apply(impulse,Math.toIntExact(due),source);
            case PawnStatus status -> pawnStatuses.apply(status,Math.toIntExact(due),source);
            case Spawn actor -> spawn.apply(actor,Math.toIntExact(due),source,activator);
            case ProgressEvent event -> {
                System.getLogger(SceneActions.class.getName()).log(System.Logger.Level.DEBUG,"Scene analytics: level={0}, progress={1}, stamp={2}, source={3}",event.level(),event.progress(),due,source);
                yield true;
            }
            case NextFloor ignored -> floorAdvances.apply(Math.toIntExact(due),source,activator);
            case Memory memory -> memories.apply(memory.mutation(),Math.toIntExact(due),source,activator);
            case Objective objective -> objectives.apply(objective.mutation(),Math.toIntExact(due),source,activator);
            case Destroy destruction -> destroy.apply(destruction.tag(),Math.toIntExact(due),source);
            case DestroySource ignored -> sourceDestruction.destroy(Math.toIntExact(due),source);
            case DestroyMany destruction -> {
                boolean found=false;for(String tag:destruction.tags())found=destroy.apply(tag,Math.toIntExact(due),source)|found;
                yield found;
            }
            case Delay delay -> enqueue(node.children.getFirst(),Math.addExact(due,delay.variance()==0?delay.millis():Math.max(0,delay.millis()+random.nextInt(delay.variance()*2)-delay.variance())),source,activator);
            case Sequence sequence -> {
                boolean success=true;
                for(var child:node.children)if(!execute(child,due,source,activator,budget)){success=false;if(sequence.stopOnFailure())break;}
                yield success;
            }
            case Limited limited -> {
                boolean allowed=node.remaining>0;
                if(allowed)node.remaining--;
                yield execute(node.children.get(allowed?0:1),due,source,activator,budget);
            }
            case Choice choice -> {
                double total=choice.weights().stream().mapToDouble(Float::doubleValue).sum(),draw=random.nextDouble(total);
                int selected=-1;for(int i=0;i<choice.weights().size();i++)if(choice.weights().get(i)>0){selected=i;draw-=choice.weights().get(i);if(draw<0)break;}
                yield execute(node.children.get(selected),due,source,activator,budget);
            }
        };
    }
    private boolean enqueue(Node node,long due,String source,String activator){
        if(closed||pending.size()>=MAX_PENDING||due>Integer.MAX_VALUE){rejected++;return false;}
        pending.add(new Pending(due,order++,node,source,activator));return true;
    }
    /** Only server-authored conversation responses enqueue standalone graphs. */
    public boolean perform(Action action,int stamp,String source){return perform(action,stamp,source,source);}
    public boolean perform(Action action,int stamp,String source,String activator){
        text(source);text(activator);validate(action,0,new int[]{0});return enqueue(new Node(action),stamp,source,activator);
    }
    public int pending(){return pending.size();}
    public void cancelSource(String source){
        text(source);pending.removeIf(task->task.source().equals(source));
        for(var state:states)if(state.active.remove(source)!=null)activeSources--;
    }
    public static Set<String> conversationIds(Action action){
        validate(action,0,new int[]{0});var ids=new LinkedHashSet<String>();conversationIds(action,ids);return Set.copyOf(ids);
    }
    private static void conversationIds(Action action,Set<String> ids){
        switch(action){
            case Conversation conversation->ids.add(conversation.id());
            case Delay delay->conversationIds(delay.action(),ids);
            case GameplayEffect effect->conversationIds(effect.action(),ids);
            case Sequence sequence->{for(var child:sequence.actions())conversationIds(child,ids);}
            case Choice choice->{for(var child:choice.actions())conversationIds(child,ids);}
            case Limited limited->{conversationIds(limited.action(),ids);conversationIds(limited.otherwise(),ids);}
            case VariableCondition condition->{conversationIds(condition.action(),ids);conversationIds(condition.otherwise(),ids);}
            case Timer timer->conversationIds(timer.action(),ids);
            case Targeted targeted->conversationIds(targeted.action(),ids);
            case Spawn spawn->{try{ids.addAll(SceneScript.conversations(new SceneContent.Spec(spawn.fields())));}catch(IOException invalid){throw new IllegalArgumentException("Invalid spawned actor dialogue",invalid);}}
            default->{}
        }
    }
    public long rejected(){return rejected;}
    public Set<String> workSources(){
        var result=new HashSet<String>();if(closed)return result;
        for(var p:pending){result.add(p.source());result.add(p.activator());}for(var state:states)result.addAll(state.active.keySet());return result;
    }
    public void close(){closed=true;pending.clear();for(var state:states)state.active.clear();activeSources=0;}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid scene action name");}
    private static void validate(Action action,int depth,int[] count){
        if(depth>16||++count[0]>1024)throw new IllegalArgumentException("Scene action graph exceeds limit");
        switch(action){case Targeted targeted->validate(targeted.action(),depth+1,count);case Timer timer->validate(timer.action(),depth+1,count);case VariableCondition condition->{validate(condition.action(),depth+1,count);validate(condition.otherwise(),depth+1,count);}case Delay delay->validate(delay.action(),depth+1,count);case GameplayEffect effect->validate(effect.action(),depth+1,count);case Sequence sequence->{for(var child:sequence.actions())validate(child,depth+1,count);}case Choice choice->{for(var child:choice.actions())validate(child,depth+1,count);}case Limited limited->{validate(limited.action(),depth+1,count);validate(limited.otherwise(),depth+1,count);}default->{}}
    }
    public static Action parse(String json)throws IOException {
        try{
            if(json.length()>4_000_000)throw new IllegalArgumentException("Scene action metadata exceeds limit");
            var action=decode(Json.parse(json,32,1024,262144),0,new int[]{0});validate(action,0,new int[]{0});return action;
        }
        catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid scene action graph",invalid);}
    }
    private static Action decode(Object value,int depth,int[] count)throws IOException{
        if(depth>16||++count[0]>1024||!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid scene action graph");
        Object kind=map.get("kind");
        if("failure".equals(kind)&&map.keySet().equals(Set.of("kind")))return new Failure();
        if("source-signal".equals(kind)&&map.keySet().equals(Set.of("kind","name")))return new SourceSignal(tag(map.get("name")));
        if("default-entrance".equals(kind)&&map.keySet().equals(Set.of("kind","target")))return new DefaultEntrance(logicTarget(map.get("target")));
        if("targeted".equals(kind)&&map.keySet().equals(Set.of("kind","target","action")))return new Targeted(logicTarget(map.get("target")),decode(map.get("action"),depth+1,count));
        if("destroy-source".equals(kind)&&map.keySet().equals(Set.of("kind")))return new DestroySource();
        if("clear-dialog".equals(kind)&&map.keySet().equals(Set.of("kind","activator"))&&map.get("activator") instanceof Boolean only)return new ClearDialog(only);
        if("set-variable".equals(kind)&&map.keySet().equals(Set.of("kind","name","value","target"))&&map.get("value") instanceof Boolean bool)return new SetVariable(tag(map.get("name")),bool,logicTarget(map.get("target")));
        if("variable-condition".equals(kind)&&map.keySet().equals(Set.of("kind","name","target","action","otherwise")))return new VariableCondition(tag(map.get("name")),logicTarget(map.get("target")),decode(map.get("action"),depth+1,count),decode(map.get("otherwise"),depth+1,count));
        if("timer".equals(kind)&&map.keySet().equals(Set.of("kind","interval","offset","limit","action")))return new Timer(integer(map.get("interval")),integer(map.get("offset")),integer(map.get("limit")),decode(map.get("action"),depth+1,count));
        if("gameplay-effect".equals(kind)&&map.keySet().equals(Set.of("kind","config","action","tag")))return new GameplayEffect(FireCatalog.modelJson(string(map.get("config"))),decode(map.get("action"),depth+1,count),string(map.get("tag")));
        if("source-impulse".equals(kind)&&map.keySet().equals(Set.of("kind","config")))return new Impulse(FireCatalog.modelJson(string(map.get("config"))));
        if("nearest-signal".equals(kind)&&map.keySet().equals(Set.of("kind","tag","name","count","excludeSource"))&&map.get("count") instanceof Number number&&map.get("excludeSource") instanceof Boolean exclude){
            long size=number.longValue();if(number.doubleValue()!=size)throw new IllegalArgumentException("Fractional target count");return new NearestSignal(tag(map.get("tag")),tag(map.get("name")),Math.toIntExact(size),exclude);
        }
        if("weighted-choice".equals(kind)&&map.keySet().equals(Set.of("kind","actions","weights"))&&map.get("actions") instanceof List<?> actions&&map.get("weights") instanceof List<?> weights){
            var children=new ArrayList<Action>();var numbers=new ArrayList<Float>();for(var child:actions)children.add(decode(child,depth+1,count));
            for(var weight:weights){if(!(weight instanceof Number number))throw new IllegalArgumentException("Invalid weight");numbers.add(number.floatValue());}
            return new Choice(children,numbers);
        }
        if("next-floor".equals(kind)&&map.keySet().equals(Set.of("kind")))return new NextFloor();
        if("knight-objective".equals(kind)&&map.keySet().equals(Set.of("kind","name","text")))return new Objective(new org.tortodrone.character.KnightObjective(string(map.get("name")),map.get("text")==null?null:string(map.get("text"))));
        if("knight-memory".equals(kind)&&map.keySet().equals(Set.of("kind","name","remember"))&&map.get("remember") instanceof Boolean remember)return new Memory(new org.tortodrone.character.KnightMemory(string(map.get("name")),remember));
        if("progress-event".equals(kind)&&map.keySet().equals(Set.of("kind","level","progress")))return new ProgressEvent(string(map.get("level")),string(map.get("progress")));
        if("source-effect".equals(kind)&&map.keySet().equals(Set.of("kind","config")))return new SourceEffect(FireCatalog.modelJson(string(map.get("config"))));
        if("tagged-effect".equals(kind)&&map.keySet().equals(Set.of("kind","config","tag")))return new SourceEffect(FireCatalog.modelJson(string(map.get("config"))),tag(map.get("tag")));
        if("conversation".equals(kind)&&map.keySet().equals(Set.of("kind","id","activator"))&&map.get("activator") instanceof Boolean target)return new Conversation(string(map.get("id")),target);
        if("pawn-status".equals(kind)&&map.keySet().equals(Set.of("kind","config"))){
            var model=FireCatalog.modelJson(string(map.get("config")));
            if(!(model.arguments().get("reference") instanceof StarterCatalog.Model reference)||!(model.arguments().get("data") instanceof FireCatalog.DataValue data))throw new IllegalArgumentException("Incomplete scripted pawn status");
            return new PawnStatus(reference,data,0);
        }
        if("pawn-clear".equals(kind)&&map.keySet().equals(Set.of("kind","mask"))&&map.get("mask") instanceof Number mask){
            if(mask.doubleValue()!=mask.longValue())throw new IllegalArgumentException("Invalid status mask");
            return new PawnStatus(null,null,Math.toIntExact(mask.longValue()));
        }
        if("spawn".equals(kind)&&(map.keySet().equals(Set.of("kind","fields","activate"))||map.keySet().equals(Set.of("kind","fields","activate","tag"))||map.keySet().equals(Set.of("kind","fields","activate","activator"))&&Boolean.TRUE.equals(map.get("activator")))&&map.get("activate") instanceof Boolean activate&&map.get("fields") instanceof Map<?,?> raw){
            var fields=new HashMap<String,String>();for(var entry:raw.entrySet())fields.put(string(entry.getKey()),string(entry.getValue()));return new Spawn(fields,activate,map.containsKey("tag")?tag(map.get("tag")):"",Boolean.TRUE.equals(map.get("activator")));
        }
        if("destroy-many".equals(kind)&&map.keySet().equals(Set.of("kind","tags"))&&map.get("tags") instanceof List<?> tags)return new DestroyMany(tags.stream().map(SceneActions::string).toList());
        if("destroy".equals(kind)&&map.keySet().equals(Set.of("kind","tag")))return new Destroy(string(map.get("tag")));
        if("signal".equals(kind)&&map.keySet().equals(Set.of("kind","tag","name")))return new Signal(string(map.get("tag")),string(map.get("name")));
        if("delay".equals(kind)&&(map.keySet().equals(Set.of("kind","millis","action"))||map.keySet().equals(Set.of("kind","millis","variance","action")))&&map.get("millis") instanceof Number number){
            long millis=number.longValue();if(number.doubleValue()!=millis)throw new IllegalArgumentException("Fractional delay");
            int variance=0;
            if(map.containsKey("variance")){
                if(!(map.get("variance") instanceof Number v)||v.doubleValue()!=v.longValue())throw new IllegalArgumentException("Invalid delay variance");
                variance=Math.toIntExact(((Number)map.get("variance")).longValue());
            }
            return new Delay(Math.toIntExact(millis),variance,decode(map.get("action"),depth+1,count));
        }
        if("sequence".equals(kind)&&map.keySet().equals(Set.of("kind","stopOnFailure","actions"))&&map.get("stopOnFailure") instanceof Boolean stop&&map.get("actions") instanceof List<?> list){
            var actions=new ArrayList<Action>();for(var child:list)actions.add(decode(child,depth+1,count));return new Sequence(actions,stop);
        }
        if("limited".equals(kind)&&map.keySet().equals(Set.of("kind","limit","action","otherwise"))&&map.get("limit") instanceof Number number){
            long limit=number.longValue();if(number.doubleValue()!=limit)throw new IllegalArgumentException("Fractional condition limit");
            return new Limited(Math.toIntExact(limit),decode(map.get("action"),depth+1,count),decode(map.get("otherwise"),depth+1,count));
        }
        throw new IllegalArgumentException("Unsupported scene action or fields");
    }
    private static String string(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("Expected scene action text");return text;}
    private static int integer(Object value){if(!(value instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Expected scene action integer");return Math.toIntExact(n.longValue());}
    private static LogicTarget logicTarget(Object value){if(!(value instanceof Map<?,?> target)||!target.keySet().equals(Set.of("mode","tag")))throw new IllegalArgumentException("Invalid logic target");return new LogicTarget(string(target.get("mode")),string(target.get("tag")));}
    private static String tag(Object value){String result=string(value);text(result);return result;}
    public static Rule rule(SceneContent.Entry entry)throws IOException {
        var spec=entry.spec();
        if(!Set.of("true","false").contains(spec.text("all","false")))throw new IOException("Invalid actor-removal all flag");
        String key=entry.key()+":"+spec.text("handler-index","");
        return new Rule(key,spec.text("event",""),spec.text("watch-tag",""),spec.text("signal",""),spec.flag("all",false),parse(spec.text("action","")),spec.text("state-key",key),"entry:"+entry.key());
    }
}
