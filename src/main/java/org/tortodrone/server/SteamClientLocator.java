package org.tortodrone.server;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded, read-only discovery; never launches Steam or crawls an entire drive. */
final class SteamClientLocator {
    private static final Pattern LIBRARY = Pattern.compile("\"(?:path|[0-9]+)\"\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern INSTALL = Pattern.compile("\"installdir\"\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    static List<Path> roots(Path home, String os, Map<String,String> environment) {
        var roots = new LinkedHashSet<Path>();
        String supplied = environment.get("STEAM_PATH");
        if (supplied != null && !supplied.isBlank()) roots.add(Path.of(supplied));
        if (os.toLowerCase(Locale.ROOT).startsWith("windows")) {
            for (String name : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
                String value = environment.get(name);
                if (value != null && !value.isBlank()) roots.add(Path.of(value).resolve("Steam"));
            }
            roots.add(Path.of("C:\\Program Files (x86)\\Steam"));
            roots.add(Path.of("C:\\Program Files\\Steam"));
        } else {
            roots.add(home.resolve(".local/share/Steam"));
            roots.add(home.resolve(".steam/steam"));
            roots.add(home.resolve(".steam/root"));
            roots.add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"));
        }
        return List.copyOf(roots);
    }

    static Optional<Path> find(List<Path> roots) {
        var libraries = new LinkedHashSet<Path>(roots);
        for (Path root : roots) {
            for (String name : List.of("steamapps/libraryfolders.vdf", "config/libraryfolders.vdf")) {
                String text = read(root.resolve(name));
                var matcher = LIBRARY.matcher(text);
                while (matcher.find() && libraries.size() < 128) {
                    try {
                        String value = unescape(matcher.group(1));
                        if (!value.isBlank()) libraries.add(Path.of(value));
                    } catch (InvalidPathException ignored) { /* A stale Steam library is not a startup failure. */ }
                }
            }
        }
        for (Path library : libraries) {
            Path apps = library.resolve("steamapps");
            var install = INSTALL.matcher(read(apps.resolve("appmanifest_99900.acf")));
            if (install.find()) {
                String name = unescape(install.group(1));
                if (!name.isBlank() && !name.contains("/") && !name.contains("\\") && !name.equals("..")) {
                    var client = client(apps.resolve("common").resolve(name));
                    if (client.isPresent()) return client;
                }
            }
            var client = client(apps.resolve("common/Spiral Knights"));
            if (client.isPresent()) return client;
        }
        return Optional.empty();
    }

    static Optional<Path> client(Path path) {
        try {
            Path root = path.toRealPath();
            return Files.isRegularFile(root.resolve("code/projectx-pcode.jar")) && Files.isDirectory(root.resolve("rsrc"))
                ? Optional.of(root) : Optional.empty();
        } catch (IOException | SecurityException ignored) { return Optional.empty(); }
    }

    private static String read(Path path) {
        try { return Files.isRegularFile(path) && Files.size(path) <= 1_048_576 ? Files.readString(path) : ""; }
        catch (IOException | SecurityException ignored) { return ""; }
    }

    private static String unescape(String value) {
        return value.replace("\\\\", "\\").replace("\\\"", "\"");
    }
}
