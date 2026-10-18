package org.tortodrone.benchmark;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.world.*;

/** Opt-in, local CPU/allocation comparison of scene hot paths; no accounts or sockets. */
public final class SceneBenchmark {
    private static volatile Object result;
    private static final com.sun.management.ThreadMXBean memory=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    public static void main(String[] args)throws Exception {
        int iterations=args.length==0?10_000:Integer.parseInt(args[0]);
        if(iterations<100||iterations>100_000)throw new IllegalArgumentException("Iterations must be from 100 to 100000");
        if(memory.isThreadAllocatedMemorySupported())memory.setThreadAllocatedMemoryEnabled(true);
        var catalog=ContentManager.load(Path.of(".")).scenes();
        System.out.println("scene,actors,operation,microseconds_per_op,allocated_bytes_per_op");
        for(int id:new int[]{1,318,294})for(int pass=0;pass<2;pass++){
            var map=catalog.scene(id);var entities=new SceneEntities(map.content(),map.collision(),map.staticCollision());
            int stamp=SceneClock.now();
            var player=new ActorState(1,101,"Probe",257,stamp,map.spawnX(),map.spawnY(),0,0,0,0,stamp,new StarterCatalog.Model("Character/PC/Default",Map.of()),false);
            var players=List.of(player);var actors=entities.actors();
            for(String operation:List.of("interaction","collision","advance","snapshot")){
                long allocated=allocated(),start=System.nanoTime();
                for(int i=0;i<iterations;i++){
                    // Exercise both spawn and actor locations, including rotated interaction shapes.
                    var at=i%2==0||actors.isEmpty()?player:actors.get(i%actors.size());
                    switch(operation){
                        case "interaction"->result=entities.interaction(at.x()+.1f,at.y(),.3f);
                        case "collision"->result=entities.penetration(at.x()+.1f,at.y(),player.id());
                        case "advance"->entities.advance(stamp+i*50,players);
                        case "snapshot"->result=entities.actors();
                        default->throw new AssertionError(operation);
                    }
                }
                long elapsed=System.nanoTime()-start,bytes=allocated()-allocated;
                if(pass==1)System.out.printf(Locale.ROOT,"%d,%d,%s,%.2f,%.0f%n",id,actors.size(),operation,elapsed/1000.0/iterations,bytes<0?-1:bytes/(double)iterations);
            }
        }
    }
    private static long allocated(){return memory.isThreadAllocatedMemorySupported()?memory.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;}
}
