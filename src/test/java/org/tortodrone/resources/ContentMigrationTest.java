package org.tortodrone.resources;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

public final class ContentMigrationTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-migration-");
        try {
            Path root=home.resolve("old"),candidate=home.resolve("candidate");ContentFixture.copy(ContentFixture.path(""),root.resolve("defaults"));
            Files.move(root.resolve("defaults/maps/advanced_training"),root.resolve("defaults/maps/294"));
            Path data=root.resolve("data");Files.createDirectories(data.resolve("keys"));Files.writeString(data.resolve("tortodrone.db"),"sentinel: resolver must not open SQLite");Files.writeString(data.resolve("keys/private.pk8"),"sentinel private key");Files.writeString(data.resolve("server.properties"),"server-port=47699\n");Files.writeString(data.resolve("server.lock"),"");
            var original=bytes(root);var old=MigrationPackLoader.load(root);
            try(var channel=FileChannel.open(data.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){failure(()->ContentMigration.migrate(root,candidate,null));check(!Files.exists(candidate),"locked source cannot publish a candidate");}
            ContentMigration.migrate(root,candidate,null);unchanged(original,root);
            var converted=ContentManager.load(candidate);
            var oldStats=old.scenes().scene(294).content().stats();
            var migratedStats=converted.scenes().scene(294).content().stats();
            for(var stat:oldStats.entrySet())if(stat.getKey().startsWith("gear."))
                check(stat.getValue().equals(migratedStats.getOrDefault(stat.getKey(),0f)),"migration preserves imported equipment stat: "+stat.getKey());
            check(converted.items().entries().equals(old.items().entries())&&converted.weapons().weapons().equals(old.weapons().weapons())&&converted.fires().entries().equals(old.fires().entries()),"migration preserves every item and weapon graph");
            check(converted.town().recipes().size()==old.town().recipes().size()&&converted.petFires().entries().equals(old.petFires().entries()),"Town and pet data are never dropped from legacy layouts");
            check(!Files.exists(candidate.resolve("data"))&&Files.readString(candidate.resolve("config/server.yml")).contains("47699"),"candidate contains config/content only and preserves listener settings");
            failure(()->ContentMigration.migrate(root,candidate,null));unchanged(original,root);
            failure(()->ContentManager.load(root));
            Files.createDirectories(root.resolve("content/custom/packs/unselected"));Files.writeString(root.resolve("content/custom/packs/unselected/owner"),"review me");failure(()->ContentMigration.migrate(root,home.resolve("conflict"),null));check(!Files.exists(home.resolve("conflict")),"unselected custom content requires explicit conflict review");
            // Import a real, compatible training fixture into a deployment where that native ID
            // is absent. The original ID and models are retained; only the folder name changes.
            var portals=Yaml.read(candidate.resolve("content/maps/haven/portals.yml"));var triggers=Yaml.map(portals.get("portals"),"portals");triggers.entrySet().removeIf(e->{try{return Yaml.map(e.getValue(),"portal").get("destination").equals("scene_294");}catch(IOException invalid){throw new UncheckedIOException(invalid);}});
            Path portalFile=candidate.resolve("content/maps/haven/portals.yml");Files.delete(portalFile);Yaml.write(portalFile,portals);ContentFixture.delete(candidate.resolve("content/maps/scene_294"));
            MapImport.install(ContentFixture.path("maps/advanced_training"),candidate,"imported_training",null);
            check(ContentManager.load(candidate).scenes().scene(294).version()==359&&Files.exists(candidate.resolve("content/maps/imported_training/npcs/konway.yml")),"validated compatible map imports without a content pack");
            check(Files.readString(candidate.resolve("content/maps/imported_training/native/import-report.txt")).contains("Supported actors="),"import reports supported and unresolved behavior explicitly");
            failure(()->MapImport.install(ContentFixture.path("maps/advanced_training"),candidate,"imported_training",null));
            failure(()->MapImport.install(ContentFixture.path("maps/advanced_training"),candidate,"../escape",null));
            System.out.println("PASS: migration retains catalogs/Town/pets, source data and config bytes; conflicts/locks/destinations refused; compatible native import validates and publishes once");
        }finally{ContentFixture.delete(home);}
    }
    private static Map<Path,byte[]> bytes(Path root)throws IOException {var out=new HashMap<Path,byte[]>();try(var paths=Files.walk(root)){for(Path p:paths.filter(Files::isRegularFile).toList())out.put(root.relativize(p),Files.readAllBytes(p));}return out;}
    private static void unchanged(Map<Path,byte[]> before,Path root)throws IOException {for(var e:before.entrySet())check(Arrays.equals(e.getValue(),Files.readAllBytes(root.resolve(e.getKey()))),"source bytes unchanged: "+e.getKey());}
    private interface Action{void run()throws Exception;}
    private static void failure(Action action)throws Exception {try{action.run();throw new AssertionError("Invalid migration/import accepted");}catch(IOException expected){}}
}
