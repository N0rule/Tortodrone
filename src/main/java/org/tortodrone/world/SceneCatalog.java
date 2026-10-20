package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** External immutable map packs. Templates are distinct from runtime place objects. */
public final class SceneCatalog {
    public record MapScene(int id,int zone,String zoneName,String name,String image,String kind,int version,
            float spawnX,float spawnY,float spawnRotation,int instances,CollisionMap collision,byte[] layout,ScenePortals portals,SceneContent content,SceneCollision staticCollision) {
        public MapScene(int id,int zone,String zoneName,String name,String image,String kind,int version,float spawnX,float spawnY,float spawnRotation,int instances,CollisionMap collision,byte[] layout,ScenePortals portals,SceneContent content){
            this(id,zone,zoneName,name,image,kind,version,spawnX,spawnY,spawnRotation,instances,collision,layout,portals,content,SceneCollision.empty());
        }
        public MapScene(int id,int zone,String zoneName,String name,String image,String kind,int version,
                float spawnX,float spawnY,float spawnRotation,int instances,CollisionMap collision,byte[] layout,ScenePortals portals) {
            this(id,zone,zoneName,name,image,kind,version,spawnX,spawnY,spawnRotation,instances,collision,layout,portals,SceneContent.empty());
        }
        public MapScene { layout=layout.clone(); }
        @Override public byte[] layout(){return layout.clone();}
        public boolean town(){return kind.equals("town");}
        /** TownSummary names a town-bundle key; ZoneSummary carries a qualified Name. */
        public String townName(){return zoneName.startsWith("%town:")?zoneName.substring(6):zoneName;}
        public String configType(){return "com.threerings.projectx."+(town()?"town.data.TownSceneConfig":kind.equals("training")?"dungeon.data.TrainingSceneConfig":"dungeon.data.LevelSceneConfig");}
    }
    private final NavigableMap<Integer,MapScene> scenes;
    private final int entry;
    private SceneCatalog(NavigableMap<Integer,MapScene> scenes,int entry) {
        this.scenes=Collections.unmodifiableNavigableMap(scenes);this.entry=entry;
    }
    public MapScene scene(int id)throws IOException {
        var scene=scenes.get(id);if(scene==null)throw new IOException("Scene is not available");return scene;
    }
    public MapScene entry(){return scenes.get(entry);}
    public List<MapScene> scenes(){return List.copyOf(scenes.values());}
    public static SceneCatalog empty(){return new SceneCatalog(new TreeMap<>(),0);}
    public static SceneCatalog load(Path directory)throws IOException {
        if(!Files.exists(directory.resolve("catalog.properties"))&&Files.isDirectory(directory)) {
            try(var paths=Files.list(directory)) {
                if(paths.anyMatch(p->Files.exists(p.resolve("map.yml")))) {
                    Path deployment=directory.toAbsolutePath().getParent().getParent();
                    String start=org.tortodrone.config.ServerSettings.read(org.tortodrone.config.ServerSettings.defaultPath(deployment)).startupMap();
                    return org.tortodrone.resources.ContentManager.loadMaps(directory,start,null);
                }
            }
        }
        var settings=properties(directory.resolve("catalog.properties"));
        return load(folders(directory),number(settings,"entry-scene",1,Integer.MAX_VALUE));
    }
    /** Compatibility/import boundary; runtime versioned packs register scenes in their manifest. */
    public static Map<Integer,Path> folders(Path directory)throws IOException {
        org.tortodrone.resources.ContentFiles.safe(directory);
        if(!Files.isDirectory(directory))throw new IOException("Missing map catalog: "+directory);
        var result=new TreeMap<Integer,Path>();
        try(var paths=Files.list(directory)) {
            var iterator=paths.iterator();int entries=0;
            while(iterator.hasNext()) {
                Path folder=iterator.next();
                if(++entries>512)throw new IOException("Map catalog exceeds entry limit");
                org.tortodrone.resources.ContentFiles.safe(folder);
                if(!Files.isDirectory(folder))continue;
                int id=number(properties(folder.resolve("map.properties")),"scene-id",1,Integer.MAX_VALUE);
                if(result.putIfAbsent(id,folder)!=null)throw new IOException("Duplicate scene ID");
                if(result.size()>64)throw new IOException("Too many map templates");
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static SceneCatalog load(Map<Integer,Path> folders,int entry)throws IOException {
        if(folders.size()>64)throw new IOException("Too many map templates");
        var scenes=new TreeMap<Integer,MapScene>();
        for(var registered:new TreeMap<>(folders).entrySet()) {
            Path folder=registered.getValue();org.tortodrone.resources.ContentFiles.safe(folder);
            Path descriptor=folder.resolve("map.properties");
            if(!descriptor.toRealPath().getParent().equals(folder.toRealPath()))throw new IOException("Map descriptor escapes its pack");
            var p=properties(descriptor);
            int id=number(p,"scene-id",1,Integer.MAX_VALUE),zone=number(p,"zone-id",1,Integer.MAX_VALUE);
            if(id!=registered.getKey())throw new IOException("Registered scene ID differs from descriptor: "+registered.getKey());
            String kind=required(p,"kind");
            if(!Set.of("town","training","level").contains(kind))throw new IOException("Unsupported scene kind");
            byte[] layout=checked(folder,"scene.dat",required(p,"scene-sha256"),900_000);
            if(layout.length<16 || layout[0]!=(byte)0xfa || layout[1]!=(byte)0xce || layout[2]!=(byte)0xaf || layout[3]!=0x0e)
                throw new IOException("Invalid archived scene header");
            checked(folder,"walkability.gz",required(p,"collision-sha256"),1024*1024);
            if(p.containsKey("content-sha256"))checked(folder,"content.properties",required(p,"content-sha256"),4_000_000);
            else if(Files.exists(folder.resolve("content.properties")))throw new IOException("Scene content requires a pinned checksum");
            if(p.containsKey("static-collision-sha256"))checked(folder,"static-collision.properties",required(p,"static-collision-sha256"),4_000_000);
            else if(Files.exists(folder.resolve("static-collision.properties")))throw new IOException("Static collision requires a pinned checksum");
            var collision=CollisionMap.read(folder.resolve("walkability.gz"));
            float x=coordinate(p,"spawn-x"),y=coordinate(p,"spawn-y");
            if(!collision.walkable(x,y))throw new IOException("Map spawn is not walkable: "+id);
            var scene=new MapScene(id,zone,required(p,"zone-name"),required(p,"name"),required(p,"image"),kind,
                integer(p,"version"),x,y,rotation(p),number(p,"instances",1,16),collision,layout,
                ScenePortals.load(folder,required(p,"scene-sha256"),collision),SceneContent.load(folder,required(p,"scene-sha256")),SceneCollision.load(folder,required(p,"scene-sha256")));
            if(scenes.putIfAbsent(id,scene)!=null)throw new IOException("Duplicate scene ID");
        }
        return of(scenes,entry);
    }
    public static SceneCatalog of(NavigableMap<Integer,MapScene> scenes,int entry)throws IOException {
        scenes=new TreeMap<>(scenes);
        if(!scenes.containsKey(entry))throw new IOException("Entry scene is missing");
        if(!scenes.get(entry).town())throw new IOException("Entry scene must be a town");
        var zones=new HashMap<Integer,String>();
        for(var scene:scenes.values()) {
            String previous=zones.putIfAbsent(scene.zone(),scene.zoneName());
            if(previous!=null&&!previous.equals(scene.zoneName()))throw new IOException("Conflicting names for one zone");
            for(var portal:scene.portals().triggers()) {
                var destination=scenes.get(portal.destination());
                if(destination==null||!destination.portals().entrances().containsKey(portal.entrance()))
                    throw new IOException("Portal destination scene or entrance is unavailable");
                var entrance=destination.portals().entrances().get(portal.entrance());
                if(destination.portals().triggers().stream().anyMatch(t->t.intersects(entrance.x(),entrance.y())))
                    throw new IOException("Portal arrival would immediately trigger another transfer");
            }
        }
        if(scenes.values().stream().mapToInt(MapScene::instances).sum()>128)throw new IOException("Too many scene instances");
        return new SceneCatalog(scenes,entry);
    }
    private static Properties properties(Path file)throws IOException {
        if(!Files.isRegularFile(file)||Files.size(file)>16384)throw new IOException("Missing or invalid map descriptor: "+file.getFileName());
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}return p;
    }
    private static String required(Properties p,String key)throws IOException {
        String value=p.getProperty(key,"").trim();
        if(value.isEmpty()||value.length()>256)throw new IOException("Missing or invalid map property: "+key);return value;
    }
    private static int integer(Properties p,String key)throws IOException {
        try{return Integer.parseInt(required(p,key));}catch(NumberFormatException e){throw new IOException("Invalid map integer: "+key,e);}
    }
    private static int number(Properties p,String key,int min,int max)throws IOException {
        int n=integer(p,key);if(n<min||n>max)throw new IOException("Invalid map range: "+key);return n;
    }
    private static float rotation(Properties p)throws IOException {
        float value=p.containsKey("spawn-rotation")?coordinate(p,"spawn-rotation"):0;
        if(Math.abs(value)>Math.PI+.00001)throw new IOException("Invalid spawn rotation");
        return value;
    }
    private static float coordinate(Properties p,String key)throws IOException {
        try {float n=Float.parseFloat(required(p,key));if(!Float.isFinite(n))throw new NumberFormatException();return n;}
        catch(NumberFormatException e){throw new IOException("Invalid map coordinate: "+key,e);}
    }
    private static byte[] checked(Path folder,String name,String expected,int limit)throws IOException {
        Path file=folder.resolve(name);
        if(!Files.isRegularFile(file)||!file.toRealPath().getParent().equals(folder.toRealPath())||Files.size(file)>limit)
            throw new IOException("Missing, escaped or oversized map file: "+name);
        byte[] data=Files.readAllBytes(file);
        try {
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if(!actual.equalsIgnoreCase(expected))throw new IOException("Map checksum mismatch: "+name);
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return data;
    }
}
