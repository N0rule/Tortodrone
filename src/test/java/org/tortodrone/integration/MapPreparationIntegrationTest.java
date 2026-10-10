package org.tortodrone.integration;

import java.nio.file.*;
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
            System.out.println("PASS: offline map preparation, edited-pack preservation, missing-source handling, owner symlinks and corrupt-source refusal");
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
