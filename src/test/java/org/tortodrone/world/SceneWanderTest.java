package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

public final class SceneWanderTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>();
        fields.putAll(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","config","Fixture/Wander","health","100","speed","2","radius",".4","turn-rate","6.2831855","collision-flags","8","collision-mask","1"));
        fields.putAll(Map.of("wander.kind","uniform-circle","wander.radius","50","wander.evaluation","2000","source-shape",shape(.4f)));
        var wall=new SceneContent.Entry("wall",.2f,1.4f,0,new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Block","kind","Block","config","Fixture/Wall","radius",".6","collision-flags","1","source-shape",shape(.6f))));
        var content=new SceneContent(List.of(new SceneContent.Entry("wander",0,0,0,new SceneContent.Spec(fields)),wall),Map.of());
        var terrain=SceneWeaponsTest.openTerrain();
        var world=new SceneEntities(content,terrain,SceneCollision.empty(),new Random(42));
        var peer=new SceneEntities(content,terrain,SceneCollision.empty(),new Random(42));
        float x=0,y=0;boolean moved=false,turned=false;
        for(int stamp=1000;stamp<=11000;stamp+=50){
            world.advance(stamp,List.of());var actor=world.actors().getFirst();
            check(Math.hypot(actor.x()-x,actor.y()-y)<=.101,"wander integrates authored speed without teleportation");
            check(Math.hypot(actor.x()-.2,actor.y()-1.4)>=.999,"native body collision prevents wandering through the wall");
            moved|=Math.hypot(actor.x(),actor.y())>.2;turned|=Math.abs(actor.rotation())>.2;x=actor.x();y=actor.y();
        }
        check(moved&&turned,"idle native wander turns and moves");
        check(peer.actors().getFirst().x()==0&&peer.actors().getFirst().y()==0,"another scene owns its own idle movement state");
        for(int stamp=1000;stamp<=11000;stamp+=50)peer.advance(stamp,List.of());
        check(peer.actors().getFirst().x()==x&&peer.actors().getFirst().y()==y,"an injected random source and scene clock reproduce the trajectory");
        try{new SceneWander(Float.NaN,2000,1);throw new AssertionError("Invalid wander radius accepted");}catch(IllegalArgumentException expected){}
        fields.put("wander.kind","unknown");
        try{SceneWander.read(new SceneContent.Spec(fields));throw new AssertionError("Unknown wander accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: native idle wander, turn/speed integration, body collision, deterministic clock/random source, scene isolation and invalid-policy refusal");
    }
    private static String shape(float radius)throws Exception {
        return FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",List.of(new FireCatalog.FieldValue("radius","float",radius))))));
    }
}
