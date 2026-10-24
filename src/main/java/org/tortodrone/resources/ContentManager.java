package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.*;

/** Atomic startup compilation. No pack selection, runtime downloads or content writes. */
public final class ContentManager {
    private ContentManager() { }
    public static void main(String[] args)throws IOException {
        if(args.length==3&&args[0].equals("--actors")) {
            if(!args[2].matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Invalid map name");
            Path folder=Path.of(args[1]).resolve("content/maps").resolve(args[2]);
            MapYaml.prepare(folder,folder.getParent().getParent().resolve("shared"));
            var bindings=Yaml.map(Json.parseCatalog(Files.readString(folder.resolve("native/bindings.json"))),"bindings");
            var actors=Yaml.map(bindings.get("actors"),"actors");
            for(var alias:new TreeMap<>(Yaml.map(bindings.getOrDefault("actorAliases",Map.of()),"aliases")).entrySet()) {
                var fixed=Yaml.map(actors.get(alias.getValue()),"native actor");
                System.out.println(alias.getKey()+"\t"+fixed.get("config")+"\t"+alias.getValue());
            }
            return;
        }
        if(args.length!=1)throw new IllegalArgumentException("Usage: ContentManager /deployment, or --actors /deployment map_name");
        var c=load(Path.of(args[0]));System.out.println("PASS: unified content; "+c.scenes().scenes().size()+" maps, "+c.items().entries().size()+" items, "+c.weapons().weapons().size()+" weapons");
    }
    public static ContentRegistry load(Path deployment)throws IOException {
        return load(deployment,org.tortodrone.config.ServerSettings.read(org.tortodrone.config.ServerSettings.defaultPath(deployment)));
    }
    public static ContentRegistry load(Path deployment,org.tortodrone.config.ServerSettings settings)throws IOException {
        Path root=deployment.toAbsolutePath().normalize(),content=root.resolve("content");ContentFiles.safe(content);
        if(!Files.isDirectory(content.resolve("maps")))throw new IOException("Unified content/maps is missing. Use ContentMigration /old-deployment /new-candidate; legacy packs are migration-only.");
        if(!settings.contentPacks().equals("vanilla"))throw new IOException("content-packs selection requires explicit ContentMigration; unified startup never ignores custom selection");
        if(Files.exists(content.resolve("packs"))||Files.exists(content.resolve("custom/packs"))||LegacyContent.present(root))throw new IOException("Mixed unified and legacy content layouts; prepare and review a separate ContentMigration candidate");
        // Fingerprint every input automatically, then recheck after all parsers/validators complete.
        var before=inputs(root);var warnings=new ArrayList<String>();
        try {
            int dodge=0;Path combat=root.resolve("config/combat.yml");
            if(Files.exists(combat)){var c=Yaml.read(combat);Yaml.fields(c,combat.toString(),"dodgeCooldownMs");dodge=Yaml.integer(c.get("dodgeCooldownMs"),"dodgeCooldownMs");}
            var definitions=ItemYaml.read(content.resolve("items"),dodge);
            var fires=FireCatalog.load(content.resolve("_internal/fires.json"));
            var pet=FireCatalog.load(content.resolve("_internal/pet-fires.json"));
            var boxes=org.tortodrone.loot.BoxCatalog.load(content,definitions.items());
            var news=NewsYaml.load(content.resolve("shared/news"));
            var townData=TownYaml.data(content.resolve("town"),definitions.items());TownYaml.references(townData,definitions.items());
            var town=org.tortodrone.town.TownCatalog.fromData(townData);
            var presentation=PresentationCatalog.load(content.resolve("shared/presentation.yml"));
            var catalog=loadMaps(content.resolve("maps"),settings.startupMap(),town,definitions.items());
            GearStats.validate(catalog.entry().content().stats());
            var controls=new HashSet<String>();
            for(var weapon:definitions.weapons().weapons()){requireItem(definitions.items(),weapon.config());controls.add(weapon.config().toLowerCase(Locale.ROOT));}
            for(var shield:definitions.weapons().shields()){requireItem(definitions.items(),shield.config());controls.add(shield.config().toLowerCase(Locale.ROOT));}
            for(var item:definitions.equipment().entries())requireItem(definitions.items(),item.config());
            for(var fire:fires.entries())if(!controls.contains(fire.weaponConfig().toLowerCase(Locale.ROOT)))throw new IOException("Fire graph has no weapon/shield controls: "+fire.weaponConfig());
            if(!before.equals(inputs(root)))throw new IOException("Content changed during startup loading; restart with stable input files");
            return new ContentRegistry(List.of(),Map.of(),catalog,definitions.items(),definitions.weapons(),definitions.equipment(),fires,warnings,town,pet,news,boxes,presentation);
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid unified content: "+invalid.getMessage(),invalid);}
    }
    /** Map-only preparation for import/native compatibility tools; runtime uses the full snapshot. */
    public static SceneCatalog loadMaps(Path maps,String start,org.tortodrone.town.TownCatalog town)throws IOException {
        var items=Files.exists(maps.getParent().resolve("_internal/item-bindings.json"))?ItemYaml.load(maps.getParent().resolve("items")):null;
        return loadMaps(maps,start,town,items);
    }
    private static SceneCatalog loadMaps(Path maps,String start,org.tortodrone.town.TownCatalog town,org.tortodrone.character.ItemCatalog items)throws IOException {
        try {
            var folders=MapYaml.folders(maps);
            Path startFolder=maps.resolve(start);Integer entry=folders.entrySet().stream().filter(e->e.getValue().equals(startFolder)).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IOException("startup-map is missing: "+start));
            var scenes=new TreeMap<Integer,SceneCatalog.MapScene>();
            for(var folder:folders.entrySet()) {
                var prepared=MapYaml.prepare(folder.getValue(),maps.getParent().resolve("shared"),items);var p=prepared.descriptor();Path nativeDir=prepared.nativeDirectory();
                byte[] layout=Files.readAllBytes(nativeDir.resolve("scene.dat"));
                if(layout.length<16||layout.length>900_000||layout[0]!=(byte)0xfa||layout[1]!=(byte)0xce||layout[2]!=(byte)0xaf||layout[3]!=0x0e)throw new IOException(nativeDir+": invalid native scene header/size");
                var collision=CollisionMap.read(nativeDir.resolve("walkability.gz"));
                float x=Float.parseFloat(p.getProperty("spawn-x")),y=Float.parseFloat(p.getProperty("spawn-y"));if(!collision.walkable(x,y))throw new IOException(folder.getValue()+": defaultSpawn is not walkable");
                int instances=Integer.parseInt(p.getProperty("instances"));if(instances<1||instances>16)throw new IOException("instances must be from 1 to 16");
                String digest=p.getProperty("scene-sha256");
                var actors=SceneContent.load(prepared.content(),digest);
                for(var cycle:actors.cycles().entrySet()) {
                    var spec=cycle.getValue();for(String field:List.of("up","down","warn","offset")){float v=spec.number(field,0);if(!Float.isFinite(v)||v<0||v>600_000)throw new IOException("Invalid trap cycle "+cycle.getKey()+"."+field);}
                    if(!actors.cycles().containsKey(spec.text("inverse",cycle.getKey())))throw new IOException("Missing inverse trap cycle: "+cycle.getKey());
                }
                for(var actor:actors.entries())if(actor.spec().fields().containsKey("cycle")&&!actors.cycles().containsKey(actor.spec().text("cycle","")))throw new IOException("Actor references missing trap cycle: "+actor.key());
                for(var conversation:actors.conversations().values())if(actors.entries().stream().filter(a->a.spec().text("dialogue",a.spec().text("config","")).equals(conversation.actor())).count()!=1)throw new IOException("Missing or ambiguous NPC actor binding: "+conversation.actor());
                for(var conversation:actors.conversations().values())for(var node:conversation.nodes().values())for(var response:node.responses())if(!response.station().isEmpty()&&town!=null&&town.station(response.station())==null)throw new IOException("NPC references missing Town station: "+response.station());
                scenes.put(folder.getKey(),new SceneCatalog.MapScene(folder.getKey(),Integer.parseInt(p.getProperty("zone-id")),p.getProperty("zone-name"),p.getProperty("name"),p.getProperty("image"),p.getProperty("kind"),Integer.parseInt(p.getProperty("version")),x,y,Float.parseFloat(p.getProperty("spawn-rotation")),instances,collision,layout,ScenePortals.load(prepared.portals(),digest,collision),actors,SceneCollision.load(nativeDir,digest)));
            }
            return SceneCatalog.of(scenes,entry);
        }catch(IllegalArgumentException invalid){throw new IOException(maps+": "+invalid.getMessage(),invalid);}
    }
    private static void requireItem(org.tortodrone.character.ItemCatalog items,String config)throws IOException{if(items.find(config)==null)throw new IOException("Combat references missing item: "+config);}
    static Map<String,String> inputs(Path root)throws IOException {
        var result=new TreeMap<String,String>();long size=0;int count=0;
        for(String directory:List.of("content","config")) {
            Path base=root.resolve(directory);ContentFiles.safe(base);if(!Files.exists(base))continue;
            try(var paths=Files.walk(base,16)) {
                for(var it=paths.iterator();it.hasNext();) {
                    Path file=it.next();ContentFiles.safe(file);if(++count>8192)throw new IOException("Content exceeds entry limit");
                    if(Files.isDirectory(file))continue;ContentFiles.regular(file);
                    if((size+=Files.size(file))>256L*1024*1024)throw new IOException("Content exceeds 256 MiB");
                    result.put(root.relativize(file).toString(),ContentFiles.digest(file));
                }
            }
        }
        return result;
    }
}
