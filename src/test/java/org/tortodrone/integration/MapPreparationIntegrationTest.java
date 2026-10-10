package org.tortodrone.integration;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.tortodrone.world.SceneCatalog;
import static org.tortodrone.support.TestWire.check;

/** Map preparation works without downloads, caches, game files or release archives. */
public final class MapPreparationIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-offline-maps-test-");
        try {
            offlinePreparation(home);
            localMapPreparation(home);
            System.out.println("PASS: offline map preparation, local map upgrades, edited-pack preservation, missing-source handling, owner symlinks and corrupt-source refusal");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void offlinePreparation(Path home) throws Exception {
        Path project = home.resolve("source without cache");
        Path source = project.resolve("defaults/maps");
        try (var paths = Files.walk(Path.of("defaults/maps"))) {
            for (Path path : paths.toList()) {
                Path out = source.resolve(Path.of("defaults/maps").relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(out); else Files.copy(path, out);
            }
        }
        Path tool = project.resolve("BuildTools.java");
        Files.copy(Path.of("scripts/BuildTools.java"), tool);
        check(runMaps(project, tool, "prepared maps") == 0, "maps prepare without dependency cache or provenance URLs");
        SceneCatalog.load(project.resolve("prepared maps"));
        Path edited = project.resolve("prepared maps/haven/map.properties");
        Files.writeString(edited, Files.readString(edited).replace("instances=1", "instances=2"));
        byte[] preserved = Files.readAllBytes(edited);
        check(runMaps(project, tool, "prepared maps") == 0 && Arrays.equals(preserved, Files.readAllBytes(edited)), "edited deployment pack preserved");
        // Optional packs can be omitted from a source distribution without blocking preparation.
        Path saved = project.resolve("saved maps");
        Files.move(source, saved);
        check(runMaps(project, tool, "missing maps") == 0 && !Files.exists(project.resolve("missing maps")),
            "absent optional map source warns and succeeds without inventing resources");
        Files.writeString(source, "invalid source directory");
        check(runMaps(project, tool, "invalid maps") != 0 && !Files.exists(project.resolve("invalid maps")),
            "present malformed map source is refused rather than treated as optional absence");
        Files.delete(source);
        Files.move(saved, source);
        // Preserve a dangling owner-file symlink rather than following or replacing it.
        Path outside = project.resolve("owner file that does not exist");
        Path linked = project.resolve("prepared maps/haven/scene.dat");
        Files.delete(linked);
        boolean symlinks = false;
        try { Files.createSymbolicLink(linked, outside); symlinks = true; }
        catch (UnsupportedOperationException | java.io.IOException unavailable) {
            System.out.println("SKIP: owner-file symlink test (symlinks unavailable)");
        }
        if (symlinks) {
            check(runMaps(project, tool, "prepared maps") == 0 && Files.isSymbolicLink(linked)
                && !Files.exists(outside), "dangling owner-file symlink preserved without writing its target");
        }
        Files.write(source.resolve("haven/scene.dat"), new byte[]{1, 2, 3});
        check(runMaps(project, tool, "damaged maps") != 0 && !Files.exists(project.resolve("damaged maps")),
            "damaged source rejected before producing a partial deployment");
        check(!Files.exists(project.resolve("build/deps")), "offline preparation creates no download cache");
    }

    private static void localMapPreparation(Path home) throws Exception {
        Path project=home.resolve("local map source project");
        Path bundled=project.resolve("defaults/maps"),local=project.resolve("local/defaults/maps");
        copyTree(Path.of("defaults/maps"),bundled);
        Files.writeString(project.resolve("defaults/items-source-marker.txt"),"bundled item resources remain included\n");
        Path tool=project.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),tool);
        Path mapsOutput=project.resolve("deployment/defaults/maps");
        check(runTool(project,tool,"maps",mapsOutput.toString())==0,"bundled baseline maps prepare before local maps exist");
        Path conflict=project.resolve("conflicting maps");copyTree(mapsOutput,conflict);

        copyTree(bundled,local);
        Path sourcePortals=local.resolve("haven/portals.properties");
        Files.writeString(sourcePortals,Files.readString(sourcePortals)+"\n# local source update\n");
        makeSyntheticPack(local,"advanced_training",900);
        makeSyntheticPack(local,"laboratory",901);
        makeSyntheticPack(local,"hall_of_heroes",902);

        byte[] baselinePortals=Files.readAllBytes(mapsOutput.resolve("haven/portals.properties"));
        Path lockFile=project.resolve("deployment/data/server.lock");Files.createDirectories(lockFile.getParent());
        Files.createFile(lockFile);
        try(FileChannel channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);FileLock lock=channel.lock()) {
            check(runTool(project,tool,"maps",mapsOutput.toString())!=0
                &&!Files.exists(mapsOutput.resolve("advanced_training"))
                &&Arrays.equals(baselinePortals,Files.readAllBytes(mapsOutput.resolve("haven/portals.properties"))),
                "active deployment lock refuses local map updates before changing any map");
        }
        check(runTool(project,tool,"maps",mapsOutput.toString())==0,"maps preparation selects the complete local source after the deployment unlocks");
        var catalog=SceneCatalog.load(mapsOutput);
        check(catalog.scenes().size()==7&&catalog.scene(900).id()==900&&catalog.scene(901).id()==901
            &&catalog.scene(902).id()==902,"local source adds three independent packs to the four towns");
        check(Arrays.equals(Files.readAllBytes(mapsOutput.resolve("haven/portals.properties")),
            Files.readAllBytes(sourcePortals)),"local town portal metadata upgrades the bundled baseline copy");
        check(runTool(project,tool,"maps",mapsOutput.toString())==0,"local maps preparation is repeatable");

        Path custom=mapsOutput.resolve("haven/map.properties");
        Files.writeString(custom,Files.readString(custom).replace("instances=1","instances=2"));
        byte[] customDescriptor=Files.readAllBytes(custom);
        check(runTool(project,tool,"maps",mapsOutput.toString())==0
            &&Arrays.equals(customDescriptor,Files.readAllBytes(custom)),"custom destination descriptor remains untouched on local-source repeat");

        Path defaultsOutput=project.resolve("prepared defaults");
        check(runTool(project,tool,"defaults",defaultsOutput.toString())==0
            &&SceneCatalog.load(defaultsOutput.resolve("maps")).scenes().size()==7
            &&Files.readString(defaultsOutput.resolve("items-source-marker.txt")).contains("bundled item resources"),
            "defaults preparation also selects local maps while retaining other defaults");

        Path conflictPortals=conflict.resolve("haven/portals.properties");
        Files.writeString(conflictPortals,Files.readString(conflictPortals)+"\n# owner edit\n");
        byte[] ownerPortal=Files.readAllBytes(conflictPortals);
        check(runTool(project,tool,"maps",conflict.toString())!=0
            &&!Files.exists(conflict.resolve("advanced_training"))
            &&Arrays.equals(ownerPortal,Files.readAllBytes(conflictPortals)),
            "conflicting town portal edit refuses the local upgrade before installing any new pack");

        Path damaged=home.resolve("corrupt local maps project");
        copyTree(Path.of("defaults/maps"),damaged.resolve("defaults/maps"));
        copyTree(local,damaged.resolve("local/defaults/maps"));
        Files.write(damaged.resolve("local/defaults/maps/arcade/scene.dat"),new byte[]{1,2,3});
        Path damagedTool=damaged.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),damagedTool);
        Path damagedOutput=damaged.resolve("output maps");
        check(runTool(damaged,damagedTool,"maps",damagedOutput.toString())!=0&&!Files.exists(damagedOutput),
            "corrupt local source is rejected before creating output");

        Path missingPack=home.resolve("missing local pack project");
        copyTree(Path.of("defaults/maps"),missingPack.resolve("defaults/maps"));
        copyTree(local,missingPack.resolve("local/defaults/maps"));
        deleteTree(missingPack.resolve("local/defaults/maps/advanced_training"));
        Path missingTool=missingPack.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),missingTool);
        Path missingOutput=missingPack.resolve("output maps");
        check(runTool(missingPack,missingTool,"maps",missingOutput.toString())!=0&&!Files.exists(missingOutput),
            "local source missing a required scene pack is rejected before creating output");

        Path missingPortals=home.resolve("missing local portals project");
        copyTree(Path.of("defaults/maps"),missingPortals.resolve("defaults/maps"));
        copyTree(local,missingPortals.resolve("local/defaults/maps"));
        Files.delete(missingPortals.resolve("local/defaults/maps/laboratory/portals.properties"));
        Path missingPortalsTool=missingPortals.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),missingPortalsTool);
        Path missingPortalsOutput=missingPortals.resolve("output maps");
        check(runTool(missingPortals,missingPortalsTool,"maps",missingPortalsOutput.toString())!=0&&!Files.exists(missingPortalsOutput),
            "local source missing portal metadata is rejected before creating output");

        Path linked=home.resolve("symlinked local maps project");
        copyTree(Path.of("defaults/maps"),linked.resolve("defaults/maps"));
        copyTree(local,linked.resolve("local/defaults/maps"));
        Path localMap=linked.resolve("local/defaults/maps");
        boolean sourceSymlink=false;
        try {Files.createSymbolicLink(localMap.resolve("source-link"),localMap.resolve("haven/map.properties"));sourceSymlink=true;}
        catch(UnsupportedOperationException|java.io.IOException unavailable) {
            System.out.println("SKIP: local source symlink test (symlinks unavailable)");
        }
        if(sourceSymlink) {
            Path linkedTool=linked.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),linkedTool);
            Path linkedOutput=linked.resolve("output maps");
            check(runTool(linked,linkedTool,"maps",linkedOutput.toString())!=0&&!Files.exists(linkedOutput),
                "local source symlink is rejected before creating output");
        }
    }

    private static void copyTree(Path source,Path target)throws Exception {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path destination=target.resolve(source.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(destination);
                else {Files.createDirectories(destination.getParent());Files.copy(path,destination);}
            }
        }
    }
    private static void makeSyntheticPack(Path maps,String name,int sceneId)throws Exception {
        Path pack=maps.resolve(name);
        copyTree(maps.resolve("bazaar"),pack);
        Path descriptor=pack.resolve("map.properties");
        Files.writeString(descriptor,Files.readString(descriptor).replace("scene-id=2", "scene-id="+sceneId));
        Properties portals=new Properties();
        portals.setProperty("format","2");
        portals.setProperty("layout-sha256",properties(descriptor).getProperty("scene-sha256"));
        portals.setProperty("entrances","");portals.setProperty("triggers","");
        try(var out=Files.newOutputStream(pack.resolve("portals.properties"))) {portals.store(out,"Synthetic isolated test pack");}
    }
    private static Properties properties(Path file)throws Exception {
        Properties values=new Properties();try(var in=Files.newInputStream(file)){values.load(in);}return values;
    }
    private static void deleteTree(Path root)throws Exception {
        try(var paths=Files.walk(root)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    private static int runTool(Path project,Path tool,String command,String output)throws Exception {
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
            tool.toString(),command,output).directory(project.toFile()).redirectErrorStream(true)
            .redirectOutput(project.resolve("tool-output.txt").toFile()).start();
        try {check(process.waitFor(30,TimeUnit.SECONDS),"map preparation finishes");return process.exitValue();}
        finally {process.destroyForcibly();process.waitFor();}
    }
    private static int runMaps(Path project, Path tool, String output) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            tool.toString(), "maps", output).directory(project.toFile()).redirectErrorStream(true)
            .redirectOutput(project.resolve("tool-output.txt").toFile()).start();
        try {
            check(process.waitFor(30, TimeUnit.SECONDS), "offline map preparation finishes");
            return process.exitValue();
        } finally { process.destroyForcibly(); process.waitFor(); }
    }
}
