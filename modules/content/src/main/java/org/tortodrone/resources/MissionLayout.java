package org.tortodrone.resources;

import static org.tortodrone.core.Yaml.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.ContentFiles;

/** Content-layout policy for the mission catalogue: the format marker, the v1 flat
 *  directory, the v2 per-mission folders and the exact refusal rules for mixed or
 *  unknown trees. Parsing and cross-reference validation stay in {@link MissionYaml}. */
final class MissionLayout {
    private MissionLayout() {}

    /** Layout marker: absent or "1" is the flat v1 tree, "2" is per-mission folders. */
    static int format(Path content) throws IOException {
        Path marker = content.resolve("format.txt");
        if (Files.notExists(marker)) return 1;
        ContentFiles.regular(marker);
        if (Files.size(marker) > 3) throw new IOException("Invalid content format marker: " + marker);
        String text = Files.readString(marker);
        String value = text.endsWith("\r\n")
                ? text.substring(0, text.length() - 2)
                : text.endsWith("\n") ? text.substring(0, text.length() - 1) : null;
        if (!"1".equals(value) && !"2".equals(value))
            throw new IOException(marker + ": expected exactly one line containing 1 or 2");
        return value.equals("1") ? 1 : 2;
    }

    /** Read the layout the marker selects; a missing marker stays v1 for existing deployments. */
    static MissionYaml.Definitions definitions(Path content) throws IOException {
        ContentFiles.safe(content);
        return format(content) == 2 ? readV2(content) : readV1(content.resolve("missions"));
    }

    static MissionYaml.Definitions readV1(Path directory) throws IOException {
        if (!Files.exists(directory)) return MissionYaml.empty();
        ContentFiles.safe(directory);
        if (!Files.isDirectory(directory)) throw new IOException("Mission catalogue is not a directory: " + directory);
        var b = new MissionYaml.Building();
        try (var paths = Files.list(directory)) {
            var files = new ArrayList<Path>();
            for (Path entry : paths.sorted().toList()) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(
                            directory + ": v2 mission folders require content/format.txt 2: " + entry.getFileName());
                files.add(entry);
            }
            if (files.size() > 16) throw new IOException("Too many mission files: " + directory);
            for (Path file : files) {
                if (!file.getFileName().toString().matches("[a-z][a-z0-9_-]*\\.yml"))
                    throw new IOException("Invalid mission file name: " + file);
                var root = read(file);
                var sections = root.keySet();
                if (sections.size() != 1)
                    throw new IOException(
                            file + ": expected one of missions, groups, properties, grants, comlinks or routes");
                MissionYaml.merge(b, sections.iterator().next(), root, file);
            }
        }
        return MissionYaml.finish(directory, b);
    }

    private static MissionYaml.Definitions readV2(Path content) throws IOException {
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
                            directory + ": mixed v1 flat mission files and v2 mission folders: " + entry.getFileName());
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
