package org.tortodrone.resources;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Real compatibility precedence, safe export, conflict refusal, and unchanged persistent bytes. */
public final class ContentMigrationTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("tortodrone-content-migration-");
        try {
            Path root=home.resolve("legacy");copy(Path.of("content/packs/vanilla"),root.resolve("defaults"));
            // A complete old top-level map catalog replaces defaults/maps, retaining owner settings.
            copy(root.resolve("defaults/maps"),root.resolve("maps"));
            Path descriptor=root.resolve("maps/bazaar/map.properties");
            Files.writeString(descriptor,Files.readString(descriptor).replace("instances=1","instances=2"));
            copy(root.resolve("defaults/items"),root.resolve("items"));
            copy(root.resolve("defaults/combat"),root.resolve("combat"));
            Path item=root.resolve("items/catalog.json");Files.writeString(item,Files.readString(item)+"\n");
            Path weapon=root.resolve("combat/weapons.tsv");Files.writeString(weapon,Files.readString(weapon)+"# owner combat override\n");
            Path data=root.resolve("data");Files.createDirectories(data.resolve("keys"));
            Files.writeString(data.resolve("tortodrone.db"),"sentinel: not a database; resolver must not open it");
            Files.writeString(data.resolve("keys/private.pk8"),"sentinel private key");
            Files.writeString(data.resolve("server.properties"),"server-port=47699\n");
            Files.writeString(data.resolve("server.lock"),"");
            var before=bytes(root);
            var legacy=ContentManager.load(root);
            check(legacy.warnings().size()==1&&legacy.scenes().scene(2).instances()==2,"legacy paths remain active with owner map settings");
            check(legacy.resources().get("items").path().equals(item.toAbsolutePath())&&legacy.resources().get("weapons").path().equals(weapon.toAbsolutePath()),"legacy item/combat overrides preserve established precedence");
            check(org.tortodrone.config.ServerSettings.defaultPath(root).equals(data.resolve("server.properties")),"existing data settings are read in place");
            unchanged(before,root);
            try(var channel=FileChannel.open(data.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {
                fail(()->ContentMigration.migrate(root));
                check(!Files.exists(root.resolve("custom")),"held deployment lock refuses migration before creating output");
            }
            ContentMigration.migrate(root);unchanged(before,root);
            Path candidate=root.resolve("config/content-migrated.properties");
            check(Files.readString(candidate).contains("enabled=legacy-import"),"migration produces a reviewable selection candidate");
            check(!Files.exists(root.resolve("config/content.properties")),"migration never implicitly changes enabled content");
            Files.copy(candidate,root.resolve("config/content.properties"));
            var migrated=ContentManager.load(root);
            check(migrated.packs().equals(List.of(new ContentRegistry.Pack("legacy-import","1.0.0")))&&migrated.warnings().isEmpty(),"exported independent pack replaces compatibility only on explicit selection");
            check(migrated.scenes().scene(2).instances()==2&&migrated.items().entries().equals(legacy.items().entries())
                &&migrated.weapons().weapons().equals(legacy.weapons().weapons())&&migrated.fires().entries().equals(legacy.fires().entries()),"export preserves maps, item identity, combat controls and fire graphs");
            var after=bytes(root);fail(()->ContentMigration.migrate(root));unchanged(after,root);
            // Invalid legacy content is never replaced by shipped vanilla or a reduced catalog.
            Files.delete(root.resolve("config/content.properties"));
            Files.writeString(root.resolve("maps/catalog.properties"),"entry-scene=999999\n");
            var damaged=bytes(root);fail(()->ContentManager.load(root));unchanged(damaged,root);
            Path conflict=home.resolve("conflict");copy(Path.of("content/packs/vanilla"),conflict.resolve("defaults"));
            Files.createDirectories(conflict.resolve("custom/packs/legacy-import"));Files.writeString(conflict.resolve("custom/packs/legacy-import/owner"),"custom content");
            var owner=bytes(conflict);fail(()->ContentMigration.migrate(conflict));unchanged(owner,conflict);
            check(!Files.exists(conflict.resolve("config/content-migrated.properties")),"existing operator pack refuses export before candidate creation");
            System.out.println("PASS: legacy precedence, safe explicit pack export, exact catalog preservation, held locks, invalid content and existing destinations; data/keys/settings untouched");
        } finally {try(var paths=Files.walk(home)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Map<Path,byte[]> bytes(Path root)throws IOException {
        var result=new HashMap<Path,byte[]>();try(var paths=Files.walk(root)){for(Path p:paths.filter(Files::isRegularFile).toList())result.put(root.relativize(p),Files.readAllBytes(p));}return result;
    }
    private static void unchanged(Map<Path,byte[]> before,Path root)throws IOException {for(var e:before.entrySet())check(Arrays.equals(e.getValue(),Files.readAllBytes(root.resolve(e.getKey()))),"original bytes preserved: "+e.getKey());}
    private static void copy(Path source,Path target)throws IOException {try(var paths=Files.walk(source)){for(Path p:paths.toList()){Path out=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(out);else Files.copy(p,out);}}}
    private interface Action {void run()throws Exception;}
    private static void fail(Action action)throws Exception {try{action.run();throw new AssertionError("Invalid content/migration accepted");}catch(IOException expected){}}
}
