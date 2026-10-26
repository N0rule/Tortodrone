package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;

/** Allowlisted scene handlers. Owned and advanced by one SceneEntities simulation, never a timer. */
public final class SceneActions {
    public sealed interface Action permits Signal,Destroy,Delay,Sequence,Limited { }
    public record Signal(String tag,String name) implements Action {
        public Signal { text(tag);text(name); }
    }
    public record Destroy(String tag) implements Action {
        public Destroy { text(tag); }
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
    @FunctionalInterface public interface DestroyActors { boolean apply(String tag,int stamp,String source); }
    @FunctionalInterface public interface Signals { boolean apply(String tag,String name,int stamp); }
    @FunctionalInterface public interface SourcedSignals { boolean apply(String tag,String name,int stamp,String source); }
    private static final class Node {
        final Action action;final List<Node> children;int remaining;
        Node(Action action){
            this.action=action;
            children=switch(action){
                case Delay delay->List.of(new Node(delay.action()));
                case Sequence sequence->sequence.actions().stream().map(Node::new).toList();
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
    private record Pending(long due,long order,Node node,String source) { }
    private static final int MAX_PENDING=4096,MAX_PER_TICK=1024;
    private final List<Compiled> rules;
    private final List<State> states;
    private final SourcedSignals signals;
    private final DestroyActors destroy;
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
    public SceneActions(List<Rule> rules,SourcedSignals signals,java.util.random.RandomGenerator random,DestroyActors destroy){
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
    public void removed(Set<String> tags,java.util.function.Predicate<String> remaining,int stamp){
        if(closed)return;
        for(var compiled:rules){var rule=compiled.rule();if(rule.event().equals("removed")&&tags.contains(rule.tag())&&(!rule.all()||!remaining.test(rule.tag())))enqueue(compiled.state().root,stamp,rule.source());}
    }
    public void signal(String tag,String name,int stamp){
        signal(tag,name,stamp,"external");
    }
    public void signal(String tag,String name,int stamp,String source){
        if(closed)return;
        text(source);
        for(var compiled:rules){var rule=compiled.rule();var state=compiled.state();
            if(rule.event().equals("removed")||!rule.tag().equals(tag)||!rule.name().equals(name))continue;
            if(rule.event().equals("signal")){enqueue(state.root,stamp,rule.source());continue;}
            boolean already=state.active.containsKey(source);
            if(!already&&activeSources>=MAX_PENDING){rejected++;continue;}
            if(!already)activeSources++;
            state.active.put(source,true);
            if(!already&&rule.event().equals("signal-start"))enqueue(state.root,stamp,rule.source());
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
                else{iterator.remove();activeSources--;if(state.definition.event().equals("signal-stop"))enqueue(state.root,stamp,state.definition.source());}
            }
            advanced=true;lastAdvanced=stamp;
            drain(stamp,budget);
        }
    }
    private void drain(int stamp,int[] budget){
        while(!pending.isEmpty()&&pending.peek().due()<=stamp&&budget[0]>0){
            var task=pending.remove();execute(task.node(),stamp,task.source(),budget);
        }
    }
    private boolean execute(Node node,long due,String source,int[] budget){
        if(--budget[0]<0){rejected++;return false;}
        return switch(node.action){
            case Signal signal -> signals.apply(signal.tag(),signal.name(),Math.toIntExact(due),source);
            case Destroy destruction -> destroy.apply(destruction.tag(),Math.toIntExact(due),source);
            case Delay delay -> enqueue(node.children.getFirst(),Math.addExact(due,delay.variance()==0?delay.millis():Math.max(0,delay.millis()+random.nextInt(delay.variance()*2)-delay.variance())),source);
            case Sequence sequence -> {
                boolean success=true;
                for(var child:node.children)if(!execute(child,due,source,budget)){success=false;if(sequence.stopOnFailure())break;}
                yield success;
            }
            case Limited limited -> {
                boolean allowed=node.remaining>0;
                if(allowed)node.remaining--;
                yield execute(node.children.get(allowed?0:1),due,source,budget);
            }
        };
    }
    private boolean enqueue(Node node,long due,String source){
        if(closed||pending.size()>=MAX_PENDING||due>Integer.MAX_VALUE){rejected++;return false;}
        pending.add(new Pending(due,order++,node,source));return true;
    }
    public int pending(){return pending.size();}
    public long rejected(){return rejected;}
    public void close(){closed=true;pending.clear();for(var state:states)state.active.clear();activeSources=0;}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid scene action name");}
    private static void validate(Action action,int depth,int[] count){
        if(depth>16||++count[0]>1024)throw new IllegalArgumentException("Scene action graph exceeds limit");
        switch(action){case Delay delay->validate(delay.action(),depth+1,count);case Sequence sequence->{for(var child:sequence.actions())validate(child,depth+1,count);}case Limited limited->{validate(limited.action(),depth+1,count);validate(limited.otherwise(),depth+1,count);}default->{}}
    }
    public static Action parse(String json)throws IOException {
        try{var action=decode(Json.parse(json,32),0,new int[]{0});validate(action,0,new int[]{0});return action;}
        catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid scene action graph",invalid);}
    }
    private static Action decode(Object value,int depth,int[] count){
        if(depth>16||++count[0]>1024||!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid scene action graph");
        Object kind=map.get("kind");
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
    public static Rule rule(SceneContent.Entry entry)throws IOException {
        var spec=entry.spec();
        if(!Set.of("true","false").contains(spec.text("all","false")))throw new IOException("Invalid actor-removal all flag");
        String key=entry.key()+":"+spec.text("handler-index","");
        return new Rule(key,spec.text("event",""),spec.text("watch-tag",""),spec.text("signal",""),spec.flag("all",false),parse(spec.text("action","")),spec.text("state-key",key),"entry:"+entry.key());
    }
}
