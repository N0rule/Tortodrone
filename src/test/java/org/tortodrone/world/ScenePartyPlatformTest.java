package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry; production intersection, state, action and checkpoint services. */
public final class ScenePartyPlatformTest {
    public static void main(String[] args)throws Exception {
        var trigger=Map.of("kind","limited","limit",1,"action",Map.of("kind","sequence","stopOnFailure",true,"actions",List.of(Map.of("kind","targeted","target",Map.of("mode","source","tag",""),"action",Map.of("kind","failure")),Map.of("kind","source-signal","name","next"))),"otherwise",Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()));
        var pressed=Map.of("kind","sequence","stopOnFailure",false,"actions",List.of(Map.of("kind","signal","tag","door","name","toggle"),Map.of("kind","default-entrance","target",Map.of("mode","activator","tag",""))));
        var fields=new HashMap<String,String>(Map.of("kind","Switch","native-type","com.threerings.projectx.dungeon.data.actor.Switch","config","Fixture/Party Platform","shape","Rectangle","width","3","height","3","collision-flags","0","collision-mask","0","switch-mode","party"));
        fields.put("party-intersection-action",Json.stringify(trigger));fields.put("state.1.scene-action",Json.stringify(pressed));new SceneActions.Spawn(fields);
        var pad=new SceneContent.Entry("1",0,0,0,new SceneContent.Spec(fields));
        var door=new SceneContent.Entry("2",8,0,0,new SceneContent.Spec(Map.of("kind","Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","config","Fixture/Door","collision-flags","1","width","1","height","1","door-speed","0","tags","door")));
        var content=new SceneContent(List.of(pad,door),Map.of());var terrain=SceneWeaponsTest.openTerrain();var scene=new SceneEntities(content,terrain);var peer=new SceneEntities(content,terrain);
        scene.advance(1000,List.of());check(state(scene)==0&&scene.defaultEntrance()==null,"empty platform never activates");
        scene.advance(1050,List.of(player(21,0),player(22,5)));check(state(scene)==0&&!scene.walkable(8,0,0),"one member waits for the rest of the living party");
        scene.advance(1100,List.of(player(21,0),player(22,1)));scene.advance(1150,List.of(player(21,0),player(22,1)));
        check(state(scene)==1&&scene.walkable(8,0,0),"all living members press the native switch and execute its supplied action");
        check(scene.defaultEntrance()!=null&&scene.defaultEntrance().id()==scene.actors().getFirst().id()&&scene.defaultEntrance().x()==0,"checkpoint is the authoritative switch, never an arbitrary player position");
        scene.advance(1200,List.of(player(21,5),player(22,5)));scene.advance(1250,List.of(player(21,0),player(22,1)));scene.advance(1300,List.of(player(21,0),player(22,1)));
        check(scene.walkable(8,0,0),"leaving/reentering cannot repeat the native Limit(1) action");
        peer.advance(1300,List.of());check(state(peer)==0&&peer.defaultEntrance()==null,"state and entrance stay scene-local");
        var living=new SceneEntities(content,terrain);living.playerActive(id->id==21);living.advance(1400,List.of(player(21,0),player(22,5)));check(state(living)==1,"inactive/dead peers are excluded from the living-party requirement");
        var missing=new SceneActions.Targeted(new SceneActions.LogicTarget("tagged","absent"),new SceneActions.Failure());var events=new ArrayList<String>();var runtime=new SceneActions(List.of(),(tag,name,time)->{events.add(name);return true;});
        runtime.perform(new SceneActions.Sequence(List.of(missing,new SceneActions.Signal("out","after")),true),1,"entry:1");runtime.advance(1);check(events.equals(List.of("after")),"TargetedAction with no targets succeeds without child effects");
        fields.put("collision-flags","1");try{new SceneActions.Spawn(fields);throw new AssertionError("Colliding platform accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: party platform living-member intersection, native pressed state, supplied action, owned entrance, TargetedAction failure semantics, one-time reentry and scene isolation (synthetic geometry)");
    }
    private static int state(SceneEntities scene){return scene.actors().getFirst().entity().state();}
    private static ActorState player(int id,float x){return new ActorState(id,1000+id,"Fixture",0,1000,x,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
