package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;

public final class SceneMovementScriptTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<>(Map.of("kind","Monster","config","Fixture/Squad","native-type","com.threerings.projectx.dungeon.data.actor.Monster","speed","2","health","0","radius",".3"));
        fields.putAll(Map.of("script.kind","wait-move-rotate","script.wait","100","script.goal","goal","script.max-path","10","script.rotation",Float.toString((float)Math.PI/2),"script.turn-rate",Float.toString((float)Math.PI)));
        var actor=new SceneContent.Entry("10",0,0,0,new SceneContent.Spec(fields));
        var goal=new SceneContent.Entry("11",3,0,0,new SceneContent.Spec(Map.of("kind","SceneAnchor","config","Fixture/Goal","source-tags","goal")));
        var content=new SceneContent(List.of(actor,goal),Map.of());var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());var peer=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        world.advance(1000,List.of());world.advance(1050,List.of());world.advance(1150,List.of());
        check(world.actors().getFirst().x()==0,"native strict wait does not finish on its exact boundary");
        float previous=0;
        for(int stamp=1200;stamp<=5000;stamp+=50){world.advance(stamp,List.of());float x=world.actors().getFirst().x();check(x>=previous&&x-previous<=.101f,"script uses normal speed/collision integration, never a goal teleport");previous=x;}
        var end=world.actors().getFirst();check(end.x()==3&&end.y()==0&&Math.abs(end.rotation()-Math.PI/2)<.0001&&(end.flags()&2)==0,"native wait, tagged move, exact legal arrival and final rotation complete");
        check(peer.actors().getFirst().x()==0,"script state belongs to its own scene");
        fields.put("collision-mask","1");
        var wall=new SceneContent.Entry("12",1.5f,0,0,new SceneContent.Spec(Map.of("kind","Block","config","Fixture/Wall","native-type","com.threerings.projectx.dungeon.data.actor.Block","radius",".6","collision-flags","1")));
        var detour=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("10",0,0,0,new SceneContent.Spec(fields)),goal,wall),Map.of()),SceneWeaponsTest.openTerrain());
        boolean wentAround=false;
        for(int stamp=1000;stamp<=8000;stamp+=50){
            detour.advance(stamp,List.of());var pose=detour.actors().getFirst();
            check(Math.hypot(pose.x()-1.5,pose.y())>=.6,"script cannot walk through a colliding actor");
            wentAround|=Math.abs(pose.y())>.5;
        }
        check(wentAround&&Math.abs(detour.actors().getFirst().x()-3)<.0001,"script uses a collision-legal detour to its marker");
        fields.put("script.max-path","1");var distant=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("10",0,0,0,new SceneContent.Spec(fields)),goal),Map.of()),SceneWeaponsTest.openTerrain());
        float progressed=0;
        for(int stamp=1000;stamp<6000;stamp+=50){distant.advance(stamp,List.of());float x=distant.actors().getFirst().x();
            check(x>=progressed-.0001&&x-progressed<=.101f,"partial path segments move at normal speed, never teleport");progressed=x;}
        var arrived=distant.actors().getFirst();
        check(Math.abs(arrived.x()-3)<.0001&&Math.abs(arrived.rotation()-Math.PI/2)<.0001,"a goal farther than max-path is reached through bounded replanned partial paths");
        try{new SceneMovementScript(-1,"goal",10,0,1);throw new AssertionError("Invalid wait accepted");}catch(IllegalArgumentException expected){}
        try{new SceneMovementScript(1,"goal",10,Float.NaN,1);throw new AssertionError("Invalid direction accepted");}catch(IllegalArgumentException expected){}
        fields.put("health","10");fields.put("script.kind","unknown");
        try{new SceneActions.Spawn(fields);throw new AssertionError("Unknown positive-health spawn script accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: native one-step-per-tick wait/move/rotate, exact wait boundary, collision/speed integration, legal goal arrival, final facing, instance isolation and bounded partial-path replanning to distant goals");
    }
}
