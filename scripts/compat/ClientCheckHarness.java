import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Shared lifecycle for the optional compat drivers: disposable sandbox children,
 *  readiness, log tails and interrupt-preserving cleanup. No check assertions live here. */
final class ClientCheckHarness {
    static final List<Process> CHILDREN = new ArrayList<>();
    private static final Pattern LISTENING = Pattern.compile("Listening on 127\\.0\\.0\\.1:(\\d+)");

    private ClientCheckHarness() {}

    static String executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    static Process start(Path scratch, List<String> command, Path log, Path directory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // The stock graphical client expects its WM hint even when launched headless here.
        builder.environment().put("_JAVA_AWT_WM_NONREPARENTING", "1");
        Process process = builder.start();
        synchronized (CHILDREN) {
            CHILDREN.add(process);
        }
        return process;
    }

    /** Run a disposable child, feed its stdin once and echo its PASS lines. */
    static void run(Path scratch, List<String> command, String name, String input, int timeout) throws Exception {
        Path log = scratch.resolve(name + ".log");
        Process process = start(scratch, command, log, scratch);
        try (var out = process.outputWriter()) {
            out.write(input);
        }
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException(name + " timed out:\n" + tail(log));
        if (process.exitValue() != 0) throw new IOException(name + " failed:\n" + tail(log));
        for (String line : Files.readAllLines(log)) if (line.startsWith("PASS:")) System.out.println(line);
    }

    /** Run a disposable child from an explicit working directory without echoing its log. */
    static void runQuiet(Path scratch, List<String> command, String name, int timeout, Path directory)
            throws Exception {
        Path log = scratch.resolve(name + ".log");
        Process process = start(scratch, command, log, directory == null ? scratch : directory);
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException(name + " timed out:\n" + tail(log));
        if (process.exitValue() != 0) throw new IOException(name + " failed:\n" + tail(log));
    }

    /** Wait for the disposable server's listening line within {@code timeout}. */
    static String ready(Process server, Path log, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var match = LISTENING.matcher(Files.exists(log) ? Files.readString(log) : "");
            if (match.find()) return match.group(1);
            if (!server.isAlive()) throw new IOException("Disposable server failed:\n" + tail(log));
            Thread.sleep(50);
        }
        throw new IOException("Disposable server readiness timeout:\n" + tail(log));
    }

    static String tail(Path path) throws IOException {
        if (!Files.exists(path)) return "(no log yet)";
        String text = Files.readString(path);
        return text.substring(Math.max(0, text.length() - 9000));
    }

    static void copyTree(Path source, Path destination, String label) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink in " + label + ": " + path);
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    static void stopChildren() {
        synchronized (CHILDREN) {
            boolean interrupted = Thread.interrupted();
            try {
                for (int i = CHILDREN.size() - 1; i >= 0; i--) interrupted = stopChild(CHILDREN.get(i), interrupted);
            } finally {
                if (Thread.interrupted()) interrupted = true;
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean stopChild(Process process, boolean interrupted) {
        if (!process.isAlive()) return interrupted;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        }
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                interrupted = true;
                process.destroyForcibly();
            }
        }
        return interrupted;
    }
}
