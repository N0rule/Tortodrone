package org.tortodrone.loot;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;
import org.tortodrone.core.Yaml;
import static org.tortodrone.core.Yaml.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.loot.BoxDefinition;

/** Whole box bodies can be shared by IDs that differ only by their readable item ID. */
public final class SharedLootBoxesTest {
    public static void main(String[] args)throws Exception {
        var items=ItemYaml.load(Path.of("content/items"));
        Path home=Files.createTempDirectory("tortodrone-shared-boxes-");
        try {
            BoxFixture.write(home);
            Path file=home.resolve("loot/boxes/test.yml");
            Path shared=home.resolve("shared/loot/boxes.yml");
            var data=read(file);
            var boxes=map(data.get("boxes"),"boxes");
            var body=new LinkedHashMap<>(map(boxes.get("item_000"),"item_000"));
            boxes.put("item_001",new LinkedHashMap<>(body));
            write(file,data);
            BoxCatalog.load(home,items);

            write(shared,Map.of("boxes",Map.of("shared_lockbox",body)));
            sharing(file,"shared_lockbox");
            var catalog=BoxCatalog.load(home,items);
            check(catalog.entries().size()==4,"both referencing boxes load alongside the untouched fixture");
            var one=catalog.entries().stream().filter(box->box.id().equals("item_000")).findFirst().orElseThrow();
            var other=catalog.entries().stream().filter(box->box.id().equals("item_001")).findFirst().orElseThrow();
            check(one.groups().equals(other.groups()),"both IDs share the exact reward graph");
            check(one.groups().get("main").entries().getFirst().rewards().getFirst() instanceof BoxDefinition.Item item
                &&item.minimum()==1,"the shared body still grants its rewards");
            check(!one.config().equals(other.config()),"each box keeps its own native config");

            // Unknown, mixed, malformed or key-mismatched shared boxes fail closed.
            sharing(file,"missing_shared");
            refu(()->BoxCatalog.load(home,items));
            mixed(file);
            refu(()->BoxCatalog.load(home,items));
            sharing(file,"shared_lockbox");
            write(shared,Map.of("boxes",Map.of("shared_lockbox",body),"extra",true));
            refu(()->BoxCatalog.load(home,items));
            write(shared,Map.of("boxes",Map.of("Bad Name",body)));
            refu(()->BoxCatalog.load(home,items));
            var unknown=new LinkedHashMap<>(body);unknown.put("unknown",true);
            write(shared,Map.of("boxes",Map.of("shared_lockbox",unknown)));
            refu(()->BoxCatalog.load(home,items));
            Files.delete(shared);
            refu(()->BoxCatalog.load(home,items));
            System.out.println("PASS: shared loot box bodies are opt-in, keep each native id/config, and refuse unknown, mixed or malformed definitions");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    /** Point the item_000 and item_001 rows at one shared body. */
    private static void sharing(Path file,String name)throws IOException {
        var data=read(file);var boxes=map(data.get("boxes"),"boxes");
        boxes.put("item_000",Map.of("shared",name));boxes.put("item_001",Map.of("shared",name));
        write(file,data);
    }
    private static void mixed(Path file)throws IOException {
        var data=read(file);var boxes=map(data.get("boxes"),"boxes");
        boxes.put("item_000",Map.of("shared","shared_lockbox","kind","keyed"));
        write(file,data);
    }
    private static void write(Path file,Map<String,Object> data)throws IOException {
        if(Files.exists(file))Files.delete(file);
        Yaml.write(file,data);
    }
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refu(Work work)throws Exception{try{work.run();throw new AssertionError("Expected invalid content refusal");}catch(IOException expected){ }}
}
