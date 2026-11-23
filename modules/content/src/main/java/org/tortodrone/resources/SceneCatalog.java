package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.world.MapScene;

/** External immutable map packs. Templates are distinct from runtime place objects. */
public final class SceneCatalog implements org.tortodrone.core.world.SceneCatalogView {
    @FunctionalInterface
    public interface Loader {
        MapScene load(Path folder) throws IOException;
    }

    private final NavigableMap<Integer, MapScene> scenes;
    private final Map<Integer, Path> paths;
    private final Loader loader;
    private final MapScene entryScene;
    private final int entry;

    private SceneCatalog(NavigableMap<Integer, MapScene> scenes, int entry) {
        this.scenes = Collections.synchronizedNavigableMap(new TreeMap<>(scenes));
        this.paths = null;
        this.loader = null;
        this.entry = entry;
        this.entryScene = scenes.get(entry);
    }

    private SceneCatalog(Map<Integer, Path> paths, int entry, MapScene entryScene, Loader loader) {
        this.scenes = Collections.synchronizedNavigableMap(new TreeMap<>());
        this.scenes.put(entry, entryScene);
        this.paths = Map.copyOf(paths);
        this.loader = Objects.requireNonNull(loader);
        this.entry = entry;
        this.entryScene = entryScene;
    }
    /** Loads map bodies on first use; ids remain addressable and the entry scene is already open. */
    public static SceneCatalog lazy(Map<Integer, Path> paths, int entry, MapScene entryScene, Loader loader) {
        return new SceneCatalog(paths, entry, entryScene, loader);
    }

    public MapScene scene(int id) throws IOException {
        synchronized (scenes) {
            var scene = scenes.get(id);
            if (scene != null) return scene;
        }
        Path folder = paths == null ? null : paths.get(id);
        if (folder == null) throw new IOException("Scene is not available");
        var loaded = loader.load(folder);
        synchronized (scenes) {
            return scenes.computeIfAbsent(id, key -> loaded);
        }
    }

    public MapScene entry() {
        return entryScene;
    }
    /** Counts without forcing lazy bodies. */
    public int sceneCount() {
        return paths != null ? paths.size() : scenes.size();
    }
    /** True when the id is registered, even if its body has not been loaded yet. */
    public boolean contains(int id) {
        return paths != null ? paths.containsKey(id) : scenes.containsKey(id);
    }

    public int loadedCount() {
        synchronized (scenes) {
            return scenes.size();
        }
    }

    public boolean isEmpty() {
        return sceneCount() == 0;
    }
    /** Town templates only; a lazy catalog loads those few and leaves dungeons on disk. */
    public List<MapScene> towns() {
        var result = new ArrayList<MapScene>();
        if (paths == null)
            return scenes.values().stream().filter(MapScene::town).toList();
        for (var entry : paths.entrySet())
            try {
                if (SceneCatalogLoader.isTown(entry.getValue())) result.add(scene(entry.getKey()));
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        return List.copyOf(result);
    }
    /** Zone ids from lightweight metadata; no scene body is loaded. */
    public java.util.Set<Integer> zoneIds() {
        var result = new java.util.TreeSet<Integer>();
        if (paths == null) {
            for (var map : scenes.values()) result.add(map.zone());
            return result;
        }
        for (Path folder : paths.values())
            try {
                var p = SceneCatalogLoader.properties(folder.resolve("native/metadata.properties"));
                result.add(Integer.parseInt(p.getProperty("zone-id")));
            } catch (Exception failure) {
                throw new IllegalStateException("Invalid map metadata: " + folder, failure);
            }
        return java.util.Collections.unmodifiableSet(result);
    }
    /** Scenes in one zone only; used by chat channels and zone summaries. */
    public List<MapScene> zoneScenes(int zone) {
        if (paths == null)
            return scenes.values().stream().filter(map -> map.zone() == zone).toList();
        var result = new ArrayList<MapScene>();
        for (var entry : paths.entrySet())
            try {
                var p = SceneCatalogLoader.properties(entry.getValue().resolve("native/metadata.properties"));
                if (Integer.parseInt(p.getProperty("zone-id")) == zone) result.add(scene(entry.getKey()));
            } catch (Exception failure) {
                throw new IllegalStateException("Invalid map metadata: " + entry.getValue(), failure);
            }
        return List.copyOf(result);
    }

    public boolean hasNonTown() {
        if (paths == null) return scenes.values().stream().anyMatch(map -> !map.town());
        for (Path folder : paths.values())
            try {
                if (!SceneCatalogLoader.isTown(folder)) return true;
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        return false;
    }

    public MapScene town(int id) throws IOException {
        if (paths != null && !SceneCatalogLoader.isTown(paths.getOrDefault(id, Path.of("")))) return null;
        var map = scene(id);
        return map.town() ? map : null;
    }

    public MapScene townByName(String name) throws IOException {
        for (var map : towns()) if (map.name().equals(name)) return map;
        return null;
    }

    public List<MapScene> scenes() {
        if (paths != null) {
            for (int id : paths.keySet())
                try {
                    scene(id);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            // Full-catalog validation is deliberate: startup stays lazy, but tools and tests that
            // ask for every scene still prove the portal graph before a release is published.
            try {
                synchronized (scenes) {
                    SceneCatalog.of(new TreeMap<>(scenes), entry);
                }
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        synchronized (scenes) {
            return List.copyOf(scenes.values());
        }
    }

    public static SceneCatalog empty() {
        return new SceneCatalog(new TreeMap<>(), 0);
    }

    public static SceneCatalog load(Path directory) throws IOException {
        return SceneCatalogLoader.load(directory);
    }

    /** Compatibility/import boundary; runtime versioned packs register scenes in their manifest. */
    public static Map<Integer, Path> folders(Path directory) throws IOException {
        return SceneCatalogLoader.folders(directory);
    }

    public static SceneCatalog load(Map<Integer, Path> folders, int entry) throws IOException {
        return SceneCatalogLoader.load(folders, entry);
    }
    /** Inspect one prepared template before assembling a catalog with a valid town entry. */
    public static MapScene loadTemplate(int id, Path folder) throws IOException {
        return SceneCatalogLoader.loadTemplate(id, folder);
    }

    public static SceneCatalog of(NavigableMap<Integer, MapScene> scenes, int entry) throws IOException {
        scenes = new TreeMap<>(scenes);
        if (!scenes.containsKey(entry)) throw new IOException("Entry scene is missing");
        if (!scenes.get(entry).town()) throw new IOException("Entry scene must be a town");
        var zones = new HashMap<Integer, String>();
        for (var scene : scenes.values()) {
            String previous = zones.putIfAbsent(scene.zone(), scene.zoneName());
            if (previous != null && !previous.equals(scene.zoneName()))
                throw new IOException("Conflicting names for one zone");
            for (var portal : scene.portals().triggers()) {
                var destination = scenes.get(portal.destination());
                if (destination == null || !destination.portals().entrances().containsKey(portal.entrance()))
                    throw new IOException("Portal from scene " + scene.id() + " to " + portal.destination()
                            + " entrance " + portal.entrance() + " is unavailable");
                var entrance = destination.portals().entrances().get(portal.entrance());
                if (destination.portals().triggers().stream().anyMatch(t -> t.intersects(entrance.x(), entrance.y())))
                    throw new IOException("Portal arrival would immediately trigger another transfer");
            }
        }
        if (scenes.values().stream().mapToInt(MapScene::instances).sum() > 128)
            throw new IOException("Too many scene instances");
        return new SceneCatalog(scenes, entry);
    }
}
