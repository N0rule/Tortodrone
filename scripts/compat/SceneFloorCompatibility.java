import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.data.*;
import org.tortodrone.world.SceneCollision;

/** Stock getFloorZ is the oracle for terrain plus authored floorTile placeables. */
public final class SceneFloorCompatibility {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(cm);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));var floor=SceneCollision.load(Path.of(args[2]),hash);
        int comparisons=0,platforms=0;
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class))if(entry.aB(cm).floorTile){
            var bounds=entry.av(cm).aeu();var min=bounds.tt();var max=bounds.tu();
            if(max.x-min.x>512||max.y-min.y>512)throw new AssertionError("Floor probe footprint exceeds bound");
            for(float x=min.x-.25f;x<=max.x+.25f;x+=.25f)for(float y=min.y-.25f;y<=max.y+.25f;y+=.25f){
                boolean expected=Float.isFinite(scene.q(x,y,Float.NaN));
                if(floor.floorSupported(x,y)!=expected)throw new AssertionError("Native floor mismatch at "+x+","+y+" entry="+entry.getKey());comparisons++;
            }
            platforms++;
            if(entry.at(cm)){
                var p=entry.transform.tD();
                if(!floor.floorSupported(p.x,p.y)||!Float.isFinite(scene.q(p.x,p.y,Float.NaN)))throw new AssertionError("Default entrance has no native floor");
                System.out.println("ENTRANCE: entry="+entry.getKey()+" at="+p.x+","+p.y+" nativeZ="+scene.q(p.x,p.y,Float.NaN)+" terrainTile="+(scene.aQ((int)Math.floor(p.x),(int)Math.floor(p.y))!=null));
            }
        }
        if(platforms==0||comparisons==0)throw new AssertionError("No native floor-placeable control exercised");
        System.out.println("PASS: scene "+scene.sceneId+" native terrain/platform floor parity, "+platforms+" floor placeables and "+comparisons+" quarter-unit comparisons; no arrival coordinates changed");
    }
}
