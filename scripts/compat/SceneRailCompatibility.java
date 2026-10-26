import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.math.*;
import com.threerings.tudey.data.*;
import com.threerings.tudey.shape.config.ShapeConfig;
import org.tortodrone.world.*;

/** Read-only native oracle for Rescue Camp's two-vertex compound rail geometry. */
public final class SceneRailCompatibility {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(cm);
        TudeySceneModel.PlaceableEntry rail=null;
        for(var candidate:scene.ci(TudeySceneModel.PlaceableEntry.class))if(candidate.getKey().toString().equals("4678"))rail=candidate;
        if(rail==null)throw new AssertionError("Captured rail absent");
        if(!rail.placeable.getName().equals("Prop/Clockworks/Platform Rail"))throw new AssertionError("Captured rail identity changed");
        var folder=Files.createTempDirectory("tortodrone-rail-oracle-");
        try {
            Files.write(folder.resolve("scene.dat"),bytes);SceneCollisionExport.export(cm,scene,folder);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var collision=SceneCollision.load(folder,hash);
            var root=rail.av(cm);var parts=new ArrayList<String>();SceneCollisionExport.geometry(root,parts,0,root.aeu());
            Path isolated=Files.createDirectory(folder.resolve("isolated"));
            var metadata=new Properties();metadata.setProperty("format","1");metadata.setProperty("layout-sha256",hash);
            metadata.setProperty("tile-count","0");metadata.setProperty("floor-count","0");metadata.setProperty("shape-count",Integer.toString(parts.size()));
            for(int i=0;i<parts.size();i++){metadata.setProperty("shape."+i+".mask",Integer.toString(rail.ax(cm)));metadata.setProperty("shape."+i+".geometry",parts.get(i));}
            try(var output=Files.newOutputStream(isolated.resolve("static-collision.properties"))){metadata.store(output,"Disposable native rail fixture");}
            var isolatedCollision=SceneCollision.load(isolated,hash);
            var disc=new ShapeConfig.Circle();disc.radius=ScenePortals.PAWN_RADIUS;
            var capsule=new ShapeConfig.Capsule();capsule.radius=.12f;capsule.length=.7f;
            var polygon=new ShapeConfig.Polygon();polygon.vertices=new ShapeConfig.Vertex[4];
            float[][] points={{-.2f,-.2f},{.2f,-.2f},{.2f,.2f},{-.2f,.2f}};
            for(int i=0;i<4;i++){polygon.vertices[i]=new ShapeConfig.Vertex();polygon.vertices[i].x=points[i][0];polygon.vertices[i].y=points[i][1];}
            var pawn=new com.threerings.projectx.dungeon.data.actor.Dungeoneer(new ConfigReference<>("Character/PC/Default"),219,100,new Vector2f(),0);pawn.init(cm);
            var nativePawn=cm.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").ai(cm);
            var bounds=rail.av(cm).aeu();int checked=0,contacts=0,ties=0;
            for(ShapeConfig profile:List.of(disc,capsule,polygon)){
                Object prepared=FireCatalogExport.argumentValue(profile);
                for(int ix=0;ix<=80;ix++)for(int iy=0;iy<=64;iy++){
                    float x=bounds.tt().x-.8f+ix*.1f,y=bounds.tt().y-.8f+iy*.1f;
                    var shape=profile.acP().d(new Transform2D(new Vector2f(x,y),.37f));
                    boolean expected=!scene.a(nativePawn.collisionMask,shape),actual=collision.clear(nativePawn.collisionMask,prepared,x,y,.37f);
                    if(isolatedCollision.clear(nativePawn.collisionMask,prepared,x,y,.37f)==root.b(shape))throw new AssertionError("Isolated native rail differs for "+profile.getClass().getSimpleName()+" at "+x+","+y);
                    if(expected!=actual)throw new AssertionError("Native rail collision differs for "+profile.getClass().getSimpleName()+" at "+x+","+y+": stock clear="+expected+" server clear="+actual);
                    if(profile==disc){
                        var vector=new Vector2f();if(root.b(shape))root.a(shape,vector);
                        var separating=isolatedCollision.penetration(nativePawn.collisionMask,x,y,disc.radius);
                        if(Math.hypot(vector.x-separating.x(),vector.y-separating.y())>.0001)throw new AssertionError("Native rail separation differs at "+x+","+y+": stock="+vector+" server="+separating);
                        var globalVector=new Vector2f();scene.a(pawn,shape,globalVector);
                        var globalSeparating=collision.penetration(nativePawn.collisionMask,x,y,disc.radius);
                        if(Math.hypot(globalVector.x-globalSeparating.x(),globalVector.y-globalSeparating.y())>.0001){
                            if(!nativeContactTie(scene,cm,shape,globalVector,globalSeparating,nativePawn.collisionMask))throw new AssertionError("Whole-scene separation differs at "+x+","+y+": stock="+globalVector+" server="+globalSeparating);
                            ties++;
                        }
                        if(separating.present())contacts++;
                    }
                    checked++;
                }
            }
            if(contacts==0)throw new AssertionError("No rail contact was exercised");
            System.out.println("PASS: captured Rescue Camp rail; "+checked+" circle/capsule/polygon collision comparisons and "+contacts+" isolated rail contacts match stock classes; "+ties+" whole-scene direction ties select another equal-depth native contact");
        } finally {try(var paths=Files.walk(folder)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static boolean nativeContactTie(TudeySceneModel scene,ConfigManager cm,com.threerings.tudey.shape.Shape query,Vector2f chosen,SceneCollision.Penetration actual,int mask){
        float expectedDepth=chosen.x*chosen.x+chosen.y*chosen.y,actualDepth=actual.x()*actual.x()+actual.y()*actual.y();
        if(Math.abs(expectedDepth-actualDepth)>.000001)return false;
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class))if((entry.ax(cm)&mask)!=0){
            var obstacle=entry.av(cm);if(obstacle==null||!obstacle.b(query))continue;
            var contact=new Vector2f();obstacle.a(query,contact);
            if(Math.hypot(contact.x-actual.x(),contact.y-actual.y())<=.0001)return true;
        }
        return false;
    }

}
