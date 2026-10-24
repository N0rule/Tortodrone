import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.opengl.model.config.ModelConfig;
import com.threerings.projectx.dungeon.config.*;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.actor.Actor;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.world.*;

/** Read-only retail oracle: retained skill parameters, intentional Ban Stick damage,
 * generator add/delta state, and the complete native Spark particle reference.
 * Stripped Quill headings/cadence and original-server Spark parity are NOT established. */
public final class GameplayParityCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path game=Path.of(args[0]);
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var fires=FireCatalog.load(Path.of("content/_internal/fires.json"));
        var weapon=(ItemConfig.Weapon)manager.a(ItemConfig.class,new ConfigReference<ItemConfig>("Weapon/Sword/The Ban Stick")).w(manager);
        var charged=(DamageConfig.Normal)weapon.chargedAttack.kW().get("Damage");
        check(charged.amount==9999&&charged.flags==3,"actual Ban Stick item binds player-inclusive heavy damage");
        for(var fire:fires.find("Weapon/Sword/The Ban Stick",290).fireRefs())
            check(SceneDamage.number(fire.action().config().arguments().get("damage"),"flags",0)==charged.flags,"served heavy target mask matches native item binding");
        for(int activity:new int[]{292,293,294})for(var fire:fires.find("Weapon/Sword/The Ban Stick",activity).fireRefs())
            check(SceneDamage.number(fire.action().config().arguments().get("damage"),"flags",0)==2,"normal swings retain monster-only damage");

        var petFires=FireCatalog.load(Path.of("content/_internal/pet-fires.json"));int skills=0;
        for(String suffix:List.of("",", Ultimate A",", Ultimate B"))for(int power=0;power<4;power++){
            String name="Sprite/Maskeraith/Caustic Quills"+suffix;
            var ref=new ConfigReference<AttackConfig>(name);ref.kW().put("Power1","0");ref.kW().put("Power2",Integer.toString(power));
            var nativeAttack=AttackConfig.b(manager,ref);
            var nativeFire=manager.a(FireActionConfig.class,nativeAttack.fireRefs[0].ref);
            var spawn=(ActionConfig.SpawnActor)nativeFire.action;
            var spawner=manager.a(ActorConfig.class,spawn.actor).ai(manager);
            var timer=(HandlerConfig.Timer)spawner.handlers[0];
            var served=petFires.find(name+"|0|"+power+"|NORMAL",292);
            var servedSpawner=served.fireRefs().getFirst().action().actor();
            if(timer.limit>0)check(servedSpawner.timers().getFirst().limit()==timer.limit,"normal Quill count matches native rank binding");
            else check(!servedSpawner.config().arguments().containsKey("Bullet Count"),"Ultimate has no retained finite projectile count; do not claim the exporter's safety bound as native behavior");
            check(served.attack().arguments().get("land").equals(nativeAttack.Qh()),"Quill launch timing matches native attack");skills++;
        }

        var maps=SceneCatalog.load(Path.of("content/maps"));
        var vial=maps.scene(294).content().entries().stream().filter(e->e.spec().child().config().name().equals("Item/Pickups/Vial/Fire Vial")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var padReference=(ConfigReference<ActorConfig>)FireCompatibility.reference(vial.spec().config());
        var nativePad=(DungeonActorConfig.Generator)manager.a(ActorConfig.class,padReference).ai(manager);
        check(nativePad.delay==vial.spec().integer("delay",-1)&&nativePad.warning==0&&vial.spec().integer("warning",-1)==0,"Training vial delay/warning match original placement arguments");
        var sprite=(DungeonActorSpriteConfig.Generator)nativePad.sprite;
        check(sprite.preGenerationTransient.getName().endsWith("fx_countdown.dat")&&sprite.generationTransient.getName().endsWith("fx_spawnin.dat"),"native warning and spawn use distinct stock models");
        var baseline=pad(vial.spec().config(),0,1);
        var added=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(baseline),List.of(),new int[0],Map.of(),true));
        Actor actor=added.aej()[0];actor.init(manager);
        for(var state:List.of(pad(baseline.config(),2,1),pad(baseline.config(),0,2))){
            var update=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(state),new int[0],Map.of(state.id(),baseline),true));
            actor=(Actor)update.aek()[0].apply(actor);
            check((int)FireCompatibility.field(actor,"_flags")==state.flags()&&(int)FireCompatibility.field(actor,"_count")==state.entity().count(),"native delta preserves warning flags and generation counter");
        }
        var late=FireCompatibility.read(new SessionCodec().sceneDelta(1002,140,0,(short)0,100,150,(short)50,List.of(pad(baseline.config(),2,1)),List.of(),new int[0],Map.of(),true)).aej()[0];
        check((int)FireCompatibility.field(late,"_flags")==2&&(int)FireCompatibility.field(late,"_count")==1,"late observer gets pending warning in initial snapshot");

        var spark=manager.a(EffectConfig.class,new ConfigReference<EffectConfig>("Player/Spark Rez"));
        check(spark.lifespan==2000&&spark.sprite.model.getName().equals("particle/fx_shareheart-energy.dat"),"Spark uses the original two-second particle effect");
        var particles=manager.a(ModelConfig.class,spark.sprite.model).getOriginal();
        Object[] layers=(Object[])FireCompatibility.field(particles,"layers");
        check(layers.length==13,"all thirteen native Spark particle layers load");
        var effect=new SceneEffect(1,new StarterCatalog.Model("Player/Spark Rez",Map.of()),150,7,9,0,0,42);
        var event=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(),new int[0],Map.of(),true,List.of(effect)));
        var decoded=event.aem()[0];
        check(FireCompatibility.field(decoded,"_target") instanceof com.threerings.tudey.data.EntityKey.Actor target&&target.vw()==42&&(int)FireCompatibility.field(decoded,"_clientOid")==0,"Spark effect reaches revived knight and observers with its actor target");
        System.out.println("PASS: intentional Ban Stick masks; "+skills+" native Quill rank/ultimate bindings and land times; generator adds/deltas/late observer; complete Spark particle reference and broadcast target");
        System.out.println("NOT VERIFIED: stripped Quill projectile headings/cadence and original-server Spark visual sequence");
    }
    private static ActorState pad(StarterCatalog.Model config,int flags,int count){
        return new ActorState(77,0,"",0,100,0,0,0,0,flags,0,100,config,false,null,null,new SceneEntityState("Generator",Integer.MAX_VALUE,0,0,-1,0,100,count,0,"pad"));
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
