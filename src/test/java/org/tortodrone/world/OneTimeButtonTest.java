package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Synthetic shape fixture for the authored pawn-intersection/one-time button primitive. */
public final class OneTimeButtonTest {
    public static void main(String[] args)throws Exception{
        var button=new SceneContent.Spec(Map.of("native-type","Switch","kind","Switch","config","Fixture/Button","radius",".4","switch-mode","button-once","state.1.tag","gate","state.1.signal","toggle"));
        var gate=new SceneContent.Spec(Map.of("native-type","Door","kind","Door","config","Fixture/Gate","width","3","height","1","collision-flags","1","tags","gate","door-speed","500"));
        var content=new SceneContent(List.of(new SceneContent.Entry("button",0,0,0,button),new SceneContent.Entry("gate",0,3,0,gate)),Map.of());
        var terrain=SceneWeaponsTest.openTerrain();var world=new SceneEntities(content,terrain);var peer=new SceneEntities(content,terrain);
        int now=SceneClock.now();world.advance(now,List.of(pose(2,0,0,now)));peer.advance(now,List.of(pose(2,0,0,now)));
        check(state(world,"button")==0&&state(world,"gate")==0&&!world.walkable(0,3,219),"outside pawn cannot press the button or cross the closed gate");
        world.advance(now+50,List.of(pose(0,0,TownMovement.DEAD,now)));
        check(state(world,"button")==0,"dead pawn cannot activate a tutorial mechanism");
        world.advance(now+100,List.of(pose(.7f,0,0,now)));
        check(state(world,"button")==1&&state(world,"gate")==6&&!world.walkable(0,3,219),"native .4 button plus pawn radius activates once; opening animation retains collision");
        world.advance(now+600,List.of(pose(0,0,0,now)));
        check(state(world,"gate")==6&&!world.walkable(0,3,219),"native strict animation boundary still retains collision at exactly 500 ms");
        world.advance(now+601,List.of(pose(0,0,0,now)));
        check(state(world,"gate")==2&&world.walkable(0,3,219),"collision clears only when the authored gate opening finishes");
        world.advance(now+700,List.of(pose(2,0,0,now)));world.advance(now+800,List.of(pose(0,0,0,now)));world.advance(now+20000,List.of(pose(0,0,0,now)));
        check(state(world,"button")==1&&state(world,"gate")==2,"leaving, reentering and holding never repeat the state action or use a timed reset");
        check(state(peer,"button")==0&&state(peer,"gate")==0,"another scene's button and gate remain untouched");
        System.out.println("PASS: one-time native-shaped button intersection, dead/outside refusal, gate animation/collision, no replay/reset and instance isolation");
    }
    private static int state(SceneEntities world,String key){var actor=world.actors().stream().filter(a->a.entity().key().equals(key)).findFirst().orElseThrow();return key.equals("gate")?actor.flags():actor.entity().state();}
    private static ActorState pose(float x,float y,int activity,int stamp){return new ActorState(219,1001,"Fixture",0,stamp,x,y,0,0,0,activity,stamp,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
