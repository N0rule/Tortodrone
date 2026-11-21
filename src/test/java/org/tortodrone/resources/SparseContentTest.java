package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.Json;
import org.tortodrone.core.Yaml;
import org.tortodrone.core.ContentFiles;

/** Golden compiled defaults from 0078b50, plus sparse edits and immutable identity failures.
 * Laboratory 30893 adopted the catalogue-backed Companion Cube attack graph (pinned by
 * MonsterCatalogParityTest), so its hash reflects that deliberate replacement. */
public final class SparseContentTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-sparse-");
        try {
            var catalogGolden=Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/provenance/catalog-baseline.json"))),"catalog baseline");
            var sceneGolden=Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/provenance/scene-defaults.json"))),"scene baseline");
            var baseline=ContentManager.load(Path.of("."));ItemCatalog.write(root.resolve("items.json"),baseline.items().entries());check(ContentFiles.digest(root.resolve("items.json")).equals(catalogGolden.get("items")),"all 9,355 indexed item metadata/arguments remain identical");
            for(var map:MapYaml.folders(Path.of("content/maps")).entrySet()) {
                if(!sceneGolden.containsKey(map.getKey().toString()))continue; // Added opening maps have separate pinned-route regressions.
                var prepared=MapYaml.prepare(map.getValue(),Path.of("content/shared"),baseline.items());
                var historical=new Properties();historical.putAll(prepared.content());
                // Executable capsule graphs are a deliberate additive import. Verify the
                // exact replacement before comparing the rest with the historical scene.
                var actionRoot=Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/item-actions.json"))),"capsule actions");
                var actions=Yaml.map(actionRoot.get("actions"),"capsule graphs");
                var binding=Yaml.map(Json.parseCatalog(Files.readString(map.getValue().resolve("native/bindings.json"))),"bindings");
                var mechanics=Yaml.map(binding.get("mechanics"),"native mechanics");
                for(String index:historical.getProperty("items","").split(",")){
                    String key="item."+index+".use-action",config=historical.getProperty("item."+index+".config");
                    if(actions.containsKey(config)){
                        check(historical.getProperty(key).equals(actions.get(config)),"native capsule graph replacement: "+config);
                        historical.setProperty(key,(String)mechanics.get(key));
                    }
                }
                // Owned instant, spawn-actor and lift definitions are another deliberate
                // additive import. Their items and use fields have dedicated regressions;
                // keep the 0078b50 scene baseline independent of them.
                var useRoot=Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/item-use.json"))),"item uses");
                Set<Object> useConfigs=new HashSet<>(Yaml.map(useRoot.get("items"),"item use definitions").keySet());
                var importedIndexes=new HashSet<String>();var remainingIndexes=new ArrayList<String>();
                for(String index:historical.getProperty("items","").split(",")){
                    if(index.isBlank())continue;
                    if(useConfigs.contains(historical.getProperty("item."+index+".config","")))importedIndexes.add(index);
                    else remainingIndexes.add(index);
                }
                for(String index:importedIndexes){String prefix="item."+index+".";historical.keySet().removeIf(raw->raw.toString().startsWith(prefix));}
                if(remainingIndexes.isEmpty())historical.remove("items");else historical.setProperty("items",String.join(",",remainingIndexes));
                // Catalogue-backed monster placements are verified by MonsterCatalogParityTest.
                // The type reference is consumed at load and never reaches the runtime spec;
                // keep it out of the inherited scene baseline.
                historical.keySet().removeIf(raw->raw.toString().matches("entry\\.[^.]++\\.monster-type"));
                // Leveled combat/variant stats are an explicit additive import. Keep
                // the 0078b50 scene/actor baseline independent of these new tables.
                historical.keySet().removeIf(raw->{String key=raw.toString();return key.startsWith("gear.table.")
                    ||key.startsWith("gear.")&&(key.endsWith(".table")||key.endsWith(".rarity"))
                    ||key.startsWith("gear.variant.")&&(key.contains(".defense.")||key.contains(".tagged."));});
                // Native monster impulse levels are a deliberate additive import: the
                // prepared spawn fields omit them and the 0078b50 baseline predates the
                // shared combat table. Their values have dedicated targeting regressions.
                historical.keySet().removeIf(raw->raw.toString().startsWith("monster."));
                // The restored native DumbSwitch startup states are a deliberate additive
                // import. Their authored values have dedicated checks in the stateful-camera
                // regression; keep the inherited scene baseline independent of them.
                historical.keySet().removeIf(raw->{String key=raw.toString();return key.matches("entry\\.[^.]++\\.(switch-states|startup-state)");});
                // One deliberate correction to the old exporter: placed entry 3733 has no
                // native rotation. Check it separately before comparing the historical snapshot.
                if(map.getKey().toString().equals("1")){
                    String rotation=historical.stringPropertyNames().stream().filter(k->k.endsWith(".town-station")&&historical.getProperty(k).equals("scene.1.placed.3733")).findFirst().orElseThrow().replace(".town-station",".rotation");
                    check(historical.getProperty(rotation).equals("0.0"),"Auction House uses captured native facing");historical.setProperty(rotation,"-1.5707964");
                    check(historical.getProperty("destination.move_auction.id").equals("7966")&&historical.getProperty("destination.move_auction.x").equals("1.5"),"native auction destination restored");
                    historical.keySet().removeIf(k->k.toString().startsWith("destination.move_auction."));
                }
                if(map.getKey()==294){
                    String rotation=historical.stringPropertyNames().stream().filter(k->k.endsWith(".town-station")&&historical.getProperty(k).equals("scene.294.placed.1955")).findFirst().orElseThrow().replace(".town-station",".rotation");
                    check(historical.getProperty(rotation).equals("0.0"),"Arsenal floor shape uses its captured placeable rotation");historical.setProperty(rotation,"-1.5707964");
                }
                for(String key:new HashSet<>(historical.stringPropertyNames()))if(key.endsWith(".collision-mask")&&key.startsWith("entry.")){
                    check(historical.getProperty(key).equals("79"),"supported monsters retain their native movement mask");historical.remove(key);
                }
                if(map.getKey()==318)for(String key:new HashSet<>(historical.stringPropertyNames()))if(key.startsWith("dialogue.")&&key.endsWith(".name")&&historical.getProperty(key).startsWith("%shop:n.hoh_"))historical.setProperty(key,historical.getProperty(key).substring(6));
                check(hash(historical).equals(sceneGolden.get(map.getKey().toString())),"inherited scene properties unchanged outside explicit corrections: "+map.getKey());
                check(hash(prepared.portals()).equals(catalogGolden.get("portals."+map.getKey())),"portal identity/coordinates/precise headings unchanged: "+map.getKey());
                var edits=Yaml.map(Yaml.read(map.getValue().resolve("actors.yml")).get("actors"),"actors");
                var expectedEdits=switch(map.getKey().toString()){
                    case "1"->Map.of("base_2",Map.of("facing",0));
                    case "30893"->Map.of("companion_cube_1_1",Map.of("monster-type","companion_cube"));
                    default->Map.of();
                };
                check(edits.equals(expectedEdits),"actor YAML contains only the deliberate auction-facing and catalogue-type corrections");
            }
            ContentFixture.copy(Path.of("content"),root.resolve("content"));ContentFixture.copy(Path.of("config"),root.resolve("config"));
            for(String resource:List.of("town-definitions.json","statuses.json")) {
                Path nativeResource=root.resolve("content/_internal/"+resource),saved=root.resolve("saved-"+resource);
                Files.move(nativeResource,saved);refused(()->loadAll(root));Files.move(saved,nativeResource);
            }
            Path townDefaults=root.resolve("content/_internal/town-definitions.json");String originalTown=Files.readString(townDefaults);
            var townData=Yaml.map(Json.parseCatalog(originalTown),"Town defaults");var itemRows=new ArrayList<Object>(Yaml.list(townData.get("items"),"native items"));itemRows.add(itemRows.getFirst());townData.put("items",itemRows);
            Files.writeString(townDefaults,Json.stringify(townData));refused(()->loadAll(root));Files.writeString(townDefaults,originalTown);
            Path actorFile=root.resolve("content/maps/training/advanced_training/actors.yml");var actors=new LinkedHashMap<String,Object>();actors.put("made_up_actor",Map.of("x",1));write(actorFile,Map.of("actors",actors));refused(()->loadAll(root));
            Path bindingFile=root.resolve("content/maps/training/advanced_training/native/bindings.json");var bindings=Yaml.map(Json.parseCatalog(Files.readString(bindingFile)),"bindings");var aliases=Yaml.map(bindings.get("actorAliases"),"aliases");var defaults=Yaml.map(bindings.get("actorDefaults"),"defaults");
            var alias=aliases.entrySet().stream().filter(e->{try{return Yaml.map(defaults.get(e.getValue()),"defaults").containsKey("child.health");}catch(IOException failure){throw new UncheckedIOException(failure);}}).findFirst().orElseThrow();
            actors.clear();actors.put(alias.getKey(),Map.of("monster",Map.of("health",33,"attackIntervalMs",3099),"position",Map.of("x",-17.5,"y",13.5)));write(actorFile,Map.of("actors",actors));
            var prepared=MapYaml.prepare(actorFile.getParent(),root.resolve("content/shared"));check(prepared.content().getProperty("entry."+alias.getValue()+".child.health").equals("33")&&prepared.content().getProperty("entry."+alias.getValue()+".child.attack-interval").equals("3099"),"semantic sparse controls retain engine units");
            actors.put(alias.getValue().toString(),Map.of());write(actorFile,Map.of("actors",actors));refused(()->loadAll(root));
            write(actorFile,Map.of("actors",Map.of(alias.getKey(),Map.of("config","Forged/Identity"))));refused(()->loadAll(root));
            check(!baseline.items().search("Silver Key").isEmpty(),"localized item names discover canonical stock IDs");
            var mirakat=baseline.items().find("Gear/Helm/Mirakat Hat");
            check(mirakat!=null&&mirakat.nameKey().equals("~Mirakat Hat"),"missing item bundle keys fall back to readable literal names");
            check(baseline.items().entries().stream().noneMatch(e->e.nameKey().startsWith("~m.")),"no unresolved item bundle keys remain");
            System.out.println("PASS: seven golden scene/portal snapshots, 9355 exact item definitions, sparse aliases/controls, duplicate/native-identity rejection and discovery names");
        }finally{ContentFixture.delete(root);}
    }
    private static String hash(Properties p)throws Exception {var sorted=new TreeMap<String,Object>();p.forEach((k,v)->sorted.put(k.toString(),v.toString()));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.stringify(sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static void write(Path path,Map<String,Object> data)throws IOException{Files.delete(path);Yaml.write(path,data);}
    private interface Work {void run()throws Exception;}
    /** Startup is lazy; force every body so a release snapshot proves its full graph. */
    private static void loadAll(Path root)throws IOException {
        var registry=ContentManager.load(root);
        try {registry.scenes().scenes();}
        catch(java.io.UncheckedIOException failure){
            if(failure.getCause() instanceof IOException invalid)throw invalid;
            throw failure;
        }
    }
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
