import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only installed-client exporters/checks, with disposable home/preferences and Java 25 compilation. */
public final class StockTools {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: java scripts/StockTools.java previews|items|weapons|fires|equipment|scene|haven game-directory [arguments]");
        Path project = Path.of("").toAbsolutePath(), game = Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(game.resolve("code/projectx-pcode.jar"))) throw new IOException("Missing installed stock game JAR");
        String fixture;
        var sources=new ArrayList<Path>();
        var fixtureArgs = new ArrayList<String>(List.of(game.toString()));
        switch (args[0]) {
            case "previews" -> {
                if (args.length != 2) throw new IllegalArgumentException("previews expects only a game directory");
                fixture = "PreviewCompatibility";
                fixtureArgs.add(project.resolve("src/main/resources/starter-models.txt").toString());
                if (!Files.isRegularFile(project.resolve("build/tortodrone.jar")))
                    throw new IOException("Build the server JAR before checking previews: ./gradlew buildServer");
            }
            case "items" -> {
                if (args.length > 3) throw new IllegalArgumentException("items expects game directory [fresh-output-directory]");
                fixture = "ItemCatalogExport";
                fixtureArgs.add(args.length == 3 ? Path.of(args[2]).toAbsolutePath().toString() : project.resolve("build/items").toString());
                if(!Files.isRegularFile(project.resolve("build/tortodrone.jar")))throw new IOException("Build the server JAR before exporting items: ./gradlew buildServer");
            }
            case "fires" -> {
                if(args.length!=3)throw new IllegalArgumentException("fires expects game-directory fresh-output-directory");
                fixture="FireCatalogExport"; fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "weapons" -> {
                if(args.length!=3)throw new IllegalArgumentException("weapons expects game-directory fresh-output-directory");
                fixture="WeaponCatalogExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "equipment" -> {
                if(args.length!=4)throw new IllegalArgumentException("equipment expects game-directory grant-catalog.json fresh-output-directory");
                fixture="EquipmentCatalogExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
            }
            case "haven" -> {
                if(args.length!=4)throw new IllegalArgumentException("haven expects game-directory captured-binaries-directory fresh-output-directory");
                fixture="HavenSceneExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
                sources.add(project.resolve("scripts/compat/ScenePackExport.java"));
                sources.add(project.resolve("scripts/compat/HavenSceneExport.java"));
            }
            case "scene" -> {
                if (args.length != 6 && args.length != 8) throw new IllegalArgumentException("scene expects game-directory archived-scene output-directory zone-id zone-name [spawn-x spawn-y]");
                fixture = "ScenePackExport";
                fixtureArgs.addAll(Arrays.asList(args).subList(2, args.length));
                sources.add(project.resolve("scripts/compat/ScenePackExport.java"));
            }
            default -> throw new IllegalArgumentException("Unknown stock tool");
        }
        if(sources.isEmpty())sources.add(project.resolve("scripts/compat/" + fixture + ".java"));
        Path scratch = Files.createTempDirectory("tortodrone-stock-tool-");
        try {
            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code/*")
                + File.pathSeparator + project.resolve("build/tortodrone.jar");
            var compile=new ArrayList<>(List.of(executable("javac"), "--release", "25", "-cp", cp, "-d", scratch.toString()));
            for(Path source:sources)compile.add(source.toString());
            run(compile);
            var command = new ArrayList<>(List.of(executable("java"), "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dno_unpack_resources=true",
                "-Duser.home=" + scratch.resolve("home"), "-Djava.util.prefs.userRoot=" + scratch.resolve("prefs"),
                "-Djava.util.prefs.systemRoot=" + scratch.resolve("system-prefs"), "-cp", scratch + File.pathSeparator + cp, fixture));
            command.addAll(fixtureArgs);
            run(command);
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static String executable(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }
    private static void run(List<String> command) throws Exception {
        Process child = new ProcessBuilder(command).inheritIO().start();
        Thread cleanup = new Thread(() -> stop(child));
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            if (!child.waitFor(180, TimeUnit.SECONDS)) throw new IOException("Stock tool timed out");
            if (child.exitValue() != 0) throw new IOException("Stock tool failed with exit " + child.exitValue());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            stop(child);
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shuttingDown) { /* The registered hook is already stopping the child. */ }
        }
    }
    private static void stop(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!child.isAlive()) return;
            child.destroy();
            try {
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException e) {
                interrupted = true;
                child.destroyForcibly();
            }
            interrupted = awaitExit(child, interrupted);
        } finally {
            if (Thread.interrupted()) interrupted = true;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static boolean awaitExit(Process child, boolean interrupted) {
        while (child.isAlive()) {
            try { child.waitFor(); }
            catch (InterruptedException e) {
                interrupted = true;
                child.destroyForcibly();
            }
        }
        return interrupted;
    }
}
