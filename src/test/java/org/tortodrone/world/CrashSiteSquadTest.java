package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** The Crash Site Knight Squad holds at its spawns until the ambush strip's "start knights"
 * trigger signals activate, then waits its authored 500 ms and walks to its waypoints. */
public final class CrashSiteSquadTest {
    private static final String SQUAD="Character/Scripted/Missions/Tutorial/Final Test/Knight Squad/Spiral Knight";
    public static void main(String[] args)throws Exception {
        var map=ContentManager.load(Path.of(".")).scenes().scene(398);
        var entities=new SceneEntities(map.content(),map.collision(),map.staticCollision());int stamp=SceneClock.now()+100;
        entities.advance(stamp,List.of());
        var spawn=positions(entities);
        check(spawn.size()==3,"three squad knights are placed: "+spawn.size());
        for(int t=50;t<=5000;t+=50)entities.advance(stamp+t,List.of());
        check(positions(entities).equals(spawn),"the squad holds at its spawns before the ambush strip fires");
        var signal=SceneEntities.class.getDeclaredMethod("signal",String.class,String.class,int.class,String.class);signal.setAccessible(true);
        int fired=stamp+5000;signal.invoke(entities,"actor_sksquad_1","activate",fired,"entry:3287");
        for(int t=50;t<=450;t+=50)entities.advance(fired+t,List.of());
        check(positions(entities).equals(spawn),"the authored 500 ms wait follows the activate signal");
        for(int t=500;t<=8000;t+=50)entities.advance(fired+t,List.of());
        var moved=positions(entities);
        check(moved.keySet().equals(spawn.keySet())&&moved.entrySet().stream().allMatch(e->!e.getValue().equals(spawn.get(e.getKey()))),"every squad knight walks after the wait");
        // Live wire: the squad stops exactly on waypoint_sk1/sk2/sk3.
        var goals=Set.of(List.of(10.5f,60.5f),List.of(12.5f,59.5f),List.of(14.5f,60.5f));
        check(new HashSet<>(moved.values()).equals(goals),"the squad stops on its authored waypoints: "+moved);
        System.out.println("PASS: Crash Site Knight Squad holds until the ambush activate signal, waits 500 ms and walks to its waypoints");
    }
    private static Map<Integer,List<Float>> positions(SceneEntities entities){
        var result=new TreeMap<Integer,List<Float>>();
        for(var actor:entities.actors())if(actor.config().name().equals(SQUAD))result.put(actor.id(),List.of(actor.x(),actor.y()));
        return result;
    }
}
