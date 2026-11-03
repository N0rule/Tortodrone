package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic graph/geometry; real entity creation, pickup admission and ownership. */
public final class SceneHeatSpawnTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>(Map.of("kind","Pickup","native-type","com.threerings.projectx.dungeon.data.actor.Pickup","config","Fixture/Heat","pickup-heat","5","radius",".4","vacuum-expansion","2.4","vacuum-speed","8","collision-flags","16","collision-mask","3"));
        var actor=new SceneActions.Spawn(fields,false,"",true);
        var targeted=new SceneActions.Targeted(new SceneActions.LogicTarget("tagged","heat"),actor);
        String graph=Json.stringify(Map.of("kind","targeted","target",Map.of("mode","tagged","tag","heat"),"action",Map.of("kind","spawn","fields",fields,"activate",false,"activator",true)));
        check(SceneActions.parse(graph).equals(targeted),"typed heat spawn/target roundtrip");
        var content=new SceneContent(List.of(entry("1",0,Map.of("kind","SceneTrigger","config","Fixture/Start","event","signal-start","watch-tag","start","signal","startup","scene-startup","true","action",graph)),
            entry("2",2,Map.of("kind","SceneAnchor","config","Fixture/Marker","source-tags","heat")),entry("3",4,Map.of("kind","SceneAnchor","config","Fixture/Marker","source-tags","heat"))),Map.of());
        var scene=new SceneEntities(content,SceneWeaponsTest.openTerrain());var peer=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        check(scene.actors().size()==2&&scene.actors().get(0).x()==2&&scene.actors().get(1).x()==4,"source-target iteration spawns actual pickups at both authoritative markers");
        int now=SceneClock.now()+1100;var pawn=new ActorState(21,1001,"Fixture",0,now,2,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));scene.advance(now,List.of(pawn));
        var claims=scene.takePickups(21);check(claims.size()==2&&claims.stream().mapToInt(SceneEntities.Pickup::heat).sum()==10,"spawned actors enter actual owned heat claims");
        int id=claims.getFirst().entityId();check(scene.heatClaim(22,id)==0&&scene.heatClaim(21,id)==5&&scene.takePickups(21).isEmpty(),"foreign ownership and repeated claim refused");
        check(!scene.activateHeat(22,id)&&scene.activateHeat(21,id)&&!scene.activateHeat(21,id),"only the claimant activates heat exactly once");
        check(peer.takePickups(21).isEmpty(),"independent scene retains its unclaimed actors");
        for(var invalid:List.of(Map.of("pickup-heat","1000001"),Map.of("pickup-item","Weapon/Sword/Proto Sword"),Map.of("pickup-equip-slot","0"),Map.of("collision-flags","1"),Map.of("vacuum-speed","NaN"),Map.of("vacuum-expansion","-1"),Map.of("lifespan","-1"),Map.of("pickup-heat","0"))){
            var bad=new HashMap<>(fields);bad.putAll(invalid);try{new SceneActions.Spawn(bad);throw new AssertionError("Invalid heat spawn accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        try{new SceneActions.Spawn(fields,true);throw new AssertionError("Monster activation accepted for heat");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: typed heat spawning at owned scene targets, real pickup claims/activation, replay and ownership refusal, isolation and invalid metadata rejection (synthetic geometry)");
    }
    private static SceneContent.Entry entry(String key,float x,Map<String,String> fields){return new SceneContent.Entry(key,x,0,0,new SceneContent.Spec(fields));}
}
