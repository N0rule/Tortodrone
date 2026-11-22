package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.MonsterType;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneClock;

/** Scene-generation wave policy: registration, bounded spawning, respawn timing and cleared
 *  signals. All work runs under the scene monitor and creates actors only through the shared
 *  SceneEntities spawn path. */
final class WaveSystem {
    private final SceneEntities scene;
    private final Map<String,Wave> waves=new LinkedHashMap<>();

    WaveSystem(SceneEntities scene) {
        this.scene = scene;
    }

    private static final class Wave {
        final MonsterGroup group;
        int spawned,previousAlive,nextSpawn=Integer.MIN_VALUE;
        boolean cleared;
        Wave(MonsterGroup group){this.group=group;}
    }

    /** Register a wave for this scene generation. Unknown or unsupported types fail here,
     *  before any actor is created. */
    void add(MonsterGroup group){
        Objects.requireNonNull(group);
        if(group.generation()!=scene.generation())throw new IllegalArgumentException("Monster group belongs to a stale scene generation");
        if(waves.containsKey(group.id()))throw new IllegalArgumentException("Duplicate monster group: "+group.id());
        try{scene.monsters.spawnable(group.typeId());}
        catch(java.io.IOException refused){throw new IllegalArgumentException("Monster group "+group.id()+": "+refused.getMessage(),refused);}
        for(var anchor:group.anchors()){
            if(!scene.terrain().walkable(anchor.x(),anchor.y()))
                throw new IllegalArgumentException("Monster group anchor is outside walkable terrain: "+anchor);
            if(!scene.staticCollision().isEmpty()&&(!scene.staticCollision().floorSupported(anchor.x(),anchor.y())
                ||scene.staticCollision().penetration(scene.pawnCollisionMask(),anchor.x(),anchor.y(),ScenePortals.PAWN_RADIUS).present()))
                throw new IllegalArgumentException("Monster group anchor is blocked: "+anchor);
        }
        waves.put(group.id(),new Wave(group));
    }
    int spawned(String id){var wave=waves.get(id);return wave==null?0:wave.spawned;}
    int alive(String id){
        int alive=0;
        for(var e:scene.entityValues())if(id.equals(e.waveId)&&e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE)alive++;
        return alive;
    }
    boolean cleared(String id){var wave=waves.get(id);return wave!=null&&wave.cleared;}
    int liveMonsters(){return (int)scene.entityValues().stream().filter(e->e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE).count();}
    void advance(int stamp){
        int spawnedThisTick=0;
        for(var wave:List.copyOf(waves.values())){
            if(wave.group.generation()!=scene.generation()){drop(wave);continue;}
            int alive=alive(wave.group.id());
            if(alive<wave.previousAlive)wave.nextSpawn=Math.max(wave.nextSpawn,stamp+wave.group.respawnDelayMs());
            wave.previousAlive=alive;
            while(wave.spawned<wave.group.total()&&alive<wave.group.maxAlive()
                    &&due(wave,stamp)&&spawnedThisTick<SceneEntities.MAX_WAVE_SPAWNS_PER_TICK
                    &&liveMonsters()<SceneEntities.MAX_ALIVE_MONSTERS){
                spawnWaveMonster(wave,stamp);wave.spawned++;alive++;spawnedThisTick++;
                wave.nextSpawn=stamp+wave.group.intervalMs();
            }
            if(!wave.cleared&&wave.spawned==wave.group.total()&&alive==0){
                wave.cleared=true;
                if(!wave.group.onCleared().isEmpty())scene.actions().signal(wave.group.id(),wave.group.onCleared(),stamp,"wave:"+wave.group.id());
            }
        }
    }
    /** The unset deadline is immediately due. Integer.MIN_VALUE cannot be compared with
     *  wrap-safe subtraction (it overflows for any ordinary stamp), so it stays a sentinel. */
    private static boolean due(Wave wave,int stamp){return wave.nextSpawn==Integer.MIN_VALUE||SceneClock.reached(stamp,wave.nextSpawn);}
    private void spawnWaveMonster(Wave wave,int stamp){
        MonsterType type;
        try{type=scene.monsters.spawnable(wave.group.typeId());}
        catch(java.io.IOException refused){throw new IllegalStateException("Wave type became unavailable: "+wave.group.typeId(),refused);}
        var fields=scene.monsters.fields(type);
        var anchor=wave.group.anchors().get(wave.spawned%wave.group.anchors().size());
        var binding=new SceneContent.Entry("wave:"+wave.group.id()+":"+(wave.spawned+1),anchor.x(),anchor.y(),0,new SceneContent.Spec(fields));
        var entity=scene.spawn(binding,binding.spec(),null,stamp);
        entity.waveId=wave.group.id();entity.monsterType=type.id();
    }
    private void drop(Wave wave){
        for(var entity:List.copyOf(scene.entityValues()))if(wave.group.id().equals(entity.waveId))scene.despawn(entity);
        waves.remove(wave.group.id());
    }
    /** Drop wave groups that cannot belong to the requested generation. */
    void retain(long next){
        for(var wave:List.copyOf(waves.values()))if(wave.group.generation()!=next)drop(wave);
    }
}
