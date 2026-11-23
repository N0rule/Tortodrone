import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/**
 * Compile {@code map-sources/<name>/scene.dat} + {@code map.yml} into a deployment map.
 *
 * <p>Development-only. A release build consumes the compiled deployment; this command never runs
 * at server startup. The scene is read read-only by the stock exporter, which derives exact
 * collision, portals, actors and localization. Anything it cannot derive is refused, not invented.
 * Sources are local client exports and are not tracked; {@code map-sources/README.md} and
 * {@code map-sources/map.example.yml} are the only committed files there.
 */
public final class MapCompiler {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--list")) {
            list(Path.of(args[1]));
            return;
        }
        boolean replace = false;
        String only = null;
        var rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--replace")) replace = true;
            else if (args[i].equals("--only") && i + 1 < args.length) only = args[++i];
            else rest.add(args[i]);
        }
        if (rest.size() != 3)
            throw new IllegalArgumentException(
                    "Usage: MapCompiler <game-directory|-> <map-sources> <deployment> [--replace] | --list <map-sources>");
        Path game = rest.get(0).isBlank() || rest.get(0).equals("-") ? null : Path.of(rest.get(0));
        compile(game, Path.of(rest.get(1)), Path.of(rest.get(2)), replace, only);
    }

    private static void list(Path root) throws IOException {
        var entries = manifests(root);
        if (entries.isEmpty()) {
            System.out.println("No map sources under " + root + "; see map-sources/README.md");
            return;
        }
        for (var entry : entries.entrySet()) {
            var map = entry.getValue();
            System.out.println(entry.getKey() + "\tid=" + map.get("id") + "\ttype=" + map.get("type") + "\tzone="
                    + map.get("zone-id") + "\t" + map.get("name"));
        }
        System.out.println(entries.size() + " map source(s)");
    }

    private static void compile(Path explicitGame, Path root, Path deployment, boolean replace, String only)
            throws Exception {
        Path game = explicitGame;
        if (game == null) {
            game = org.tortodrone.server.SteamClientLocator.find(org.tortodrone.server.SteamClientLocator.roots(
                            Path.of(System.getProperty("user.home")), System.getProperty("os.name"), System.getenv()))
                    .orElseThrow(() -> new IOException(
                            "Spiral Knights was not found; pass its install directory or set STEAM_PATH"));
            System.out.println("Using client: " + game);
        }
        if (!Files.isDirectory(game.resolve("code"))) throw new IOException("game directory has no code/: " + game);
        var manifests = manifests(root);
        if (manifests.isEmpty())
            throw new IOException("No map sources under " + root
                    + "; copy map.example.yml to <name>/map.yml and read map-sources/README.md");
        Path deploymentRoot = deployment.toAbsolutePath().normalize();
        if (!Files.isDirectory(deploymentRoot.resolve("content/shared")))
            throw new IOException("deployment content is not prepared; run prepareContent first: " + deploymentRoot);
        int compiled = 0;
        for (var entry : manifests.entrySet()) {
            String name = entry.getKey();
            if (only != null && !only.equals(name)) continue;
            Map<String, Object> map = entry.getValue();
            Path scene = root.resolve(name).resolve("scene.dat");
            if (!Files.isRegularFile(scene)) throw new IOException("Missing scene.dat: " + scene);
            String id = text(map, "id"),
                    type = text(map, "type"),
                    zoneId = text(map, "zone-id"),
                    zoneName = text(map, "zone-name");
            if (!id.matches("[1-9][0-9]{0,9}")) throw new IOException(name + ": id must be a positive scene id");
            if (!Set.of("town", "training", "level").contains(type))
                throw new IOException(name + ": type must be town, training or level");
            if (!zoneId.matches("[1-9][0-9]{0,9}")) throw new IOException(name + ": zone-id is required");
            if (!zoneName.startsWith("%"))
                throw new IOException(name + ": zone-name must be a bundle key such as %town:m.haven");
            Path target = deploymentRoot.resolve("content/maps/" + type + "/" + name);
            if (Files.exists(target) && !replace)
                throw new IOException("Map already exists; pass --replace for a disposable build output: " + target);
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var command = new ArrayList<>(List.of(
                    java,
                    "scripts/build/MapImport.java",
                    game.toString(),
                    scene.toString(),
                    deploymentRoot.toString(),
                    name,
                    zoneId,
                    zoneName));
            if (map.get("spawn-x") != null && map.get("spawn-y") != null) {
                command.add(text(map, "spawn-x"));
                command.add(text(map, "spawn-y"));
            }
            if (replace) command.add("--replace");
            run(command);
            var pack = deploymentRoot.resolve("content/maps/" + type + "/" + name);
            // MapImport already compiles stock static collision into collision.bin; only a legacy
            // pack that still carries static metadata needs the separate conversion step.
            if (!Files.isRegularFile(pack.resolve("native/collision.bin"))) CollisionPackExport.export(pack);
            var report = new StringBuilder()
                    .append("source=")
                    .append(root.resolve(name))
                    .append('\n')
                    .append("scene-id=")
                    .append(id)
                    .append('\n')
                    .append("type=")
                    .append(type)
                    .append('\n')
                    .append("zone-id=")
                    .append(zoneId)
                    .append('\n')
                    .append("zone-name=")
                    .append(zoneName)
                    .append('\n')
                    .append("link=")
                    .append(map.getOrDefault("link", "none"))
                    .append('\n')
                    .append("The visual scene, collision, portals and supported bindings are generated.\n")
                    .append("Routing beyond an existing portal is a design decision and is not invented.\n");
            Files.writeString(pack.resolve("native/compile-report.txt"), report);
            compiled++;
        }
        System.out.println("PASS: compiled " + compiled + " map(s) into " + deploymentRoot.resolve("content/maps"));
    }

    private static Map<String, Map<String, Object>> manifests(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Missing map source directory: " + root);
        var result = new TreeMap<String, Map<String, Object>>();
        try (var paths = Files.list(root)) {
            for (Path folder : paths.sorted().toList()) {
                if (!Files.isDirectory(folder)) continue;
                String name = folder.getFileName().toString();
                if (!name.matches("[a-z][a-z0-9_]{0,63}")) throw new IOException("Invalid map source name: " + name);
                Path file = folder.resolve("map.yml");
                if (!Files.isRegularFile(file)) throw new IOException("Missing map.yml: " + file);
                Object parsed = new Yaml().load(Files.readString(file));
                if (!(parsed instanceof Map<?, ?> raw)) throw new IOException(file + ": expected a mapping");
                var map = new LinkedHashMap<String, Object>();
                for (var e : raw.entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
                result.put(name, map);
            }
        }
        return result;
    }

    private static String text(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IOException("Missing map.yml field: " + key);
        return String.valueOf(value);
    }

    private static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) throw new IOException("Map preparation failed: " + String.join(" ", command));
    }

    private static void delete(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
