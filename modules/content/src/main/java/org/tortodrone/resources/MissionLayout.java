package org.tortodrone.resources;

import static org.tortodrone.core.Yaml.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.ContentFiles;

/** Content-layout policy for the mission catalogue: one folder per mission under
 *  {@code content/missions/}, shared rows under {@code content/shared/missions/}, and the exact
 *  refusal rules for anything else. Parsing and cross-reference validation stay in
 *  {@link MissionYaml}. */
final class MissionLayout {
    private MissionLayout() {}

    static MissionYaml.Definitions definitions(Path content) throws IOException {
        var b = new MissionYaml.Building();
        Path shared = content.resolve("shared/missions");
        for (String name : List.of("groups.yml", "properties.yml")) {
            Path file = shared.resolve(name);
            if (Files.notExists(file)) continue;
            var root = read(file);
            var sections = root.keySet();
            if (sections.size() != 1) throw new IOException(file + ": expected one of groups or properties");
            String section = sections.iterator().next();
            if (!section.equals("groups") && !section.equals("properties"))
                throw new IOException(file + ": expected one of groups or properties");
            MissionYaml.merge(b, section, root, file);
        }
        Path directory = content.resolve("missions");
        if (Files.notExists(directory)) return MissionYaml.finish(directory, b);
        ContentFiles.safe(directory);
        if (!Files.isDirectory(directory)) throw new IOException("Mission catalogue is not a directory: " + directory);
        try (var paths = Files.list(directory)) {
            for (Path entry : paths.sorted().toList()) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(
                            directory + ": flat mission files are not supported; expected one folder per mission: "
                                    + entry.getFileName());
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(directory + ": invalid mission entry: " + entry.getFileName());
                String id = entry.getFileName().toString();
                if (!id.matches("[a-z][a-z0-9_]{0,127}"))
                    throw new IOException(directory + ": invalid mission folder name " + id);
                missionFolder(entry, id, b);
            }
        }
        return MissionYaml.finish(directory, b);
    }

    private static void missionFolder(Path folder, String id, MissionYaml.Building b) throws IOException {
        Path missionFile = null, grantsFile = null, comlinkFile = null, routeFile = null;
        try (var paths = Files.list(folder)) {
            for (Path entry : paths.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(folder + ": unknown mission folder entry " + name);
                switch (name) {
                    case "mission.yml" -> missionFile = entry;
                    case "grants.yml" -> grantsFile = entry;
                    case "comlink.yml" -> comlinkFile = entry;
                    case "route.yml" -> routeFile = entry;
                    case "objectives.yml" ->
                        throw new IOException(
                                folder + ": objectives.yml is not supported until the objective subsystem exists");
                    default -> throw new IOException(folder + ": unknown mission file " + name);
                }
            }
        }
        if (missionFile == null) throw new IOException(folder + ": missing mission.yml");
        MissionYaml.merge(b, "missions", single(missionFile, "missions", id), missionFile);
        if (grantsFile != null) MissionYaml.merge(b, "grants", single(grantsFile, "grants", id), grantsFile);
        if (comlinkFile != null) MissionYaml.merge(b, "comlinks", single(comlinkFile, "comlinks", id), comlinkFile);
        if (routeFile != null) MissionYaml.merge(b, "routes", single(routeFile, "routes", id), routeFile);
    }

    /** One section holding exactly one row, whose key is the folder's mission id. */
    private static Map<String, Object> single(Path file, String section, String id) throws IOException {
        var root = read(file);
        var sections = root.keySet();
        if (sections.size() != 1 || !sections.contains(section))
            throw new IOException(file + ": expected one " + section + " section");
        var rows = map(root.get(section), file + " " + section);
        if (rows.size() != 1) throw new IOException(file + ": expected exactly one " + section + " row");
        if (!rows.keySet().iterator().next().equals(id))
            throw new IOException(file + ": mission id does not match folder " + id);
        return root;
    }
}
