package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Count region applications independently of probabilistic Fire/status protection. */
public final class HazardLifetimeTest {
    public static void main(String[] args)throws Exception {
        var map=SceneRegistry.defaultEntry().map();
        var content=new SceneContent(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of());
        var spawn=SceneEntities.class.getDeclaredMethod("hazard",String.class,Object.class,float.class,int.class,Map.class,float.class,float.class,int.class);
        spawn.setAccessible(true);
        for(int active:new int[]{4000,6000})for(int offset:new int[]{-1,0,1}){
            var world=new SceneEntities(content,map.collision());
            int[] hits={0};world.playerHits(hit->hits[0]++);
            Object damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of("flags",4));
            spawn.invoke(world,"Fixture",damage,1f,6000,Map.of("hazard-active-lifetime",Integer.toString(active)),map.spawnX(),map.spawnY(),1000);
            var hazard=world.actors().getFirst();
            var player=new ActorState(7,1001,"Fixture",0,1000,map.spawnX(),map.spawnY(),0,0,0,new org.tortodrone.character.StarterCatalog.Model("Fixture",Map.of()));
            var occupants=List.of(player);
            world.advance(1000,occupants);check(hits[0]==1,"fresh hazard applies one region");
            // Skipping several cadences makes a region otherwise due on either side of expiry.
            int expected=offset<0?2:1;
            world.advance(1000+active+offset,occupants);check(hits[0]==expected,"active boundary offset "+offset);
            if(active==4000)check(world.actor(hazard.id())!=null,"inactive torch retained for cleanup");
            world.advance(7001,occupants);check(hits[0]==expected&&world.actor(hazard.id())==null,"cleanup deadline never damages");
            world.advance(7101,occupants);check(world.actors().isEmpty()&&hits[0]==expected,"publication grace ends without another hit");
        }
        System.out.println("PASS: active/cleanup hazard deadlines, skipped ticks and destroyed publication grace");
    }
}
