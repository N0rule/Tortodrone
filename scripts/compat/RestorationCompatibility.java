import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.opengl.model.config.*;
import com.threerings.tudey.data.actor.Actor;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.world.*;


/** Installed-client actor initialization and retained orbit-gun model skeletons. */
public final class RestorationCompatibility {
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path game=Path.of(args[0]);
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var catalog=FireCatalog.load(Path.of("content/_internal/fires.json"));
        var maps=SceneCatalog.load(Path.of(System.getProperty("tortodrone.deployment")).resolve("content/maps"));
        int checked=0;
        for(String name:List.of("Fiery Diskgun","Static Diskgun","Overcharged Mixmaster (OLD)","Celestial Orbitgun (OLD)"))for(int activity:List.of(292,290)){
            var scene=new SceneWeapons(maps.entry().collision());var owner=new ActorState(51,1001,"Fixture",0,100,maps.entry().spawnX(),maps.entry().spawnY(),0,0,0,new StarterCatalog.Model("Fixture",Map.of()));
            scene.fire(catalog.find("Weapon/Handgun/"+name,activity).fireRefs().getFirst().action(),owner,100);
            var snapshots=scene.actors();var codec=new SessionCodec();var event=FireCompatibility.read(codec.sceneDelta(1001,140,0,(short)0,100,100,(short)50,snapshots,List.of(),new int[0],Map.of(),true));
            for(Object decoded:event.aej()){
                var actor=(Actor)decoded;actor.init(manager);checked++;
                FireCompatibility.check(FireCompatibility.field(actor,"_config")!=null,"legacy config initializes");
            }
            var updates=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,101,(short)50,List.of(),snapshots,new int[0],snapshots.stream().collect(java.util.stream.Collectors.toMap(ActorState::id,a->a)),true));
            for(int i=0;i<updates.aek().length;i++)updates.aek()[i].apply(event.aej()[i]);
        }
        for(String name:List.of("Catalyzer","Industrial Catalyzer","Volatile Catalyzer","Neutralizer","Toxic Catalyzer","Virulent Catalyzer","Biohazard","Pollinator")){
            var fire=catalog.find("Weapon/Handgun/"+name,292).fireRefs().getFirst().action();
            while(fire.actor()==null)fire=fire.children().getFirst();
            var spec=fire.actor().attachment();FireCompatibility.check(spec!=null,"retained tag reference");
            var state=new ProjectileState("ORBITAL",spec.config(),0,100,Integer.MAX_VALUE,1,2,ProjectileState.UNSPECIFIED_Z,spec.speed(),spec.maxStep(),51,false,new ProjectileState.Orbit(spec.orbit().radius(),77,.5f,0,0,0,51));
            var snapshot=new ActorState(71,0,"",0,100,1.5f,2,0,0,0,0,100,spec.config(),false,null,state);
            var event=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(snapshot),List.of(),new int[0],Map.of(),true));
            var actor=(Actor)event.aej()[0];actor.init(manager);
            FireCompatibility.check((int)FireCompatibility.field(actor,"_followId")==77,"native tag follows the struck actor rather than its owner");
        }
        for(String name:List.of("Player/Learn Recipe","Energy Spent")){
            var model=new StarterCatalog.Model(name,Map.of());
            var event=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(),List.of(),new int[0],Map.of(),true,List.of(new SceneEffect(1,model,100,1,2,0,0,51))));
            Object effects=null;for(var f:event.getClass().getDeclaredFields())if(f.getType().isArray()&&f.getType().getComponentType().getName().endsWith(".Effect")){f.setAccessible(true);effects=f.get(event);}
            FireCompatibility.check(effects!=null,"native scene effect collection");var effect=java.lang.reflect.Array.get(effects,0);
            FireCompatibility.check((int)FireCompatibility.field(effect,"_clientOid")==0&&FireCompatibility.field(effect,"_target") instanceof com.threerings.tudey.data.EntityKey.Actor target&&target.vw()==51,"server effect targets the knight and reaches owner/observers");
        }
        for(int rank=3;rank<=5;rank++){
            String standard="item/weapon/handgun/orbit/model_r"+rank+".dat";
            var base=manager.a(ModelConfig.class,new ConfigReference<ModelConfig>(standard));FireCompatibility.check(base!=null,"standard orbit model exists");
            var original=base.getOriginal();FireCompatibility.check(original instanceof ArticulatedConfig,"standard articulated weapon");
            var bones=new TreeSet<>(Arrays.asList(((ArticulatedConfig)original).getNodeNames()));
            for(String family:List.of("fir","fre","poi","sho")){
                String file="item/weapon/handgun/orbit/model_"+family+"_r"+rank+".dat";
                var model=manager.a(ModelConfig.class,new ConfigReference<ModelConfig>(file));FireCompatibility.check(model!=null&&model.getOriginal() instanceof ArticulatedConfig,"elemental model loads: "+file);
                var nodes=new TreeSet<>(Arrays.asList(((ArticulatedConfig)model.getOriginal()).getNodeNames()));
                FireCompatibility.check(nodes.equals(bones),"elemental model uses the standard rank skeleton: "+file);
                FireCompatibility.check(Arrays.stream(model.parameters).map(p->p.name).toList().equals(Arrays.stream(base.parameters).map(p->p.name).toList()),"elemental/standard parameter compatibility");
                System.out.println("MODEL "+family+" rank="+rank+" standardNodes="+bones+" variantNodes="+nodes+" parameters="+Arrays.stream(model.parameters).map(p->p.name).toList());
            }
        }
        System.out.println("PASS: "+checked+" legacy carrier/orbital native adds, deltas and initialization; eight tag references; two targeted effects; twelve elemental and three standard model loads");
    }
}
