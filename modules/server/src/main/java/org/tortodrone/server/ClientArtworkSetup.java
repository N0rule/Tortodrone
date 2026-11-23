package org.tortodrone.server;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.tortodrone.core.ContentFiles;
import org.tortodrone.core.Json;

/** Pre-listener local artwork preparation. Installed game files are always read-only. */
public final class ClientArtworkSetup {
    @FunctionalInterface
    interface Prompt {
        String read() throws IOException;
    }

    @FunctionalInterface
    interface Exporter {
        void export(Path game, Path output) throws IOException;
    }

    private ClientArtworkSetup() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IOException(
                    "Usage: java -cp tortodrone.jar org.tortodrone.server.ClientArtworkSetup DEPLOYMENT [CLIENT_DIRECTORY]");
        Path deployment = Path.of(args[0]).toRealPath();
        ensureDirectory(deployment.resolve("data"));
        try (var channel = FileChannel.open(
                        deployment.resolve("data/server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Stop this deployment's server before preparing artwork");
            prepare(
                    deployment,
                    args.length == 2 ? Path.of(args[1]) : null,
                    false,
                    System.out::println,
                    System.err::println);
            if (!ready(deployment.resolve("webui/assets/game")))
                throw new IOException("No complete artwork pack was prepared");
        }
    }

    static void prepare(Path deployment, Path explicit, boolean skip, Consumer<String> info, Consumer<String> warn) {
        var console = System.console();
        try {
            prepare(
                    deployment,
                    explicit,
                    skip,
                    SteamClientLocator.roots(
                            Path.of(System.getProperty("user.home")), System.getProperty("os.name"), System.getenv()),
                    console == null
                            ? null
                            : () -> console.readLine("Spiral Knights installation folder (blank to skip): "),
                    new ArtworkExporter(info),
                    info);
        } catch (IOException | RuntimeException failure) {
            warn.accept("WebUI artwork unavailable: " + failure.getMessage());
            warn.accept(
                    "Continuing with fallback icons. Retry with --client \"/path/to/Spiral Knights\"; game maps are unchanged.");
        }
    }

    static void prepare(
            Path deployment,
            Path explicit,
            boolean skip,
            List<Path> roots,
            Prompt prompt,
            Exporter exporter,
            Consumer<String> info)
            throws IOException {
        if (skip) return;
        deployment = deployment.toRealPath();
        Path data = deployment.resolve("data");
        ensureDirectory(data);
        Path target = deployment.resolve("webui/assets/game");
        Path skipped = deployment.resolve("webui/client-artwork.skipped");
        if (explicit == null) {
            if (ready(target)) return;
            // Honor old skip decisions without maintaining another runtime artwork location.
            if (Files.exists(skipped, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(data.resolve("client-artwork.skipped"), LinkOption.NOFOLLOW_LINKS)) return;
        }
        ensureDirectory(skipped.getParent());
        Path game = null;
        if (explicit != null) {
            game = SteamClientLocator.client(explicit)
                    .orElseThrow(() -> new IOException("Not a client installation: " + explicit));
        } else {
            game = SteamClientLocator.find(roots).orElse(null);
            if (game == null) {
                info.accept(
                        "Spiral Knights was not found in Steam's libraries. Select its installation folder to prepare WebUI images.");
                if (prompt == null) {
                    info.accept(
                            "No interactive terminal: use --client \"/path/to/Spiral Knights\" or --skip-client-assets. Fallback icons remain available.");
                    return;
                }
                for (int attempt = 0; attempt < 3 && game == null; attempt++) {
                    String value = prompt.read();
                    if (value == null || value.isBlank()) {
                        Files.writeString(
                                skipped,
                                "Skipped by the local operator; --client retries preparation.\n",
                                StandardOpenOption.CREATE_NEW);
                        info.accept("WebUI image preparation skipped; fallback icons remain available.");
                        return;
                    }
                    value = value.strip();
                    if (value.length() > 4096) throw new IOException("Client path is too long");
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                        value = value.substring(1, value.length() - 1);
                    try {
                        game = SteamClientLocator.client(Path.of(value)).orElse(null);
                    } catch (InvalidPathException ignored) {
                        /* Offer another attempt. */
                    }
                    if (game == null)
                        info.accept(
                                "That folder must contain code/projectx-pcode.jar and rsrc/. Try again or leave it blank.");
                }
                if (game == null) throw new IOException("No valid client folder supplied");
            }
        }
        if (deployment.startsWith(game))
            throw new IOException("Artwork output must be outside the client installation");
        info.accept("Preparing WebUI images from " + game);
        Path work = Files.createTempDirectory(deployment.resolve("webui"), ".client-artwork-");
        try {
            Path output = work.resolve("export");
            exporter.export(game, output);
            info.accept("WebUI artwork: validating images...");
            if (!ready(output)) throw new IOException("Export did not produce a complete artwork manifest");
            publish(output, target);
            Files.deleteIfExists(skipped);
            Files.deleteIfExists(data.resolve("client-artwork.skipped"));
            info.accept("WebUI images ready. No map scene data was extracted.");
        } finally {
            delete(work);
        }
    }

    /** Validate all served paths/hashes, including optimized prebuilt PNG/WebP packs. */
    static boolean ready(Path directory) throws IOException {
        ContentFiles.safe(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(directory)) throw new IOException("Symbolic artwork directory refused: " + directory);
        Path manifest = directory.resolve("manifest.json");
        ContentFiles.safe(manifest);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.size(manifest) > 8_388_608) throw new IOException("Artwork manifest is too large");
        if (!(Json.parseCatalog(Files.readString(manifest)) instanceof Map<?, ?> data))
            throw new IOException("Invalid artwork manifest");
        var files = new LinkedHashSet<String>();
        for (String group : List.of("items", "sprites", "ui", "symbols")) {
            if (!(data.get(group) instanceof Map<?, ?> entries)) {
                if (group.equals("symbols")) continue;
                return false;
            }
            if ((group.equals("items") || group.equals("ui")) && entries.isEmpty()) return false;
            for (Object entry : entries.values()) {
                if (!(entry instanceof String name) || !name.matches("icons/[a-f0-9]{64}\\.(png|webp)"))
                    throw new IOException("Unsafe artwork path");
                files.add(name);
            }
        }
        if (files.size() > 20_000) throw new IOException("Too many artwork files");
        Path root = directory.toRealPath();
        long total = 0;
        for (String name : files) {
            Path path = directory.resolve(name);
            ContentFiles.safe(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!path.toRealPath().startsWith(root)) throw new IOException("Artwork file escapes its directory");
            long size = Files.size(path);
            total += size;
            if (size > 1_048_576 || total > 268_435_456) throw new IOException("Artwork image budget exceeded");
            String hash;
            try (var input = new java.security.DigestInputStream(
                    Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
                input.transferTo(OutputStream.nullOutputStream());
                hash = HexFormat.of().formatHex(input.getMessageDigest().digest());
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            if (!name.substring(6, 70).equals(hash)) return false;
        }
        return true;
    }

    /** Replace only a generated artwork directory, preserving the old pack on publication failure. */
    static void publish(Path source, Path destination) throws IOException {
        if (!ready(source)) throw new IOException("Cannot publish an incomplete artwork pack");
        checkReplaceable(source);
        ensureDirectory(destination.getParent());
        checkReplaceable(destination);
        Path stage = Files.createTempDirectory(destination.getParent(), ".artwork-publish-");
        Path old = stage.resolveSibling(stage.getFileName() + "-previous");
        boolean moved = false;
        try {
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Symbolic artwork input refused");
                    Path copy = stage.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(copy);
                    else if (Files.isRegularFile(path)) Files.copy(path, copy);
                    else throw new IOException("Unsupported artwork file");
                }
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(destination, old);
                moved = true;
            }
            try {
                Files.move(stage, destination);
            } catch (IOException failed) {
                if (moved)
                    try {
                        Files.move(old, destination);
                    } catch (IOException restore) {
                        failed.addSuppressed(restore);
                    }
                throw failed;
            }
            delete(old);
        } finally {
            delete(stage);
        }
    }

    private static void checkReplaceable(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Artwork destination is not a directory");
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.toList()) {
                String name = path.relativize(entry).toString().replace('\\', '/');
                if (Files.isSymbolicLink(entry)
                        || !(name.isEmpty()
                                || name.equals("manifest.json")
                                || name.equals("icons")
                                || name.matches("icons/[a-f0-9]{64}\\.(png|webp)")))
                    throw new IOException("Preserving unrecognized artwork content: " + entry);
            }
        }
    }

    private static void ensureDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path parent = absolute; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent))
                throw new IOException("Symbolic artwork output parent refused: " + parent);
        Files.createDirectories(path);
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }
}
