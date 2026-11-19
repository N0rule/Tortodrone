import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import com.threerings.config.*;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.*;
import com.threerings.math.*;

/** Read-only stock configuration oracle. Exports a server map pack, never game assets. */
public final class ScenePackExport {
    public static void main(String[] args)throws Exception {
        boolean content=args.length>0&&args[args.length-1].equals("--content");
        if(content)args=Arrays.copyOf(args,args.length-1);
        if(args.length!=5&&args.length!=7)throw new IllegalArgumentException(
            "Usage: ScenePackExport game-directory scene-source output-directory zone-id zone-name [spawn-x spawn-y]");
        Path source=Path.of(args[1]),out=Path.of(args[2]);
        if(Files.exists(out))throw new IOException("Output already exists; choose a fresh map pack directory");
        byte[] layout=Files.readAllBytes(source);if(layout.length>900_000)throw new IOException("Scene is too large");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("gf",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class)
            .newInstance(resource,null,"config/");manager.init();
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(layout);scene.init(manager);
        String kind=sceneKind(scene.aev());
        boolean explicitSpawn=args.length==7;
        float targetX=explicitSpawn?Float.parseFloat(args[5]):Float.NaN;
        float targetY=explicitSpawn?Float.parseFloat(args[6]):Float.NaN;
        Entrance entrance=defaultEntrance(scene,manager,explicitSpawn,targetX,targetY);
        var coords=scene.aew();if(coords.isEmpty())throw new IOException("No static tiles");
        int minX=coords.stream().mapToInt(c->c.x).min().orElseThrow()-1;
        int minY=coords.stream().mapToInt(c->c.y).min().orElseThrow()-1;
        int maxX=coords.stream().mapToInt(c->c.x).max().orElseThrow()+2;
        int maxY=coords.stream().mapToInt(c->c.y).max().orElseThrow()+2;
        int width=Math.multiplyExact(maxX-minX,4)+1,height=Math.multiplyExact(maxY-minY,4)+1;
        if((long)width*height>4_000_000)throw new IOException("Scene collision bounds are too large");
        var actor=new com.threerings.projectx.data.actor.Knight(new ConfigReference<>("Character/PC/Default"),1,0,new Vector2f(0,0),0);
        actor.init(manager);
        var config=manager.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").al(manager);
        var shape=config.shape.adH();var bits=new BitSet(width*height);
        if(!explicitSpawn){targetX=entrance.x;targetY=entrance.y;}
        if(!Float.isFinite(targetX)||!Float.isFinite(targetY))throw new IOException("Invalid spawn");
        float spawnX=targetX,spawnY=targetY;
        for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
            float x=minX+col*.25f,y=minY+row*.25f;
            if(!Float.isFinite(scene.q(x,y,Float.NaN))
                    ||scene.a(actor,shape.d(new Transform2D(new Vector2f(x,y),0))))continue;
            bits.set(row*width+col);
        }
        int spawnCol=Math.round((spawnX-minX)*4),spawnRow=Math.round((spawnY-minY)*4);
        if(spawnCol<0||spawnCol>=width||spawnRow<0||spawnRow>=height||!bits.get(spawnRow*width+spawnCol)
                ||!Float.isFinite(scene.q(spawnX,spawnY,Float.NaN))
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
        if(!(scene.aev() instanceof com.threerings.projectx.data.ProjectXSceneConfig projectConfig)||projectConfig.NQ()==null)
            throw new IOException("Missing native scene background");
        p.setProperty("image",projectConfig.NQ());p.setProperty("instances","1");
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
        // Mission returns use the town's real entrance too (powered Camp 218/5158).
        // Retain the native identity for the same movement handshake as level arrivals.
        writePortals(out,digest(out.resolve("scene.dat")),entrance,scene,manager);
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
    private static Entrance defaultEntrance(TudeySceneModel scene,ConfigManager manager,
            boolean explicitSpawn,float targetX,float targetY)throws IOException {
        var matches=new ArrayList<TudeySceneModel.PlaceableEntry>();
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)) {
            // Entrances may be stateful elevator props, not only editor markers (scene 337).
            if(entry.au(manager))matches.add(entry);
        }
        if(matches.isEmpty())throw new IOException("No native default entrance");
        if(matches.size()>1){
            if(!explicitSpawn)throw new IOException("Expected exactly one native default entrance, found "+matches.size());
            // A scene may carry many entrance markers (training halls do). The authored spawn
            // coordinate selects the one the deployment actually uses; nothing is invented.
            matches.sort(java.util.Comparator.comparingDouble(entry->{
                var p=entry.transform.tK();return (p.x-targetX)*(p.x-targetX)+(p.y-targetY)*(p.y-targetY);
            }));
        }
        var entry=matches.getFirst();var position=entry.transform.tK();var rotation=entry.transform.tL();
        float yaw=(float)Math.atan2(2f*(rotation.w*rotation.z+rotation.x*rotation.y),
            1f-2f*(rotation.y*rotation.y+rotation.z*rotation.z));
        if(!Float.isFinite(position.x)||!Float.isFinite(position.y)||!Float.isFinite(yaw))
            throw new IOException("Invalid default entrance transform");
        return new Entrance(((Number)entry.getKey()).intValue(),position.x,position.y,yaw);
    }
    /** Every native town arrival and every Portals/Portal area, not just the default marker. */
    private static void writePortals(Path out,String layoutDigest,Entrance entrance,
            TudeySceneModel scene,ConfigManager manager)throws IOException {
        var p=new Properties();p.setProperty("format","2");p.setProperty("layout-sha256",layoutDigest);
        var collision=org.tortodrone.world.CollisionMap.read(out.resolve("walkability.gz"));
        var entrances=new TreeMap<String,Entrance>();
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)){
            if(!entry.placeable.getName().equals("Dynamic/Portal/Destination"))continue;
            Object raw=entry.placeable.ld().get("Tag");
            if(!(raw instanceof String tag)||tag.isBlank())continue;
            var position=entry.transform.tK();var rotation=entry.transform.tL();
            float yaw=(float)Math.atan2(2f*(rotation.w*rotation.z+rotation.x*rotation.y),
                1f-2f*(rotation.y*rotation.y+rotation.z*rotation.z));
            if(!collision.walkable(position.x,position.y)){System.out.println("SKIP unwalkable native portal entrance: "+tag);continue;}
            if(entrances.putIfAbsent(tag,new Entrance(((Number)entry.getKey()).intValue(),position.x,position.y,yaw))!=null)
                throw new IOException("Duplicate native portal destination tag: "+tag);
        }
        if(entrances.values().stream().noneMatch(e->e.id==entrance.id))entrances.put("level_start",entrance);
        p.setProperty("entrances",String.join(",",entrances.keySet()));
        for(var entry:entrances.entrySet()){
            String prefix="entrance."+entry.getKey()+".";var e=entry.getValue();
            p.setProperty(prefix+"id",Integer.toString(e.id));p.setProperty(prefix+"x",Float.toString(e.x));
            p.setProperty(prefix+"y",Float.toString(e.y));p.setProperty(prefix+"rotation",Float.toString(e.rotation));
        }
        var triggers=new TreeMap<String,String>();var geometry=new HashMap<String,String>();
        for(var entry:scene.ci(TudeySceneModel.AreaEntry.class)){
            if(!entry.area.getName().equals("Portals/Portal"))continue;
            int id=((Number)entry.getKey()).intValue();var args=entry.getReference().ld();
            Object sceneId=args.get("Scene ID");if(!(sceneId instanceof Number destination))continue;
            Object portal=args.get("Portal");
            if(!(portal instanceof ActionConfig.TaggedPortal tagged)||tagged.tag==null||tagged.tag.isBlank())
                throw new IOException("Native portal has no arrival tag: scene="+scene.sceneId+" area="+id);
            triggers.put(Integer.toString(id),Integer.toString(destination.intValue()));
            geometry.put(Integer.toString(id),tagged.tag+";"+vertices(entry.vertices));
        }
        p.setProperty("triggers",String.join(",",triggers.keySet()));
        for(var trigger:triggers.entrySet()){
            String prefix="trigger."+trigger.getKey()+".";
            p.setProperty(prefix+"scene",trigger.getValue());
            String[] parts=geometry.get(trigger.getKey()).split(";",2);
            p.setProperty(prefix+"entrance",parts[0]);p.setProperty(prefix+"shape","polygon");p.setProperty(prefix+"vertices",parts[1]);
        }
        writeProperties(out.resolve("portals.properties"),p);
    }
    private static String vertices(TudeySceneModel.Vertex[] points)throws IOException {
        if(points==null||points.length<3||points.length>4096)throw new IOException("Invalid native portal polygon");
        var result=new StringBuilder();
        for(var point:points){if(!Float.isFinite(point.x)||!Float.isFinite(point.y))throw new IOException("Invalid native portal vertex");if(result.length()>0)result.append(';');result.append(point.x).append(',').append(point.y);}
        return result.toString();
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
