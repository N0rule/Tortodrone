package org.tortodrone.loot;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.tortodrone.resources.*;
import static org.tortodrone.resources.Yaml.*;
import static org.tortodrone.support.TestWire.check;

/** Reject guessed keys, partial grants and invalid probability graphs before publication. */
public final class BoxCatalogTest {
    public static void main(String[] args)throws Exception {
        var items=ItemYaml.load(Path.of("content/items"));Path home=Files.createTempDirectory("tortodrone-box-content-");
        try {
            BoxFixture.write(home);var file=home.resolve("loot/boxes/test.yml");var original=Files.readString(file);
            var stock=BoxCatalog.load(Path.of("content"),items);
            check(stock.entries().size()==250&&stock.entries().stream().filter(BoxDefinition::enabled).count()==12,"all stock boxes classified; twelve explicit approximations enabled");
            check(stock.entries().stream().filter(BoxDefinition::enabled).allMatch(b->b.status()==BoxDefinition.Status.APPROXIMATE),"estimates never claim verified odds");
            check(stock.entries().stream().filter(b->b.status()==BoxDefinition.Status.INCOMPLETE||b.status()==BoxDefinition.Status.UNSUPPORTED||b.status()==BoxDefinition.Status.UNRESEARCHED).noneMatch(BoxDefinition::enabled),"unknown and incomplete pools remain closed");
            for(var edit:List.<Consumer<Map<String,Object>>>of(
                b->b.put("acceptedKeys",List.of("shadow_key")),b->b.put("acceptedKeys",List.of("missing_key")),
                b->b.put("acceptedKeys",List.of("slime_key")),b->b.put("kind","keyless"),b->b.put("groups",Map.of()),
                b->b.put("guaranteed",List.of(BoxFixture.item("unknown_reward",1,"bound"))),
                b->b.put("guaranteed",List.of(BoxFixture.item("calibur",0,"unbound"))),
                b->b.put("guaranteed",List.of(BoxFixture.item("calibur",10000,"unbound"))),
                b->b.put("guaranteed",List.of(BoxFixture.item("calibur",1,"guess"))),
                b->b.put("guaranteed",List.of(BoxFixture.item(items.referenceId(items.entries().stream().filter(e->!e.grantable()).findFirst().orElseThrow()),1,"bound"))),
                b->b.put("guaranteed",List.of(BoxFixture.item("lockbox",1,"unbound"))),
                b->b.put("groups",Map.of("main",BoxFixture.group(100,List.of(Map.of("group","main"))))),
                b->b.put("groups",Map.of("main",BoxFixture.group(100,List.of(Map.of("group","missing"))))),
                b->b.put("groups",Map.of("main",Map.of("chance","100.00001%","rolls",1,"entries",List.of()))),
                b->b.put("groups",Map.of("main",BoxFixture.group(101,List.of(BoxFixture.item("calibur",1,"unbound"))))),
                b->b.put("groups",Map.of("main",Map.of("chance","100%","rolls",1,"entries",List.of(Map.of("weight",0,"rewards",List.of(BoxFixture.item("calibur",1,"unbound"))))))),
                b->b.put("guaranteed",List.of(BoxFixture.item("Weapon/Sword/Calibur",1,"unbound"))))) {
                Files.writeString(file,original);var data=read(file);var box=map(map(data.get("boxes"),"boxes").get("lockbox"),"lockbox");edit.accept(box);
                Files.delete(file);write(file,data);refused(()->BoxCatalog.load(home,items));
            }
            Files.writeString(file,original);Files.writeString(home.resolve("loot/boxes/duplicate.yml"),original);refused(()->BoxCatalog.load(home,items));Files.delete(home.resolve("loot/boxes/duplicate.yml"));
            var data=read(file);var box=map(map(data.get("boxes"),"boxes").get("lockbox"),"lockbox");box.put("guaranteed",List.of());
            box.put("groups",Map.of("main",Map.of("chance","100%","rolls",1,"entries",List.of(
                Map.of("weight",3,"rewards",List.of(BoxFixture.item("brimstone",2,"bound"))),
                Map.of("weight",1,"rewards",List.of(BoxFixture.item("spark_of_life",5,"unbound"))))),
                "bonus_extra",BoxFixture.group(50,List.of(BoxFixture.item("calibur",1,"unbound")))));
            Files.delete(file);write(file,data);var weighted=BoxCatalog.load(home,items).find("Upgrade/Lockbox");
            for(int n=0;n<4;n++) {
                final int slot=n;RandomGenerator rng=new RandomGenerator(){public long nextLong(){throw new AssertionError("unbounded RNG");}public long nextLong(long bound){return bound==4?slot:0;}public int nextInt(int bound){return bound==1_000_000?500_000:0;}};
                var rewards=BoxRewardSelector.select(weighted,rng);
                check(rewards.size()==1&&rewards.getFirst().config().equals(n<3?"Material/Brimstone":"Rarity/Spark of Life")&&rewards.getFirst().count()==(n<3?2:5),"exact 3:1 boundaries and independent 50% bonus exclusion");
            }
            RandomGenerator bonus=new RandomGenerator(){public long nextLong(){return 0;}public long nextLong(long bound){return 0;}public int nextInt(int bound){return 0;}};
            check(BoxRewardSelector.select(weighted,bonus).size()==2,"bonus is additional to the unchanged main selection");
            // Helper groups and inclusive quantity ranges use the same injected bounded RNG.
            box.put("groups",Map.of("main",BoxFixture.group(100,List.of(Map.of("group","quantity"))),
                "quantity",BoxFixture.group(100,List.of(Map.of("item","brimstone","quantity",Map.of("min",3,"max",5),"binding","bound")))));
            Files.delete(file);write(file,data);var ranged=BoxCatalog.load(home,items).find("Upgrade/Lockbox");
            RandomGenerator maximum=new RandomGenerator(){public long nextLong(){return 0;}public long nextLong(long bound){return 0;}public int nextInt(int bound){return bound-1;}};
            check(BoxRewardSelector.select(ranged,maximum).getFirst().count()==5,"nested helper preserves inclusive quantity upper bound");
            // A -> B -> A is rejected even though nested boxes are only manually opened.
            box.put("guaranteed",List.of(BoxFixture.item("lucky_prize_box",1,"unbound")));
            map(map(data.get("boxes"),"boxes").get("lucky_prize_box"),"prize").put("guaranteed",List.of(BoxFixture.item("lockbox",1,"unbound")));
            Files.delete(file);write(file,data);refused(()->BoxCatalog.load(home,items));
            System.out.println("PASS: 250 audited stock definitions with explicit approximation status, readable IDs, key parity, quantities/binding/classes, probabilities, duplicate/cyclic graphs and deterministic weighted boundaries");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected invalid content refusal");}catch(IOException expected){ }}
}
