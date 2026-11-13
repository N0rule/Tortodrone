package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Crash Site help bubbles are native two-state Switches: they show in state 0 and the
 * authored "next tut move"/"next tut shield" signals hide exactly the tagged bubble. */
public final class CrashSiteHelpBubbleTest {
    private static final String BUBBLE="Dynamic/Help Bubble/Help";
    public static void main(String[] args)throws Exception {
        var map=ContentManager.load(Path.of(".")).scenes().scene(398);
        var bubbles=map.content().entries().stream().filter(e->e.spec().config().name().equals(BUBBLE)).toList();
        check(bubbles.size()==7&&bubbles.stream().allMatch(e->e.spec().integer("switch-states",0)==2&&!e.spec().fields().containsKey("startup-state")),
            "all seven bubbles carry the native two-state count and default state 0");
        var entities=new SceneEntities(map.content(),map.collision(),map.staticCollision());int stamp=SceneClock.now()+100;
        entities.advance(stamp,List.of());
        var signal=SceneEntities.class.getDeclaredMethod("signal",String.class,String.class,int.class,String.class);signal.setAccessible(true);
        check(visible(entities).size()==7,"every bubble starts visible in state 0 with START set");
        signal.invoke(entities,"tut move","next",stamp+1,"entry:3481");entities.advance(stamp+2,List.of());
        check(!visible(entities).contains("m.tut_move_short")&&visible(entities).size()==6,"next tut move hides only the movement bubble");
        signal.invoke(entities,"tut shield","next",stamp+3,"entry:3393");entities.advance(stamp+4,List.of());
        check(!visible(entities).contains("m.tut_shield")&&visible(entities).size()==5,"next tut shield hides only the shield bubble");
        signal.invoke(entities,"tut move","next",stamp+5,"test");entities.advance(stamp+6,List.of());
        check(visible(entities).contains("m.tut_move_short"),"a further next wraps back to the visible state like SwitchLogic");
        signal.invoke(entities,"tut clearing","unrelated",stamp+7,"test");entities.advance(stamp+8,List.of());
        check(visible(entities).contains("m.tut_clearing"),"unrelated signal names are swallowed");
        System.out.println("PASS: Crash Site help bubbles are native two-state switches hidden by their authored next signals");
    }
    private static Set<String> visible(SceneEntities entities){
        var result=new HashSet<String>();
        for(var actor:entities.actors())if(actor.config().name().equals(BUBBLE)&&actor.entity().state()==0&&(actor.flags()&2)!=0)
            result.add(String.valueOf(actor.config().arguments().get("Message")));
        return result;
    }
}
