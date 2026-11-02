package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;

/** Bounded native scripted-behavior metadata. Execution stays in SceneEntities. */
public record SceneScript(List<Step> steps) {
    public sealed interface Step permits Wait,Move,Rotate,Condition,Action,Attack,Goto { }
    public record Wait(int millis) implements Step {public Wait{if(millis<0||millis>3_600_000)throw new IllegalArgumentException("Invalid script wait");}}
    public record Move(String tag,float maxPath) implements Step {public Move{text(tag,true);if(!Float.isFinite(maxPath)||maxPath<=0||maxPath>4096)throw new IllegalArgumentException("Invalid script path bound");}}
    public record Rotate(float direction,float variance,boolean relative) implements Step {public Rotate{if(!Float.isFinite(direction)||!Float.isFinite(variance)||Math.abs(direction)>1_000_000||variance<0||variance>2*Math.PI)throw new IllegalArgumentException("Invalid script rotation");}}
    /** Empty name is the native Always condition; other names reference Source's boolean variable. */
    public record Condition(String name) implements Step {public Condition{text(name,true);}}
    public record Action(SceneActions.Action action) implements Step {public Action{Objects.requireNonNull(action);}}
    public record Attack(int index) implements Step {public Attack{if(index<0||index>255)throw new IllegalArgumentException("Invalid script attack");}}
    public record Goto(int step) implements Step {public Goto{if(step<0||step>255)throw new IllegalArgumentException("Invalid script destination");}}
    public SceneScript {steps=List.copyOf(steps);if(steps.isEmpty()||steps.size()>256)throw new IllegalArgumentException("Invalid script size");for(var step:steps)if(step instanceof Goto jump&&jump.step()>=steps.size())throw new IllegalArgumentException("Script jump is outside program");}
    private static void text(String value,boolean empty){if(value==null||!empty&&value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid script name");}
    public static SceneScript read(SceneContent.Spec spec){
        String graph=spec.text("script.program","");if(graph.isEmpty())return null;
        if(spec.fields().containsKey("script.kind"))throw new IllegalArgumentException("Conflicting native scripts");
        try{
            if(graph.length()>262144)throw new IOException("Script exceeds limit");var decoded=Json.parse(graph,32,1024,262144);
            if(!(decoded instanceof List<?> list))throw new IOException("Script must be an array");var steps=new ArrayList<Step>();
            for(Object value:list){if(!(value instanceof Map<?,?> m))throw new IOException("Invalid script step");var keys=m.keySet();Object kind=m.get("kind");
                if("wait".equals(kind)&&keys.equals(Set.of("kind","millis")))steps.add(new Wait(integer(m.get("millis"))));
                else if("move".equals(kind)&&keys.equals(Set.of("kind","tag","maxPath")))steps.add(new Move(string(m.get("tag")),number(m.get("maxPath"))));
                else if("rotate".equals(kind)&&keys.equals(Set.of("kind","direction","variance","relative"))&&m.get("relative") instanceof Boolean relative)steps.add(new Rotate(number(m.get("direction")),number(m.get("variance")),relative));
                else if("condition".equals(kind)&&keys.equals(Set.of("kind","name")))steps.add(new Condition(string(m.get("name"))));
                else if("action".equals(kind)&&keys.equals(Set.of("kind","action")))steps.add(new Action(SceneActions.parse(Json.stringify(m.get("action")))));
                else if("attack".equals(kind)&&keys.equals(Set.of("kind","index")))steps.add(new Attack(integer(m.get("index"))));
                else if("goto".equals(kind)&&keys.equals(Set.of("kind","step")))steps.add(new Goto(integer(m.get("step"))));
                else throw new IOException("Unsupported complete script step");
            }
            var program=new SceneScript(steps);float turn=spec.number("turn-rate",Float.NaN);
            if(!Float.isFinite(turn)||turn<=0||turn>1_000_000)throw new IOException("Invalid scripted turn rate");
            for(var step:steps)if(step instanceof Attack attack&&!spec.fields().containsKey("attack."+attack.index()+".clear"))throw new IOException("Missing scripted attack");
            return program;
        }catch(IOException invalid){throw new IllegalArgumentException("Invalid native script",invalid);}
    }
    public Set<String> conversations(){var ids=new LinkedHashSet<String>();for(var step:steps)if(step instanceof Action action)ids.addAll(SceneActions.conversationIds(action.action()));return Set.copyOf(ids);}
    public static Set<String> conversations(SceneContent.Spec spec)throws IOException {
        var ids=new LinkedHashSet<String>();var program=read(spec);if(program!=null)ids.addAll(program.conversations());
        for(var field:spec.fields().entrySet())if(field.getKey().equals("scene-shutdown-action")||field.getKey().startsWith("attack.")&&field.getKey().endsWith(".scene-action"))ids.addAll(SceneActions.conversationIds(SceneActions.parse(field.getValue())));
        return Set.copyOf(ids);
    }
    private static int integer(Object value){if(!(value instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Invalid script integer");return Math.toIntExact(n.longValue());}
    private static float number(Object value){if(!(value instanceof Number n))throw new IllegalArgumentException("Invalid script number");return n.floatValue();}
    private static String string(Object value){if(!(value instanceof String s))throw new IllegalArgumentException("Invalid script string");return s;}
}
