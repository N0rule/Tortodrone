package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Native counter/latch semantics through production actor deaths on synthetic geometry. */
public final class SceneRemovedGroupTest {
    private static final String A="wave 5d",B="wave 4d";
    private static final List<String> GROUP=List.of(A,B);
    private static final String OPEN="{\"kind\":\"signal\",\"tag\":\"door end\",\"name\":\"open\"}";
    public static void main(String[] args)throws Exception {
        var entries=new ArrayList<SceneContent.Entry>();entries.add(monster("a1",0,A));entries.add(monster("a2",2,A));entries.add(monster("b",4,B));
        entries.add(entry("door",8,Map.of("config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","kind","Door","collision-flags","1","width","2","height","1","tags","door end","door-speed","0")));
        for(int i=0;i<2;i++)entries.add(handler(i,GROUP.get(i)));
        var content=new SceneContent(entries,Map.of());var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());var isolated=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        int now=SceneClock.now()+100;
        check(!world.walkable(8,0,219),"two-wave exit starts physically blocked");
        kill(world,0,now);tick(world,now+100);check(!world.walkable(8,0,219),"one dead actor does not clear a wave with a surviving member");
        kill(world,4,now+200);tick(world,now+300);check(!world.walkable(8,0,219),"clearing the second tag first does not open the exit");
        kill(world,2,now+400);tick(world,now+500);check(world.walkable(8,0,219),"removing the final actor from both actual waves opens normal door collision");
        check(!isolated.walkable(8,0,219)&&isolated.actors().stream().filter(a->a.entity().kind().equals("Monster")).count()==3,"removal latches and door state belong to their scene instance");
        var signals=new ArrayList<String>();var runtime=new SceneActions(rules(),(tag,name,time)->{signals.add(tag+":"+name);return true;});
        runtime.removed(Set.of(A),tag->false,1000);runtime.advance(1000);check(signals.isEmpty(),"a tag initially absent without its removal event does not satisfy a counter latch");
        runtime.removed(Set.of(A),tag->false,1050);runtime.advance(1050);check(signals.isEmpty(),"duplicate first-wave removals cannot count as the second wave");
        runtime.removed(Set.of(B),tag->tag.equals(A),1100);runtime.advance(1100);check(signals.equals(List.of("door end:open")),"native Limit(1) remembers the first cleared wave even after that tag respawns");
        runtime.removed(Set.of(A,B),tag->false,1150);runtime.advance(1150);check(signals.size()==1,"duplicate completion cannot replay the authored action graph");
        runtime.close();runtime.removed(Set.of(A,B),tag->false,1200);runtime.advance(1200);check(signals.size()==1,"closed scene cannot retain executable gate work");
        var together=new ArrayList<String>();var simultaneous=new SceneActions(rules(),(tag,name,time)->{together.add(name);return true;});
        simultaneous.removed(Set.of(A,B),tag->false,1000);simultaneous.advance(1000);check(together.equals(List.of("open")),"one actor carrying both tags satisfies both native latches once");
        int[] failures={0};var failed=new SceneActions(rules(),(tag,name,time)->{failures[0]++;return false;});
        failed.removed(Set.of(A,B),tag->false,1000);failed.advance(1000);failed.removed(Set.of(A,B),tag->false,1050);failed.advance(1050);check(failures[0]==1,"failed final action does not reset native consumed wave limits");
        refuse(()->new SceneActions.Rule("bad","signal",A,"activate",true,new SceneActions.Signal("door","open"),"bad","entry:1",GROUP));
        refuse(()->new SceneActions.Rule("bad","removed",A,"",false,new SceneActions.Signal("door","open"),"bad","entry:1",GROUP));
        refuse(()->new SceneActions.Rule("bad","removed",A,"",true,new SceneActions.Signal("door","open"),"bad","entry:1",List.of(A,A)));
        var conflicting=new ArrayList<>(rules());conflicting.set(1,new SceneActions.Rule("second","removed",B,"",true,new SceneActions.Signal("door end","open"),"3352:removed-group","entry:3352",List.of(B,"different")));
        refuse(()->new SceneActions(conflicting,(tag,name,time)->true));
        System.out.println("PASS: real actor deaths/removal, all-wave guards, native latched counter semantics, door collision, duplicate/failure behavior, simultaneous tags, scene isolation, close and invalid/contradictory groups (synthetic geometry)");
    }
    private static List<SceneActions.Rule> rules()throws Exception{return List.of(SceneActions.rule(handler(0,A)),SceneActions.rule(handler(1,B)));}
    private static SceneContent.Entry handler(int index,String tag){
        var fields=new HashMap<>(Map.of("config","Trigger/Actor Removed (2 Tags)","kind","SceneTrigger","event","removed","watch-tag",tag,"all","true","state-key","3352:removed-group","removed-group",A+","+B,"action",OPEN,"handler-index",Integer.toString(index)));
        return entry("3352",0,fields);
    }
    private static SceneContent.Entry monster(String key,float x,String tag){return entry(key,x,Map.of("config","Fixture/Enemy","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","1","radius",".4","damage-mask","2","tags",tag,"death-time","0"));}
    private static SceneContent.Entry entry(String key,float x,Map<String,String> fields){return new SceneContent.Entry(key,x,0,0,new SceneContent.Spec(fields));}
    private static void kill(SceneEntities world,float x,int stamp){world.damage(new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",.5f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",1f)))),x,0,0,stamp,219);}
    private static void tick(SceneEntities world,int stamp){world.advance(stamp,List.of());world.advance(stamp+50,List.of());}
    private static void refuse(Runnable work){try{work.run();throw new AssertionError("Invalid removal group accepted");}catch(IllegalArgumentException expected){}}
}
