package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Server-assigned scene instances; a client scene version is never an instance selector. */
public final class SceneRegistry {
    public static final String ZONE_RECEIVER="2d900cf54355111b4bb4befcdff42b82";
    public static final class Instance {
        private final int oid,index,partyOwner;
        private final SceneCatalog.MapScene map;
        private final SceneSimulation simulation;
        public Instance(int oid,int index,SceneCatalog.MapScene map){this(oid,index,map,0);}
        private Instance(int oid,int index,SceneCatalog.MapScene map,int partyOwner){this.oid=oid;this.index=index;this.map=map;this.partyOwner=partyOwner;simulation=new SceneSimulation(this);}
        public int partyOwner(){return partyOwner;}
        public int oid(){return oid;}
        public int index(){return index;}
        public SceneCatalog.MapScene map(){return map;}
        public SceneSimulation simulation(){return simulation;}
    }
    private final SceneCatalog catalog;
    private final PartyRegistry parties=new PartyRegistry();
    public PartyRegistry parties(){return parties;}
    private org.tortodrone.town.TownCatalog town=org.tortodrone.town.TownCatalog.empty();
    public org.tortodrone.town.TownCatalog town(){return town;}
    public void town(org.tortodrone.town.TownCatalog catalog){town=Objects.requireNonNull(catalog);equipment.town(town);for(var copies:instances.values())for(var instance:copies)instance.simulation().town(town);}
    private org.tortodrone.loot.BoxCatalog boxes=org.tortodrone.loot.BoxCatalog.empty();
    public org.tortodrone.loot.BoxCatalog boxes(){return boxes;}
    public void boxes(org.tortodrone.loot.BoxCatalog catalog){boxes=Objects.requireNonNull(catalog);}
    private org.tortodrone.news.NewsCatalog news=org.tortodrone.news.NewsCatalog.disabled();
    public org.tortodrone.news.NewsCatalog news(){return news;}
    public void news(org.tortodrone.news.NewsCatalog catalog){news=Objects.requireNonNull(catalog);}
    private WeaponCatalog weapons=WeaponCatalog.empty();
    private EquipmentCatalog equipment=EquipmentCatalog.empty();
    public EquipmentCatalog equipment(){return equipment;}
    public void equipment(EquipmentCatalog catalog){equipment=Objects.requireNonNull(catalog);equipment.town(town);}
    private FireCatalog petFires=FireCatalog.empty();
    public void petFires(FireCatalog catalog){petFires=Objects.requireNonNull(catalog);for(var copies:instances.values())for(var instance:copies)instance.simulation().petFires(catalog);}
    private FireCatalog fires=FireCatalog.empty();
    public FireCatalog fires(){return fires;}
    public void fires(FireCatalog catalog){fires=Objects.requireNonNull(catalog);for(var copies:instances.values())for(var instance:copies)instance.simulation().fires(catalog);}
    public WeaponCatalog weapons(){return weapons;}
    public void weapons(WeaponCatalog catalog){weapons=Objects.requireNonNull(catalog);}
    private final Map<Integer,List<Instance>> instances=new HashMap<>();
    private record InteriorKey(int scene,int worldId){}
    private final Map<InteriorKey,Instance> interiors=new HashMap<>();
    private final Map<Instance,Set<Integer>> interiorLeases=new IdentityHashMap<>();
    // Static places end before 138; player/trade objects start at 1000. Keep
    // active solo interior places and their party objects in disjoint ranges.
    private final ArrayDeque<Integer> interiorOids=new ArrayDeque<>();
    private static SceneRegistry system;
    private final Map<Integer,Integer> channelZones=new HashMap<>();
    private final Map<Integer,SceneChat> channels=new HashMap<>();
    public int channelOid(int zone,boolean trade){Integer base=channelZones.get(zone);if(base==null)throw new IllegalArgumentException("Unknown chat zone");return base+(trade?1:0);}
    public int channelType(int oid){for(int base:channelZones.values()){if(oid==base)return 6;if(oid==base+1)return 7;}return -1;}
    public SceneChat channel(int oid){var channel=channels.get(oid);if(channel==null)throw new IllegalArgumentException("Unknown chat channel");return channel;}
    public SceneRegistry(SceneCatalog catalog) {
        this.catalog=catalog;int oid=10;
        int chatOid=1_500_000_000;
        for(int zone:catalog.scenes().stream().map(SceneCatalog.MapScene::zone).distinct().sorted().toList()) {
            channelZones.put(zone,chatOid);channels.put(chatOid,new SceneChat());channels.put(chatOid+1,new SceneChat());chatOid+=2;
        }
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
    public record Status(int templateId,int instanceId,String name,int players,String state) { }
    public synchronized List<Status> statuses() {
        var result=new ArrayList<Status>();
        // Interior templates are instantiated per owner; their unused prototype is not active.
        for(var copies:instances.values())for(var instance:copies)if(instance.map().town())
            result.add(status(instance));
        for(var instance:interiors.values())result.add(status(instance));
        return result.stream().sorted(Comparator.comparingInt(Status::templateId).thenComparingInt(Status::instanceId)).toList();
    }
    private static Status status(Instance instance) {
        int players=instance.simulation().playerCount();
        return new Status(instance.map().id(),instance.oid(),instance.map().name(),players,players==0?"Idle":"Active");
    }
    public Instance entry(){return instances.get(catalog.entry().id()).get(0);}
    public Instance instance(int scene,int index)throws IOException {
        var copies=instances.get(scene);
        if(copies==null||index<0||index>=copies.size())throw new IOException("Scene instance is not available");return copies.get(index);
    }
    public synchronized Instance destination(int scene,int worldId)throws IOException {
        return destination(scene,worldId,null);
    }
    public synchronized Instance destination(int scene,int worldId,PartyRegistry.Member member)throws IOException {
        var map=catalog.scene(scene);
        if(!map.town()) {
            if(worldId<1)throw new IOException("Invalid interior owner");
            int owner=map.kind().equals("training")&&member!=null?parties.owner(member,worldId):0;
            var key=new InteriorKey(scene,owner==0?worldId:-owner);var existing=interiors.get(key);
            if(existing!=null){interiorLeases.get(existing).add(worldId);return existing;}
            var oid=interiorOids.pollFirst();if(oid==null)throw new IOException("Interior capacity is full; try again later");
            var created=new Instance(oid,0,map,owner);created.simulation().fires(fires);created.simulation().petFires(petFires);created.simulation().town(town);interiors.put(key,created);interiorLeases.put(created,new HashSet<>(Set.of(worldId)));return created;
        }
        return instance(scene,Math.floorMod(worldId,instances.get(scene).size()));
    }
    public synchronized void release(Instance scene,int worldId) {
        if(scene==null||scene.map().town())return;
        var leases=interiorLeases.get(scene);if(leases==null)return;leases.remove(worldId);
        if(leases.isEmpty()&&scene.simulation().playerCount()==0){interiorLeases.remove(scene);interiors.values().removeIf(value->value==scene);interiorOids.addLast(scene.oid());}
    }
    public boolean authorized(Instance scene,int world,PartyRegistry.Member member){
        if(scene.partyOwner()==0)return true;
        try{return parties.owner(member,world)==scene.partyOwner();}catch(IOException invalid){return false;}
    }
    public static synchronized SceneRegistry system()throws IOException {
        if(system==null) {
            try {
                Path source=Path.of(SceneRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path deployment=Files.isDirectory(source)?source:source.getParent();
                system=org.tortodrone.resources.ContentManager.load(deployment).createScenes();
            }catch(java.net.URISyntaxException e){throw new IOException("Cannot locate content directory",e);}
        }
        return system;
    }
    public static synchronized void install(SceneRegistry registry){system=Objects.requireNonNull(registry);}
    public static Instance defaultEntry() {
        try{return system().entry();}catch(IOException e){throw new IllegalStateException("Prepared maps are unavailable",e);}
    }
}
