package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Server-assigned scene instances; a client scene version is never an instance selector. */
public final class SceneRegistry {
    public static final String ZONE_RECEIVER="2d900cf54355111b4bb4befcdff42b82";
    public static final class Instance {
        private final int oid,index;
        private final SceneCatalog.MapScene map;
        private final SceneSimulation simulation;
        public Instance(int oid,int index,SceneCatalog.MapScene map){this.oid=oid;this.index=index;this.map=map;simulation=new SceneSimulation(this);}
        public int oid(){return oid;}
        public int index(){return index;}
        public SceneCatalog.MapScene map(){return map;}
        public SceneSimulation simulation(){return simulation;}
    }
    private final SceneCatalog catalog;
    private WeaponCatalog weapons=WeaponCatalog.empty();
    private EquipmentCatalog equipment=EquipmentCatalog.empty();
    public EquipmentCatalog equipment(){return equipment;}
    public void equipment(EquipmentCatalog catalog){equipment=Objects.requireNonNull(catalog);}
    private FireCatalog fires=FireCatalog.empty();
    public FireCatalog fires(){return fires;}
    public void fires(FireCatalog catalog){fires=Objects.requireNonNull(catalog);for(var copies:instances.values())for(var instance:copies)instance.simulation().fires(catalog);}
    public WeaponCatalog weapons(){return weapons;}
    public void weapons(WeaponCatalog catalog){weapons=Objects.requireNonNull(catalog);}
    private final Map<Integer,List<Instance>> instances=new HashMap<>();
    private record InteriorKey(int scene,int worldId){}
    private final Map<InteriorKey,Instance> interiors=new HashMap<>();
    // Static places end before 138; player/trade objects start at 1000. Keep
    // active solo interior places and their party objects in disjoint ranges.
    private final ArrayDeque<Integer> interiorOids=new ArrayDeque<>();
    private static SceneRegistry system;
    public SceneRegistry(SceneCatalog catalog) {
        this.catalog=catalog;int oid=10;
        for(int id=140;id<500;id++)interiorOids.add(id);
        if(catalog.scenes().isEmpty())return;
        // Keep the entry object's identity stable for existing clients; other templates are ordered.
        var ordered=new ArrayList<>(catalog.scenes());ordered.remove(catalog.entry());ordered.add(0,catalog.entry());
        for(var map:ordered) {
            var copies=new ArrayList<Instance>();
            for(int i=0;i<map.instances();i++)copies.add(new Instance(oid++,i,map));
            instances.put(map.id(),List.copyOf(copies));
        }
    }
    public SceneCatalog catalog(){return catalog;}
    public Instance entry(){return instances.get(catalog.entry().id()).get(0);}
    public Instance instance(int scene,int index)throws IOException {
        var copies=instances.get(scene);
        if(copies==null||index<0||index>=copies.size())throw new IOException("Scene instance is not available");return copies.get(index);
    }
    public synchronized Instance destination(int scene,int worldId)throws IOException {
        var map=catalog.scene(scene);
        if(!map.town()) {
            if(worldId<1)throw new IOException("Invalid interior owner");
            var key=new InteriorKey(scene,worldId);var existing=interiors.get(key);
            if(existing!=null)return existing;
            var oid=interiorOids.pollFirst();if(oid==null)throw new IOException("Interior capacity is full; try again later");
            var created=new Instance(oid,0,map);created.simulation().fires(fires);interiors.put(key,created);return created;
        }
        return instance(scene,Math.floorMod(worldId,instances.get(scene).size()));
    }
    public synchronized void release(Instance scene,int worldId) {
        if(scene==null||scene.map().town())return;
        if(interiors.remove(new InteriorKey(scene.map().id(),worldId),scene))interiorOids.addLast(scene.oid());
    }
    public static synchronized SceneRegistry system()throws IOException {
        if(system==null) {
            try {
                Path source=Path.of(SceneRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path deployment=Files.isDirectory(source)?source:source.getParent();
                Path maps=deployment.resolve("maps");
                if(!Files.exists(maps))maps=deployment.resolve("defaults/maps");
                system=new SceneRegistry(SceneCatalog.load(maps));
            }catch(java.net.URISyntaxException e){throw new IOException("Cannot locate map directory",e);}
        }
        return system;
    }
    public static synchronized void install(SceneRegistry registry){system=Objects.requireNonNull(registry);}
    public static Instance defaultEntry() {
        try{return system().entry();}catch(IOException e){throw new IllegalStateException("Prepared maps are unavailable",e);}
    }
}
