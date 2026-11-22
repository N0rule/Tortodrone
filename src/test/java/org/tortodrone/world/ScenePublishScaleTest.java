package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.protocol.SessionCodec;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.resources.SceneCatalog;

/**
 * Scale check for the shared town publication path. A shared town ticks one simulation for
 * every player in it, so snapshot capture, delta construction and frame encoding grow with
 * user count. Recipients of one tick differ only in a few header fields, but each connection
 * owns a class/string symbol table, so the shared body is a template that must materialize to
 * exactly the bytes a direct per-connection encode would produce.
 */
public final class ScenePublishScaleTest {
    private static final int PLAYERS=200;

    public static void main(String[] args)throws Exception {
        var catalog=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps"));
        var haven=catalog.scene(1);
        var largest=catalog.scenes().stream().max(Comparator.comparingInt(s->s.content().entries().size())).orElseThrow();
        sharedEncoding(haven,"haven");
        sharedEncoding(largest,"largest scene "+largest.id());
        tickCost(largest);
        sceneThreads(haven);
        System.out.println("PASS: shared scene templates materialize byte-identically per connection, stay cheap for 200 recipients, keep a dense monster map inside the tick SLO and keep occupied scenes off platform threads");
    }

    private static List<ActorState> players(org.tortodrone.core.world.MapScene map){
        var players=new ArrayList<ActorState>();
        for(int i=0;i<PLAYERS;i++)players.add(new ActorState(1000+i,2000+i,"Scale "+i,256,1,
            map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Fixture",Map.of())));
        return players;
    }

    private static void sharedEncoding(org.tortodrone.core.world.MapScene map,String label)throws Exception {
        var instance=new SceneRegistry.Instance(790,1,map);
        var simulation=instance.simulation();
        var members=new ArrayList<SceneSimulation.Membership>();
        var players=players(map);
        for(var player:players)members.add(simulation.join(player,s->{ }));
        for(var member:members)member.enter();
        for(int i=0;i<50;i++)simulation.snapshot();
        var baseline=simulation.snapshot();
        // One native effect after the baseline makes the per-delta effect list non-empty so its
        // symbol positions are covered too.
        members.getFirst().effect(new StarterCatalog.Model("Fixture/Scale Effect",Map.of()));
        var current=simulation.snapshot();
        var first=encoded(current,baseline,map,instance);
        var again=encoded(current,baseline,map,instance);
        check(again==first,"a repeated publication for the same baseline reuses the shared encoding: "+label);
        check(!first.effects().isEmpty(),"the shared encoding includes the pending scene effect: "+label);

        int[][] headers={{2000,0,0,0,50},{2001,12345,7,99,50},{2002,-1,-1,-1,255},{2003,Integer.MAX_VALUE,Short.MAX_VALUE,Integer.MIN_VALUE,1}};
        var cold=new SessionCodec();
        check(cold.materialize(first.frame(),2000,0,(short)0,0,(short)50)==null,
            "a cold connection falls back instead of emitting an unknown symbol code: "+label);
        var warm=new SessionCodec();
        // A different message history may assign different symbol codes; materialization must
        // use this connection's table, not the one that built the template.
        warm.bootstrap(1,2000,"127.0.0.1",47624);
        for(var header:headers){
            int oid=header[0],acknowledge=header[1],reference=header[3];
            short ping=(short)header[2],elapsed=(short)header[4];
            // The first delta for a connection is the direct fallback; it registers every symbol.
            warm.sceneDelta(oid,instance.oid(),acknowledge,ping,reference,current.timestamp(),elapsed,
                first.delta().added(),first.delta().updated(),first.delta().removed(),first.delta().baseline(),
                !map.town(),first.effects(),first.combat());
            var expected=warm.sceneDelta(oid,instance.oid(),acknowledge,ping,reference,current.timestamp(),elapsed,
                first.delta().added(),first.delta().updated(),first.delta().removed(),first.delta().baseline(),
                !map.town(),first.effects(),first.combat());
            var shared=warm.materialize(first.frame(),oid,acknowledge,ping,reference,elapsed);
            check(shared!=null,"a warm connection materializes the shared template: "+label);
            check(Arrays.equals(expected,shared),
                "materialized frame is byte-identical to direct encoding for oid "+oid+": "+label);
        }

        // A later tick that adds one actor and removes another exercises those array branches.
        var extra=simulation.join(new ActorState(9001,9001,"Scale extra",256,1,
            map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Fixture",Map.of())),s->{ });
        extra.enter();
        members.getFirst().close();
        var nextCurrent=simulation.snapshot();
        var next=encoded(nextCurrent,current,map,instance);
        check(next.delta().added().size()==1&&next.delta().removed().length==1,
            "the later tick carries one added and one removed actor: "+label);
        for(var header:headers){
            int oid=header[0],acknowledge=header[1],reference=header[3];
            short ping=(short)header[2],elapsed=(short)header[4];
            warm.sceneDelta(oid,instance.oid(),acknowledge,ping,reference,nextCurrent.timestamp(),elapsed,
                next.delta().added(),next.delta().updated(),next.delta().removed(),next.delta().baseline(),
                !map.town(),next.effects(),next.combat());
            var expected=warm.sceneDelta(oid,instance.oid(),acknowledge,ping,reference,nextCurrent.timestamp(),elapsed,
                next.delta().added(),next.delta().updated(),next.delta().removed(),next.delta().baseline(),
                !map.town(),next.effects(),next.combat());
            var shared=warm.materialize(next.frame(),oid,acknowledge,ping,reference,elapsed);
            check(shared!=null&&Arrays.equals(expected,shared),
                "added/removed actor records materialize byte-identically for oid "+oid+": "+label);
        }

        // The whole town's recipients must stay far below the 50 ms tick budget.
        long began=System.nanoTime();
        for(int tick=0;tick<10;tick++)for(var player:players)
            warm.materialize(first.frame(),player.clientOid(),0,(short)1,0,(short)50);
        double ms=(System.nanoTime()-began)/1_000_000.0/10;
        System.out.printf(Locale.ROOT,"%s: %d players, %d-byte template, materialized all recipients in %.3f ms%n",
            label,PLAYERS,first.frame().template().length,ms);
        check(ms<50,"200 recipient frames stay far below one tick: "+label);

        began=System.nanoTime();
        int captures=200;
        for(int i=0;i<captures;i++)simulation.snapshot();
        ms=(System.nanoTime()-began)/1_000_000.0/captures;
        System.out.printf(Locale.ROOT,"%s: capture at %d players %.3f ms%n",label,PLAYERS,ms);
        check(ms<50,"capture at 200 players stays below one tick: "+label);
        // Stop the 200-player test scene so later measurements are not competing with its ticker.
        for(var member:members)member.close();
        extra.close();
    }

    private static SceneSnapshot.EncodedDelta encoded(SceneSnapshot current,SceneSnapshot baseline,
            org.tortodrone.core.world.MapScene map,SceneRegistry.Instance instance)throws Exception {
        return current.encoded(baseline,!map.town(),(delta,effects,combat,interior)->
            SessionCodec.sceneDeltaTemplate(ItemCatalogHolder.CATALOG,instance.oid(),current.timestamp(),
                delta.added(),delta.updated(),delta.removed(),delta.baseline(),interior,effects,combat));
    }

    /** A monster-heavy prepared scene with a full party must stay inside the published tick SLO. */
    private static void tickCost(org.tortodrone.core.world.MapScene map){
        var entities=new SceneEntities(map.content(),map.collision(),map.staticCollision(),new Random(1),true,SceneClock.now());
        var players=new ArrayList<ActorState>();
        for(int i=0;i<8;i++)players.add(new ActorState(1000+i,2000+i,"Scale "+i,256,1,
            map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Fixture",Map.of())));
        int stamp=SceneClock.now();
        for(int t=0;t<100;t++,stamp+=50)entities.advance(stamp,players);
        long began=System.nanoTime();
        int ticks=200;
        for(int t=0;t<ticks;t++,stamp+=50)entities.advance(stamp,players);
        double ms=(System.nanoTime()-began)/1_000_000.0/ticks;
        System.out.printf(Locale.ROOT,"scene %d: %d live monsters, 8-player tick %.3f ms%n",
            map.id(),entities.liveMonsters(),ms);
        check(ms<8,"an 8-player tick on the largest prepared scene stays inside the SLO");
    }

    /** Many occupied level instances must not reserve one platform thread each. */
    private static void sceneThreads(org.tortodrone.core.world.MapScene map){
        int before=Thread.getAllStackTraces().size();
        var members=new ArrayList<SceneSimulation.Membership>();
        for(int i=0;i<100;i++){
            var instance=new SceneRegistry.Instance(2000+i,1,map);
            var member=instance.simulation().join(new ActorState(3000+i,3000+i,"Thread "+i,256,1,
                map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Fixture",Map.of())),s->{ });
            member.enter();
            members.add(member);
        }
        int after=Thread.getAllStackTraces().size();
        System.out.println("100 occupied scenes added "+(after-before)+" platform threads");
        check(after-before<20,"occupied scene tickers run on virtual threads");
        for(var member:members)member.close();
    }

    private static final class ItemCatalogHolder {
        static final org.tortodrone.core.character.ItemCatalog CATALOG=org.tortodrone.core.character.ItemCatalog.starter();
    }
}
