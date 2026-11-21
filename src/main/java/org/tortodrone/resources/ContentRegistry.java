package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.*;

/** One fully validated immutable definition snapshot; mutable scene instances remain in SceneRegistry. */
public record ContentRegistry(List<Pack> packs, Map<String,Resource> resources, SceneCatalog scenes,
        ItemCatalog items, WeaponCatalog weapons, EquipmentCatalog equipment, FireCatalog fires,
        List<String> warnings, org.tortodrone.town.TownCatalog town, FireCatalog petFires,
        org.tortodrone.news.NewsCatalog news, org.tortodrone.loot.BoxCatalog boxes, PresentationCatalog presentation,
        org.tortodrone.character.MissionCatalog missions, MissionComlinks comlinks, MissionGates gates) {
    public ContentRegistry(List<Pack> packs,Map<String,Resource> resources,SceneCatalog scenes,ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment,FireCatalog fires,List<String> warnings,org.tortodrone.town.TownCatalog town,FireCatalog petFires,org.tortodrone.news.NewsCatalog news,org.tortodrone.loot.BoxCatalog boxes){this(packs,resources,scenes,items,weapons,equipment,fires,warnings,town,petFires,news,boxes,PresentationCatalog.empty(),org.tortodrone.character.MissionCatalog.empty(),MissionComlinks.empty(),MissionGates.empty());}
    public ContentRegistry(List<Pack> packs,Map<String,Resource> resources,SceneCatalog scenes,ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment,FireCatalog fires,List<String> warnings,org.tortodrone.town.TownCatalog town,FireCatalog petFires,org.tortodrone.news.NewsCatalog news,org.tortodrone.loot.BoxCatalog boxes,PresentationCatalog presentation){this(packs,resources,scenes,items,weapons,equipment,fires,warnings,town,petFires,news,boxes,presentation,org.tortodrone.character.MissionCatalog.empty(),MissionComlinks.empty(),MissionGates.empty());}
    public record Pack(String id,String version) { }
    public record Resource(String pack,Path path,String sha256) { }
    public ContentRegistry {
        packs=List.copyOf(packs);resources=Collections.unmodifiableMap(new TreeMap<>(resources));warnings=List.copyOf(warnings);
        Objects.requireNonNull(scenes);Objects.requireNonNull(items);Objects.requireNonNull(weapons);
        Objects.requireNonNull(equipment);Objects.requireNonNull(fires);Objects.requireNonNull(town);Objects.requireNonNull(petFires);
        Objects.requireNonNull(news);Objects.requireNonNull(boxes);Objects.requireNonNull(missions);Objects.requireNonNull(comlinks);Objects.requireNonNull(gates);
    }
    public SceneRegistry createScenes() {
        var registry=new SceneRegistry(scenes);
        registry.boxes(boxes);registry.town(town);registry.petFires(petFires);registry.news(news);
        registry.weapons(weapons);registry.equipment(equipment);registry.fires(fires);registry.missions(missions);registry.comlinks(comlinks);registry.gates(gates);
        return registry;
    }
}
