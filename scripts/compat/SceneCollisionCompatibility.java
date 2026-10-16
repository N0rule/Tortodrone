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
        var pawn=new com.threerings.projectx.dungeon.data.actor.Dungeoneer(new ConfigReference<ActorConfig>("Character/PC/Default"),219,100,new Vector2f(),0);pawn.init(cm);
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
            var separating=new Vector2f();scene.a(pawn,pc.shape.acP().d(transform),separating);
            var actualSeparation=collision.penetration(pc.collisionMask,x,y,ScenePortals.PAWN_RADIUS);
            separation(separating,actualSeparation,"static pawn at "+x+","+y);
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
        var content=SceneContent.load(pack,"bbf967c39b3883e438fd8165fba74c3be45cfcbecdec9c585f1edaa21d1cc144");
        var specs=new ArrayList<SceneContent.Spec>();
        for(var binding:content.entries()){specs.add(binding.spec());if(!binding.spec().child().fields().isEmpty())specs.add(binding.spec().child());}
        var disc=new com.threerings.tudey.shape.config.ShapeConfig.Circle();disc.radius=ScenePortals.PAWN_RADIUS;
        Object preparedDisc=FireCatalogExport.argumentValue(disc);int dynamic=0;
        for(var spec:specs){
            var data=spec.model("source-shape");if(data==null)continue;
            @SuppressWarnings("unchecked") var ref=(ConfigReference<ActorConfig>)FireCompatibility.reference(spec.config());
            var original=cm.a(ActorConfig.class,ref).ai(cm);
            var nativeShape=original.shape.acP().d(new Transform2D(new Vector2f(1.25f,-.75f),.63f));
            var compiledShape=SceneCollision.pawnShape(data.arguments().get("value")).at(1.25f,-.75f,.63f);
            for(float dx=-1.71f;dx<=1.71f;dx+=.19f)for(float dy=-1.71f;dy<=1.71f;dy+=.19f){
                float x=1.25f+dx,y=-.75f+dy;
                boolean expected=nativeShape.b(disc.acP().d(new Transform2D(new Vector2f(x,y),0)));
                boolean actual=SceneCollision.overlaps(data.arguments().get("value"),1.25f,-.75f,.63f,preparedDisc,x,y,0,0);
                if(actual!=expected)throw new AssertionError("Dynamic actor hitbox differs: "+spec.config().name()+" at "+dx+","+dy);
                if(spec.contains(1.25f,-.75f,.63f,x,y,disc.radius)!=expected)throw new AssertionError("Dynamic pawn collision differs: "+spec.config().name());
                if(expected){
                    var separating=nativeShape.a(disc.acP().d(new Transform2D(new Vector2f(x,y),0)),new Vector2f());
                    var actualSeparation=compiledShape.penetration(x,y,disc.radius);
                    separation(separating,actualSeparation,spec.config().name()+" at "+dx+","+dy);
                }
                dynamic++;
            }
        }
        System.out.println("PASS: "+dynamic+" stock dynamic hitbox/rotated pawn-contact comparisons, including native separating vectors");
    }
    private static void separation(Vector2f expected,SceneCollision.Penetration actual,String context){
        if(Math.hypot(expected.x-actual.x(),expected.y-actual.y())>.0001)throw new AssertionError("Pawn separation differs: "+context+"; native="+expected+", server="+actual);
    }
    private static void collect(FireCatalog.Action action,Map<String,FireCatalog.ActorSpec> out){if(action.actor()!=null)out.putIfAbsent(action.actor().config().name(),action.actor());for(var child:action.children())collect(child,out);}
}
