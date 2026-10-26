package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Exercises the real entity removal and dynamic-door collision paths, with explicit tick times. */
public final class SceneActionsTest {
    private static final String EXIT="{\"kind\":\"delay\",\"millis\":5000,\"action\":{\"kind\":\"signal\",\"tag\":\"door end\",\"name\":\"open\"}}";
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var boss=entry("giant",0,0,Map.of("config","Fixture/Giant","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","10","damage-mask","2","radius","0.4","tags","wave miniboss","death-time","100"));
        var door=entry("door",3,0,Map.of("config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","kind","Door","collision-flags","1","width","2","height","1","tags","door end","door-speed","0"));
        var trigger=entry("3306",0,0,Map.of("config","Trigger/Actor Removed","kind","SceneTrigger","event","removed","watch-tag","wave miniboss","action",EXIT));
        var content=new SceneContent(List.of(boss,door,trigger),Map.of());
        var scene=new SceneEntities(content,map.collision());var isolated=new SceneEntities(content,map.collision());
        check(scene.actors().size()==2,"script handler never creates a native actor or phantom place");
        int time=SceneClock.now()+100;
        scene.advance(time,List.of());check(!scene.walkable(3,0,219),"exit begins collision-blocked");
        var hit=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f)),
            "damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",20f))));
        scene.damage(hit,0,0,0,time,219);
        scene.advance(time+199,List.of());check(!scene.walkable(3,0,219),"HP zero alone cannot open the exit");
        scene.advance(time+200,List.of()); // 100 ms death animation plus existing 100 ms publication grace.
        scene.advance(time+5199,List.of());check(!scene.walkable(3,0,219),"exit remains blocked before the sourced five-second delay");
        scene.advance(time+5200,List.of());
        var opened=scene.actors().stream().filter(a->a.entity().kind().equals("Door")).findFirst().orElseThrow();
        check((opened.flags()&6)==6,"door publishes OPEN and TRANSITION flags at the scheduled time");
        scene.advance(time+5201,List.of());check(scene.walkable(3,0,219),"native transition completion releases collision");
        isolated.advance(time+10000,List.of());check(!isolated.walkable(3,0,219),"another scene's boss/door are unaffected");
        scene.advance(time+15000,List.of());check(scene.walkable(3,0,219),"removal is processed once");
        scheduler();
        limits();
        transitions();
        try{SceneActions.rule(entry("bad",0,0,Map.of("kind","SceneTrigger","event","removed","watch-tag","wave","all","unknown","action",EXIT)));throw new AssertionError("Invalid group flag accepted");}catch(IOException expected){}
        for(String invalid:List.of("{\"kind\":\"executeClass\",\"name\":\"arbitrary\"}","{\"kind\":\"signal\",\"tag\":\"x\",\"name\":\"open\",\"extra\":true}",EXIT.replace("5000","-1"),EXIT.replace("5000","0.5"),EXIT.replace("5000","999999999999")))
            try{SceneActions.parse(invalid);throw new AssertionError("Invalid graph accepted");}catch(IOException expected){}
        System.out.println("PASS: actor-removal exit delay, door flags/collision, scene isolation, scheduling, all-target guards, failure, cancellation and graph validation");
    }
    private static SceneContent.Entry entry(String key,float x,float y,Map<String,String> fields){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(fields));}
    private static void scheduler()throws Exception {
        var events=new ArrayList<String>();
        var action=new SceneActions.Sequence(List.of(new SceneActions.Delay(20,new SceneActions.Delay(30,new SceneActions.Signal("door","first"))),new SceneActions.Delay(50,new SceneActions.Signal("door","second"))),false);
        var rule=new SceneActions.Rule("all","removed","wave","",true,action);
        var runtime=new SceneActions(List.of(rule),(tag,name,time)->{events.add(name+":"+time);return true;});
        runtime.removed(Set.of("wave"),tag->true,100);runtime.advance(200);check(events.isEmpty(),"all=true waits for the last matching actor");
        runtime.removed(Set.of("other"),tag->false,200);runtime.advance(200);check(events.isEmpty(),"unrelated actors cannot activate the rule");
        runtime.removed(Set.of("wave"),tag->false,200);runtime.advance(500);
        check(events.equals(List.of("second:250","first:250")),"late ticks retain nested due times and insertion order for equal deadlines");
        runtime.removed(Set.of("wave"),tag->false,600);runtime.close();runtime.advance(1000);check(events.size()==2&&runtime.pending()==0,"scene cancellation discards delayed work");
        for(boolean stop:List.of(false,true)){
            events.clear();var compound=new SceneActions.Sequence(List.of(new SceneActions.Signal("door","fail"),new SceneActions.Signal("door","after")),stop);
            var failure=new SceneActions(List.of(new SceneActions.Rule("failure","signal","start","go",false,compound)),(tag,name,time)->{events.add(name);return !name.equals("fail");});
            failure.signal("start","go",1);failure.advance(1);check(events.size()==(stop?1:2),"compound honors native stopOnFailure");
        }
        SceneActions[] loop={null};loop[0]=new SceneActions(List.of(new SceneActions.Rule("loop","signal","loop","go",false,new SceneActions.Signal("loop","go"))),
            (tag,name,time)->{loop[0].signal(tag,name,time);return true;});
        loop[0].signal("loop","go",1);loop[0].advance(1);check(loop[0].pending()==1,"cyclic signals are tick-budgeted instead of recursing");loop[0].close();
    }
    private static void limits(){
        var events=new ArrayList<String>();
        var limited=new SceneActions.Limited(1,new SceneActions.Signal("x","once"),new SceneActions.Signal("x","exhausted"));
        // Reusing an immutable descriptor must not share mutable condition state across nodes/rules/scenes.
        var sequence=new SceneActions.Sequence(List.of(limited,limited),false);
        var rules=List.of(new SceneActions.Rule("a","signal","x","a",false,sequence),new SceneActions.Rule("b","signal","x","b",false,limited));
        for(int instance=0;instance<2;instance++){
            events.clear();var runtime=new SceneActions(rules,(tag,name,time)->{events.add(name);return false;});
            runtime.signal("x","a",100);runtime.advance(100);
            check(events.equals(List.of("once","once")),"each node owns a condition counter, even with a reused descriptor");
            runtime.signal("x","a",101);runtime.advance(101);
            check(events.equals(List.of("once","once","exhausted","exhausted")),"failed actions still consume their native Limit evaluation");
            runtime.signal("x","b",102);runtime.advance(102);
            check(events.getLast().equals("once"),"another handler owns an independent condition counter");
        }
    }
    private static void transitions(){
        var events=new ArrayList<String>();
        var rules=List.of(new SceneActions.Rule("start","signal-start","gate","activate",false,new SceneActions.Signal("out","start")),
            new SceneActions.Rule("stop","signal-stop","gate","activate",false,new SceneActions.Signal("out","stop")));
        var runtime=new SceneActions(rules,(tag,name,time,source)->{events.add(name+":"+time+":"+source);return true;});
        runtime.signal("gate","activate",100,"actor:1");runtime.signal("gate","activate",100,"actor:1");
        runtime.signal("gate","activate",100,"actor:2");runtime.advance(100);runtime.advance(100);
        check(events.equals(List.of("start:100:start","start:100:start")),"signal starts deduplicate per source; repeated advance cannot age transitions");
        runtime.signal("gate","activate",150,"actor:1");runtime.advance(150);
        check(events.getLast().equals("stop:150:stop")&&events.size()==3,"only the source that stopped signaling emits SignalStop");
        runtime.advance(200);check(events.getLast().equals("stop:200:stop")&&events.size()==4,"remaining source stops on its next quiet tick");
        runtime.signal("gate","activate",250,"actor:1");runtime.advance(250);
        check(events.getLast().equals("start:250:start")&&events.size()==5,"a source can start again after a quiet tick");
        runtime.close();runtime.advance(300);check(events.size()==5,"closing a scene cancels pending signal-stop transitions");

        var once=new SceneActions.Limited(1,new SceneActions.Delay(10,new SceneActions.Signal("out","once")),new SceneActions.Sequence(List.of(),false));
        var aliases=List.of(new SceneActions.Rule("first","signal-start","first-tag","activate",false,once,"shared-handler","entry:3265"),
            new SceneActions.Rule("alias","signal-start","second-tag","activate",false,once,"shared-handler","entry:3265"));
        for(int instance=0;instance<2;instance++){
            events.clear();var single=new SceneActions(aliases,(tag,name,time,source)->{events.add(name+":"+time+":"+source);return true;});
            single.signal("first-tag","activate",100,"actor:1");single.signal("second-tag","activate",100,"actor:1");
            single.signal("second-tag","activate",100,"actor:2");single.advance(100);single.advance(110);
            check(events.equals(List.of("once:110:entry:3265")),"tag aliases share Limit state and delayed effects retain their trigger source");
            single.advance(150);single.signal("first-tag","activate",200,"actor:1");single.advance(200);single.advance(250);
            check(events.size()==1,"reactivation cannot replay a one-time encounter");
        }
        try{
            new SceneActions(List.of(aliases.getFirst(),new SceneActions.Rule("bad","signal-stop","second-tag","activate",false,once,"shared-handler","entry:3265")),(tag,name,time)->true);
            throw new AssertionError("Incompatible handler aliases accepted");
        }catch(IllegalArgumentException expected){}
        var bounded=new SceneActions(rules,(tag,name,time)->true);
        for(int i=0;i<5000;i++)bounded.signal("gate","activate",100,"actor:"+i);
        check(bounded.rejected()>0&&bounded.pending()<=4096,"activation tracking and pending work are bounded across handlers");bounded.close();
    }
}
