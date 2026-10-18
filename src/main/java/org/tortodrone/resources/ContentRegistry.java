package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.*;

/** One fully validated immutable definition snapshot; mutable scene instances remain in SceneRegistry. */
public record ContentRegistry(List<Pack> packs, Map<String,Resource> resources, SceneCatalog scenes,
        ItemCatalog items, WeaponCatalog weapons, EquipmentCatalog equipment, FireCatalog fires,
        List<String> warnings, org.tortodrone.town.TownCatalog town, FireCatalog petFires) {
    public record Pack(String id,String version) { }
    public record Resource(String pack,Path path,String sha256) { }
    public ContentRegistry {
        packs=List.copyOf(packs);resources=Collections.unmodifiableMap(new TreeMap<>(resources));warnings=List.copyOf(warnings);
        Objects.requireNonNull(scenes);Objects.requireNonNull(items);Objects.requireNonNull(weapons);
        Objects.requireNonNull(equipment);Objects.requireNonNull(fires);Objects.requireNonNull(town);Objects.requireNonNull(petFires);
    }
    public SceneRegistry createScenes() {
        var registry=new SceneRegistry(scenes);
        registry.town(town);registry.petFires(petFires);
        registry.weapons(weapons);registry.equipment(equipment);registry.fires(fires);
        return registry;
    }
}
