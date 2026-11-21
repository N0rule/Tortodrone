package org.tortodrone.world;

import java.lang.reflect.*;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.world.FireCatalog;

/** Official-capture pot torches: Fire Power 100 for a direct Fire Pot, Fire (Scaled)
 *  flags 1 / 0.0 / 0 for oil ignition, and sequential ignition down an oil line. */
public final class OilLineTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        directTorch(map);
        oilLine(map);
        System.out.println("PASS: pot torch status constants and oil-line chain propagation");
    }

    private static void directTorch(SceneCatalog.MapScene map)throws Exception {
        var fields=new HashMap<String,String>();
        fields.put("native-type","com.threerings.projectx.dungeon.data.actor.DungeonLift");
        fields.put("config","Dynamic/Lift Objects/Fire Pot");
        fields.put("kind","DungeonLift");
        fields.put("radius",".45");
        fields.put("collision-flags","514");
        var entry=new SceneContent.Entry("firepot",5.5f,-12.5f,0,new SceneContent.Spec(fields));
        var world=new SceneEntities(new SceneContent(List.of(entry),map.content().scales(),Map.of(),
            map.content().stats(),Map.of(),Map.of(),map.content().statuses()),map.collision());
        var pot=world.actors().getFirst();
        land(world,pot.id(),1000);
        var torch=torches(world).getFirst();
        assertStatus(hazardStatus(world,torch.id()),"Fire",2,1f,100);
    }

    private static void oilLine(SceneCatalog.MapScene map)throws Exception {
        float x=5.5f,y=-12.5f,spacing=.91f;
        var entries=new ArrayList<SceneContent.Entry>();
        for(int i=0;i<5;i++)entries.add(new SceneContent.Entry("slick"+i,x,y+i*spacing,0,slick()));
        var world=new SceneEntities(new SceneContent(entries,map.content().scales(),Map.of(),
            map.content().stats(),Map.of(),Map.of(),map.content().statuses()),map.collision());
        int start=1000;
        world.advance(start,List.of());
        var fire=new FireCatalog.DataValue("Damage$Status",Map.of("flags",2,
            "statusCondition",new StarterCatalog.Model("Fire",Map.of())));
        world.damage(pointHit(fire),x,y,0,start+1,7);
        check(torches(world).size()==1,"the first oil patch ignites alone");
        for(int step=1;step<=4;step++)world.advance(start+1+step*500,List.of());
        var live=torches(world);
        check(live.size()==5,"fire propagates across all five oil patches");
        var created=live.stream().map(ActorState::created).sorted().toList();
        check(created.getLast()>=start+2000,"the chain reaches the far patch on later hazard ticks");
        var last=live.stream().max(Comparator.comparingDouble(ActorState::y)).orElseThrow();
        check(Math.abs(last.y()-(y+4*spacing))<.001f,"the last torch sits on the last patch");
        assertStatus(hazardStatus(world,last.id()),"Fire (Scaled)",1,0f,0);
    }

    private static SceneContent.Spec slick(){
        var fields=new HashMap<String,String>();
        fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Bomb");
        fields.put("config","Bomb/Monster/Oil Slick");
        fields.put("shape","Circle");
        fields.put("radius",".45");
        fields.put("collision-flags","16");
        fields.put("hazard-lifetime","15000");
        return new SceneContent.Spec(fields);
    }
    private static StarterCatalog.Model pointHit(Object damage){
        return new StarterCatalog.Model("DamageRegionFromPoint",Map.of(
            "damage",damage,
            "region",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of("expansion",0f)),
            "shape",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,
                List.of(new FireCatalog.FieldValue("radius","float",.1f)))));
    }
    private static void land(SceneEntities world,int id,int stamp)throws Exception {
        var entities=SceneEntities.class.getDeclaredField("entities");entities.setAccessible(true);
        var entity=((Map<?,?>)entities.get(world)).get(id);
        var method=SceneEntities.class.getDeclaredMethod("potLand",entity.getClass(),int.class);method.setAccessible(true);
        method.invoke(world,entity,stamp);
    }
    private static Object hazardStatus(SceneEntities world,int id)throws Exception {
        var entities=SceneEntities.class.getDeclaredField("entities");entities.setAccessible(true);
        var entity=((Map<?,?>)entities.get(world)).get(id);
        var field=entity.getClass().getDeclaredField("hazardStatus");field.setAccessible(true);return field.get(entity);
    }
    private static void assertStatus(Object raw,String name,int flags,float chance,int power){
        var damage=(FireCatalog.DataValue)raw;
        check(damage!=null,"a torch carries its hazard status");
        check(((Number)SceneDamage.field(damage,"flags")).intValue()==flags,"torch damage flags");
        var condition=(StarterCatalog.Model)SceneDamage.field(damage,"statusCondition");
        check(condition.name().equals(name),"torch status model: "+condition.name());
        check(((Number)condition.arguments().get("Chance")).floatValue()==chance,"torch status chance");
        check(((Number)condition.arguments().get("Power")).intValue()==power,"torch status power");
    }
    private static List<ActorState> torches(SceneEntities world){
        return world.actors().stream().filter(a->a.config().name().equals("Bomb/Monster/ScorcherTorch")).toList();
    }
}
