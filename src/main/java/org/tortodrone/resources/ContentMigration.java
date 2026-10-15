package org.tortodrone.resources;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Explicit, non-destructive export to a new pack and a reviewable selection candidate. */
public final class ContentMigration {
    private ContentMigration() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -cp tortodrone.jar org.tortodrone.resources.ContentMigration /deployment");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        migrate(root);
        System.out.println("Exported content/custom/packs/legacy-import and data/server-migrated.properties. Review the candidate, then select it as data/server.properties while stopped. Original resources and data are unchanged.");
    }
    public static void migrate(Path deployment) throws IOException {
        Path root=deployment.toAbsolutePath().normalize();ContentFiles.safe(root);
        Path lockFile=root.resolve("data/server.lock");ContentFiles.safe(lockFile);
        try(var channel=Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS)?FileChannel.open(lockFile,StandardOpenOption.WRITE):null;
            FileLock lock=acquire(channel)) {
            var content=ContentManager.load(root);
            Path parent=root.resolve("content/custom/packs"),target=parent.resolve("legacy-import"),candidate=root.resolve("data/server-migrated.properties");
            ContentFiles.safe(target);ContentFiles.safe(candidate);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)||Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("Migration destination already exists; nothing replaced");
            Files.createDirectories(parent);Files.createDirectories(candidate.getParent());
            Path staging=Files.createTempDirectory(parent,".legacy-import-");
            boolean published=false;
            try {
                var manifest=new Properties();manifest.setProperty("format","1");manifest.setProperty("engine-format","1");
                manifest.setProperty("id","legacy-import");manifest.setProperty("version","1.0.0");
                var resources=new TreeMap<String,ContentRegistry.Resource>();
                for(var entry:content.resources().entrySet()) {
                    String key=entry.getKey();
                    String relative=key.startsWith("scene.")?"maps/"+key.substring(6):switch(key) {
                        case "entry"->"maps/catalog.properties";case "items"->"items/catalog.json";
                        case "weapons"->"combat/weapons.tsv";case "equipment"->"combat/equipment.tsv";case "fires"->"combat/fires.json";
                        default->throw new IOException("Unsupported migration resource: "+key);
                    };
                    Path destination=staging.resolve(relative);copy(entry.getValue().path(),destination);
                    String digest=ContentFiles.digest(destination);
                    manifest.setProperty("resource."+key,relative);manifest.setProperty("sha256."+key,digest);
                    resources.put(key,new ContentRegistry.Resource("legacy-import",destination,digest));
                }
                ContentManager.validate(List.of(new ContentRegistry.Pack("legacy-import","1.0.0")),resources,List.of());
                try(var output=Files.newOutputStream(staging.resolve("pack.properties"),StandardOpenOption.CREATE_NEW)){manifest.store(output,"Validated legacy export; independent complete pack");}
                // No REPLACE_EXISTING anywhere: conflicts preserve the owner's files.
                Files.move(staging,target);published=true;
                var settings=org.tortodrone.config.ServerSettings.properties(root.resolve("data/server.properties"));
                settings.setProperty("content-packs","legacy-import");settings.setProperty("legacy-content","disabled");
                try(var output=Files.newOutputStream(candidate,StandardOpenOption.CREATE_NEW)) {settings.store(output,"Review before replacing data/server.properties; original settings are unchanged");}
                catch(IOException failed){throw new IOException("Pack exported to "+target+" but candidate creation failed. Select content-packs=legacy-import, legacy-content=disabled explicitly in data/server.properties; existing files were preserved",failed);}
            } finally {
                if(!published)try(var paths=Files.walk(staging)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
            }
        }
    }
    private static FileLock acquire(FileChannel channel) throws IOException {
        if(channel==null)return null;
        try {FileLock lock=channel.tryLock();if(lock!=null)return lock;}
        catch(OverlappingFileLockException held){ }
        throw new IOException("Server is running; stop it before content migration");
    }
    private static void copy(Path source,Path target) throws IOException {
        ContentFiles.safe(source);ContentFiles.safe(target);
        if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) {
            try(var paths=Files.walk(source)) {
                for(Path path:paths.sorted().toList()) {
                    ContentFiles.safe(path);Path destination=target.resolve(source.relativize(path));
                    if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(destination);
                    else {ContentFiles.regular(path);Files.copy(path,destination);}
                }
            }
        } else {Files.createDirectories(target.getParent());Files.copy(source,target);}
    }
}
