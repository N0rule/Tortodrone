package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.*;

/** One fully validated immutable definition snapshot; mutable scene instances remain in SceneRegistry. */
public record ContentRegistry(List<Pack> packs, Map<String,Resource> resources, SceneCatalog scenes,
        ItemCatalog items, WeaponCatalog weapons, EquipmentCatalog equipment, FireCatalog fires,
        List<String> warnings) {
    public record Pack(String id,String version) { }
    public record Resource(String pack,Path path,String sha256) { }
    public ContentRegistry {
        packs=List.copyOf(packs);resources=Collections.unmodifiableMap(new TreeMap<>(resources));warnings=List.copyOf(warnings);
        Objects.requireNonNull(scenes);Objects.requireNonNull(items);Objects.requireNonNull(weapons);
        Objects.requireNonNull(equipment);Objects.requireNonNull(fires);
    }
    public SceneRegistry createScenes() {
        var registry=new SceneRegistry(scenes);
        registry.weapons(weapons);registry.equipment(equipment);registry.fires(fires);
        return registry;
    }
    /** Diagnostic output only. Cache never determines enabled packs or bypasses validation. */
    public void writeLockfile(Path deployment) throws IOException {
        Path directory=deployment.resolve("cache");ContentFiles.safe(directory);Files.createDirectories(directory);
        Path file=directory.resolve("content.lock");ContentFiles.safe(file);
        var text=new StringBuilder("format=1\n");
        for(Pack pack:packs)text.append("pack.").append(pack.id()).append('=').append(pack.version()).append('\n');
        resources.forEach((key,value)->text.append("resource.").append(key).append('=').append(value.pack()).append(',').append(value.sha256()).append('\n'));
        Path staged=Files.createTempFile(directory,".content-lock-",".tmp");
        try {
            Files.writeString(staged,text);
            Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(staged);}
    }
}
