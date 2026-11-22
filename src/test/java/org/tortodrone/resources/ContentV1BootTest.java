package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.Yaml;
import static org.tortodrone.support.TestWire.check;

/** A converted flat v1 mission tree still boots through the normal content path. */
public final class ContentV1BootTest {
    public static void main(String[] args)throws Exception {
        Path project=Path.of(".").toAbsolutePath().normalize();
        Path content=project.resolve("content");
        if(Files.notExists(content.resolve("format.txt")))throw new AssertionError("Expected the checked-in v2 content tree");
        var expected=MissionYaml.definitions(content);
        Path root=Files.createTempDirectory("tortodrone-v1-boot-");
        try {
            copyTree(content,root.resolve("content"));
            copyTree(project.resolve("config"),root.resolve("config"));
            Files.deleteIfExists(root.resolve("content/format.txt"));
            flatten(root.resolve("content"));
            var loaded=ContentManager.load(root);
            check(loaded.missions().missions().equals(expected.missions().missions()),"v1 missions match the v2 catalogue");
            check(loaded.missions().groups().equals(expected.missions().groups()),"v1 groups match");
            check(loaded.missions().properties().equals(expected.missions().properties()),"v1 properties match");
            for(var mission:expected.missions().missions())
                check(loaded.missions().grants(mission.id()).equals(expected.missions().grants(mission.id())),"v1 grants match: "+mission.id());
            check(loaded.comlinks().conversations().equals(expected.comlinks().conversations()),"v1 comlinks match");
            var expectedGates=new java.util.LinkedHashMap<String,org.tortodrone.core.mission.MissionGates.Gate>();
            for(var gate:expected.gates().gates())expectedGates.put(gate.missionId(),gate);
            var loadedGates=new java.util.LinkedHashMap<String,org.tortodrone.core.mission.MissionGates.Gate>();
            for(var gate:loaded.gates().gates())loadedGates.put(gate.missionId(),gate);
            check(loadedGates.keySet().equals(expectedGates.keySet()),"v1 gate routes keep exactly the v2 missions: missing="+
                expectedGates.keySet().stream().filter(id->!loadedGates.containsKey(id)).toList()
                +" extra="+loadedGates.keySet().stream().filter(id->!expectedGates.containsKey(id)).toList());
            for(var entry:expectedGates.entrySet())
                check(entry.getValue().equals(loadedGates.get(entry.getKey())),"v1 gate route matches for "+entry.getKey()
                    +": expected "+entry.getValue()+" but was "+loadedGates.get(entry.getKey()));
            check(loaded.scenes().scenes().size()==17,"v1 deployment loads every map");
            check(loaded.items().entries().size()>9_000,"v1 deployment loads the item catalogue");
            System.out.println("PASS: a flat v1 mission tree boots through ContentManager with the same missions, comlinks, gates, maps and items");
        } finally {
            delete(root);
        }
    }

    private static void flatten(Path content)throws Exception {
        Map<String,Object> missions=new LinkedHashMap<>(),groups=new LinkedHashMap<>(),properties=new LinkedHashMap<>(),
            grants=new LinkedHashMap<>(),comlinks=new LinkedHashMap<>(),routes=new LinkedHashMap<>();
        merge(content.resolve("shared/missions/groups.yml"),"groups",groups);
        merge(content.resolve("shared/missions/properties.yml"),"properties",properties);
        Path folder=content.resolve("missions");
        try(var entries=Files.list(folder)) {
            for(Path mission:entries.sorted().toList()) {
                merge(mission.resolve("mission.yml"),"missions",missions);
                merge(mission.resolve("grants.yml"),"grants",grants);
                merge(mission.resolve("comlink.yml"),"comlinks",comlinks);
                merge(mission.resolve("route.yml"),"routes",routes);
            }
        }
        delete(folder);
        Files.createDirectories(folder);
        write(folder.resolve("rank.yml"),"missions",missions);
        write(folder.resolve("groups.yml"),"groups",groups);
        write(folder.resolve("properties.yml"),"properties",properties);
        write(folder.resolve("grants.yml"),"grants",grants);
        write(folder.resolve("comlinks.yml"),"comlinks",comlinks);
        write(folder.resolve("routes.yml"),"routes",routes);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Path file,String section,Map<String,Object> rows)throws Exception {
        if(Files.notExists(file))return;
        var root=Yaml.read(file);
        if(root.size()!=1||!root.containsKey(section))throw new AssertionError(file+": expected one "+section+" section");
        rows.putAll((Map<String,Object>)root.get(section));
    }

    private static void write(Path file,String section,Map<String,Object> rows)throws Exception {
        Yaml.write(file,Map.of(section,rows));
    }

    private static void copyTree(Path source,Path target)throws Exception {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path destination=target.resolve(source.relativize(path).toString());
                if(Files.isDirectory(path))Files.createDirectories(destination);
                else Files.copy(path,destination,StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void delete(Path root)throws Exception {
        if(Files.notExists(root))return;
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
}
