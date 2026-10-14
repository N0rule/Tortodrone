package org.tortodrone.integration;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
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
            checkedInSourcePreparation(home);
            System.out.println("PASS: offline map and content preparation, published-pack upgrades, edited-pack preservation, deployment locks, added packs, missing-source handling, owner symlinks and corrupt-source refusal");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void offlinePreparation(Path home) throws Exception {
        Path project = home.resolve("source without cache");
        Path source = project.resolve("content/packs/vanilla/maps");
        try (var paths = Files.walk(Path.of("content/packs/vanilla/maps"))) {
            for (Path path : paths.toList()) {
                Path out = source.resolve(Path.of("content/packs/vanilla/maps").relativize(path));
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

    private static void checkedInSourcePreparation(Path home) throws Exception {
        Path project=home.resolve("checked-in map source project");
        Path maps=project.resolve("content/packs/vanilla/maps");
        copyTree(Path.of("content/packs/vanilla"),project.resolve("content/packs/vanilla"));
        if(Files.isDirectory(Path.of("config")))copyTree(Path.of("config"),project.resolve("config"));
        if(Files.isDirectory(Path.of("custom/packs")))copyTree(Path.of("custom/packs"),project.resolve("custom/packs"));
        Path tool=project.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),tool);
        Path mapsOutput=project.resolve("deployment/defaults/maps");
        check(runTool(project,tool,"maps",mapsOutput.toString())==0,"maps prepare from the checked-in source alone");
        var catalog=SceneCatalog.load(mapsOutput);
        check(catalog.scenes().size()==7,"the checked-in source prepares all seven Haven scenes");
        check(runTool(project,tool,"maps",mapsOutput.toString())==0,"checked-in source preparation is repeatable");

        Path vanillaOutput=project.resolve("deployment/content/packs/vanilla");
        check(runTool(project,tool,"content",vanillaOutput.toString())==0
            &&SceneCatalog.load(vanillaOutput.resolve("maps")).scenes().size()==7
            &&Files.isRegularFile(vanillaOutput.resolve("items/catalog.json")),
            "full content preparation includes maps, item definitions and combat data");
        Path absent=home.resolve("absent vanilla source project");
        Files.createDirectories(absent);
        Path absentTool=absent.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),absentTool);
        Path absentOutput=absent.resolve("content/packs/vanilla");
        check(runTool(absent,absentTool,"content",absentOutput.toString())!=0&&!Files.exists(absentOutput),
            "full content preparation refuses a missing canonical vanilla source");
        Path locked=project.resolve("locked deployment");
        Path lockFile=locked.resolve("data/server.lock");Files.createDirectories(lockFile.getParent());
        Files.createFile(lockFile);
        Path lockedVanilla=locked.resolve("content/packs/vanilla");
        try(FileChannel channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);FileLock lock=channel.lock()) {
            check(runTool(project,tool,"content",lockedVanilla.toString())!=0
                &&!Files.exists(lockedVanilla),
                "active deployment lock refuses all canonical content changes before writing");
        }
        check(runTool(project,tool,"content",lockedVanilla.toString())==0,
            "canonical content preparation proceeds after deployment unlocks");
        try(FileChannel channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);FileLock lock=channel.lock()) {
            check(runTool(project,tool,"maps",locked.resolve("maps").toString())!=0
                &&!Files.exists(locked.resolve("maps")),
                "active deployment lock refuses map updates before changing any map");
        }
        check(runTool(project,tool,"maps",locked.resolve("maps").toString())==0,"preparation proceeds once the deployment unlocks");

        // Packs this source published earlier are upgraded; edited packs keep their bytes.
        Path upgraded=project.resolve("previous generation");
        check(runTool(project,tool,"maps",upgraded.toString())==0,"previous generation prepares from the checked-in source");
        Path upgradedPortals=upgraded.resolve("haven/portals.properties");
        byte[] published=Files.readAllBytes(upgradedPortals);
        byte[] earlier=("# previous generation\n"+new String(published,java.nio.charset.StandardCharsets.UTF_8))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(upgradedPortals,earlier);
        var manifest=properties(maps.resolve("scene-source.properties"));
        manifest.setProperty("owned.haven/portals.properties",manifest.getProperty("owned.haven/portals.properties")
            +","+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(earlier)));
        try(var out=Files.newOutputStream(maps.resolve("scene-source.properties"))) {manifest.store(out,"ownership covers an earlier generation");}
        check(runTool(project,tool,"maps",upgraded.toString())==0
            &&Arrays.equals(published,Files.readAllBytes(upgradedPortals)),
            "a pack this source published earlier is upgraded on preparation");
        byte[] custom=("# owner edit\n"+new String(published,java.nio.charset.StandardCharsets.UTF_8))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(upgradedPortals,custom);
        Path descriptor=upgraded.resolve("haven/map.properties");
        Files.writeString(descriptor,Files.readString(descriptor).replace("instances=1","instances=4"));
        byte[] customDescriptor=Files.readAllBytes(descriptor);
        check(runTool(project,tool,"maps",upgraded.toString())==0
            &&Arrays.equals(custom,Files.readAllBytes(upgradedPortals))
            &&Arrays.equals(customDescriptor,Files.readAllBytes(descriptor)),
            "edited packs and descriptors are preserved instead of replaced or refused");

        makeSyntheticPack(maps,"training_yard",905);
        Path discovered=project.resolve("discovered pack");
        check(runTool(project,tool,"maps",discovered.toString())==0
            &&SceneCatalog.load(discovered).scene(905).id()==905,
            "an added source pack is discovered without a name list to edit");

        Path damaged=home.resolve("corrupt map source project");
        copyTree(Path.of("content/packs/vanilla/maps"),damaged.resolve("content/packs/vanilla/maps"));
        Files.write(damaged.resolve("content/packs/vanilla/maps/arcade/scene.dat"),new byte[]{1,2,3});
        Path damagedTool=damaged.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),damagedTool);
        Path damagedOutput=damaged.resolve("output maps");
        check(runTool(damaged,damagedTool,"maps",damagedOutput.toString())!=0&&!Files.exists(damagedOutput),
            "damaged source is rejected before creating output");

        Path tampered=home.resolve("tampered map source project");
        copyTree(Path.of("content/packs/vanilla/maps"),tampered.resolve("content/packs/vanilla/maps"));
        Files.writeString(tampered.resolve("content/packs/vanilla/maps/laboratory/portals.properties"),
            Files.readString(tampered.resolve("content/packs/vanilla/maps/laboratory/portals.properties"))+"# stray edit\n");
        Path tamperedTool=tampered.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),tamperedTool);
        Path tamperedOutput=tampered.resolve("output maps");
        check(runTool(tampered,tamperedTool,"maps",tamperedOutput.toString())!=0&&!Files.exists(tamperedOutput),
            "a source pack that no longer matches its checksum manifest is rejected before creating output");

        Path missingPack=home.resolve("missing map source project");
        copyTree(Path.of("content/packs/vanilla/maps"),missingPack.resolve("content/packs/vanilla/maps"));
        deleteTree(missingPack.resolve("content/packs/vanilla/maps/hall_of_heroes"));
        Path missingTool=missingPack.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),missingTool);
        Path missingOutput=missingPack.resolve("output maps");
        check(runTool(missingPack,missingTool,"maps",missingOutput.toString())!=0&&!Files.exists(missingOutput),
            "source missing a pinned scene pack is rejected before creating output");

        Path linked=home.resolve("symlinked map source project");
        copyTree(Path.of("content/packs/vanilla/maps"),linked.resolve("content/packs/vanilla/maps"));
        boolean sourceSymlink=false;
        try {Files.createSymbolicLink(linked.resolve("content/packs/vanilla/maps/source-link"),linked.resolve("content/packs/vanilla/maps/haven/map.properties"));sourceSymlink=true;}
        catch(UnsupportedOperationException|java.io.IOException unavailable) {
            System.out.println("SKIP: source symlink test (symlinks unavailable)");
        }
        if(sourceSymlink) {
            Path linkedTool=linked.resolve("BuildTools.java");Files.copy(Path.of("scripts/BuildTools.java"),linkedTool);
            Path linkedOutput=linked.resolve("output maps");
            check(runTool(linked,linkedTool,"maps",linkedOutput.toString())!=0&&!Files.exists(linkedOutput),
                "source symlink is rejected before creating output");
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
