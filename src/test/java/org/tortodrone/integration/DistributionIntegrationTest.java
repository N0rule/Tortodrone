package org.tortodrone.integration;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import java.util.concurrent.TimeUnit;
import org.tortodrone.world.SceneCatalog;
import static org.tortodrone.support.TestWire.check;

/** End-user release: external maps, no source/cache/game dependency, isolated startup. */
public final class DistributionIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-release-test-");
        try {
            Path deployment;
            try (var archive = new ZipFile(Path.of(args[0]).toFile())) {
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    Path output = home.resolve(entry.getName()).normalize();
                    check(output.startsWith(home), "ZIP stays inside extraction directory");
                    check(!entry.getName().contains("/data/") && !entry.getName().contains("/build/"), "no runtime state or cache shipped");
                    Files.createDirectories(output.getParent());
                    try (var input = archive.getInputStream(entry)) { Files.copy(input, output); }
                }
            }
            try (var paths = Files.list(home)) { deployment = paths.findFirst().orElseThrow(); }
            var catalog = SceneCatalog.load(deployment.resolve("maps"));
            check(catalog.scenes().size() == 2 && catalog.entry().version() == -1072195501,
                "complete regular Town Square and Bazaar included");
            offlinePreparation(home, deployment);
            for (String name : List.of("tortodrone.jar", "tortodrone-admin.jar")) {
                Path jar = deployment.resolve(name);
                try (var archive = new JarFile(jar.toFile())) {
                    check(archive.stream().noneMatch(entry -> entry.getName().startsWith("maps/")
                        || entry.getName().endsWith("scene.dat") || entry.getName().endsWith("walkability.gz")), "maps remain outside both JARs");
                }
                Process version = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-jar", jar.toString(), "--version").directory(home.toFile()).redirectErrorStream(true).start();
                try {
                    check(version.waitFor(15, TimeUnit.SECONDS) && version.exitValue() == 0, "extracted application runs independently");
                } finally { version.destroyForcibly(); version.waitFor(); }
            }
            Path config = deployment.resolve("test.properties");
            Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nlog-mode=normal\n");
            try (var server = new ServerIntegrationTest.Running(deployment.resolve("tortodrone.jar"), config, home)) {
                check(server.ready() > 0, "unpacked server starts from another directory without tools or map downloads");
            }
            System.out.println("PASS: complete offline release, external regular maps, independent JARs and isolated startup/shutdown");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void offlinePreparation(Path home, Path deployment) throws Exception {
        Path project = home.resolve("source without cache");
        Path source = project.resolve("maps/default");
        try (var paths = Files.walk(deployment.resolve("maps"))) {
            for (Path path : paths.toList()) {
                Path out = source.resolve(deployment.resolve("maps").relativize(path));
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
