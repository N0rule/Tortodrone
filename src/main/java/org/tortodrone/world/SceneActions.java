package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;

/** Allowlisted scene handlers. Owned and advanced by one SceneEntities simulation, never a timer. */
public final class SceneActions {
    public sealed interface Action permits Signal,Delay,Sequence { }
    public record Signal(String tag,String name) implements Action {
        public Signal { text(tag);text(name); }
    }
    public record Delay(int millis,Action action) implements Action {
        public Delay { if(millis<0||millis>3_600_000)throw new IllegalArgumentException("Invalid scene action delay");Objects.requireNonNull(action); }
    }
    public record Sequence(List<Action> actions,boolean stopOnFailure) implements Action {
        public Sequence { actions=List.copyOf(actions);if(actions.size()>256)throw new IllegalArgumentException("Too many compound actions"); }
    }
    public record Rule(String key,String event,String tag,String name,boolean all,Action action) {
        public Rule {
            text(key);text(tag);Objects.requireNonNull(action);
            if(!Set.of("removed","signal").contains(event))throw new IllegalArgumentException("Unsupported scene handler");
            if(event.equals("signal"))text(name);
        }
    }
    @FunctionalInterface public interface Signals { boolean apply(String tag,String name,int stamp); }
    private record Pending(long due,long order,Action action) { }
    private static final int MAX_PENDING=4096,MAX_PER_TICK=1024;
    private final List<Rule> rules;
    private final Signals signals;
    private final PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingLong(Pending::due).thenComparingLong(Pending::order));
    private long order,rejected;
    private boolean closed;
    public SceneActions(List<Rule> rules,Signals signals){
        this.rules=List.copyOf(rules);this.signals=Objects.requireNonNull(signals);
        if(rules.size()>2048)throw new IllegalArgumentException("Too many scene handlers");
        var keys=new HashSet<String>();for(var rule:rules){if(!keys.add(rule.key()))throw new IllegalArgumentException("Duplicate scene handler");validate(rule.action(),0,new int[]{0});}
    }
    public void removed(Set<String> tags,java.util.function.Predicate<String> remaining,int stamp){
        if(closed)return;
        for(var rule:rules)if(rule.event().equals("removed")&&tags.contains(rule.tag())&&(!rule.all()||!remaining.test(rule.tag())))enqueue(rule.action(),stamp);
    }
    public void signal(String tag,String name,int stamp){
        if(closed)return;
        for(var rule:rules)if(rule.event().equals("signal")&&rule.tag().equals(tag)&&rule.name().equals(name))enqueue(rule.action(),stamp);
    }
    /** Equal-time actions retain authored order; nested delays retain their original due time. */
    public void advance(int stamp){
        if(closed)return;
        int[] budget={MAX_PER_TICK};
        while(!pending.isEmpty()&&pending.peek().due()<=stamp&&budget[0]>0){
            var task=pending.remove();execute(task.action(),task.due(),budget);
        }
    }
    private boolean execute(Action action,long due,int[] budget){
        if(--budget[0]<0){rejected++;return false;}
        return switch(action){
            case Signal signal -> signals.apply(signal.tag(),signal.name(),Math.toIntExact(due));
            case Delay delay -> enqueue(delay.action(),Math.addExact(due,delay.millis()));
            case Sequence sequence -> {
                boolean success=true;
                for(var child:sequence.actions())if(!execute(child,due,budget)){success=false;if(sequence.stopOnFailure())break;}
                yield success;
            }
        };
    }
    private boolean enqueue(Action action,long due){
        if(closed||pending.size()>=MAX_PENDING||due>Integer.MAX_VALUE){rejected++;return false;}
        pending.add(new Pending(due,order++,action));return true;
    }
    public int pending(){return pending.size();}
    public long rejected(){return rejected;}
    public void close(){closed=true;pending.clear();}
    private static void text(String value){if(value==null||value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid scene action name");}
    private static void validate(Action action,int depth,int[] count){
        if(depth>16||++count[0]>1024)throw new IllegalArgumentException("Scene action graph exceeds limit");
        switch(action){case Delay delay->validate(delay.action(),depth+1,count);case Sequence sequence->{for(var child:sequence.actions())validate(child,depth+1,count);}default->{}}
    }
    public static Action parse(String json)throws IOException {
        try{var action=decode(Json.parse(json,32),0,new int[]{0});validate(action,0,new int[]{0});return action;}
        catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid scene action graph",invalid);}
    }
    private static Action decode(Object value,int depth,int[] count){
        if(depth>16||++count[0]>1024||!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid scene action graph");
        Object kind=map.get("kind");
        if("signal".equals(kind)&&map.keySet().equals(Set.of("kind","tag","name")))return new Signal(string(map.get("tag")),string(map.get("name")));
        if("delay".equals(kind)&&map.keySet().equals(Set.of("kind","millis","action"))&&map.get("millis") instanceof Number number){
            long millis=number.longValue();if(number.doubleValue()!=millis)throw new IllegalArgumentException("Fractional delay");
            return new Delay(Math.toIntExact(millis),decode(map.get("action"),depth+1,count));
        }
        if("sequence".equals(kind)&&map.keySet().equals(Set.of("kind","stopOnFailure","actions"))&&map.get("stopOnFailure") instanceof Boolean stop&&map.get("actions") instanceof List<?> list){
            var actions=new ArrayList<Action>();for(var child:list)actions.add(decode(child,depth+1,count));return new Sequence(actions,stop);
        }
        throw new IllegalArgumentException("Unsupported scene action or fields");
    }
    private static String string(Object value){if(!(value instanceof String text))throw new IllegalArgumentException("Expected scene action text");return text;}
    public static Rule rule(SceneContent.Entry entry)throws IOException {
        var spec=entry.spec();
        if(!Set.of("true","false").contains(spec.text("all","false")))throw new IOException("Invalid actor-removal all flag");
        return new Rule(entry.key()+":"+spec.text("handler-index",""),spec.text("event",""),spec.text("watch-tag",""),spec.text("signal",""),spec.flag("all",false),parse(spec.text("action","")));
    }
}
