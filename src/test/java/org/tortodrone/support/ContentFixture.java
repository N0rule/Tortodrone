package org.tortodrone.support;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.*;

/** Disposable legacy fixtures derived from the one unified source for reader/migration regressions. */
public final class ContentFixture {
    private static Path root;
    private ContentFixture() { }
    public static synchronized Path path(String relative)throws IOException {
        if(root==null) {
            Path temporary=Files.createTempDirectory("tortodrone-legacy-fixture-");
            try{generate(temporary);root=temporary;}catch(IOException|RuntimeException failed){delete(temporary);throw failed;}
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{delete(temporary);}catch(IOException ignored){ }},"ContentFixtureCleanup"));
        }
        return root.resolve(relative);
    }
    public static void generate(Path target)throws IOException {
        var snapshot=ContentManager.load(Path.of("."));Files.createDirectories(target.resolve("items"));Files.createDirectories(target.resolve("town"));
        ItemCatalog.write(target.resolve("items/catalog.json"),snapshot.items().entries());Files.copy(Path.of("content/_internal/provenance/items.tsv"),target.resolve("items/provenance.tsv"));
        WeaponCatalog.write(target.resolve("combat/weapons.tsv"),snapshot.weapons().weapons(),snapshot.weapons().shields(),snapshot.weapons().dodgeCooldown());
        EquipmentCatalog.write(target.resolve("combat/equipment.tsv"),snapshot.equipment().entries());
        Files.copy(Path.of("content/_internal/fires.json"),target.resolve("combat/fires.json"));Files.copy(Path.of("content/_internal/pet-fires.json"),target.resolve("town/pet-fires.json"));
        Files.createDirectories(target.resolve("town"));Files.writeString(target.resolve("town/catalog.json"),Json.stringify(TownYaml.data(Path.of("content/town"),snapshot.items())));
        var resources=new TreeMap<String,String>();resources.put("entry","maps/catalog.properties");resources.put("items","items/catalog.json");resources.put("weapons","combat/weapons.tsv");resources.put("equipment","combat/equipment.tsv");resources.put("fires","combat/fires.json");resources.put("pet-fires","town/pet-fires.json");resources.put("town","town/catalog.json");
        for(var folder:MapYaml.folders(Path.of("content/maps")).entrySet()) {
            String name=folder.getValue().getFileName().toString();Path out=target.resolve("maps/"+name);Files.createDirectories(out);
            var prepared=MapYaml.prepare(folder.getValue(),Path.of("content/shared"),snapshot.items());
            for(String file:List.of("scene.dat","walkability.gz","static-collision.properties"))if(Files.exists(prepared.nativeDirectory().resolve(file)))Files.copy(prepared.nativeDirectory().resolve(file),out.resolve(file));
            store(out.resolve("content.properties"),prepared.content());store(out.resolve("portals.properties"),prepared.portals());
            var descriptor=prepared.descriptor();descriptor.setProperty("content-sha256",ContentFiles.digest(out.resolve("content.properties")));store(out.resolve("map.properties"),descriptor);resources.put("scene."+folder.getKey(),"maps/"+name);
        }
        var entry=new Properties();entry.setProperty("entry-scene",Integer.toString(snapshot.scenes().entry().id()));store(target.resolve("maps/catalog.properties"),entry);
        var manifest=new Properties();manifest.setProperty("format","1");manifest.setProperty("engine-format","1");manifest.setProperty("id","vanilla");manifest.setProperty("version","1.0.0");
        for(var e:resources.entrySet()){manifest.setProperty("resource."+e.getKey(),e.getValue());manifest.setProperty("sha256."+e.getKey(),ContentFiles.digest(target.resolve(e.getValue())));}
        store(target.resolve("pack.properties"),manifest);
    }
    public static void store(Path path,Properties p)throws IOException {Files.createDirectories(path.getParent());try(var out=Files.newOutputStream(path)){p.store(out,"Disposable legacy reader fixture");}}
    public static void copy(Path source,Path target)throws IOException {try(var paths=Files.walk(source)){for(Path p:paths.toList()){Path out=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(out);else Files.copy(p,out);}}}
    public static void delete(Path directory)throws IOException {try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
}
