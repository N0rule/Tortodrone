package org.tortodrone.server;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** All inputs/outputs are disposable; no installed game or subprocess is needed. */
public final class ClientArtworkSetupTest {
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--wait-child")){System.out.println("ready");Thread.sleep(60_000);return;}
        Path root = Files.createTempDirectory("tortodrone-artwork-test-");
        try {
            discovery(root.resolve("discovery"));
            lifecycle(root.resolve("lifecycle"));
            prompts(root.resolve("prompts"));
            failures(root.resolve("failures"));
            cancellation();
            options();
            System.out.println("PASS: Steam Linux/Windows roots and additional libraries, prompts, noninteractive/skip, persistent artwork cache, safe publication, failure preservation and startup flags");
        } finally { delete(root); }
    }

    private static void discovery(Path root) throws Exception {
        Path home = root.resolve("home"), steam = home.resolve(".local/share/Steam");
        client(steam.resolve("steamapps/common/Spiral Knights"));
        check(SteamClientLocator.find(SteamClientLocator.roots(home, "Linux", Map.of())).orElseThrow()
            .equals(steam.resolve("steamapps/common/Spiral Knights").toRealPath()), "Linux Steam client");
        delete(steam.resolve("steamapps/common/Spiral Knights"));
        Path library = root.resolve("External Steam Library");
        Path installed = client(library.resolve("steamapps/common/Custom Folder"));
        Files.createDirectories(steam.resolve("steamapps"));
        Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"), "\"libraryfolders\" { \"1\" { \"path\" \"" + library.toAbsolutePath().toString().replace("\\", "\\\\") + "\" } }");
        Files.writeString(library.resolve("steamapps/appmanifest_99900.acf"), "\"AppState\" { \"installdir\" \"Custom Folder\" }");
        check(SteamClientLocator.find(List.of(steam)).orElseThrow().equals(installed), "Additional library and app 99900 install directory");
        Files.writeString(library.resolve("steamapps/appmanifest_99900.acf"), "\"installdir\" \"../../outside\"");
        check(SteamClientLocator.find(List.of(steam)).isEmpty(), "Manifest path traversal refused");
        Path programs = root.resolve("Program Files (x86)");
        Path windows = client(programs.resolve("Steam/steamapps/common/Spiral Knights"));
        var roots = SteamClientLocator.roots(home, "Windows 11", Map.of("ProgramFiles(x86)", programs.toString()));
        check(roots.contains(programs.resolve("Steam")) && SteamClientLocator.find(roots).orElseThrow().equals(windows), "Windows Program Files discovery independent of test host OS");
        Path flatpak = client(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/common/Spiral Knights"));
        check(SteamClientLocator.find(SteamClientLocator.roots(home, "Linux", Map.of())).orElseThrow().equals(flatpak), "Flatpak Steam discovery");
        Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"), "\"1\" \"" + programs.resolve("Steam").toString().replace("\\", "\\\\") + "\"");
        check(SteamClientLocator.find(List.of(steam)).orElseThrow().equals(windows), "Legacy libraryfolders format");
        check(SteamClientLocator.client(root.resolve("missing")).isEmpty(), "Missing client rejected");
    }

    private static void lifecycle(Path root) throws Exception {
        Files.createDirectories(root);
        Path game = client(root.resolve("installed client")), deployment = root.resolve("deployment");
        Files.createDirectories(deployment);
        var exports = new AtomicInteger();
        ClientArtworkSetup.Exporter exporter = (input, output) -> {
            check(input.equals(game), "Exact real client root passed to exporter");
            exports.incrementAndGet(); pack(output, 0xff2288aa);
        };
        ClientArtworkSetup.prepare(deployment, game, false, List.of(), null, exporter, message -> {});
        Path target = deployment.resolve("webui/assets/game"), cache = deployment.resolve("data/client-artwork");
        check(exports.get() == 1 && ClientArtworkSetup.ready(target) && ClientArtworkSetup.ready(cache), "Explicit first export installs and caches");
        check(Files.readString(game.resolve("code/projectx-pcode.jar")).equals("read-only fixture"), "Client input unchanged");
        delete(target); Files.createDirectories(target);
        Files.writeString(target.resolve("manifest.json"), "{\"items\":{},\"ui\":{},\"sprites\":{}}");
        ClientArtworkSetup.prepare(deployment, null, false, List.of(), () -> { throw new AssertionError("Cache must not prompt"); }, exporter, message -> {});
        check(exports.get() == 1 && ClientArtworkSetup.ready(target), "Build-reset artwork restores without installed client or export");
        delete(cache);
        ClientArtworkSetup.prepare(deployment, null, false, List.of(), null, exporter, message -> {});
        check(exports.get() == 1 && ClientArtworkSetup.ready(cache), "Prebuilt valid artwork seeds durable cache");
        Path locked = deployment.resolve("data/server.lock");
        try (var channel = FileChannel.open(locked, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            try { ClientArtworkSetup.main(new String[]{deployment.toString(), game.toString()}); throw new AssertionError("Running deployment lock ignored"); }
            catch (java.nio.channels.OverlappingFileLockException expected) { }
        }
    }

    private static void prompts(Path root) throws Exception {
        Files.createDirectories(root);
        Path game = client(root.resolve("Game With Spaces"));
        Path deployment = Files.createDirectory(root.resolve("deployment"));
        var messages = new ArrayList<String>();
        var prompts = new ArrayDeque<>(List.of(root.resolve("invalid").toString(), "\"" + game + "\""));
        var exports = new AtomicInteger();
        ClientArtworkSetup.Exporter exporter = (input, output) -> { exports.incrementAndGet(); pack(output, 0xffaabbcc); };
        ClientArtworkSetup.prepare(deployment, null, false, List.of(), prompts::remove, exporter, messages::add);
        check(prompts.isEmpty() && exports.get() == 1 && messages.stream().anyMatch(m -> m.contains("Try again")), "Invalid prompt retries, quoted path accepted");
        Path skipped = Files.createDirectory(root.resolve("skipped"));
        ClientArtworkSetup.prepare(skipped, null, false, List.of(), () -> "", exporter, messages::add);
        check(Files.isRegularFile(skipped.resolve("data/client-artwork.skipped")), "Explicit skip persists");
        ClientArtworkSetup.prepare(skipped, null, false, List.of(), () -> { throw new AssertionError("Skipped setup prompted again"); }, exporter, messages::add);
        check(exports.get() == 1, "Persisted skip does not export");
        ClientArtworkSetup.prepare(skipped, game, false, List.of(), null, exporter, messages::add);
        check(exports.get() == 2 && !Files.exists(skipped.resolve("data/client-artwork.skipped")), "Explicit client overrides skipped first setup");
        Path noninteractive = Files.createDirectory(root.resolve("noninteractive"));
        ClientArtworkSetup.prepare(noninteractive, null, false, List.of(), null, exporter, messages::add);
        check(messages.stream().anyMatch(m -> m.contains("No interactive terminal")) && exports.get() == 2, "Piped/service startup never consumes console commands or blocks");
        ClientArtworkSetup.prepare(noninteractive, game, true, List.of(), null, exporter, messages::add);
        check(exports.get() == 2, "Skip flag bypasses extraction");
    }

    private static void failures(Path root) throws Exception {
        Files.createDirectories(root);
        Path game = client(root.resolve("game")), deployment = Files.createDirectory(root.resolve("deployment"));
        Path current = deployment.resolve("webui/assets/game"); pack(current, 0xff112233);
        String before = Files.readString(current.resolve("manifest.json"));
        try {
            ClientArtworkSetup.prepare(deployment, game, false, List.of(), null, (input, output) -> {
                Files.createDirectories(output); Files.writeString(output.resolve("incomplete"), "partial"); throw new IOException("deliberate exporter failure");
            }, message -> {});
            throw new AssertionError("Exporter failure ignored");
        } catch (IOException expected) { check(expected.getMessage().contains("deliberate"), "Original failure retained"); }
        check(Files.readString(current.resolve("manifest.json")).equals(before) && ClientArtworkSetup.ready(current), "Failed export preserves previous pack");
        try (var entries = Files.list(deployment.resolve("data"))) { check(entries.noneMatch(p -> p.getFileName().toString().startsWith(".client-artwork-")), "Failed scratch removed"); }
        Path source = root.resolve("source"); pack(source, 0xff334455);
        Files.writeString(current.resolve("owner-file.txt"), "preserve");
        try { ClientArtworkSetup.publish(source, current); throw new AssertionError("Unknown destination overwritten"); }
        catch (IOException expected) { check(expected.getMessage().contains("Preserving"), "Unknown files refused"); }
        check(Files.readString(current.resolve("owner-file.txt")).equals("preserve"), "Unrecognized owner file preserved");
        Path traversal = Files.createDirectory(root.resolve("unsafe"));
        Files.writeString(traversal.resolve("manifest.json"), "{\"items\":{\"item\":\"../../private.txt\"},\"ui\":{\"icon\":\"../../private.txt\"},\"sprites\":{}}");
        try { ClientArtworkSetup.ready(traversal); throw new AssertionError("Unsafe path accepted"); } catch (IOException expected) { }
        Path external = Files.createDirectory(root.resolve("external"));
        try {
            Path link = Files.createSymbolicLink(root.resolve("linked"), external);
            try { ClientArtworkSetup.publish(source, link.resolve("pack")); throw new AssertionError("Symlink output accepted"); } catch (IOException expected) { }
            check(!Files.exists(external.resolve("pack")), "No writes through output symlink");
            Path linkedDeployment = Files.createDirectory(root.resolve("linked-deployment"));
            Files.createSymbolicLink(linkedDeployment.resolve("data"), external);
            try { ClientArtworkSetup.main(new String[]{linkedDeployment.toString(), game.toString()}); throw new AssertionError("Manual preparation followed a data symlink"); }
            catch (IOException expected) { }
            check(!Files.exists(external.resolve("server.lock")), "Manual setup refuses linked data before opening its lease");
        } catch (UnsupportedOperationException | FileSystemException unavailable) { /* Windows may require developer mode to create a test symlink. */ }
        var warnings = new ArrayList<String>();
        ClientArtworkSetup.prepare(deployment, root.resolve("missing-client"), false, message -> {}, warnings::add);
        check(warnings.stream().anyMatch(m -> m.contains("Continuing with fallback")), "Invalid explicit input does not prevent game hosting");
    }

    private static void options() throws Exception {
        Path config = Path.of("server.yml");
        var selected = ServerOptions.parse(new String[]{"--client", "Game With Spaces", "--nogui"}, config);
        check(selected.client().equals(Path.of("Game With Spaces")) && !selected.skipClientAssets(), "Client path option");
        check(ServerOptions.parse(new String[]{"--skip-client-assets"}, config).skipClientAssets(), "Service skip option");
        for (String[] args : List.of(new String[]{"--client"}, new String[]{"--client", "game", "--client", "other"}, new String[]{"--client", "game", "--skip-client-assets"}, new String[]{"--skip-client-assets", "--skip-client-assets"})) {
            try { ServerOptions.parse(args, config); throw new AssertionError("Invalid artwork options accepted"); } catch (IOException expected) { }
        }
    }

    private static void cancellation() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable, "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"), ClientArtworkSetupTest.class.getName(), "--wait-child").redirectErrorStream(true).start();
        try {
            var ready = new java.util.concurrent.CompletableFuture<String>();
            Thread reader = new Thread(() -> { try { ready.complete(child.inputReader().readLine()); } catch(IOException failed) { ready.completeExceptionally(failed); } });
            reader.setDaemon(true); reader.start();
            check("ready".equals(ready.get(10, java.util.concurrent.TimeUnit.SECONDS)), "Disposable child started");
            Thread.currentThread().interrupt();
            ClientArtworkSetup.stop(child);
            check(Thread.interrupted(), "Cancellation status preserved after child cleanup");
            check(!child.isAlive(), "Owned export child reaped even while interrupted");
            reader.join(1000);
        } finally { ClientArtworkSetup.stop(child); }
    }

    private static Path client(Path path) throws IOException {
        Files.createDirectories(path.resolve("code")); Files.createDirectories(path.resolve("rsrc"));
        Files.writeString(path.resolve("code/projectx-pcode.jar"), "read-only fixture"); return path.toRealPath();
    }

    static void pack(Path path, int color) throws IOException {
        Files.createDirectories(path.resolve("icons"));
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, color);
        var bytes = new java.io.ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        String name = "icons/" + hash + ".png"; Files.write(path.resolve(name), bytes.toByteArray());
        Files.writeString(path.resolve("manifest.json"), Json.stringify(Map.of("items", Map.of("Weapon/Test", name), "ui", Map.of("nav-test", name), "sprites", Map.of(), "symbols", Map.of("brand", name))));
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) { for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry); }
    }
}
