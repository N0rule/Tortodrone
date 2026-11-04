package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Real scene/membership/lift/input services on synthetic geometry; not a Generator playthrough. */
public final class SceneGoldKeyTest {
    public static void main(String[] args)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();var content=content();
        var entities=new SceneEntities(content,terrain);int now=SceneClock.now()+100;
        var owner=player(219,0,0,now);var peer=player(220,1.5f,0,now);
        int key=actor(entities,"key").id(),second=actor(entities,"second").id();
        check(entities.interaction(1.5f,0,0,219,0)==null,"a knight without a carried key has no gold-unlock prompt");
        check(entities.carry(key,owner,now),"server-owned key pickup");
        check(entities.interaction(1.5f,0,0,219,key).action().equals("UnlockGold"),"compatible carried lift offers the native gold-unlock action");
        check(entities.interaction(1.5f,0,0,220,key)==null,"another knight cannot borrow a carried key for its prompt");
        entities.dropLift(key,peer,now);check((actor(entities,"door").flags()&2)==0&&entities.actor(key)!=null,"foreign drop changes neither door nor key");
        entities.dropLift(key,player(219,-5,0,now),now);check((actor(entities,"door").flags()&2)==0&&entities.actor(key)!=null,"dropping away from the door cannot consume a key or unlock remotely");
        check(entities.carry(key,owner,now+1),"dropped key remains reusable");
        entities.dropLift(key,player(219,1.5f,0,now+1),now+1);
        check((actor(entities,"door").flags()&6)==6&&entities.actor(key)==null&&!entities.walkable(3,0,219),"owned drop atomically consumes one scene key and starts the blocked native door animation");
        check(entities.carry(second,peer,now+1),"second knight owns its distinct key");entities.dropLift(second,peer,now+1);entities.dropLift(key,owner,now+1);
        check(entities.actor(second)!=null,"an already opened door consumes no second key, including the same scene timestamp");
        entities.advance(now+502,List.of());check(entities.walkable(3,0,219),"door collision opens after its authored animation");
        movement(terrain,content);
        System.out.println("PASS: gold-key capability/ownership, native unlock prompt, proximity, exact single consumption, duplicate/concurrent-intent safety, native drop/door timing and legal movement through the opened door (synthetic geometry)");
    }
    private static SceneContent content(){
        var entries=new ArrayList<SceneContent.Entry>();
        entries.add(entry("door",3,0,Map.of("kind","Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","config","Fixture/Gold Door","door-lock","gold","width","1","height","3","collision-flags","1","door-speed","500")));
        for(String key:List.of("key","second"))entries.add(entry(key,.7f,key.equals("key")?0:.65f,Map.of("kind","DungeonLift","native-type","com.threerings.projectx.dungeon.data.actor.DungeonLift","config","Dynamic/Lift Objects/Gold Key","radius",".2","can-throw","false","collision-flags","0","collision-mask","7","actionable-signal","gold_unlock")));
        return new SceneContent(entries,Map.of(),Map.of(),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());
    }
    private static void movement(CollisionMap terrain,SceneContent content)throws Exception {
        var map=new SceneCatalog.MapScene(903,3<<24,"Fixture","Fixture","fixture","level",1,0,0,0,1,terrain,new byte[]{1},ScenePortals.EMPTY,content);
        var scene=new SceneRegistry.Instance(703,0,map).simulation();int stamp=SceneClock.now();
        try(var owner=scene.join(player(219,0,0,stamp),s->{});var peer=scene.join(player(220,0,.65f,stamp),s->{})){
            owner.enter();peer.enter();input(owner,0,4);input(peer,0,4);
            await(()->pawn(scene,219).activity()==269&&pawn(scene,220).activity()==269,"both knights pick up separate keys through normal input");
            walk(owner,scene,219,1.5f);walk(peer,scene,220,1.5f);
            await(()->pawn(scene,219).interaction()!=null&&pawn(scene,219).interaction().action().equals("UnlockGold"),"scene snapshot publishes UnlockGold for the carried key");
            var first=CompletableFuture.runAsync(()->input(owner,0,4));var second=CompletableFuture.runAsync(()->input(peer,0,4));first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
            await(()->pawn(scene,219).activity()==271&&pawn(scene,220).activity()==271,"concurrent intents begin the normal drop animation");
            check((door(scene).flags()&2)==0,"pressing the button alone does not unlock before the native drop event");
            await(()->(door(scene).flags()&2)!=0,"the actual timed drop opens the door");
            await(()->pawn(scene,219).sceneKnight().lift()==0&&pawn(scene,220).sceneKnight().lift()==0,"both owned carry slots clear");
            long liveKeys=scene.snapshot().actors().stream().filter(a->a.entity()!=null&&a.entity().kind().equals("DungeonLift")&&a.entity().destroyed()==Integer.MAX_VALUE).count();
            check(liveKeys==1,"concurrent drops at one door consume exactly one key");
            walk(owner,scene,219,3.8f);
        }
        check(scene.playerCount()==0,"disconnect leaves no scene occupants");
    }
    private static ActorState door(SceneSimulation scene){return scene.snapshot().actors().stream().filter(a->a.entity()!=null&&a.entity().kind().equals("Door")).findFirst().orElseThrow();}
    private static ActorState actor(SceneEntities scene,String key){return scene.actors().stream().filter(a->a.entity().key().equals(key)).findFirst().orElseThrow();}
    private static ActorState pawn(SceneSimulation scene,int id){return scene.snapshot().players().stream().filter(a->a.id()==id).findFirst().orElseThrow();}
    private static void input(SceneSimulation.Membership member,float facing,int flags){int stamp=SceneClock.now();member.input(stamp,facing,facing,flags,stamp,stamp,System.nanoTime()/1_000_000L);}
    private static void walk(SceneSimulation.Membership member,SceneSimulation scene,int id,float x)throws Exception {
        long until=System.nanoTime()+5_000_000_000L;
        while(pawn(scene,id).x()<x){
            if(System.nanoTime()>until)throw new AssertionError("Gold doorway walk blocked: x="+pawn(scene,id).x()+", activity="+pawn(scene,id).activity()+", door flags="+door(scene).flags());
            input(member,0,1);Thread.sleep(25);
        }
        input(member,0,0);
    }
    private static void await(BooleanSupplier condition,String message)throws Exception{long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(5);}throw new AssertionError(message);}
    private static ActorState player(int id,float x,float y,int stamp){return new ActorState(id,1000+id,"Key-"+id,0,stamp,x,y,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
    private static SceneContent.Entry entry(String key,float x,float y,Map<String,String> fields){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(fields));}
}
