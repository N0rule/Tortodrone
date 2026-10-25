import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.threerings.config.*;
import com.threerings.tudey.config.PlaceableConfig;
import com.threerings.tudey.data.*;
import com.threerings.math.*;

/** Read-only stock configuration oracle. Exports a server map pack, never game assets. */
public final class ScenePackExport {
    public static void main(String[] args)throws Exception {
        boolean content=args.length>0&&args[args.length-1].equals("--content");
        if(content)args=Arrays.copyOf(args,args.length-1);
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
        String kind=sceneKind(scene.adD());
        Entrance entrance=defaultEntrance(scene,manager);
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
        float targetX=args.length==7?Float.parseFloat(args[5]):entrance.x;
        float targetY=args.length==7?Float.parseFloat(args[6]):entrance.y;
        if(!Float.isFinite(targetX)||!Float.isFinite(targetY))throw new IOException("Invalid spawn");
        float spawnX=targetX,spawnY=targetY;
        for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
            float x=minX+col*.25f,y=minY+row*.25f;
            if(scene.aQ((int)Math.floor(x),(int)Math.floor(y))==null
                    ||scene.a(actor,shape.d(new Transform2D(new Vector2f(x,y),0))))continue;
            bits.set(row*width+col);
        }
        int spawnCol=Math.round((spawnX-minX)*4),spawnRow=Math.round((spawnY-minY)*4);
        if(spawnCol<0||spawnCol>=width||spawnRow<0||spawnRow>=height||!bits.get(spawnRow*width+spawnCol)
                ||scene.aQ((int)Math.floor(spawnX),(int)Math.floor(spawnY))==null
                ||scene.a(actor,shape.d(new Transform2D(new Vector2f(spawnX,spawnY),0))))
            throw new IOException("Native or requested spawn is not walkable at "+spawnX+","+spawnY+" (native entrance "+entrance.id+"); refusing an invented arrival");
        // Stage only after all configuration/collision validation succeeds.
        Files.createDirectories(out);
        Files.write(out.resolve("scene.dat"),layout);
        try(var stream=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(out.resolve("walkability.gz"))))) {
            stream.writeInt(0x54445731);stream.writeInt(width);stream.writeInt(height);
            stream.writeFloat(minX);stream.writeFloat(minY);stream.writeFloat(.25f);
            stream.write(Arrays.copyOf(bits.toByteArray(),(width*height+7)/8));
        }
        var p=new Properties();p.setProperty("kind",kind);p.setProperty("scene-id",Integer.toString(scene.sceneId));
        p.setProperty("zone-id",args[3]);p.setProperty("zone-name",args[4]);p.setProperty("name",scene.name);
        p.setProperty("version",Integer.toString(scene.version));
        if(!(scene.adD() instanceof com.threerings.projectx.data.ProjectXSceneConfig projectConfig)||projectConfig.Nk()==null)
            throw new IOException("Missing native scene background");
        p.setProperty("image",projectConfig.Nk());p.setProperty("instances","1");
        p.setProperty("spawn-rotation",Float.toString(entrance.rotation));
        p.setProperty("spawn-x",Float.toString(spawnX));p.setProperty("spawn-y",Float.toString(spawnY));
        p.setProperty("scene-sha256",digest(out.resolve("scene.dat")));
        p.setProperty("collision-sha256",digest(out.resolve("walkability.gz")));
        if(content||kind.equals("training")){
            SceneContentExport.export(Path.of(args[0]),manager,scene,out);
            SceneCollisionExport.export(manager,scene,out);
            p.setProperty("static-collision-sha256",digest(out.resolve("static-collision.properties")));
            p.setProperty("content-sha256",digest(out.resolve("content.properties")));
        }
        writeProperties(out.resolve("map.properties"),p);
        if(!kind.equals("town"))writeEntrance(out,digest(out.resolve("scene.dat")),entrance);
        System.out.println("PASS: kind="+kind+" scene="+scene.sceneId+" version="+scene.version+
            " samples="+bits.cardinality()+" spawn="+spawnX+","+spawnY+
            (" entrance="+entrance.id+" yaw="+entrance.rotation));
    }
    private static String sceneKind(TudeySceneConfig config)throws IOException {
        if(config instanceof com.threerings.projectx.dungeon.data.TrainingSceneConfig training&&"training".equals(training.getType()))return "training";
        if(config instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig level&&"level".equals(level.getType()))return "level";
        if(config instanceof com.threerings.projectx.town.data.TownSceneConfig town&&"town".equals(town.getType()))return "town";
        throw new IOException("Unsupported scene configuration kind: "+config.getClass().getName());
    }
    private static Entrance defaultEntrance(TudeySceneModel scene,ConfigManager manager)throws IOException {
        var matches=new ArrayList<TudeySceneModel.PlaceableEntry>();
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)) {
            // Entrances may be stateful elevator props, not only editor markers (scene 337).
            if(entry.at(manager))matches.add(entry);
        }
        if(matches.size()!=1)throw new IOException("Expected exactly one native default entrance, found "+matches.size());
        var entry=matches.getFirst();var position=entry.transform.tD();var rotation=entry.transform.tE();
        float yaw=(float)Math.atan2(2f*(rotation.w*rotation.z+rotation.x*rotation.y),
            1f-2f*(rotation.y*rotation.y+rotation.z*rotation.z));
        if(!Float.isFinite(position.x)||!Float.isFinite(position.y)||!Float.isFinite(yaw))
            throw new IOException("Invalid default entrance transform");
        return new Entrance(((Number)entry.getKey()).intValue(),position.x,position.y,yaw);
    }
    private static void writeEntrance(Path out,String layoutDigest,Entrance entrance)throws IOException {
        var p=new Properties();p.setProperty("format","2");p.setProperty("layout-sha256",layoutDigest);
        p.setProperty("entrances","level_start");p.setProperty("entrance.level_start.id",Integer.toString(entrance.id));
        p.setProperty("entrance.level_start.x",Float.toString(entrance.x));
        p.setProperty("entrance.level_start.y",Float.toString(entrance.y));
        p.setProperty("entrance.level_start.rotation",Float.toString(entrance.rotation));
        writeProperties(out.resolve("portals.properties"),p);
    }
    private record Entrance(int id,float x,float y,float rotation) { }
    static void writeProperties(Path path,Properties values)throws IOException {
        var lines=new ArrayList<String>();
        for(String key:values.stringPropertyNames())lines.add(key+"="+values.getProperty(key));
        Collections.sort(lines);
        Files.writeString(path,String.join("\n",lines)+"\n");
    }
    private static String digest(Path file)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
