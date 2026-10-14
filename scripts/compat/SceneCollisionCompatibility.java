import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.*;
import com.threerings.math.*;
import org.tortodrone.world.*;

/** Small stock-oracle check for native masks/shapes, separate from player walkability. */
public final class SceneCollisionCompatibility {
    public static void main(String[] args)throws Exception {
        Path game=Path.of(args[0]),pack=Path.of(args[1]);
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(Files.readAllBytes(pack.resolve("scene.dat")));scene.init(cm);
        var collision=SceneCollision.load(pack,"bbf967c39b3883e438fd8165fba74c3be45cfcbecdec9c585f1edaa21d1cc144");
        var pc=cm.d(ActorConfig.class,"Character/PC/Default").ai(cm);
        Object playerShape=FireCatalogExport.argumentValue(pc.shape);int checked=0,different=0;
        var walk=CollisionMap.read(Path.of("content/packs/vanilla/maps/advanced_training/walkability.gz"));
        // The stock default pawn and two independently exported projectile profiles.
        var profiles=new ArrayList<FireCatalog.ActorSpec>();
        var fires=FireCatalog.load(Path.of(args[2]));
        for(String name:List.of("Weapon/Handgun/Proto Gun","Weapon/Handgun/Blitz Needle")){
            var found=new LinkedHashMap<String,FireCatalog.ActorSpec>();
            for(var attack:fires.entries())if(attack.weaponConfig().equals(name))for(var fire:attack.fireRefs())collect(fire.action(),found);
            profiles.add(found.values().stream().filter(a->a.nativeType().equals("BULLET")).findFirst().orElseThrow());
        }
        for(float x=-33.125f;x<35;x+=1.25f)for(float y=-23.125f;y<25;y+=1.25f){
            var transform=new Transform2D(new Vector2f(x,y),0);
            boolean nativePlayer=!scene.a(pc.collisionMask,pc.shape.acP().d(transform));
            if(collision.clear(pc.collisionMask,playerShape,x,y,0)!=nativePlayer)throw new AssertionError("Pawn geometry differs at "+x+","+y);
            boolean floor=scene.aQ((int)Math.floor(x),(int)Math.floor(y))!=null;
            if(collision.floorSupported(x,y)!=floor)throw new AssertionError("Native floor differs");checked++;
            for(var profile:profiles){
                @SuppressWarnings("unchecked") var ref=(ConfigReference<ActorConfig>)FireCompatibility.reference(profile.config());
                var actor=cm.a(ActorConfig.class,ref).ai(cm);
                boolean expected=!scene.a(actor.collisionMask,actor.shape.acP().d(transform));
                boolean actual=collision.clear(profile.collisionMask(),profile.collisionShape(),x,y,0);
                if(expected!=actual)throw new AssertionError("Projectile geometry differs at "+x+","+y+" for "+profile.config().name());
                if(actual&&!walk.walkable(x,y)&&floor)different++;checked++;
            }
        }
        if(different==0)throw new AssertionError("No player-blocked/projectile-clear position checked");
        System.out.println("PASS: "+checked+" stock mask/shape/floor comparisons; "+different+" floor positions pass projectiles while the old pawn grid blocks them");
    }
    private static void collect(FireCatalog.Action action,Map<String,FireCatalog.ActorSpec> out){if(action.actor()!=null)out.putIfAbsent(action.actor().config().name(),action.actor());for(var child:action.children())collect(child,out);}
}
