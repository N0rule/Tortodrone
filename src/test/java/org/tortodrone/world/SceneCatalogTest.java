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
                check(expected.getMessage().contains("catalog.properties"),"missing catalog identifies the required descriptor");
            }
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            var catalog=SceneCatalog.load(maps);
            check(catalog.scenes().size()==9 && catalog.entry().id()==1,"all nine bundled scenes load without installed game");
            check(catalog.scene(294).image().equals("traininghall.jpg"),"Training loading screen uses its native background");
            check(catalog.entry().zoneName().equals("%town:m.haven")&&catalog.entry().townName().equals("m.haven"),
                "captured qualified zone name and town localization key remain distinct");
            check(catalog.scenes().stream().mapToInt(s->s.portals().triggers().size()).sum()==18,
                "twelve town links plus six interior transitions connect the seven Haven scenes");
            var laboratory=catalog.scene(30893);var heroes=catalog.scene(318);
            check(laboratory.kind().equals("level")&&laboratory.version()==817806710
                &&HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(laboratory.layout()))
                    .equals("2fd25972c6ab80c67bc042c477c3d7ec176d6c4556e752f228e02d20a578f04f"),
                "modern Laboratory scene identity, version and captured layout digest");
            check(heroes.kind().equals("level")&&heroes.version()==801483335
                &&HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(heroes.layout()))
                    .equals("3e4242d1bc28f94b757cdd2b81f7d08531e496b617dcaaee82e26c9bb146a21c"),
                "modern Hall of Heroes star 4 identity, version and captured layout digest");
            check(Math.abs(laboratory.spawnRotation()+3.141592f)<.00001f
                &&Math.abs(heroes.spawnRotation()+3.1415913f)<.00001f,
                "captured modern interior spawn facings are retained");
            check(catalog.scene(2).collision().walkable(catalog.scene(2).spawnX(),catalog.scene(2).spawnY()),"verified Bazaar spawn");
            check(catalog.scene(1).portals().entrances().containsKey("from_bazaar_east")
                &&catalog.scene(2).portals().entrances().containsKey("from_town_square_west"),
                "default portal metadata is loaded with matching archived layouts");
            var arrival=catalog.scene(2).portals().entrances().get("from_town_square_west");
            check(arrival.id()==4539&&catalog.scene(2).collision().walkable(arrival.x(),arrival.y())
                &&Float.isFinite(arrival.rotation()),
                "captured modern Bazaar destination entry is retained at a walkable facing");
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
            change(manifest,"spawn-x",Float.toString(catalog.entry().spawnX()));
            change(manifest,"spawn-y","9999");refuse(()->SceneCatalog.load(maps));
            change(manifest,"spawn-y",Float.toString(catalog.entry().spawnY()));change(manifest,"scene-sha256","0".repeat(64));refuse(()->SceneCatalog.load(maps));
            change(manifest,"scene-sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(catalog.entry().layout())));
            Path laboratoryManifest=maps.resolve("laboratory/map.properties");
            change(laboratoryManifest,"spawn-rotation","NaN");refuse(()->SceneCatalog.load(maps));
            change(laboratoryManifest,"spawn-rotation",Float.toString(laboratory.spawnRotation()));
            change(laboratoryManifest,"spawn-rotation","4");refuse(()->SceneCatalog.load(maps));
            change(laboratoryManifest,"spawn-rotation",Float.toString(laboratory.spawnRotation()));
            change(maps.resolve("bazaar/map.properties"),"scene-id","1");refuse(()->SceneCatalog.load(maps));
            change(maps.resolve("bazaar/map.properties"),"scene-id","2");
            change(maps.resolve("bazaar/map.properties"),"zone-name","Other");refuse(()->SceneCatalog.load(maps));
            change(maps.resolve("bazaar/map.properties"),"zone-name",catalog.scene(2).zoneName());
            change(maps.resolve("bazaar/map.properties"),"kind","dungeon");refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            Path havenPortals=maps.resolve("haven/portals.properties"),bazaarPortals=maps.resolve("bazaar/portals.properties");
            change(havenPortals,"layout-sha256","0".repeat(64));refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.x","NaN");refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.x","9999");refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            var knownEntrance=catalog.scene(1).portals().entrances().get("from_bazaar_east");
            change(havenPortals,"entrances","from_bazaar_east,duplicate");
            change(havenPortals,"entrance.duplicate.id",Integer.toString(knownEntrance.id()));
            change(havenPortals,"entrance.duplicate.x",Float.toString(knownEntrance.x()));
            change(havenPortals,"entrance.duplicate.y",Float.toString(knownEntrance.y()));
            change(havenPortals,"entrance.duplicate.rotation",Float.toString(knownEntrance.rotation()));
            refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            change(bazaarPortals,"entrance.from_town_square_west.rotation","4");
            refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            int bazaarTrigger=catalog.scene(2).portals().triggers().getFirst().id();
            change(bazaarPortals,"trigger."+bazaarTrigger+".scene","99");refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            change(bazaarPortals,"trigger."+bazaarTrigger+".entrance","missing_arrival");refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            change(havenPortals,"entrance.from_bazaar_east.x","-38.25");
            change(havenPortals,"entrance.from_bazaar_east.y","0.4");
            refuse(()->SceneCatalog.load(maps));
            copy(org.tortodrone.support.ContentFixture.path("maps"),maps);
            try(var folders=Files.list(maps)) {
                for(Path folder:folders.filter(Files::isDirectory).toList())
                    Files.delete(folder.resolve("portals.properties"));
            }
            var legacy=SceneCatalog.load(maps);
            check(legacy.scene(1).portals().equals(ScenePortals.EMPTY)
                &&legacy.scene(2).portals().equals(ScenePortals.EMPTY)
                &&legacy.scene(294).portals().equals(ScenePortals.EMPTY),
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
