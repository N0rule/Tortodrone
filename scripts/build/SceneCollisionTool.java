import com.threerings.config.ConfigManager;
import com.threerings.tudey.data.TudeySceneModel;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Build-time exact collision for an already-installed unified map.
 *
 * <p>Unlike {@link MapCompiler}, this never rewrites portals, actors or YAML. It reads the pinned
 * {@code native/scene.dat}, asks the stock exporter for the native tile masks and placeable
 * shapes, then writes {@code static-collision.properties} and {@code collision.bin}. Existing
 * hand-verified travel data stays exactly as it was.
 */
public final class SceneCollisionTool {
    public static void main(String[] args) throws Exception {
        boolean force = false;
        var rest = new ArrayList<String>();
        for (String arg : args)
            if (arg.equals("--force")) force = true;
            else rest.add(arg);
        if (rest.size() != 2)
            throw new IllegalArgumentException("Usage: SceneCollisionTool <game-directory|-> <maps-root> [--force]");
        Path game = rest.get(0).isBlank() || rest.get(0).equals("-") ? null : Path.of(rest.get(0));
        if (game == null)
            game = org.tortodrone.server.SteamClientLocator.find(org.tortodrone.server.SteamClientLocator.roots(
                            Path.of(System.getProperty("user.home")), System.getProperty("os.name"), System.getenv()))
                    .orElseThrow(() -> new IOException("Spiral Knights was not found; pass its install directory"));
        System.out.println("Using client: " + game);
        var manager = config(game);
        int count = 0;
        try (var paths = Files.list(Path.of(rest.get(1)))) {
            for (Path folder : paths.sorted().toList()) {
                Path nativeDir = folder.resolve("native");
                if (!Files.isRegularFile(nativeDir.resolve("scene.dat"))) continue;
                Path staticCollision = nativeDir.resolve("static-collision.properties");
                if (Files.exists(staticCollision) && !force) {
                    System.out.println("SKIP: " + folder + " already has static collision");
                    continue;
                }
                if (Files.exists(staticCollision)) Files.delete(staticCollision);
                byte[] layout = Files.readAllBytes(nativeDir.resolve("scene.dat"));
                if (layout.length < 16 || layout[0] != (byte) 0xfa || layout[1] != (byte) 0xce)
                    throw new IOException("Not a Clyde scene: " + folder);
                var scene = (TudeySceneModel) com.threerings.export.util.b.e(layout);
                scene.init(manager);
                SceneCollisionExport.export(manager, scene, nativeDir);
                CollisionPackExport.export(folder);
                Files.deleteIfExists(staticCollision);
                var metadata = nativeDir.resolve("metadata.properties");
                var props = new java.util.Properties();
                try (var in = Files.newInputStream(metadata)) {
                    props.load(in);
                }
                props.remove("sha256.static-collision.properties");
                try (var out = Files.newOutputStream(metadata)) {
                    props.store(out, "Generated exact collision");
                }
                count++;
            }
        }
        System.out.println("PASS: generated exact collision for " + count + " map(s)");
    }

    private static ConfigManager config(Path game) throws Exception {
        Object resource =
                Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass()
                .getMethod("gf", String.class)
                .invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager) ConfigManager.class
                .getConstructor(Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
                .newInstance(resource, null, "config/");
        manager.init();
        return manager;
    }
}
