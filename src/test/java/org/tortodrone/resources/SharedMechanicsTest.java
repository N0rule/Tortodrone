package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.Yaml;

/** Opt-in shared map mechanics merge in a fixed order and stay behind the field whitelist. */
public final class SharedMechanicsTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-shared-mechanics-");
        try {
            ContentFixture.copy(Path.of("content"),root.resolve("content"));
            Path shared=root.resolve("content/shared/mechanics.yml");
            Path map=root.resolve("content/maps/level/ancient_generator");
            var base=Yaml.map(Yaml.read(shared).get("properties"),"shared mechanics");
            check(prepare(root,map).getProperty("player.collision-flags").equals("4"),"opt-in shared mechanics reach the prepared map");
            check(prepare(root,root.resolve("content/maps/town/haven")).getProperty("player.collision-flags")==null,"maps without the mechanics section are unaffected");
            write(shared,Map.of("properties",with(base,"player.collision-flags",3)));
            check(prepare(root,map).getProperty("player.collision-flags").equals("3"),"shared mechanics refine the shared combat table");
            write(map.resolve("mechanics.yml"),Map.of("properties",Map.of("player.collision-flags",5)));
            check(prepare(root,map).getProperty("player.collision-flags").equals("5"),"map mechanics still override shared mechanics");
            write(map.resolve("mechanics.yml"),Map.of("properties",Map.of("unknown-mechanic",5)));
            refused(()->prepare(root,map));
            Files.delete(map.resolve("mechanics.yml"));
            write(shared,Map.of("properties",with(base,"unknown-mechanic",5)));
            refused(()->prepare(root,map));
            Files.delete(shared);
            refused(()->prepare(root,map));
            System.out.println("PASS: shared map mechanics are opt-in, merge before map overrides, and refuse missing files or unknown fields");
        }finally{ContentFixture.delete(root);}
    }
    private static Properties prepare(Path root,Path map)throws Exception {return MapYaml.prepare(map,root.resolve("content/shared")).content();}
    private static Map<String,Object> with(Map<String,Object> values,String key,Object value) {
        var copy=new LinkedHashMap<>(values);copy.put(key,value);return copy;
    }
    private static void write(Path path,Map<String,Object> data)throws IOException {
        if(Files.exists(path))Files.delete(path);
        Yaml.write(path,data);
    }
    private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception {
        try {work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}
    }
}
