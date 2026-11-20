package org.tortodrone.resources;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.resources.Yaml.*;

/** Transactional installation of a compatible, prepared native scene into the unified tree. */
public final class MapImport {
    private MapImport() { }
    public static void main(String[] args)throws IOException {
        boolean replace=false;var rest=new ArrayList<String>();
        for(String arg:args)if(arg.equals("--replace"))replace=true;else rest.add(arg);
        if(rest.size()<3||rest.size()>4)throw new IllegalArgumentException("Usage: MapImport /prepared-native-map /deployment map_name [/read-only-game] [--replace]");
        install(Path.of(rest.get(0)),Path.of(rest.get(1)),rest.get(2),rest.size()==4?Path.of(rest.get(3)):null,replace);
        System.out.println("Validated and installed map; restart the server. Review native/import-report.txt before connecting a portal.");
    }
    public static void install(Path source,Path deployment,String name,Path game)throws IOException {
        install(source,deployment,name,game,false);
    }
    public static void install(Path source,Path deployment,String name,Path game,boolean replace)throws IOException {
        if(!name.matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Map name must match [a-z][a-z0-9_]{0,63}");
        var p=ContentFiles.properties(source.resolve("map.properties"));
        String kind=p.getProperty("kind","");
        if(!MapYaml.CATEGORIES.contains(kind))throw new IOException("Unsupported map type: "+kind);
        Path root=deployment.toAbsolutePath().normalize(),target=root.resolve("content/maps/"+kind+"/"+name);ContentFiles.safe(source);ContentFiles.safe(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!replace)throw new IOException("Map already exists; no replacement performed: "+name);
        for(var file:Map.of("scene.dat","scene-sha256","collision.bin","collision-sha256","content.properties","content-sha256","static-collision.properties","static-collision-sha256").entrySet())
            if(p.containsKey(file.getValue())&&!ContentFiles.digest(source.resolve(file.getKey())).equals(p.getProperty(file.getValue())))throw new IOException("Prepared native map fingerprint differs: "+file.getKey());
        Path lockFile=root.resolve("data/server.lock");ContentFiles.safe(lockFile);
        try(var channel=Files.exists(lockFile)?FileChannel.open(lockFile,StandardOpenOption.WRITE):null;var lock=channel==null?null:channel.tryLock()) {
            if(channel!=null&&lock==null)throw new IOException("Stop the server before importing a map");
            var initialInputs=ContentManager.inputs(root);var before=ContentManager.load(root);int id=Integer.parseInt(p.getProperty("scene-id"));
            var existing=before.scenes().scenes().stream().filter(s->s.id()==id).findFirst();
            if(existing.isPresent()&&(!replace||!MapYaml.folders(root.resolve("content/maps")).get(id).getFileName().toString().equals(name)))
                throw new IOException("Native scene ID already exists: "+id);
            Path staging=Files.createTempDirectory(root.getParent(),".map-import-");
            try {
                copy(root.resolve("content"),staging.resolve("content"));if(Files.exists(root.resolve("config")))copy(root.resolve("config"),staging.resolve("config"));
                var names=new HashMap<Integer,String>();for(var e:MapYaml.folders(staging.resolve("content/maps")).entrySet())names.put(e.getKey(),e.getValue().getFileName().toString());names.put(id,name);
                var cycles=flat(map(read(staging.resolve("content/shared/trap-cycles.yml")).get("cycles"),"cycles"));
                var combat=flat(map(read(staging.resolve("content/shared/combat.yml")).get("properties"),"combat"));
                var statuses=new LinkedHashMap<>(map(read(staging.resolve("content/shared/statuses.yml")).get("properties"),"statuses"));
                Path prepared=staging.resolve("content/maps/"+kind+"/"+name);if(Files.exists(prepared))ContentMigration.delete(prepared);var exporter=new UnifiedExport(game);exporter.exportMap(source,prepared,names,cycles,statuses,combat);
                // New shared presets stay local to the imported map, preserving the deployment's
                // existing source of truth and allowing publication with one directory rename.
                var sharedCycles=flat(map(read(root.resolve("content/shared/trap-cycles.yml")).get("cycles"),"cycles"));var sharedCombat=flat(map(read(root.resolve("content/shared/combat.yml")).get("properties"),"combat"));var sharedStatuses=map(read(root.resolve("content/shared/statuses.yml")).get("properties"),"statuses");
                var local=Files.exists(prepared.resolve("mechanics.yml"))?new LinkedHashMap<>(map(read(prepared.resolve("mechanics.yml")).get("properties"),"mechanics")):new LinkedHashMap<String,Object>();
                combat.forEach((k,v)->{if(!sharedCombat.containsKey(k))local.put(k,v);});cycles.forEach((k,v)->{if(!sharedCycles.containsKey(k))local.put("cycle."+k,v);});statuses.forEach((k,v)->{if(!sharedStatuses.containsKey(k))local.put(k,v);});
                Files.deleteIfExists(prepared.resolve("mechanics.yml"));if(!local.isEmpty())write(prepared.resolve("mechanics.yml"),Map.of("properties",local));
                var bindings=map(Json.parseCatalog(Files.readString(prepared.resolve("native/bindings.json"))),"bindings");bindings.put("mechanicFields",new ArrayList<>(local.keySet()));Files.writeString(prepared.resolve("native/bindings.json"),Json.stringify(bindings));
                var metadata=new Properties();metadata.putAll(ContentFiles.properties(prepared.resolve("native/metadata.properties")));metadata.setProperty("sha256.bindings.json",ContentFiles.digest(prepared.resolve("native/bindings.json")));Files.delete(prepared.resolve("native/metadata.properties"));UnifiedExport.store(prepared.resolve("native/metadata.properties"),metadata);
                String sourceReport="No raw extraction report supplied; only prepared bindings can be validated.\n";
                Path extractedReport=source.resolve("import-source-report.txt");if(Files.exists(extractedReport)){ContentFiles.regular(extractedReport);if(Files.size(extractedReport)>1_000_000)throw new IOException("Import report exceeds size limit");sourceReport=Files.readString(extractedReport);}
                var preparedBindings=map(Json.parseCatalog(Files.readString(prepared.resolve("native/bindings.json"))),"bindings");
                String supported="Supported actors="+map(preparedBindings.get("actors"),"actors").size()+", NPC conversations="+map(preparedBindings.get("npcs"),"npcs").size()+", portal triggers="+map(preparedBindings.get("portals"),"portals").size()+"\n";
                Files.writeString(prepared.resolve("native/import-report.txt"),"Prepared native identity and default entrance retained. Supported extracted bindings were validated.\nCached scenes cannot establish missing server scripts, AI, rewards or portal actions; no behaviors invented.\nReview unresolved localization in NPC YAML and verify travel with an isolated stock client.\n"+supported+sourceReport+String.join("\n",exporter.report())+"\n");
                exactCollision(prepared);
                ContentManager.load(staging,org.tortodrone.config.ServerSettings.read(org.tortodrone.config.ServerSettings.defaultPath(root)));
                if(!initialInputs.equals(ContentManager.inputs(root)))throw new IOException("Deployment content/config changed during import; no map published");
                Path backup=null;
                try{
                    Files.createDirectories(target.getParent());
                    if(Files.exists(target)){backup=staging.resolveSibling(staging.getFileName()+"-backup");Files.move(target,backup,StandardCopyOption.ATOMIC_MOVE);}
                    Files.move(prepared,target,StandardCopyOption.ATOMIC_MOVE);
                }catch(IOException failure){
                    if(backup!=null&&Files.exists(backup)&&!Files.exists(target))Files.move(backup,target,StandardCopyOption.ATOMIC_MOVE);
                    throw failure;
                }finally{if(backup!=null)ContentMigration.delete(backup);}
            }finally{ContentMigration.delete(staging);}
        }catch(OverlappingFileLockException held){throw new IOException("Stop the server before importing a map",held);}
    }
    /** Compile exact collision before validation so a scene is never published without it. */
    private static void exactCollision(Path prepared)throws IOException {
        Path nativeDir=prepared.resolve("native"),staticCollision=nativeDir.resolve("static-collision.properties");
        if(!Files.isRegularFile(staticCollision))return;
        var metadata=ContentFiles.properties(nativeDir.resolve("metadata.properties"));
        String hash=metadata.getProperty("sha256.scene.dat");
        if(hash==null)throw new IOException("Prepared map has no scene hash");
        var collision=org.tortodrone.world.MapCollision.fromProperties(staticCollision);
        Path output=nativeDir.resolve("collision.bin");collision.write(output,hash);
        metadata.setProperty("sha256.collision.bin",ContentFiles.digest(output));
        metadata.remove("sha256.static-collision.properties");
        Files.delete(staticCollision);
        Files.delete(nativeDir.resolve("metadata.properties"));
        UnifiedExport.store(nativeDir.resolve("metadata.properties"),metadata);
    }
    private static LinkedHashMap<String,Object> flat(Map<String,Object> data)throws IOException {
        var p=new Properties();MapYaml.properties(p,"",data);var result=new LinkedHashMap<String,Object>();for(String key:p.stringPropertyNames())result.put(key,p.getProperty(key));return result;
    }
    private static void copy(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source)){for(Path p:paths.sorted().toList()){ContentFiles.safe(p);Path out=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(out);else{ContentFiles.regular(p);Files.copy(p,out);}}}
    }
}
