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
        private final TutorialRoute tutorial;
        private final OpeningGate.Attempt opening;
        public Instance(int oid,int index,SceneCatalog.MapScene map){this(oid,index,map,0);}
        private Instance(int oid,int index,SceneCatalog.MapScene map,int partyOwner){this(oid,index,map,partyOwner,null);}
        private Instance(int oid,int index,SceneCatalog.MapScene map,int partyOwner,TutorialRoute tutorial){this(oid,index,map,partyOwner,tutorial,null);}
        private Instance(int oid,int index,SceneCatalog.MapScene map,int partyOwner,TutorialRoute tutorial,OpeningGate.Attempt opening){this.oid=oid;this.index=index;this.map=map;this.partyOwner=partyOwner;this.tutorial=tutorial!=null&&tutorial.source()==map?tutorial:null;this.opening=opening;simulation=new SceneSimulation(this);}
        public TutorialRoute tutorial(){return tutorial;}
        public OpeningGate.Attempt opening(){return opening;}
        public int partyOwner(){return partyOwner;}
        public int oid(){return oid;}
        public int index(){return index;}
        public SceneCatalog.MapScene map(){return map;}
        public SceneSimulation simulation(){return simulation;}
    }
    private final SceneCatalog catalog;
    private final TutorialRoute tutorial;
    private final OpeningGate generator;
    public TutorialRoute tutorial(){return tutorial;}
    public OpeningGate openingGate(org.tortodrone.character.OpeningMission mission){return mission==org.tortodrone.character.OpeningMission.ANCIENT_GENERATOR?generator:null;}
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
    public void equipment(EquipmentCatalog catalog){equipment=Objects.requireNonNull(catalog);equipment.town(town);equipment.stats(this.catalog.entry().content().stats());}
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
        this.catalog=catalog;tutorial=TutorialRoute.available(catalog);generator=OpeningGate.available(catalog,org.tortodrone.character.OpeningMission.ANCIENT_GENERATOR);int oid=10;
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
            for(int i=0;i<map.instances();i++)copies.add(new Instance(oid++,i,map,0,tutorial));
            instances.put(map.id(),List.copyOf(copies));
        }
    }
    public SceneCatalog catalog(){return catalog;}
    /** Inspect only an already allocated instance; release this monitor before entering simulation. */
    public SceneSimulation.Companion companion(int instanceId,int knight) {
        Instance target=null;
        synchronized(this) {
            for(var copies:instances.values())for(var candidate:copies)if(candidate.oid()==instanceId)target=candidate;
            if(target==null)for(var candidate:interiors.values())if(candidate.oid()==instanceId)target=candidate;
        }
        return target==null?null:target.simulation().companion(knight);
    }
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
            if(tutorial!=null&&tutorial.source()==map&&member!=null){
                if(member.worldId!=worldId)throw new IOException("Foreign tutorial owner");
                if(interiorOids.isEmpty()&&!interiors.containsKey(new InteriorKey(scene,worldId)))throw new IOException("Interior capacity is full; try again later");
                parties.enterTutorial(member);
            }
            boolean assigned=map.kind().equals("training")&&member!=null&&parties.owner(member,worldId)==0;
            if(map.kind().equals("training")&&member!=null)parties.enterTraining(member);
            int owner=map.kind().equals("training")&&member!=null?parties.owner(member,worldId):0;
            var key=new InteriorKey(scene,owner==0?worldId:-owner);var existing=interiors.get(key);
            if(existing!=null){interiorLeases.get(existing).add(worldId);return existing;}
            var oid=interiorOids.pollFirst();if(oid==null){if(assigned)parties.leaveTraining(member);throw new IOException("Interior capacity is full; try again later");}
            var created=new Instance(oid,0,map,owner,tutorial);created.simulation().fires(fires);created.simulation().petFires(petFires);created.simulation().town(town);interiors.put(key,created);interiorLeases.put(created,new HashSet<>(Set.of(worldId)));return created;
        }
        return instance(scene,Math.floorMod(worldId,instances.get(scene).size()));
    }
    public synchronized void release(Instance scene,int worldId) {
        if(scene==null||scene.map().town())return;
        var leases=interiorLeases.get(scene);if(leases==null)return;leases.remove(worldId);
        if(leases.isEmpty()&&scene.simulation().playerCount()==0){interiorLeases.remove(scene);interiors.values().removeIf(value->value==scene);interiorOids.addLast(scene.oid());}
    }
    /** Allocate only an authenticated private party's sourced mission floor. */
    public synchronized Instance startOpening(OpeningGate gate,int worldId,PartyRegistry.Member member,String difficulty)throws IOException {
        if(gate==null||gate.source()!=catalog.scene(gate.source().id())||gate.destination()!=catalog.scene(gate.destination().id())||member==null||member.worldId!=worldId)throw new IOException("Invalid mission route owner");
        var view=parties.view(member);
        return parties.soloGate(member,view.oid(),()->{
        var attempt=new OpeningGate.Attempt(gate,worldId,view.oid(),difficulty);
        var key=new InteriorKey(gate.source().id(),worldId);
        if(interiors.containsKey(key)||interiorOids.isEmpty())throw new IOException("Mission instance is unavailable");
        // Construct before changing party state or consuming a reusable place OID.
        var created=new Instance(interiorOids.getFirst(),0,gate.source(),0,null,attempt);
        created.simulation().fires(fires);created.simulation().petFires(petFires);created.simulation().town(town);
        parties.enterOpening(member);interiorOids.removeFirst();interiors.put(key,created);interiorLeases.put(created,new HashSet<>(Set.of(worldId)));return created;
        });
    }
    public boolean authorized(Instance scene,int world,PartyRegistry.Member member){
        if(scene.opening()!=null)return scene.opening().worldId()==world&&parties.owns(member,scene.opening().partyOid());
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
