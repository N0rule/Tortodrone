package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry: real scene scheduler/effect publication/spawn/impulse paths. */
public final class SceneGameplayEffectTest {
    public static void main(String[] args)throws Exception {
        var vector=new FireCatalog.DataValue("com.threerings.math.Vector2f",Map.of("x",2f,"y",0f));
        var impulse=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("translation",vector,"duration",200,"level",10));
        var region=new StarterCatalog.Model("ImpartImpulse",Map.of("region",new FireCatalog.DataValue("com.threerings.tudey.config.RegionConfig$Default",Map.of("expansion",0f)),"shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Point",Map.of()),"impulse",impulse,"impulse-origin",new FireCatalog.DataValue("com.threerings.math.Vector2f",Map.of("x",0f,"y",0f)),"flags",1));
        for(var invalid:List.of(
                new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("translation",vector,"duration",-1,"level",10)),
                new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("translation",vector,"duration",200,"level",Float.NaN)),
                new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("translation",new FireCatalog.DataValue("com.threerings.math.Vector2f",Map.of("x",Float.POSITIVE_INFINITY,"y",0f)),"duration",200,"level",10)))){
            var bad=new HashMap<>(region.arguments());bad.put("impulse",invalid);
            try{new SceneActions.Impulse(new StarterCatalog.Model("ImpartImpulse",bad));throw new AssertionError("Invalid native impulse admitted");}catch(IllegalArgumentException expected){}
        }
        var fields=Map.of("config","Fixture/Breakable","kind","Block","native-type","com.threerings.projectx.dungeon.data.actor.Block","shape","Circle","radius",".25","damage-action","destroy","collision-flags","1");
        var body=Map.of("kind","delay","millis",150,"action",Map.of("kind","sequence","stopOnFailure",true,"actions",List.of(Map.of("kind","source-impulse","config",FireCatalog.modelJson(region)),Map.of("kind","spawn","activate",false,"fields",fields))));
        String graph=Json.stringify(Map.of("kind","gameplay-effect","config",FireCatalog.modelJson(new StarterCatalog.Model("Fixture/Emerge",Map.of())),"tag","emerge","action",body));
        var action=(SceneActions.GameplayEffect)SceneActions.parse(graph);check(action.locationTag().equals("emerge"),"typed source/tagged effect graph");
        var area=new SceneContent.Entry("10",0,0,0,new SceneContent.Spec(Map.of("config","Fixture/Area","kind","SceneTrigger","handler-index","0","event","signal-start","watch-tag","area","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".4","action",graph)));
        var marker=new SceneContent.Entry("20",2,0,(float)Math.PI/2,new SceneContent.Spec(Map.of("config","Fixture/Marker","kind","SceneAnchor","source-tags","emerge")));
        var content=new SceneContent(List.of(area,marker),Map.of());var scene=new SceneEntities(content,SceneWeaponsTest.openTerrain());var peer=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        var effects=new ArrayList<ActorState>();var pushes=new HashMap<Integer,KnightCombat.Impulse>();
        scene.effects((config,source,stamp)->{check(config.equals(action.config())&&stamp==1000,"native effect reference/timestamp retained");effects.add(source);});scene.playerImpulse(pushes::put);
        var players=List.of(player(21,0),player(22,2.1f),player(23,2.6f));
        scene.advance(1000,players);check(effects.size()==1&&effects.getFirst().entity().entrySource()&&effects.getFirst().entity().key().equals("20"),"publication targets the authored entry, without a fake effect actor");
        check(contexts(scene)==1&&scene.actors().isEmpty()&&pushes.isEmpty(),"effect source survives only while its delayed gameplay remains queued");
        scene.advance(1149,players);check(scene.actors().isEmpty()&&pushes.isEmpty(),"authored delay is not shortened");
        scene.advance(1150,players);peer.advance(1150,List.of());
        var block=scene.actors().stream().filter(a->a.config().name().equals("Fixture/Breakable")).findFirst().orElseThrow();
        check(block.x()==2&&block.y()==0&&block.rotation()==marker.rotation()&&block.created()==1150,"spawn executes from the frozen effect location/orientation at the authored time");
        check(pushes.keySet().equals(Set.of(22))&&Math.abs(pushes.get(22).x()-2)<.001&&Math.abs(pushes.get(22).y())<.001&&pushes.get(22).duration()==200,"native point region, mask, power, duration and radial impulse; distant pawn excluded");
        check(contexts(scene)==0&&contexts(peer)==0&&peer.actors().isEmpty(),"completed effect contexts are released and isolated from peers");
        scene.advance(1200,players);check(scene.actors().size()==1&&effects.size()==1,"held intersection does not repeat gameplay or visuals");
        System.out.println("PASS: gameplay effect -> frozen source -> exact delayed point impulse and actor spawn, native publication identity, finite/bounded parameters, context cleanup and scene isolation (synthetic geometry)");
    }
    private static int contexts(SceneEntities scene)throws Exception{var f=SceneEntities.class.getDeclaredField("effectSources");f.setAccessible(true);return ((Map<?,?>)f.get(scene)).size();}
    private static ActorState player(int id,float x){return new ActorState(id,1001,"Fixture",0,1000,x,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
}
