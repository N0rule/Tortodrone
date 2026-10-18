import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.*;
import org.tortodrone.protocol.*;
import com.threerings.config.*;
import com.threerings.tudey.config.ActorConfig;

/** Native actor class, config resolution and destruction delta oracle for pot hazards. */
public final class HazardCompatibility {
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resources=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resources.getClass().getMethod("ga",String.class).invoke(resources,Path.of(args[0]).resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resources,null,"config/");manager.init();
        var map=SceneRegistry.defaultEntry().map();
        var world=new SceneEntities(new SceneContent(List.of(),Map.of()),map.collision());
        var spawn=SceneEntities.class.getDeclaredMethod("hazard",String.class,Object.class,float.class,int.class,Map.class,float.class,float.class,int.class);spawn.setAccessible(true);
        Object fire=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of("flags",2));
        spawn.invoke(world,"Bomb/Monster/ScorcherTorch",fire,1f,6000,Map.of("config.argument.Lifetime","string|4 Seconds","hazard-active-lifetime","4000"),map.spawnX(),map.spawnY(),1000);
        for(int i=0;i<9;i++)spawn.invoke(world,"Bomb/Monster/Oil Slick",null,.45f,15000,Map.of(),map.spawnX(),map.spawnY(),1000);
        var actors=world.actors();
        var added=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,1000,1000,(short)50,actors,List.of(),new int[0],Map.of(),true));
        for(int i=0;i<actors.size();i++){
            var source=actors.get(i);var nativeActor=added.aej()[i];
            var reference=FireCompatibility.reference(source.config());
            var config=manager.a(ActorConfig.class,(ConfigReference<ActorConfig>)reference);var original=config.ai(manager);
            var expected=original.a((ConfigReference<ActorConfig>)reference,source.id(),1000,new com.threerings.math.Vector2f(),0);
            if(nativeActor.getClass()!=expected.getClass())throw new AssertionError("Wrong hazard class: "+nativeActor.getClass());
            nativeActor.init(manager);
            if(!(nativeActor instanceof com.threerings.projectx.dungeon.data.actor.Bomb)||!FireCompatibility.field(nativeActor,"_config").equals(reference)
                ||(float)FireCompatibility.field(nativeActor,"_z")!=Float.NEGATIVE_INFINITY)throw new AssertionError("Bomb configuration/floor state differs");
            if(i==0){
                if(!"4 Seconds".equals(reference.kW().get("Lifetime")))throw new AssertionError("Lifetime choice lost");
                var models=(Object[])original.sprite.model.kW().get("Models");
                var model=(ConfigReference<?>)models[0].getClass().getField("model").get(models[0]);
                if(!"4 seconds".equals(model.kW().get("Lifespan")))throw new AssertionError("Native flame did not resolve to four seconds: "+reference.kW()+" -> "+model.kW());
            }
            world.advance(source.config().name().endsWith("ScorcherTorch")?7000:16000,List.of());
            var ended=world.actors().stream().filter(a->a.id()==source.id()).findFirst().orElseThrow();
            var event=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,1000,(short)0,1000,16000,(short)50,List.of(),List.of(ended),new int[0],Map.of(source.id(),source),true));
            var applied=event.aek()[0].apply(nativeActor);
            if((int)FireCompatibility.field(applied,"_destroyed")!=(i==0?7000:16000))throw new AssertionError("Bomb expiry delta differs");
        }
        System.out.println("PASS: ten native Fire/Oil Bomb actors, resolved four-second flame, floor Z and destruction deltas");
    }
}
