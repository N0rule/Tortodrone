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
        spawning(map.collision());
        destruction(map.collision());
        scheduler();
        variance();
        limits();
        transitions();
        try{SceneActions.rule(entry("bad",0,0,Map.of("kind","SceneTrigger","event","removed","watch-tag","wave","all","unknown","action",EXIT)));throw new AssertionError("Invalid group flag accepted");}catch(IOException expected){}
        for(String invalid:List.of("{\"kind\":\"executeClass\",\"name\":\"arbitrary\"}","{\"kind\":\"signal\",\"tag\":\"x\",\"name\":\"open\",\"extra\":true}",EXIT.replace("5000","-1"),EXIT.replace("5000","0.5"),EXIT.replace("5000","999999999999")))
            try{SceneActions.parse(invalid);throw new AssertionError("Invalid graph accepted");}catch(IOException expected){}
        System.out.println("PASS: actor-removal exit delay, door flags/collision, scene isolation, scheduling, all-target guards, failure, cancellation and graph validation");
    }
    private static SceneContent.Entry entry(String key,float x,float y,Map<String,String> fields){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(fields));}
    private static void spawning(CollisionMap terrain)throws Exception {
        var fields=Map.of("config","Fixture/Spawned","kind","Monster","native-type","com.threerings.projectx.dungeon.data.actor.Monster","health","10","damage-mask","2","tags","wave miniboss","death-time","0","collision-flags","1","radius","0.4","activate-time","10");
        String action=org.tortodrone.resources.Json.stringify(Map.of("kind","spawn","fields",fields,"activate",true));
        var parsed=SceneActions.parse(action);check(parsed instanceof SceneActions.Spawn,"typed spawn metadata");
        var seed=entry("seed",0,0,Map.of("config","Fixture/Seed","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","1","damage-mask","2","tags","seed","death-time","0"));
        var trigger=new SceneContent.Entry("spawnpoint",5,0,1.25f,new SceneContent.Spec(Map.of("config","Trigger/Removed","kind","SceneTrigger","event","removed","watch-tag","seed","action",action)));
        var content=new SceneContent(List.of(seed,trigger),Map.of());var scene=new SceneEntities(content,terrain);var isolated=new SceneEntities(content,terrain);
        int now=SceneClock.now()+100;scene.advance(now,List.of());
        var damage=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",2f))));
        scene.damage(damage,0,0,0,now,219);scene.advance(now+100,List.of());
        var spawned=scene.actors().getFirst();
        check(scene.actors().size()==1&&spawned.x()==5&&spawned.y()==0&&spawned.rotation()==1.25f,"spawn uses authored source marker transform");
        check(spawned.entity().health()==1&&spawned.activity()==3,"spawn publishes full health and native activation state");
        check(!scene.walkable(5,0,219),"spawned actor participates in dynamic collision");
        scene.damage(damage,5,0,0,now+101,219);
        check(scene.actors().getFirst().entity().health()==.8f,"spawned actor takes authoritative combat damage");
        for(int hit=0;hit<4;hit++)scene.damage(damage,5,0,0,now+102+hit,219);
        scene.advance(now+300,List.of());check(scene.actors().isEmpty(),"spawned enemy follows normal death/removal lifecycle");
        isolated.advance(now+1000,List.of());check(isolated.actors().size()==1&&isolated.actors().getFirst().x()==0,"spawn is isolated to its scene instance");
        for(String invalid:List.of(action.replace("\"10\"","\"NaN\""),action.replace("actor.Monster","actor.Pickup"),action.replace("\"collision-flags\":\"1\"","\"collision-flags\":\"invalid\"")))try{SceneActions.parse(invalid);throw new AssertionError("Invalid spawn accepted");}catch(IOException expected){}
        var runtime=new SceneActions(List.of(new SceneActions.Rule("missing","signal","in","go",false,parsed)),(tag,name,time)->true);
        runtime.signal("in","go",1);runtime.advance(1);check(runtime.pending()==0,"unavailable spawn executor fails safely");
    }
    private static void destruction(CollisionMap terrain)throws Exception {
        var seed=entry("seed",0,0,Map.of("config","Fixture/Seed","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","1","damage-mask","2","tags","seed","death-time","0"));
        var target=entry("target",5,0,Map.of("config","Fixture/Target","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","10","damage-mask","2","tags","targets","death-time","5000","collision-flags","1","radius","0.4"));
        var other=entry("other",8,0,target.spec().fields());
        var destroy=entry("destroy",0,0,Map.of("config","Trigger/Removed","kind","SceneTrigger","event","removed","watch-tag","seed","action","{\"kind\":\"destroy\",\"tag\":\"targets\"}"));
        var removed=entry("removed",0,0,Map.of("config","Trigger/Removed","kind","SceneTrigger","event","removed","watch-tag","targets","all","true","action","{\"kind\":\"signal\",\"tag\":\"door\",\"name\":\"toggle\"}"));
        var door=entry("door",12,0,Map.of("config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","kind","Door","tags","door","collision-flags","1","width","2","height","1","door-speed","0"));
        var content=new SceneContent(List.of(seed,target,other,destroy,removed,door),Map.of());
        var scene=new SceneEntities(content,terrain);var isolated=new SceneEntities(content,terrain);var hits=new ArrayList<SceneCombatEvent>();scene.feedback(hits::add);
        int now=SceneClock.now()+100;scene.advance(now,List.of());
        var damage=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",2f))));
        scene.damage(damage,0,0,0,now,219);int combatEvents=hits.size();scene.advance(now+100,List.of());
        var victims=scene.actors().stream().filter(a->Set.of("target","other").contains(a.entity().key())).toList();
        check(victims.size()==2&&victims.stream().allMatch(a->a.entity().destroyed()==now+100&&a.entity().health()==1),"script destruction publishes timestamp without damage or death animation");
        check(hits.size()==combatEvents,"script destruction emits no combat damage reward event");
        scene.advance(now+100,List.of());check(scene.actors().size()==3,"same tick retains destroyed actor publication");
        scene.advance(now+101,List.of());check(scene.actors().size()==1,"scripted actors removed on next tick");
        scene.advance(now+102,List.of());check(scene.walkable(12,0,219),"all-target removal signals door exactly once");
        isolated.advance(now+1000,List.of());check(isolated.actors().size()==4&&!isolated.walkable(12,0,219),"destruction remains scene-local");
        var events=new ArrayList<String>();
        var action=new SceneActions.Sequence(List.of(new SceneActions.Destroy("missing"),new SceneActions.Signal("out","after")),true);
        var runtime=new SceneActions(List.of(new SceneActions.Rule("failure","signal","in","go",false,action)),(tag,name,time)->{events.add(name);return true;});
        runtime.signal("in","go",1);runtime.advance(1);check(events.isEmpty(),"missing actor fails and stops compound");
    }
    private static void scheduler()throws Exception {
        var events=new ArrayList<String>();
        var action=new SceneActions.Sequence(List.of(new SceneActions.Delay(20,new SceneActions.Delay(30,new SceneActions.Signal("door","first"))),new SceneActions.Delay(50,new SceneActions.Signal("door","second"))),false);
        var rule=new SceneActions.Rule("all","removed","wave","",true,action);
        var runtime=new SceneActions(List.of(rule),(tag,name,time)->{events.add(name+":"+time);return true;});
        runtime.removed(Set.of("wave"),tag->true,100);runtime.advance(200);check(events.isEmpty(),"all=true waits for the last matching actor");
        runtime.removed(Set.of("other"),tag->false,200);runtime.advance(200);check(events.isEmpty(),"unrelated actors cannot activate the rule");
        runtime.removed(Set.of("wave"),tag->false,200);runtime.advance(200);runtime.advance(500);
        check(events.equals(List.of("second:500")),"late tick does not collapse a nested delay");runtime.advance(530);
        check(events.equals(List.of("second:500","first:530")),"late actions publish current tick and nested delays start there");
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
    private static void variance()throws Exception {
        var parsed=SceneActions.parse("{\"kind\":\"delay\",\"millis\":5,\"variance\":10,\"action\":{\"kind\":\"signal\",\"tag\":\"out\",\"name\":\"go\"}}");
        for(boolean upper:List.of(false,true)){
            var events=new ArrayList<Integer>();
            var random=new Random(0){@Override public int nextInt(int bound){check(bound==20,"native variance range is twice the variance");return upper?bound-1:0;}};
            var runtime=new SceneActions(List.of(new SceneActions.Rule("v","signal","in","go",false,parsed)),(tag,name,time,source)->{events.add(time);return true;},random);
            runtime.signal("in","go",100);runtime.advance(100);
            if(upper){check(events.isEmpty(),"upper variance delays dispatch");runtime.advance(113);check(events.isEmpty(),"exclusive upper bound");runtime.advance(114);}
            check(events.equals(List.of(upper?114:100)),"variance clamps negative sampled delay to zero");
        }
        var first=new ArrayList<Integer>();var second=new ArrayList<Integer>();
        for(var events:List.of(first,second)){
            var runtime=new SceneActions(List.of(new SceneActions.Rule("seed","signal","in","go",false,parsed)),(tag,name,time,source)->{events.add(time);return true;},new Random(77));
            for(int i=0;i<20;i++){int now=100+i*100;runtime.signal("in","go",now);for(int tick=now;tick<=now+20;tick++)runtime.advance(tick);}
        }
        check(first.equals(second)&&first.size()==20,"seeded scene randomness replays without shared state");
        var fixed=new SceneActions(List.of(new SceneActions.Rule("fixed","signal","in","go",false,new SceneActions.Delay(0,new SceneActions.Signal("out","go")))),
            (tag,name,time,source)->true,new Random(){@Override public int nextInt(int bound){throw new AssertionError("Fixed delay consumed randomness");}});
        fixed.signal("in","go",1);fixed.advance(1);
        for(String value:List.of("-1","0.5","3600001","null","\"10\"")){
            try{SceneActions.parse("{\"kind\":\"delay\",\"millis\":5,\"variance\":"+value+",\"action\":{\"kind\":\"sequence\",\"actions\":[],\"stopOnFailure\":false}}");throw new AssertionError("Invalid variance accepted");}catch(IOException expected){}
        }
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
