package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.SceneCatalog;

/** Read-only compatibility boundary. Old precedence is preserved, never copied into or over live data. */
final class LegacyContent {
    private LegacyContent() { }
    static boolean present(Path root) {
        return List.of("defaults","maps","items/catalog.json","combat/weapons.tsv","combat/equipment.tsv","combat/fires.json")
            .stream().anyMatch(name->Files.exists(root.resolve(name),LinkOption.NOFOLLOW_LINKS));
    }
    static Map<String,ContentRegistry.Resource> resolve(Path root,List<String> warnings) throws IOException {
        var resources=new TreeMap<String,ContentRegistry.Resource>();
        Path base=Files.exists(root.resolve("defaults"),LinkOption.NOFOLLOW_LINKS)?root.resolve("defaults"):root.resolve("content/packs/vanilla");
        Path maps=Files.exists(root.resolve("maps"),LinkOption.NOFOLLOW_LINKS)?root.resolve("maps"):base.resolve("maps");
        add(resources,"entry",maps.resolve("catalog.properties"));
        for(var scene:SceneCatalog.folders(maps).entrySet())add(resources,"scene."+scene.getKey(),scene.getValue());
        for(var catalog:Map.of("items","items/catalog.json","weapons","combat/weapons.tsv","equipment","combat/equipment.tsv","fires","combat/fires.json").entrySet()) {
            Path custom=root.resolve(catalog.getValue());
            add(resources,catalog.getKey(),Files.exists(custom,LinkOption.NOFOLLOW_LINKS)?custom:base.resolve(catalog.getValue()));
        }
        warnings.add("Legacy resource paths are active (read-only compatibility); migrate to a versioned pack or set data/server.properties legacy-content=disabled. Base: "+base+"; maps: "+maps);
        return resources;
    }
    private static void add(Map<String,ContentRegistry.Resource> resources,String key,Path path) throws IOException {
        resources.put(key,new ContentRegistry.Resource("legacy",path,ContentFiles.digest(path)));
    }
}
