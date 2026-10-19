package org.tortodrone.loot;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.Yaml;

/** Deliberately synthetic rewards, never shipped as official loot. */
public final class BoxFixture {
    private BoxFixture() { }
    public static void write(Path content)throws IOException {
        Files.createDirectories(content.resolve("_internal"));Files.createDirectories(content.resolve("loot/boxes"));
        Files.copy(Path.of("content/_internal/box-bindings.json"),content.resolve("_internal/box-bindings.json"));
        var boxes=new LinkedHashMap<String,Object>();
        boxes.put("lockbox",box(List.of("silver_key"),1));
        boxes.put("item_000",box(List.of("slime_key","silver_key"),1));
        boxes.put("lucky_prize_box",box(List.of(),2));
        Yaml.write(content.resolve("loot/boxes/test.yml"),Map.of("boxes",boxes));
    }
    public static Map<String,Object> box(List<String> keys,int count) {
        return new LinkedHashMap<>(Map.of("kind",keys.isEmpty()?"keyless":"keyed","acceptedKeys",keys,"status","custom",
            "evidence","Synthetic regression fixture; not official rewards",
            "groups",new LinkedHashMap<>(Map.of("main",group(100,List.of(item("calibur",count,"unbound"))),
                "bonus_sparks",group(100,List.of(item("spark_of_life",3,"unbound"))))),
            "guaranteed",List.of(item("brimstone",2,"bound"))));
    }
    public static Map<String,Object> group(int chance,List<Object> rewards) {
        return new LinkedHashMap<>(Map.of("chance",chance+"%","rolls",1,"entries",List.of(new LinkedHashMap<>(Map.of("weight",1,"rewards",rewards)))));
    }
    public static Map<String,Object> item(String id,int count,String binding) {
        return new LinkedHashMap<>(Map.of("item",id,"quantity",count,"binding",binding));
    }
}
