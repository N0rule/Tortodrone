package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.tortodrone.server.Server;
import static org.tortodrone.support.TestWire.check;

public final class SceneCatalogTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-map-catalog-");
        try {
            Path maps=home.resolve("maps");
            try { SceneCatalog.load(maps); throw new AssertionError("Missing maps accepted"); }
            catch(IOException expected) {
                check(expected.getMessage().contains("./gradlew prepareMaps")
                    &&expected.getMessage().contains("build/defaults/maps")
                    &&expected.getMessage().contains("defaults/maps/"),"missing maps hint gives Gradle preparation command and deployment paths");
                check(!expected.getMessage().contains("make maps"),"missing maps hint has no stale Make command");
            }
            copy(Path.of("defaults/maps"),maps);
            var catalog=SceneCatalog.load(maps);
            check(catalog.scenes().size()==4 && catalog.entry().id()==1,"all four Haven towns load without installed game");
            check(catalog.scenes().stream().mapToInt(s->s.portals().triggers().size()).sum()==10,
                "all ten directed links connect the four archived Haven towns");
            check(catalog.scene(2).collision().walkable(catalog.scene(2).spawnX(),catalog.scene(2).spawnY()),"verified Bazaar spawn");
            check(catalog.scene(1).portals().entrances().containsKey("from_bazaar_east")
                &&catalog.scene(2).portals().entrances().containsKey("from_town_square_west"),
                "default portal metadata is loaded with matching archived layouts");
            var arrival=catalog.scene(2).portals().entrances().get("from_town_square_west");
            check(arrival.id()==1550&&arrival.x()==16.5f&&arrival.y()==7.5f
                &&Math.abs(arrival.rotation()+1.570796f)<.00001f,
                "default entrance ID, position and facing are validated and retained");
            byte[] layout=catalog.scene(1).layout();layout[0]=0;
            check(catalog.scene(1).layout()[0]==(byte)0xfa,"layout snapshots are immutable");
            Path manifest=maps.resolve("haven/map.properties");
            change(manifest,"instances","2");
            var registry=new SceneRegistry(SceneCatalog.load(maps));
            var first=registry.instance(1,0);var second=registry.instance(1,1);
            check(first.oid()!=second.oid()&&first.map()==second.map()&&registry.entry()==first,"instances share immutable template but have distinct object identities");
            check(registry.destination(1,1)==second && registry.destination(1,2)==first,"server assignment is stable");
            refuse(()->registry.instance(1,2));refuse(()->registry.instance(999,0));
            change(manifest,"spawn-x","NaN");refuse(()->SceneCatalog.load(maps));
            change(manifest,"spawn-x","0");change(manifest,"spawn-y","9999");refuse(()->SceneCatalog.load(maps));
            change(manifest,"spawn-y","-8");change(manifest,"scene-sha256","0".repeat(64));refuse(()->SceneCatalog.load(maps));
            change(manifest,"scene-sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(catalog.entry().layout())));
            change(maps.resolve("bazaar/map.properties"),"scene-id","1");refuse(()->SceneCatalog.load(maps));
            change(maps.resolve("bazaar/map.properties"),"scene-id","2");
            change(maps.resolve("bazaar/map.properties"),"zone-name","Other");refuse(()->SceneCatalog.load(maps));
            change(maps.resolve("bazaar/map.properties"),"zone-name",catalog.scene(2).zoneName());
            change(maps.resolve("bazaar/map.properties"),"kind","dungeon");refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            Path havenPortals=maps.resolve("haven/portals.properties"),bazaarPortals=maps.resolve("bazaar/portals.properties");
            change(havenPortals,"layout-sha256","0".repeat(64));refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.x","NaN");refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.x","9999");refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(havenPortals,"entrances","from_bazaar_east,duplicate");
            change(havenPortals,"entrance.duplicate.id","3874");
            change(havenPortals,"entrance.duplicate.x","-30.5");
            change(havenPortals,"entrance.duplicate.y","0.5");
            change(havenPortals,"entrance.duplicate.rotation","0");
            refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.rotation","4");
            refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(bazaarPortals,"trigger.3096.scene","99");refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(bazaarPortals,"trigger.3096.entrance","missing_arrival");refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            change(havenPortals,"entrance.from_bazaar_east.x","-38.5");
            change(havenPortals,"entrance.from_bazaar_east.y","-1.5");
            refuse(()->SceneCatalog.load(maps));
            copy(Path.of("defaults/maps"),maps);
            for(String folder:List.of("haven","bazaar","arcade","garrison"))
                Files.delete(maps.resolve(folder+"/portals.properties"));
            var legacy=SceneCatalog.load(maps);
            check(legacy.scene(1).portals().equals(ScenePortals.EMPTY)
                &&legacy.scene(2).portals().equals(ScenePortals.EMPTY),
                "custom map packs without portal metadata remain compatible");
            Path jar=Path.of(Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try(var archive=new JarFile(jar.toFile())) {
                check(archive.getEntry("scenes/town-square.dat")==null&&archive.getEntry("town-walkability.gz")==null,
                    "runtime map geometry/collision are outside the JAR");
            }
            System.out.println("PASS: external map catalog, portal metadata/hash/destination validation, legacy packs, scene instances, immutable templates, checksums and spawn validation");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void copy(Path source,Path target)throws IOException {
        if(Files.exists(target))try(var paths=Files.walk(target)){
            for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
        }
        try(var paths=Files.walk(source)){for(var p:paths.toList()){
            Path out=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(out);else Files.copy(p,out);
        }}
    }
    private static void change(Path file,String key,String value)throws IOException {
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}p.setProperty(key,value);
        try(var out=Files.newOutputStream(file)){p.store(out,"test");}
    }
    private static void refuse(Action action)throws Exception {try{action.run();throw new AssertionError("Invalid map accepted");}catch(IOException expected){}}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
