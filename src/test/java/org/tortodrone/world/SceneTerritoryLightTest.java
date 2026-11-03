package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Native neutral lighting is a scene actor; this is not PvP territory control. */
public final class SceneTerritoryLightTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>(Map.of("native-type","com.threerings.projectx.dungeon.arena.data.actor.TerritoryProp","kind","TerritoryProp","config","Static/PvP/Territory Props/Lamps and Lighting/Lights/Spot Lights/24 x 24","is-static","true","shape","Point","collision-flags","0","collision-mask","0"));
        fields.put("config.argument.Intensity","string|Strong");
        var world=world(fields);var source=world.actors().getFirst();
        check(source.entity().nativeClass().equals(fields.get("native-type"))&&source.entity().state()==99,"exact native class and neutral colorId");
        check(source.x()==.5f&&source.y()==.5f&&source.rotation()==-(float)Math.PI/2&&source.config().arguments().get("Intensity").equals("Strong"),"authored transform and light configuration preserved");
        world.advance(SceneClock.now()+50,List.of());check(world.actors().getFirst().x()==source.x()&&world.walkable(.5f,.5f,219),"neutral lighting stays static without creating collision");
        for(var edit:Map.of("is-static","false","shape","Rectangle","collision-flags","1","collision-mask","1","territory-color","0","config","Static/PvP/Territory Props/Tower").entrySet()){
            var invalid=new HashMap<>(fields);invalid.put(edit.getKey(),edit.getValue());
            try{world(invalid);throw new AssertionError("Unsupported territory behavior accepted: "+edit.getKey());}catch(IllegalArgumentException expected){}
        }
        try{new SceneEntityState("TerritoryProp",0,0,0,0,256,0,0,0,"751");throw new AssertionError("Non-byte color accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: native neutral lighting identity/config/color/transform, static noncollision and unsupported territory-control rejection (synthetic geometry)");
    }
    private static SceneEntities world(Map<String,String> fields)throws Exception {return new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("751",.5f,.5f,-(float)Math.PI/2,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());}
}
