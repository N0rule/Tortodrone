package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.NewsCatalog;
import org.tortodrone.core.mission.MissionCatalog;
import org.tortodrone.core.mission.MissionComlinks;
import org.tortodrone.core.mission.MissionGates;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.WeaponCatalog;

/** One fully validated immutable definition snapshot; mutable scene instances remain in SceneRegistry. */
public record ContentRegistry(List<Pack> packs, Map<String,Resource> resources, SceneCatalog scenes,
        ItemCatalog items, WeaponCatalog weapons, EquipmentCatalog equipment, FireCatalog fires,
        List<String> warnings, org.tortodrone.core.town.TownCatalog town, FireCatalog petFires,
        org.tortodrone.core.NewsCatalog news, org.tortodrone.resources.BoxCatalog boxes, PresentationCatalog presentation,
        org.tortodrone.core.mission.MissionCatalog missions, MissionComlinks comlinks, MissionGates gates) {
    public ContentRegistry(List<Pack> packs,Map<String,Resource> resources,SceneCatalog scenes,ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment,FireCatalog fires,List<String> warnings,org.tortodrone.core.town.TownCatalog town,FireCatalog petFires,org.tortodrone.core.NewsCatalog news,org.tortodrone.resources.BoxCatalog boxes){this(packs,resources,scenes,items,weapons,equipment,fires,warnings,town,petFires,news,boxes,PresentationCatalog.empty(),org.tortodrone.core.mission.MissionCatalog.empty(),MissionComlinks.empty(),MissionGates.empty());}
    public ContentRegistry(List<Pack> packs,Map<String,Resource> resources,SceneCatalog scenes,ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment,FireCatalog fires,List<String> warnings,org.tortodrone.core.town.TownCatalog town,FireCatalog petFires,org.tortodrone.core.NewsCatalog news,org.tortodrone.resources.BoxCatalog boxes,PresentationCatalog presentation){this(packs,resources,scenes,items,weapons,equipment,fires,warnings,town,petFires,news,boxes,presentation,org.tortodrone.core.mission.MissionCatalog.empty(),MissionComlinks.empty(),MissionGates.empty());}
    public record Pack(String id,String version) { }
    public record Resource(String pack,Path path,String sha256) { }
    public ContentRegistry {
        packs=List.copyOf(packs);resources=Collections.unmodifiableMap(new TreeMap<>(resources));warnings=List.copyOf(warnings);
        Objects.requireNonNull(scenes);Objects.requireNonNull(items);Objects.requireNonNull(weapons);
        Objects.requireNonNull(equipment);Objects.requireNonNull(fires);Objects.requireNonNull(town);Objects.requireNonNull(petFires);
        Objects.requireNonNull(news);Objects.requireNonNull(boxes);Objects.requireNonNull(missions);Objects.requireNonNull(comlinks);Objects.requireNonNull(gates);
    }

}
