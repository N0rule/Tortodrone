package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.Yaml;
import org.tortodrone.core.ContentFiles;
import org.tortodrone.core.config.ServerSettings;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.MonsterCatalog;
import org.tortodrone.core.world.MapCollision;
import org.tortodrone.core.world.CollisionMap;

/** Atomic startup compilation. No pack selection, runtime downloads or content writes. */
public final class ContentManager {
    private ContentManager() { }
    public static void main(String[] args)throws IOException {
        if(args.length==3&&args[0].equals("--actors")) {
            if(!args[2].matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Invalid map name");
            Path maps=Path.of(args[1]).resolve("content/maps"),folder=MapYaml.folder(maps,args[2]);
            MapYaml.prepare(folder,maps.getParent().resolve("shared"));
            var bindings=Yaml.map(Json.parseCatalog(Files.readString(folder.resolve("native/bindings.json"))),"bindings");
            var actors=Yaml.map(bindings.get("actors"),"actors");
            for(var alias:new TreeMap<>(Yaml.map(bindings.getOrDefault("actorAliases",Map.of()),"aliases")).entrySet()) {
                var fixed=Yaml.map(actors.get(alias.getValue()),"native actor");
                System.out.println(alias.getKey()+"\t"+fixed.get("config")+"\t"+alias.getValue());
            }
            return;
        }
        if(args.length!=1)throw new IllegalArgumentException("Usage: ContentManager /deployment, or --actors /deployment map_name");
        var c=load(Path.of(args[0]));var all=c.scenes().scenes();System.out.println("PASS: unified content; "+all.size()+" maps, "+c.items().entries().size()+" items, "+c.weapons().weapons().size()+" weapons, "+c.missions().size()+" missions");
    }
    public static ContentRegistry load(Path deployment)throws IOException {
        return load(deployment,org.tortodrone.core.config.ServerSettings.read(org.tortodrone.core.config.ServerSettings.defaultPath(deployment)));
    }
    public static ContentRegistry load(Path deployment,org.tortodrone.core.config.ServerSettings settings)throws IOException {
        Path root=deployment.toAbsolutePath().normalize(),content=root.resolve("content");ContentFiles.safe(content);
        if(!Files.isDirectory(content.resolve("maps")))throw new IOException("Unified content/maps is missing. Use ContentMigration /old-deployment /new-candidate; legacy packs are migration-only.");
        if(!settings.contentPacks().equals("vanilla"))throw new IOException("content-packs selection requires explicit ContentMigration; unified startup never ignores custom selection");
        if(Files.exists(content.resolve("packs"))||Files.exists(content.resolve("custom/packs"))||LegacyContentSource.present(root))throw new IOException("Mixed unified and legacy content layouts; prepare and review a separate ContentMigration candidate");
        // Fingerprint every input automatically, then recheck after all parsers/validators complete.
        var before=inputs(root);var warnings=new ArrayList<String>();
        try {
            int dodge=0;Path combat=root.resolve("config/combat.yml");
            if(Files.exists(combat)){var c=Yaml.read(combat);Yaml.fields(c,combat.toString(),"dodgeCooldownMs");dodge=Yaml.integer(c.get("dodgeCooldownMs"),"dodgeCooldownMs");}
            var definitions=ItemYaml.read(content.resolve("items"),dodge);
            var fires=FireCatalog.load(content.resolve("_internal/fires.json.gz"));
            var pet=FireCatalog.load(content.resolve("_internal/pet-fires.json.gz"));
            var boxes=org.tortodrone.resources.BoxCatalog.load(content,definitions.items());
            var news=NewsYaml.load(content.resolve("shared/news"));
            var townData=TownYaml.data(content.resolve("town"),definitions.items());TownYaml.references(townData,definitions.items());
            var town=org.tortodrone.core.town.TownCatalog.fromData(townData);
            var presentation=PresentationCatalog.load(content.resolve("shared/presentation.yml"));
            var missionDefinitions=MissionYaml.definitions(content);
            var missions=missionDefinitions.missions();
            if(missions.size()==0)warnings.add("Mission catalogue is missing; mission definitions cannot be served");
            for(var entry:missions.missions())for(var grant:missions.grants(entry.id()))if(definitions.items().find(grant.config())==null)throw new IOException("Mission "+entry.id()+" grants missing item: "+grant.config());
            var monsters=MonsterCatalog.load(content.resolve("monsters"));
            if(monsters.size()==0)warnings.add("Monster catalogue is missing; monster spawns cannot resolve type ids");
            var catalog=loadMaps(content.resolve("maps"),settings.startupMap(),town,definitions.items(),monsters);
            GearStats.validate(catalog.entry().content().stats());
            var controls=new HashSet<String>();
            for(var weapon:definitions.weapons().weapons()){requireItem(definitions.items(),weapon.config());controls.add(weapon.config().toLowerCase(Locale.ROOT));}
            for(var shield:definitions.weapons().shields()){requireItem(definitions.items(),shield.config());controls.add(shield.config().toLowerCase(Locale.ROOT));}
            for(var item:definitions.equipment().entries())requireItem(definitions.items(),item.config());
            for(var fire:fires.entries())if(!controls.contains(fire.weaponConfig().toLowerCase(Locale.ROOT)))throw new IOException("Fire graph has no weapon/shield controls: "+fire.weaponConfig());
            if(!before.equals(inputs(root)))throw new IOException("Content changed during startup loading; restart with stable input files");
            return new ContentRegistry(List.of(),Map.of(),catalog,definitions.items(),definitions.weapons(),definitions.equipment(),fires,warnings,town,pet,news,boxes,presentation,missions,missionDefinitions.comlinks(),missionDefinitions.gates());
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid unified content: "+invalid.getMessage(),invalid);}
    }
    /** Map-only preparation for import/native compatibility tools; runtime uses the full snapshot. */
    public static SceneCatalog loadMaps(Path maps,String start,org.tortodrone.core.town.TownCatalog town)throws IOException {
        var items=Files.exists(maps.getParent().resolve("_internal/item-bindings.json"))?ItemYaml.load(maps.getParent().resolve("items")):null;
        return loadMaps(maps,start,town,items,MonsterCatalog.load(maps.getParent().resolve("monsters")));
    }
    private static SceneCatalog loadMaps(Path maps,String start,org.tortodrone.core.town.TownCatalog town,org.tortodrone.core.character.ItemCatalog items,MonsterCatalog monsters)throws IOException {
        try {
            var folders=MapYaml.folders(maps);
            var startEntry=folders.entrySet().stream().filter(e->e.getValue().getFileName().toString().equals(start)).findFirst().orElseThrow(()->new IOException("startup-map is missing: "+start));
            Path startFolder=startEntry.getValue();Integer entry=startEntry.getKey();
            Path shared=maps.getParent().resolve("shared");
            var entryScene=loadMap(startFolder,shared,town,items,monsters);
            if(!entryScene.town())throw new IOException("Entry scene must be a town");
            return SceneCatalog.lazy(folders,entry,entryScene,folder->loadMap(folder,shared,town,items,monsters));
        }catch(IllegalArgumentException invalid){throw new IOException(maps+": "+invalid.getMessage(),invalid);}
    }
    private static org.tortodrone.core.world.MapScene loadMap(Path folder,Path shared,org.tortodrone.core.town.TownCatalog town,org.tortodrone.core.character.ItemCatalog items,MonsterCatalog monsters)throws IOException {
        var prepared=MapYaml.prepare(folder,shared,items);var p=prepared.descriptor();Path nativeDir=prepared.nativeDirectory();
        byte[] layout=Files.readAllBytes(nativeDir.resolve("scene.dat"));
        if(layout.length<16||layout.length>900_000||layout[0]!=(byte)0xfa||layout[1]!=(byte)0xce||layout[2]!=(byte)0xaf||layout[3]!=0x0e)throw new IOException(nativeDir+": invalid native scene header/size");
        float x=Float.parseFloat(p.getProperty("spawn-x")),y=Float.parseFloat(p.getProperty("spawn-y"));
        int instances=Integer.parseInt(p.getProperty("instances"));if(instances<1||instances>16)throw new IOException("instances must be from 1 to 16");
        String digest=p.getProperty("scene-sha256");
        var actors=SceneContent.load(prepared.content(),digest);
        for(var cycle:actors.cycles().entrySet()) {
            var spec=cycle.getValue();for(String field:List.of("up","down","warn","offset")){float v=spec.number(field,0);if(!Float.isFinite(v)||v<0||v>600_000)throw new IOException("Invalid trap cycle "+cycle.getKey()+"."+field);}
            if(!actors.cycles().containsKey(spec.text("inverse",cycle.getKey())))throw new IOException("Missing inverse trap cycle: "+cycle.getKey());
        }
        for(var actor:actors.entries())if(actor.spec().fields().containsKey("cycle")&&!actors.cycles().containsKey(actor.spec().text("cycle","")))throw new IOException("Actor references missing trap cycle: "+actor.key());
        monsters.validateScene(folder.getFileName().toString(),actors);
        actors=monsters.expand(actors);
        for(var conversation:actors.conversations().values())for(var node:conversation.nodes().values())for(var response:node.responses())if(!response.station().isEmpty()&&town!=null&&town.station(response.station())==null)throw new IOException("NPC references missing Town station: "+response.station());
        int collisionMask=Math.round(actors.stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK));
        MapCollision exact=null;
        if(p.getProperty("exact-collision-sha256")!=null){
            exact=MapCollision.read(nativeDir.resolve("collision.bin"));
            if(!exact.layoutHash().equals(digest))throw new IOException(nativeDir+": exact collision layout hash mismatch");
            if(!exact.clear(collisionMask,x,y,ScenePortals.PAWN_RADIUS))throw new IOException(folder+": defaultSpawn is not clear in exact collision");
        }
        if(exact==null)throw new IOException(folder+": exact collision.bin is required; run generateNativeCollision");
        var collision=CollisionMap.exact(exact,collisionMask,ScenePortals.PAWN_RADIUS);
        var portals=ScenePortals.load(prepared.portals(),digest,collision);
        return new org.tortodrone.core.world.MapScene(Integer.parseInt(p.getProperty("scene-id")),Integer.parseInt(p.getProperty("zone-id")),p.getProperty("zone-name"),p.getProperty("name"),p.getProperty("image"),p.getProperty("kind"),Integer.parseInt(p.getProperty("version")),x,y,Float.parseFloat(p.getProperty("spawn-rotation")),instances,collision,layout,portals,actors,SceneCollision.fromMapCollision(exact),exact,monsters);
    }
    private static void requireItem(org.tortodrone.core.character.ItemCatalog items,String config)throws IOException{if(items.find(config)==null)throw new IOException("Combat references missing item: "+config);}
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
