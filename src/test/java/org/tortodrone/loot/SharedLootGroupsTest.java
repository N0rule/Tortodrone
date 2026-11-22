package org.tortodrone.loot;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;
import org.tortodrone.core.Yaml;
import static org.tortodrone.core.Yaml.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.loot.BoxDefinition;

/** Repeated reward bodies live in one shared file and are referenced, never copied. */
public final class SharedLootGroupsTest {
    public static void main(String[] args)throws Exception {
        var items=ItemYaml.load(Path.of("content/items"));
        Path home=Files.createTempDirectory("tortodrone-shared-loot-");
        try {
            BoxFixture.write(home);
            Path file=home.resolve("loot/boxes/test.yml");
            Path shared=home.resolve("shared/loot/groups.yml");
            String original=Files.readString(file);

            // Without a shared file, ordinary local bodies keep loading.
            BoxCatalog.load(home,items);

            write(shared,Map.of("groups",Map.of("shared_lockbox",BoxFixture.group(100,List.of(BoxFixture.item("calibur",2,"unbound"))))));
            referencing(file,original,"shared_lockbox",null,null);
            var catalog=BoxCatalog.load(home,items);
            var lockbox=catalog.find("Upgrade/Lockbox");
            var main=lockbox.groups().get("main").entries().getFirst().rewards().getFirst();
            check(main instanceof BoxDefinition.Reference reference&&reference.group().equals("main_pool"),"a box keeps its local group name and graph");
            var expanded=lockbox.groups().get("main_pool").entries().getFirst().rewards().getFirst();
            check(expanded instanceof BoxDefinition.Item item&&item.minimum()==2&&item.maximum()==2&&item.config().endsWith("/Calibur"),"the reference expands to the exact shared body");
            check(catalog.entries().size()==3,"boxes without a reference remain unaffected");

            // Rewards nested inside a shared body resolve against the referencing box.
            write(shared,Map.of("groups",Map.of("shared_lockbox",BoxFixture.group(100,List.of(Map.of("group","shared_item"))))));
            referencing(file,original,"shared_lockbox",null,null);
            refu(()->BoxCatalog.load(home,items));
            referencing(file,original,"shared_lockbox","shared_item",null);
            var nested=BoxCatalog.load(home,items).find("Upgrade/Lockbox").groups().get("main_pool");
            check(nested.entries().getFirst().rewards().getFirst() instanceof BoxDefinition.Reference reference&&reference.group().equals("shared_item"),"nested helper groups resolve in the referencing box");

            write(shared,Map.of("groups",Map.of("shared_lockbox",BoxFixture.group(100,List.of(BoxFixture.item("calibur",2,"unbound"))))));
            referencing(file,original,"missing_shared",null,null);
            refu(()->BoxCatalog.load(home,items));
            referencing(file,original,"shared_lockbox",null,Map.of("shared","shared_lockbox","chance","100%"));
            refu(()->BoxCatalog.load(home,items));
            referencing(file,original,"shared_lockbox",null,null);
            write(shared,Map.of("groups",Map.of("shared_lockbox",BoxFixture.group(100,List.of(BoxFixture.item("calibur",2,"unbound")))),"extra",true));
            refu(()->BoxCatalog.load(home,items));
            write(shared,Map.of("groups",Map.of("Bad Name",BoxFixture.group(100,List.of(BoxFixture.item("calibur",2,"unbound"))))));
            refu(()->BoxCatalog.load(home,items));
            write(shared,Map.of("groups",Map.of("shared_lockbox",Map.of("chance","100%","rolls",1,"entries",List.of()))));
            refu(()->BoxCatalog.load(home,items));
            Files.delete(shared);
            refu(()->BoxCatalog.load(home,items));
            System.out.println("PASS: shared loot bodies are opt-in, exact, resolvable against their box, and refuse unknown, mixed or malformed definitions");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    /** Rewrite the fixture lockbox so main references main_pool and main_pool references a shared body. */
    private static void referencing(Path file,String original,String name,String localGroup,Map<String,Object> mainPool)throws IOException {
        Files.writeString(file,original);
        var data=read(file);
        var box=map(map(data.get("boxes"),"boxes").get("lockbox"),"lockbox");
        var replacement=new LinkedHashMap<>(map(box.get("groups"),"lockbox groups"));
        replacement.put("main",BoxFixture.group(100,List.of(Map.of("group","main_pool"))));
        replacement.put("main_pool",mainPool!=null?mainPool:Map.of("shared",name));
        if(localGroup!=null)replacement.put(localGroup,BoxFixture.group(100,List.of(BoxFixture.item("calibur",1,"unbound"))));
        box.put("groups",replacement);
        Files.delete(file);write(file,data);
    }
    private static void write(Path file,Map<String,Object> data)throws IOException {
        if(Files.exists(file))Files.delete(file);
        Yaml.write(file,data);
    }
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refu(Work work)throws Exception{try{work.run();throw new AssertionError("Expected invalid content refusal");}catch(IOException expected){ }}
}
