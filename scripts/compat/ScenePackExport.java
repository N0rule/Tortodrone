import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.threerings.config.*;
import com.threerings.tudey.data.*;
import com.threerings.math.*;

/** Read-only stock configuration oracle. Exports a server map pack, never game assets. */
public final class ScenePackExport {
    public static void main(String[] args)throws Exception {
        if(args.length!=5&&args.length!=7)throw new IllegalArgumentException(
            "Usage: ScenePackExport game-directory archived-scene output-directory zone-id zone-name [spawn-x spawn-y]");
        Path source=Path.of(args[1]),out=Path.of(args[2]);
        if(Files.exists(out))throw new IOException("Output already exists; choose a fresh map pack directory");
        byte[] layout=Files.readAllBytes(source);if(layout.length>900_000)throw new IOException("Scene is too large");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class)
            .newInstance(resource,null,"config/");manager.init();
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(layout);scene.init(manager);
        if(!(scene.adD() instanceof com.threerings.projectx.town.data.TownSceneConfig))
            throw new IOException("Only verified town scene packs are currently supported");
        var coords=scene.adE();if(coords.isEmpty())throw new IOException("No static tiles");
        int minX=coords.stream().mapToInt(c->c.x).min().orElseThrow()-1;
        int minY=coords.stream().mapToInt(c->c.y).min().orElseThrow()-1;
        int maxX=coords.stream().mapToInt(c->c.x).max().orElseThrow()+2;
        int maxY=coords.stream().mapToInt(c->c.y).max().orElseThrow()+2;
        int width=Math.multiplyExact(maxX-minX,4)+1,height=Math.multiplyExact(maxY-minY,4)+1;
        if((long)width*height>4_000_000)throw new IOException("Scene collision bounds are too large");
        var actor=new com.threerings.projectx.data.actor.Knight(new ConfigReference<>("Character/PC/Default"),1,0,new Vector2f(0,0),0);
        actor.init(manager);
        var config=manager.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").ai(manager);
        var shape=config.shape.acP();var bits=new BitSet(width*height);
        float targetX=args.length==7?Float.parseFloat(args[5]):0,targetY=args.length==7?Float.parseFloat(args[6]):0;
        if(!Float.isFinite(targetX)||!Float.isFinite(targetY))throw new IOException("Invalid spawn");
        float spawnX=0,spawnY=0;double nearest=Double.POSITIVE_INFINITY;
        for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
            float x=minX+col*.25f,y=minY+row*.25f;
            if(scene.aQ((int)Math.floor(x),(int)Math.floor(y))==null
                    ||scene.a(actor,shape.d(new Transform2D(new Vector2f(x,y),0))))continue;
            bits.set(row*width+col);double distance=(x-targetX)*(x-targetX)+(y-targetY)*(y-targetY);
            if(distance<nearest){nearest=distance;spawnX=x;spawnY=y;}
        }
        if(!Double.isFinite(nearest))throw new IOException("No walkable spawn");
        if(args.length==7&&nearest>.00001)throw new IOException("Requested spawn is not walkable");
        // Stage only after all configuration/collision validation succeeds.
        Files.createDirectories(out);
        Files.write(out.resolve("scene.dat"),layout);
        try(var stream=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(out.resolve("walkability.gz"))))) {
            stream.writeInt(0x54445731);stream.writeInt(width);stream.writeInt(height);
            stream.writeFloat(minX);stream.writeFloat(minY);stream.writeFloat(.25f);
            stream.write(Arrays.copyOf(bits.toByteArray(),(width*height+7)/8));
        }
        var p=new Properties();p.setProperty("kind","town");p.setProperty("scene-id",Integer.toString(scene.sceneId));
        p.setProperty("zone-id",args[3]);p.setProperty("zone-name",args[4]);p.setProperty("name",scene.name);
        p.setProperty("version",Integer.toString(scene.version));p.setProperty("image","haven.jpg");p.setProperty("instances","1");
        p.setProperty("spawn-x",Float.toString(spawnX));p.setProperty("spawn-y",Float.toString(spawnY));
        p.setProperty("scene-sha256",digest(out.resolve("scene.dat")));
        p.setProperty("collision-sha256",digest(out.resolve("walkability.gz")));
        try(var stream=Files.newOutputStream(out.resolve("map.properties"))){p.store(stream,"Prepared from read-only stock scene/configuration data");}
        System.out.println("PASS: scene="+scene.sceneId+" version="+scene.version+" samples="+bits.cardinality()+" spawn="+spawnX+","+spawnY);
    }
    private static String digest(Path file)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
