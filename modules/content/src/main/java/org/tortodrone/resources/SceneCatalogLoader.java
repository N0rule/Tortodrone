package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.core.ContentFiles;
import org.tortodrone.core.world.*;
import org.tortodrone.core.world.MapScene;

/**
 * Descriptor ingestion for {@link SceneCatalog}.
 *
 * <p>Loads one prepared map folder: validates path containment, pinned checksums, the archived
 * scene header, the compiled collision hash, spawn clearance and the portal graph. The lazy
 * runtime catalogue keeps only ids, loaded bodies and lookup; this class owns the untrusted
 * descriptor format and the build-output trust boundary.
 */
final class SceneCatalogLoader {
    private SceneCatalogLoader() { }

    static SceneCatalog load(Path directory)throws IOException {
        if(!Files.exists(directory.resolve("catalog.properties"))&&unifiedMaps(directory)) {
            Path deployment=directory.toAbsolutePath().getParent().getParent();
            String start=org.tortodrone.core.config.ServerSettings.read(org.tortodrone.core.config.ServerSettings.defaultPath(deployment)).startupMap();
            return org.tortodrone.resources.ContentManager.loadMaps(directory,start,null);
        }
        var settings=properties(directory.resolve("catalog.properties"));
        return load(folders(directory),number(settings,"entry-scene",1,Integer.MAX_VALUE));
    }

    /** Unified content groups map folders by type; legacy packs keep flat templates. */
    private static boolean unifiedMaps(Path directory)throws IOException {
        if(!Files.isDirectory(directory))return false;
        for(String category:org.tortodrone.resources.MapYaml.CATEGORIES) {
            Path folder=directory.resolve(category);
            if(!Files.isDirectory(folder))continue;
            try(var paths=Files.list(folder)) {
                if(paths.anyMatch(p->Files.isRegularFile(p.resolve("map.yml"))))return true;
            }
        }
        return false;
    }

    /** Compatibility/import boundary; runtime versioned packs register scenes in their manifest. */
    static Map<Integer,Path> folders(Path directory)throws IOException {
        org.tortodrone.core.ContentFiles.safe(directory);
        if(!Files.isDirectory(directory))throw new IOException("Missing map catalog: "+directory);
        var result=new TreeMap<Integer,Path>();
        try(var paths=Files.list(directory)) {
            var iterator=paths.iterator();int entries=0;
            while(iterator.hasNext()) {
                Path folder=iterator.next();
                if(++entries>512)throw new IOException("Map catalog exceeds entry limit");
                org.tortodrone.core.ContentFiles.safe(folder);
                if(!Files.isDirectory(folder))continue;
                int id=number(properties(folder.resolve("map.properties")),"scene-id",1,Integer.MAX_VALUE);
                if(result.putIfAbsent(id,folder)!=null)throw new IOException("Duplicate scene ID");
                if(result.size()>64)throw new IOException("Too many map templates");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static SceneCatalog load(Map<Integer,Path> folders,int entry)throws IOException {
        return SceneCatalog.of(readTemplates(folders),entry);
    }
    /** Inspect one prepared template before assembling a catalog with a valid town entry. */
    static MapScene loadTemplate(int id,Path folder)throws IOException {
        return readTemplates(Map.of(id,folder)).get(id);
    }
    private static NavigableMap<Integer,MapScene> readTemplates(Map<Integer,Path> folders)throws IOException {
        if(folders.size()>64)throw new IOException("Too many map templates");
        var scenes=new TreeMap<Integer,MapScene>();
        for(var registered:new TreeMap<>(folders).entrySet()) {
            Path folder=registered.getValue();org.tortodrone.core.ContentFiles.safe(folder);
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
            if(p.containsKey("content-sha256"))checked(folder,"content.properties",required(p,"content-sha256"),SceneContent.MAX_BYTES);
            else if(Files.exists(folder.resolve("content.properties")))throw new IOException("Scene content requires a pinned checksum");
            Path collisionFile=folder.resolve("collision.bin");
            if(!Files.isRegularFile(collisionFile))throw new IOException("Map has no compiled collision.bin: "+id);
            String expected=required(p,"collision-sha256");
            if(!expected.equals(org.tortodrone.core.ContentFiles.digest(collisionFile)))throw new IOException("Collision checksum mismatch: "+id);
            MapCollision exact=MapCollision.read(collisionFile);
            if(!Objects.equals(exact.layoutHash(),required(p,"scene-sha256")))throw new IOException("Collision layout hash mismatch: "+id);
            float x=coordinate(p,"spawn-x"),y=coordinate(p,"spawn-y");
            var content=SceneContent.load(folder,required(p,"scene-sha256"));
            int mask=Math.round(content.stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK));
            if(!exact.clear(mask,x,y,ScenePortals.PAWN_RADIUS))throw new IOException("Map spawn is not clear in exact collision: "+id);
            var collision=CollisionMap.exact(exact,mask,ScenePortals.PAWN_RADIUS);
            var scene=new MapScene(id,zone,required(p,"zone-name"),required(p,"name"),required(p,"image"),kind,
                integer(p,"version"),x,y,rotation(p),number(p,"instances",1,16),collision,layout,
                ScenePortals.load(folder,required(p,"scene-sha256"),collision),content,SceneCollision.fromMapCollision(exact),exact,MonsterCatalog.empty());
            if(scenes.putIfAbsent(id,scene)!=null)throw new IOException("Duplicate scene ID");
        }
        return scenes;
    }

    static boolean isTown(Path folder)throws IOException {
        if(folder.toString().isEmpty()||!Files.isRegularFile(folder.resolve("native/metadata.properties")))return false;
        var p=properties(folder.resolve("native/metadata.properties"));
        return "town".equals(p.getProperty("kind"));
    }

    static Properties properties(Path file)throws IOException {
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
