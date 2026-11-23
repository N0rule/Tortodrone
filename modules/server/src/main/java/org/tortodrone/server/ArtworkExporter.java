package org.tortodrone.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * JDK toolchain and subprocess lifecycle for {@link ClientArtworkSetup}.
 *
 * <p>The setup class keeps installation discovery, skip markers, pack validation and atomic
 * publication. This class owns only the bundled exporter: classpath assembly, the javac/java
 * invocations, progress polling, the deadline, log tail and child shutdown hook.
 */
final class ArtworkExporter implements ClientArtworkSetup.Exporter {
    private final Consumer<String> info;

    ArtworkExporter(Consumer<String> info) {
        this.info = Objects.requireNonNull(info);
    }

    @Override
    public void export(Path game, Path output) throws IOException {
        export(game, output, info);
    }

    private static void export(Path game, Path output, Consumer<String> info) throws IOException {
        Path scratch = output.getParent();
        Path source = scratch.resolve("WebUiAssetsExport.java");
        try (var input = ClientArtworkSetup.class.getResourceAsStream("/tools/WebUiAssetsExport.java")) {
            if (input == null) throw new IOException("Bundled artwork exporter is missing");
            Files.copy(input, source);
        }
        Path jar;
        try {
            jar = Path.of(ClientArtworkSetup.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException(invalid);
        }
        String cp = exportClasspath(game, jar);
        String suffix = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? ".exe" : "";
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (!Files.isRegularFile(bin.resolve("javac" + suffix)))
            throw new IOException("Install JDK 25 (including javac) to prepare artwork");
        run(
                List.of(
                        bin.resolve("javac" + suffix).toString(),
                        "-J-XX:ActiveProcessorCount=2",
                        "--release",
                        "25",
                        "-cp",
                        cp,
                        "-d",
                        scratch.toString(),
                        source.toString()),
                scratch,
                "compile",
                info,
                percent -> {});
        try (var progress = new ArtworkProgress(
                info, System.console() == null ? null : System.console().writer())) {
            run(
                    List.of(
                            bin.resolve("java" + suffix).toString(),
                            "-XX:ActiveProcessorCount=2",
                            "-Xmx512m",
                            "--add-opens=java.base/java.lang=ALL-UNNAMED",
                            "--add-opens=java.base/java.util=ALL-UNNAMED",
                            "-Djava.awt.headless=true",
                            "-Dno_unpack_resources=true",
                            "-Duser.home=" + scratch.resolve("home"),
                            "-Djava.util.prefs.userRoot=" + scratch.resolve("prefs"),
                            "-Djava.util.prefs.systemRoot=" + scratch.resolve("system-prefs"),
                            "-Djava.io.tmpdir=" + scratch,
                            "-cp",
                            scratch + File.pathSeparator + cp,
                            "WebUiAssetsExport",
                            game.toString(),
                            output.toString(),
                            scratch.resolve("artwork.progress").toString()),
                    scratch,
                    "export",
                    info,
                    progress);
        }
    }

    static String exportClasspath(Path game, Path jar) {
        // '*' belongs to the Java launcher, not Path (Windows rejects wildcard paths).
        return game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code") + File.separator
                + "*" + File.pathSeparator + jar;
    }

    static void run(List<String> command, Path scratch, String step, Consumer<String> info, IntConsumer progress)
            throws IOException {
        info.accept("WebUI artwork: " + (step.equals("compile") ? "compiling the image exporter" : "extracting images")
                + "...");
        Path log = scratch.resolve(step + ".log");
        Process child = new ProcessBuilder(command)
                .directory(scratch.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        Thread cleanup = new Thread(child::destroyForcibly, "ArtworkExportCleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
            int previous = -1;
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Artwork " + step + " timed out");
                boolean done =
                        child.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                Path status = scratch.resolve("artwork.progress");
                if (step.equals("export")
                        && Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(status) <= 4) {
                    String value = Files.readString(status);
                    if (value.matches("[0-9]{1,3}")) {
                        int percent = Integer.parseInt(value);
                        if (percent > previous && percent <= 100) {
                            progress.accept(percent);
                            previous = percent;
                        }
                    }
                }
                if (done) break;
            }
            if (child.exitValue() != 0) {
                String detail =
                        Files.size(log) <= 65_536 ? Files.readString(log) : "Exporter output exceeded diagnostic limit";
                throw new IOException("Artwork " + step + " failed: " + detail.strip());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Artwork preparation interrupted", interrupted);
        } finally {
            stop(child);
            try {
                Runtime.getRuntime().removeShutdownHook(cleanup);
            } catch (IllegalStateException shuttingDown) {
                /* The hook already owns this child. */
            }
        }
    }

    static void stop(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (child.isAlive()) {
                child.destroyForcibly();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (child.isAlive() && System.nanoTime() < deadline) {
                    try {
                        child.waitFor(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
