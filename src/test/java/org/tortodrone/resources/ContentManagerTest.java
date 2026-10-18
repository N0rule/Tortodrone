package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Real administrator edits, unchanged defaults, bounded inputs and failed snapshot isolation. */
public final class ContentManagerTest {
    public static void main(String[] args)throws Exception {
        var baseline=ContentManager.load(Path.of("."));
        check(baseline.scenes().scenes().size()==7&&baseline.items().entries().size()==9348,"all verified maps and items retained");
        check(baseline.weapons().weapons().size()==382&&baseline.weapons().shields().size()==123,"verified combat controls retained");
        check(baseline.scenes().scenes().stream().mapToInt(s->s.portals().triggers().size()).sum()==18,"all directed stock portals retained");
        var training=baseline.scenes().scene(294);var konway=training.content().conversations().get("Character/NPC/Advanced Training Gym/Greeter");
        check(konway.initial().equals("part 1a")&&konway.nodes().get("part 1a").message().equals("m.atg_greeter_1a"),"default dialogue retains localized wire keys and jumps");
        check(baseline.town().stations().size()==99&&baseline.town().recipes().size()==668,"Town service and recipe catalogs retained");
        check(training.content().cycles().get("A").number("up",0)==4&&training.content().cycles().get("A").text("inverse","").equals("B2"),"shared trap timing and inversion retained");
        check(baseline.news().enabled()&&baseline.news().propertyList().contains("news/featured.png"),"Uplink News banners compile into the published snapshot");
        Path home=Files.createTempDirectory("tortodrone-unified-test-");
        try {
            ContentFixture.copy(Path.of("content"),home.resolve("content"));ContentFixture.copy(Path.of("config"),home.resolve("config"));
            Path npc=home.resolve("content/maps/advanced_training/npcs/konway.yml");String original=Files.readString(npc);
            check(original.contains("Welcome to our advanced training hall."),"verified English default is readable beside its map");
            Files.writeString(npc,original.replace("Welcome to our advanced training hall.","An arbitrary new sentence."));
            failure(()->MapYaml.prepare(npc.getParent().getParent(),home.resolve("content/shared")),"literal dialogue text is unsupported");
            Files.writeString(npc,original);
            var edited=Yaml.read(npc);var dialogue=Yaml.map(edited.get("dialogue"),"dialogue");var nodes=Yaml.map(dialogue.get("nodes"),"nodes");
            Yaml.map(nodes.get("part 1a"),"node").put("text","Thanks! I'm off to explore now.");Files.delete(npc);Yaml.write(npc,edited);
            Path weaponFile=home.resolve("content/items/weapons.yml");var weaponYaml=Yaml.read(weaponFile);var weaponRows=Yaml.map(weaponYaml.get("items"),"weapons");
            var weaponRow=weaponRows.entrySet().stream().filter(e->{try{return Yaml.map(e.getValue(),"weapon").containsKey("combat");}catch(IOException bad){throw new UncheckedIOException(bad);}}).findFirst().orElseThrow();
            var controls=Yaml.map(Yaml.map(weaponRow.getValue(),"weapon").get("combat"),"combat");int oldCharge=Yaml.integer(controls.get("chargeTimeMs"),"chargeTimeMs");controls.put("chargeTimeMs",oldCharge+123);Files.delete(weaponFile);Yaml.write(weaponFile,weaponYaml);
            Path actorFile=home.resolve("content/maps/advanced_training/actors.yml");var actorYaml=Yaml.read(actorFile);var actorRows=Yaml.map(actorYaml.get("actors"),"actors");
            var actorRow=actorRows.entrySet().stream().filter(e->{try{return Yaml.map(e.getValue(),"actor").containsKey("child.health");}catch(IOException bad){throw new UncheckedIOException(bad);}}).findFirst().orElseThrow();
            Yaml.map(actorRow.getValue(),"actor").put("child.health",31);Files.delete(actorFile);Yaml.write(actorFile,actorYaml);
            Path portalEdit=home.resolve("content/maps/advanced_training/portals.yml");var portalYaml=Yaml.read(portalEdit);var portalRows=Yaml.map(portalYaml.get("portals"),"portals");Yaml.map(portalRows.values().iterator().next(),"portal").put("entrance","from_bazaar_east");Files.delete(portalEdit);Yaml.write(portalEdit,portalYaml);
            Path shopFile=home.resolve("content/town/shops.yml");var shops=Yaml.read(shopFile);var good=Yaml.map(Yaml.list(shops.get("depot"),"depot").getFirst(),"good");int energy=Yaml.integer(good.get("energy"),"energy");good.put("energy",energy+1);Files.delete(shopFile);Yaml.write(shopFile,shops);
            Path recipeFile=home.resolve("content/town/recipes.yml");var recipes=Yaml.read(recipeFile);var recipeRow=Yaml.map(Yaml.list(recipes.get("recipes"),"recipes").getFirst(),"recipe");var costs=new ArrayList<Object>(Yaml.list(recipeRow.get("crowns"),"crowns"));int oldCost=Yaml.integer(costs.getFirst(),"crowns");costs.set(0,oldCost+1);recipeRow.put("crowns",costs);Files.delete(recipeFile);Yaml.write(recipeFile,recipes);
            Path serverConfig=home.resolve("config/server.yml");Files.writeString(serverConfig,Files.readString(serverConfig).replace("startup-map: haven","startup-map: bazaar"));
            var changed=ContentManager.load(home);
            check(changed.scenes().entry().id()==2&&baseline.scenes().entry().id()==1,"startup-map selects a readable town folder without changing existing snapshots");var conversation=changed.scenes().scene(294).content().conversations().get(konway.actor());
            check(conversation.nodes().get("part 1a").message().equals("r.atg_greeter_2a"),"verified changed text reaches the runtime conversation");
            check(changed.weapons().find(weaponRow.getKey()).chargeTime()==oldCharge+123,"weapon YAML changes supported timing");
            check(changed.scenes().scene(294).content().entries().stream().anyMatch(e->e.spec().number("child.health",0)==31),"actor YAML changes existing monster-generator health");
            check(changed.scenes().scene(294).portals().triggers().getFirst().entrance().equals("from_bazaar_east"),"portal YAML redirects existing native geometry");
            check(changed.town().depot().getFirst().good().energy()==energy+1&&changed.town().recipe((String)recipeRow.get("config")).cost(0)==oldCost+1,"shop and recipe YAML updates reach existing transaction catalogs");
            var codec=new SessionCodec();byte[] serialized=codec.conversationDialog(1,2,conversation,"part 1a",new SceneEntities(training.content(),training.collision()).actors().stream().filter(a->a.config().name().equals(konway.actor())).findFirst().orElseThrow(),1L);
            check(new String(serialized,java.nio.charset.StandardCharsets.ISO_8859_1).contains("r.atg_greeter_2a"),"edited sentence is serialized to the native dialog");
            check(conversation.nodes().get("part 1a").responses().equals(konway.nodes().get("part 1a").responses()),"sentence edit preserves response order and jumps");
            Files.writeString(npc,original.replace("next: part 2a","next: absent"));
            failure(()->{var p=MapYaml.prepare(npc.getParent().getParent(),home.resolve("content/shared"));SceneContent.load(p.content(),p.descriptor().getProperty("scene-sha256"));},"Broken conversation jump");
            Files.writeString(npc,original);
            Path portal=home.resolve("content/maps/advanced_training/portals.yml");String portals=Files.readString(portal);
            Files.writeString(portal,portals.replace("destination: haven","destination: absent_map"));failure(()->ContentManager.load(home),"absent_map");Files.writeString(portal,portals);
            Path nativeFile=home.resolve("content/maps/advanced_training/native/bindings.json");String nativeText=Files.readString(nativeFile);Files.writeString(nativeFile,nativeText+" ");failure(()->MapYaml.prepare(npc.getParent().getParent(),home.resolve("content/shared")),"fingerprint");Files.writeString(nativeFile,nativeText);
            Path gear=home.resolve("content/items/materials.yml");var yaml=Yaml.read(gear);var definitions=Yaml.map(yaml.get("items"),"items");var first=definitions.entrySet().iterator().next();var item=Yaml.map(first.getValue(),"item");Object stockId=item.put("stockId","invalid");Files.delete(gear);Yaml.write(gear,yaml);failure(()->ContentManager.load(home),"item");item.put("stockId",stockId);Files.delete(gear);Yaml.write(gear,yaml);
            check(baseline.items().entries().size()==9348&&baseline.scenes().scene(294).content().conversations().get(konway.actor()).equals(konway),"failed loads never mutate an existing snapshot");
            Path mapFile=home.resolve("content/maps/advanced_training/map.yml");String mapText=Files.readString(mapFile);Files.writeString(mapFile,mapText.replace("id: 294","id: 1"));failure(()->MapYaml.folders(home.resolve("content/maps")),"Duplicate");Files.writeString(mapFile,mapText);
            Path misc=home.resolve("content/items/misc.yml");String miscOriginal=Files.readString(misc);var miscYaml=Yaml.read(misc);Yaml.map(miscYaml.get("items"),"items").put(first.getKey(),item);Files.delete(misc);Yaml.write(misc,miscYaml);failure(()->ContentManager.load(home),"duplicate item config");Files.writeString(misc,miscOriginal);
            var town=TownYaml.data(home.resolve("content/town"));var recipe=Yaml.map(Yaml.list(town.get("recipes"),"recipes").getFirst(),"recipe");recipe.put("item","Missing/Item");failure(()->TownYaml.references(town,baseline.items()),"missing item");
            Path bad=home.resolve("invalid.yml");
            for(String text:List.of("id: 1\nid: 2\n","a: [\n","a: !!java.net.URL [bad]\n","a: &a [1]\nb: *a\n")) {Files.writeString(bad,text);failure(()->Yaml.read(bad),"invalid.yml");}
            failure(()->ContentFiles.relative(home,"../escape"),"escapes");failure(()->ContentFiles.relative(home,"a/../../escape"),"escapes");
            Path link=home.resolve("link.yml");try{Files.createSymbolicLink(link,bad);failure(()->Yaml.read(link),"Symlink");}catch(UnsupportedOperationException unavailable){ }
            // createScenes consumes the validated Town/pet objects even if source files disappear.
            Files.move(home.resolve("content/town"),home.resolve("removed-town"));check(changed.createScenes().town()==changed.town(),"published registry performs no Town file reads");
            System.out.println("PASS: unified stock snapshot, readable localized dialogue edits and literal rejection, immutable publication, graphs, portals, Town references, native fingerprints, YAML diagnostics and path safety");
        }finally{ContentFixture.delete(home);}
    }
    private interface Action{void run()throws Exception;}
    private static void failure(Action action,String message)throws Exception {try{action.run();throw new AssertionError("Invalid content accepted: "+message);}catch(IOException expected){check(expected.toString().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),"actionable error: "+expected);}}
}
