package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;
import org.tortodrone.character.StarterCatalog;

/** Allowlisted scene handlers. Owned and advanced by one SceneEntities simulation, never a timer. */
public final class SceneActions {
    public sealed interface Action permits Signal,Destroy,DestroyMany,Spawn,PawnStatus,Conversation,SourceEffect,ProgressEvent,NextFloor,Memory,Delay,Sequence,Limited,Choice { }
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
            if(fields.size()>1024||!(monster||cosmetic||breakable)||fields.getOrDefault("config","").isBlank())throw new IllegalArgumentException("Unsupported scene spawn actor");
            for(var field:fields.entrySet())if(field.getKey().endsWith(".chain-unsupported")||field.getKey().length()>256||field.getValue().length()>262144)throw new IllegalArgumentException("Unsupported or oversized spawn metadata");
            var spec=new SceneContent.Spec(fields);
            var script=SceneMovementScript.read(spec);
            if(script!=null&&!monster)throw new IllegalArgumentException("Movement script requires a monster actor");
            if(cosmetic&&(spec.integer("collision-flags",0)!=0||spec.integer("collision-mask",0)!=0||!spec.text("damage-action","").isEmpty()))throw new IllegalArgumentException("Cosmetic scene block can collide or react to damage");
            for(String key:fields.keySet())if(key.endsWith(".chain-next")){
                int slot=spec.integer(key,-1),rearm=spec.integer(key.substring(0,key.length()-10)+"chain-rearm",-1);
                if(slot<0||slot>255||!fields.containsKey("attack."+slot+".clear")||rearm<0||rearm>3_600_000)throw new IllegalArgumentException("Invalid prepared attack chain");
            }
            for(String key:List.of("health","speed","radius","width","height","bounds-min-x","bounds-min-y","bounds-max-x","bounds-max-y"))if(!Float.isFinite(spec.number(key,0)))throw new IllegalArgumentException("Non-finite spawn actor value");
            if(monster&&(spec.number("health",0)<0||spec.number("health",0)==0&&script==null)||spec.number("radius",0)<0||spec.number("speed",0)<0)throw new IllegalArgumentException("Invalid spawn actor values");
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
        final Action action;final List<Node> children;int remaining;
        Node(Action action){
            this.action=action;
            children=switch(action){
                case Delay delay->List.of(new Node(delay.action()));
                case Sequence sequence->sequence.actions().stream().map(Node::new).toList();
                case Choice choice->choice.actions().stream().map(Node::new).toList();
                case Limited limited->{remaining=limited.limit();yield List.of(new Node(limited.action()),new Node(limited.otherwise()));}
                default->List.of();
            };
        }
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
        if(closed)return;
        text(source);
        for(var compiled:rules){var rule=compiled.rule();var state=compiled.state();
            if(rule.event().equals("removed")||!rule.tag().equals(tag)||!rule.name().equals(name))continue;
            if(rule.event().equals("signal")){enqueue(state.root,stamp,rule.source(),source);continue;}
            boolean already=state.active.containsKey(source);
            if(!already&&activeSources>=MAX_PENDING){rejected++;continue;}
            if(!already)activeSources++;
            state.active.put(source,true);
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
            case Conversation conversation -> conversations.apply(conversation,Math.toIntExact(due),source,activator);
            case SourceEffect effect -> effects.apply(effect,Math.toIntExact(due),source);
            case PawnStatus status -> pawnStatuses.apply(status,Math.toIntExact(due),source);
            case Spawn actor -> spawn.apply(actor,Math.toIntExact(due),source,activator);
            case ProgressEvent event -> {
                System.getLogger(SceneActions.class.getName()).log(System.Logger.Level.DEBUG,"Scene analytics: level={0}, progress={1}, stamp={2}, source={3}",event.level(),event.progress(),due,source);
                yield true;
            }
            case NextFloor ignored -> floorAdvances.apply(Math.toIntExact(due),source,activator);
            case Memory memory -> memories.apply(memory.mutation(),Math.toIntExact(due),source,activator);
            case Destroy destruction -> destroy.apply(destruction.tag(),Math.toIntExact(due),source);
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
    public static Set<String> conversationIds(Action action){
        validate(action,0,new int[]{0});var ids=new LinkedHashSet<String>();conversationIds(action,ids);return Set.copyOf(ids);
    }
    private static void conversationIds(Action action,Set<String> ids){
        switch(action){
            case Conversation conversation->ids.add(conversation.id());
            case Delay delay->conversationIds(delay.action(),ids);
            case Sequence sequence->{for(var child:sequence.actions())conversationIds(child,ids);}
            case Choice choice->{for(var child:choice.actions())conversationIds(child,ids);}
            case Limited limited->{conversationIds(limited.action(),ids);conversationIds(limited.otherwise(),ids);}
            default->{}
        }
    }
    public long rejected(){return rejected;}
    public void close(){closed=true;pending.clear();for(var state:states)state.active.clear();activeSources=0;}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid scene action name");}
    private static void validate(Action action,int depth,int[] count){
        if(depth>16||++count[0]>1024)throw new IllegalArgumentException("Scene action graph exceeds limit");
        switch(action){case Delay delay->validate(delay.action(),depth+1,count);case Sequence sequence->{for(var child:sequence.actions())validate(child,depth+1,count);}case Choice choice->{for(var child:choice.actions())validate(child,depth+1,count);}case Limited limited->{validate(limited.action(),depth+1,count);validate(limited.otherwise(),depth+1,count);}default->{}}
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
        if("weighted-choice".equals(kind)&&map.keySet().equals(Set.of("kind","actions","weights"))&&map.get("actions") instanceof List<?> actions&&map.get("weights") instanceof List<?> weights){
            var children=new ArrayList<Action>();var numbers=new ArrayList<Float>();for(var child:actions)children.add(decode(child,depth+1,count));
            for(var weight:weights){if(!(weight instanceof Number number))throw new IllegalArgumentException("Invalid weight");numbers.add(number.floatValue());}
            return new Choice(children,numbers);
        }
        if("next-floor".equals(kind)&&map.keySet().equals(Set.of("kind")))return new NextFloor();
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
    private static String tag(Object value){String result=string(value);text(result);return result;}
    public static Rule rule(SceneContent.Entry entry)throws IOException {
        var spec=entry.spec();
        if(!Set.of("true","false").contains(spec.text("all","false")))throw new IOException("Invalid actor-removal all flag");
        String key=entry.key()+":"+spec.text("handler-index","");
        return new Rule(key,spec.text("event",""),spec.text("watch-tag",""),spec.text("signal",""),spec.flag("all",false),parse(spec.text("action","")),spec.text("state-key",key),"entry:"+entry.key());
    }
}
