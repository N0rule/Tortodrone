package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** External immutable map packs. Templates are distinct from runtime place objects. */
public final class SceneCatalog {
    public record MapScene(int id,int zone,String zoneName,String name,String image,int version,
            float spawnX,float spawnY,int instances,CollisionMap collision,byte[] layout) {
        public MapScene { layout=layout.clone(); }
        @Override public byte[] layout(){return layout.clone();}
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
    public static SceneCatalog load(Path directory)throws IOException {
        if(!Files.isDirectory(directory))throw new IOException("Missing maps; extract the complete Tortodrone release with maps/ beside the server JAR");
        Path root=directory.toRealPath();var settings=properties(root.resolve("catalog.properties"));
        int entry=number(settings,"entry-scene",1,Integer.MAX_VALUE);var scenes=new TreeMap<Integer,MapScene>();
        try(var paths=Files.list(root)) {
            for(var folder:paths.filter(Files::isDirectory).sorted().toList()) {
                if(scenes.size()>=64)throw new IOException("Too many map templates");
                if(!folder.toRealPath().startsWith(root))throw new IOException("Map folder escapes the catalog");
                Path descriptor=folder.resolve("map.properties");
                if(!descriptor.toRealPath().getParent().equals(folder.toRealPath()))throw new IOException("Map descriptor escapes its pack");
                var p=properties(descriptor);
                int id=number(p,"scene-id",1,Integer.MAX_VALUE),zone=number(p,"zone-id",1,Integer.MAX_VALUE);
                if(!"town".equals(required(p,"kind")))throw new IOException("Unsupported scene kind; only verified town objects are available");
                byte[] layout=checked(folder,"scene.dat",required(p,"scene-sha256"),900_000);
                if(layout.length<16 || layout[0]!=(byte)0xfa || layout[1]!=(byte)0xce || layout[2]!=(byte)0xaf || layout[3]!=0x0e)
                    throw new IOException("Invalid archived scene header");
                checked(folder,"walkability.gz",required(p,"collision-sha256"),1024*1024);
                var collision=CollisionMap.read(folder.resolve("walkability.gz"));
                float x=coordinate(p,"spawn-x"),y=coordinate(p,"spawn-y");
                if(!collision.walkable(x,y))throw new IOException("Map spawn is not walkable: "+id);
                var scene=new MapScene(id,zone,required(p,"zone-name"),required(p,"name"),required(p,"image"),
                    integer(p,"version"),x,y,number(p,"instances",1,16),collision,layout);
                if(scenes.putIfAbsent(id,scene)!=null)throw new IOException("Duplicate scene ID");
            }
        }
        if(!scenes.containsKey(entry))throw new IOException("Entry scene is missing");
        var zones=new HashMap<Integer,String>();
        for(var scene:scenes.values()) {
            String previous=zones.putIfAbsent(scene.zone(),scene.zoneName());
            if(previous!=null&&!previous.equals(scene.zoneName()))throw new IOException("Conflicting names for one zone");
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
