import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import com.threerings.config.*;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.*;
import com.threerings.math.*;
import org.tortodrone.world.CollisionMap;
import org.tortodrone.world.ScenePortals;

/** Exports a pinned seven-scene Haven catalog from a local scene-archive checkout. */
public final class ArchiveTownExport {
    /** Optional verified spawn keeps a bundle's documented arrival point; null derives the closest walkable sample. */
    private record Pack(int id,String folder,String path,String kind,String digest,Float spawnX,Float spawnY) { }
    private record Entrance(int id,float x,float y,float rotation) { }
    private record Point(float x,float y) { }
    private record Route(int source,int destination,String tag,String areaName) { }
    private record NativePortal(int id,int destination,String tag,List<Point> vertices) { }
    private record TownArrivals(Map<String,Entrance> tagged,List<Entrance> defaults) { }
    private record Arrival(String tag,Entrance entrance) { }

    private static final List<Pack> PACKS=List.of(
        // The bundled entry scene is the regular 2017 Town Square, not the snowy master revision;
        // its verified spawn stays the documented arrival point instead of the closest sample to the origin.
        new Pack(1,"haven","Legacy/BukkitBoss/2017/1","town","26f15faf59dcf711e66bf0494d64fda7da6bd95ffffa5934a659a41a398d8c57",0f,-8f),
        new Pack(2,"bazaar","Master/Static/2","town","e0410e91851f3367bdd7283683ee4d45f6abf0e61d63f0e01bde5d10f5012033",null,null),
        new Pack(3,"arcade","Master/Static/3","town","dff99bfbbba7d37cf5585746e8883e3f6c09cfadec7ffe39ac19327a42b22b9f",null,null),
        new Pack(445,"garrison","Master/Static/445","town","a1e3e970b31c59d2aa661ed6bd836fe578cb08bf46e64533febec8566286b9a1",null,null),
        new Pack(294,"advanced_training","Master/Static/294","training","d0cf0863721970d15090f613d217495ceaa18fbf125813ff9d105b16966f2619",null,null),
        new Pack(437,"laboratory","Master/Static/437","level","5f47c41368b5771f1571de791fe30fae1edddedbdeb74dda4a4d9affa0ea67e0",null,null),
        new Pack(449,"hall_of_heroes","Master/Static/449","level","fa15e1f1c6b538794bb4b6e560157cdfb7c98b0574beebd2633f223695ba40b6",null,null));
    private static final Set<Integer> TOWNS=Set.of(1,2,3,445);
    private static final String STOCK_DEFAULT="stock_default";
    private static final List<Route> INTERIOR_ROUTES=List.of(
        new Route(1,294,"level_start","Portals/To Advanced Training"),
        new Route(445,437,"level_start","Portals/To Laboratory"),
        new Route(445,449,"level_start","Portals/To Hall of Heroes"),
        new Route(294,1,"from_advanced_training","Portals/Leave Party"),
        new Route(437,445,"from_lab","Portals/Town Portal"),
        new Route(449,445,"from_hoh","Portals/Town Portal"));
    private static final String FORMAT="1";

    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException(
            "Usage: ArchiveTownExport game-directory archive-root fresh-maps-output-directory");
        Path project=Path.of("").toAbsolutePath().normalize();
        Path game=Path.of(args[0]).toAbsolutePath().normalize();
        Path archive=archiveRoot(Path.of(args[1]).toAbsolutePath().normalize());
        Path output=Path.of(args[2]).toAbsolutePath().normalize();
        if(!Files.isRegularFile(game.resolve("code/projectx-pcode.jar")))throw new IOException("Missing installed stock game JAR");
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("Output exists; choose a fresh maps directory");

        var sourceFiles=new LinkedHashMap<Pack,Path>();
        for(Pack pack:PACKS) {
            Path source=archive.resolve(pack.path).normalize();
            if(!source.startsWith(archive)||Files.isSymbolicLink(source)||!Files.isRegularFile(source))
                throw new IOException("Missing or unsafe pinned archive scene: "+pack.path);
            String actual=digest(source);
            if(!pack.digest.equals(actual))throw new IOException("Pinned archive digest mismatch for "+pack.path+": "+actual);
            sourceFiles.put(pack,source);
        }

        Files.createDirectories(output.getParent());
        Path staging=Files.createTempDirectory(output.getParent(),".archive-haven-");
        boolean published=false;
        try {
            for(Pack pack:PACKS) {
                Path folder=staging.resolve(pack.folder);
                ScenePackExport.main(sceneArgs(game,sourceFiles.get(pack),folder,pack));
                Properties descriptor=properties(folder.resolve("map.properties"));
                if(Integer.parseInt(descriptor.getProperty("scene-id"))!=pack.id
                    ||!pack.kind.equals(descriptor.getProperty("kind")))
                    throw new IOException("Stock exporter returned the wrong scene identity/config for "+pack.id);
            }
            Properties catalog=new Properties();catalog.setProperty("entry-scene","1");
            writeProperties(staging.resolve("catalog.properties"),catalog);

            ConfigManager manager=manager(game);
            var folders=new HashMap<Integer,Path>();var scenes=new HashMap<Integer,TudeySceneModel>();
            for(Pack pack:PACKS) {
                Path folder=staging.resolve(pack.folder);folders.put(pack.id,folder);
                scenes.put(pack.id,readScene(folder,manager,pack.id));
            }
            var arrivals=exportTownPortals(folders,scenes,manager);
            exportInteriorRoutes(folders,scenes,manager,arrivals);
            validateNetwork(folders);
            verifyBundledTowns(project,folders);
            verifyKnownPortalMetadata(project,folders);

            Properties manifest=sourceManifest(project,archive,sourceFiles,staging);
            writeProperties(staging.resolve("scene-source.properties"),manifest);
            Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);published=true;
            for(Pack pack:PACKS) {
                Properties p=properties(output.resolve(pack.folder).resolve("map.properties"));
                Properties portals=properties(output.resolve(pack.folder).resolve("portals.properties"));
                System.out.printf("EXPORTED scene=%d kind=%s version=%s scene-sha256=%s entrances=%d triggers=%d%n",
                    pack.id,p.getProperty("kind"),p.getProperty("version"),p.getProperty("scene-sha256"),
                    csv(portals.getProperty("entrances","")).size(),csv(portals.getProperty("triggers","")).size());
            }
            System.out.println("PASS: exported seven pinned Haven scenes and native portal links to "+output);
        } finally { if(!published)deleteTree(staging); }
    }

    private static Path archiveRoot(Path input)throws IOException {
        for(Path candidate:List.of(input,input.resolve("vendor/scene-archive")))
            if(Files.isDirectory(candidate.resolve("Master/Static"),LinkOption.NOFOLLOW_LINKS))return candidate.toRealPath();
        throw new IOException("Archive root must contain Master/Static or vendor/scene-archive/Master/Static");
    }

    private static ConfigManager manager(Path game)throws Exception {
        Object resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),
            com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");
        manager.init();return manager;
    }

    private static TudeySceneModel readScene(Path folder,ConfigManager manager,int expectedId)throws Exception {
        Properties p=properties(folder.resolve("map.properties"));Path layout=folder.resolve("scene.dat");
        if(!p.getProperty("scene-sha256").equals(digest(layout)))throw new IOException("Scene digest mismatch: "+folder);
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(Files.readAllBytes(layout));scene.init(manager);
        if(scene.sceneId!=expectedId)throw new IOException("Wrong scene ID "+scene.sceneId+" in "+folder);
        return scene;
    }

    private static Map<Integer,TownArrivals> exportTownPortals(Map<Integer,Path> folders,Map<Integer,TudeySceneModel> scenes,
                                                               ConfigManager manager)throws Exception {
        var markerTags=new HashMap<Integer,TownArrivals>();
        for(int id:TOWNS)markerTags.put(id,destinationMarkers(scenes.get(id),manager,folders.get(id)));
        var required=new HashMap<Integer,Map<String,Entrance>>();
        for(int id:TOWNS)required.put(id,new TreeMap<>());
        var nativePortals=new HashMap<Integer,List<NativePortal>>();
        for(int source:TOWNS) {
            var discovered=new ArrayList<NativePortal>();
            for(var entry:scenes.get(source).ci(TudeySceneModel.AreaEntry.class)) {
                if(!entry.area.getName().equals("Portals/Portal"))continue;
                int id=((Number)entry.getKey()).intValue();var ref=entry.getReference();Map<String,Object> args=ref.kW();
                int destination=number(args.get("Scene ID"),"Scene ID",source,id);
                if(!TOWNS.contains(destination))continue;
                Object tagged=args.get("Portal");
                if(!(tagged instanceof ActionConfig.TaggedPortal portal)||portal.tag==null||portal.tag.isBlank())
                    throw new IOException("Native portal has no tagged entrance: scene="+source+" area="+id);
                Arrival arrival=resolveArrival(destination,portal.tag,markerTags.get(destination));
                addEntrance(required.get(destination),arrival.tag,arrival.entrance,destination);
                verifyIntersection(entry,manager,id);
                List<Point> vertices=points(entry.vertices,id);validateConvex(vertices,id);
                discovered.add(new NativePortal(id,destination,arrival.tag,vertices));
                if(!arrival.tag.equals(portal.tag))
                    System.out.printf("FALLBACK scene=%d area=%d native-tag=%s -> unique stock default entrance %d%n",
                        source,id,portal.tag,arrival.entrance.id);
            }
            nativePortals.put(source,discovered);
        }
        for(int source:TOWNS) {
            Path folder=folders.get(source);Properties map=properties(folder.resolve("map.properties"));
            Properties metadata=new Properties();metadata.setProperty("format","2");
            metadata.setProperty("layout-sha256",map.getProperty("scene-sha256"));
            var triggers=new ArrayList<String>();
            for(NativePortal portal:nativePortals.get(source)) {
                triggers.add(Integer.toString(portal.id));String prefix="trigger."+portal.id+".";
                metadata.setProperty(prefix+"scene",Integer.toString(portal.destination));
                metadata.setProperty(prefix+"entrance",portal.tag);metadata.setProperty(prefix+"shape","polygon");
                metadata.setProperty(prefix+"vertices",vertices(portal.vertices));
                System.out.printf("PORTAL scene=%d area=%d -> scene=%d entrance=%s polygon=%s%n",
                    source,portal.id,portal.destination,portal.tag,vertices(portal.vertices));
            }
            metadata.setProperty("triggers",String.join(",",triggers));
            var entrances=new TreeSet<>(required.get(source).keySet());
            metadata.setProperty("entrances",String.join(",",entrances));
            for(var entry:required.get(source).entrySet()) {
                Entrance p=entry.getValue();String prefix="entrance."+entry.getKey()+".";
                metadata.setProperty(prefix+"id",Integer.toString(p.id));metadata.setProperty(prefix+"x",Float.toString(p.x));
                metadata.setProperty(prefix+"y",Float.toString(p.y));metadata.setProperty(prefix+"rotation",Float.toString(p.rotation));
            }
            writeProperties(folder.resolve("portals.properties"),metadata);
        }
        int links=nativePortals.values().stream().mapToInt(List::size).sum();
        if(links!=10)throw new IOException("Expected ten directed native Haven town links, found "+links);
        return Map.copyOf(markerTags);
    }

    private static TownArrivals destinationMarkers(TudeySceneModel scene,ConfigManager manager,Path folder)throws Exception {
        var result=new TreeMap<String,Entrance>();var defaults=new ArrayList<Entrance>();
        CollisionMap collision=CollisionMap.read(folder.resolve("walkability.gz"));
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)) {
            var config=entry.placeable.a(manager,PlaceableConfig.class).q(manager);
            if(!(config instanceof PlaceableConfig.Marker marker))
                continue;
            if(marker.defaultEntrance) {
                Vector3f defaultPosition=entry.transform.tD();Quaternion defaultRotation=entry.transform.tE();
                float defaultYaw=(float)Math.atan2(2f*(defaultRotation.w*defaultRotation.z+defaultRotation.x*defaultRotation.y),
                    1f-2f*(defaultRotation.y*defaultRotation.y+defaultRotation.z*defaultRotation.z));
                Entrance fallback=new Entrance(((Number)entry.getKey()).intValue(),defaultPosition.x,defaultPosition.y,defaultYaw);
                if(!Float.isFinite(defaultYaw)||!collision.walkable(fallback.x,fallback.y))
                    throw new IOException("Native town default entrance is not walkable: scene="+scene.sceneId+" entry="+fallback.id);
                defaults.add(fallback);
            }
            if(!entry.placeable.getName().equals("Dynamic/Portal/Destination"))continue;
            Object raw=entry.placeable.kW().get("Tag");String tag=raw instanceof String value?value:"";
            Vector3f position=entry.transform.tD();Quaternion rotation=entry.transform.tE();
            float yaw=(float)Math.atan2(2f*(rotation.w*rotation.z+rotation.x*rotation.y),
                1f-2f*(rotation.y*rotation.y+rotation.z*rotation.z));
            Entrance entrance=new Entrance(((Number)entry.getKey()).intValue(),position.x,position.y,yaw);
            if(!Float.isFinite(yaw)||!collision.walkable(entrance.x,entrance.y))
                throw new IOException("Town destination marker is not walkable: scene="+scene.sceneId+" tag="+tag);
            if(!marker.defaultEntrance&&tag.isBlank())throw new IOException("Native non-default destination has no tag: scene="+scene.sceneId+" entry="+entrance.id);
            else if(!marker.defaultEntrance&&result.putIfAbsent(tag,entrance)!=null)throw new IOException("Duplicate town destination tag: scene="+scene.sceneId+" tag="+tag);
        }
        if(result.isEmpty()&&defaults.isEmpty())throw new IOException("Town scene has no native destination markers: "+scene.sceneId);
        return new TownArrivals(Map.copyOf(result),List.copyOf(defaults));
    }

    private static void exportInteriorRoutes(Map<Integer,Path> folders,Map<Integer,TudeySceneModel> scenes,
                                             ConfigManager manager,Map<Integer,TownArrivals> arrivals)throws Exception {
        for(Route route:INTERIOR_ROUTES) {
            var scene=scenes.get(route.source);var matches=new ArrayList<TudeySceneModel.AreaEntry>();
            for(var area:scene.ci(TudeySceneModel.AreaEntry.class))
                if(area.area.getName().equals(route.areaName))matches.add(area);
            if(matches.size()!=1)throw new IOException("Expected one native area "+route.areaName+" in scene "+route.source+", found "+matches.size());
            var area=matches.getFirst();int areaId=((Number)area.getKey()).intValue();
            verifyIntersection(area,manager,areaId);List<Point> vertices=points(area.vertices,areaId);validateConvex(vertices,areaId);
            Path sourceFolder=folders.get(route.source),targetFolder=folders.get(route.destination);
            Properties targetPortals=properties(targetFolder.resolve("portals.properties"));
            String targetTag=route.tag;
            if(TOWNS.contains(route.destination)) {
                Arrival resolved=resolveArrival(route.destination,route.tag,arrivals.get(route.destination));
                targetTag=resolved.tag;addTownEntrance(targetFolder,targetTag,resolved.entrance,route.destination);
                targetPortals=properties(targetFolder.resolve("portals.properties"));
            } else if(!csv(targetPortals.getProperty("entrances","")).contains(targetTag))
                throw new IOException("Interior route has no native destination entrance: "+route.destination+"/"+targetTag);
            Properties sourcePortals=properties(sourceFolder.resolve("portals.properties"));
            var ids=csv(sourcePortals.getProperty("triggers",""));
            if(!ids.add(Integer.toString(areaId)))throw new IOException("Duplicate native route area: "+route.source+"/"+areaId);
            sourcePortals.setProperty("triggers",String.join(",",ids));sourcePortals.setProperty("format","2");
            sourcePortals.setProperty("layout-sha256",properties(sourceFolder.resolve("map.properties")).getProperty("scene-sha256"));
            String prefix="trigger."+areaId+".";
            sourcePortals.setProperty(prefix+"scene",Integer.toString(route.destination));
            sourcePortals.setProperty(prefix+"entrance",targetTag);sourcePortals.setProperty(prefix+"shape","polygon");
            sourcePortals.setProperty(prefix+"vertices",vertices(vertices));writeProperties(sourceFolder.resolve("portals.properties"),sourcePortals);
            System.out.printf("INTERIOR scene=%d area=%d name=%s -> scene=%d entrance=%s%n",
                route.source,areaId,route.areaName,route.destination,targetTag);
        }
    }

    private static Arrival resolveArrival(int scene,String tag,TownArrivals arrivals)throws IOException {
        Entrance exact=arrivals.tagged.get(tag);if(exact!=null)return new Arrival(tag,exact);
        if(arrivals.defaults.size()!=1)
            throw new IOException("Native portal tag has no marker and stock default entrance is not unique: scene="+scene+
                " tag="+tag+" markers="+arrivals.tagged.keySet()+" defaults="+arrivals.defaults.size());
        if(arrivals.tagged.containsKey(STOCK_DEFAULT))throw new IOException("Reserved fallback tag collides with a native marker in scene "+scene);
        return new Arrival(STOCK_DEFAULT,arrivals.defaults.getFirst());
    }

    private static void addEntrance(Map<String,Entrance> entrances,String tag,Entrance entrance,int scene)throws IOException {
        Entrance old=entrances.putIfAbsent(tag,entrance);
        if(old!=null&&!old.equals(entrance))throw new IOException("Conflicting entrance for scene="+scene+" tag="+tag);
    }

    private static void addTownEntrance(Path folder,String tag,Entrance entrance,int scene)throws Exception {
        Properties p=properties(folder.resolve("portals.properties"));var names=new TreeSet<>(csv(p.getProperty("entrances","")));
        String prefix="entrance."+tag+".";
        if(names.contains(tag)) {
            if(Integer.parseInt(p.getProperty(prefix+"id"))!=entrance.id
                ||Float.parseFloat(p.getProperty(prefix+"x"))!=entrance.x||Float.parseFloat(p.getProperty(prefix+"y"))!=entrance.y
                ||Float.parseFloat(p.getProperty(prefix+"rotation"))!=entrance.rotation)
                throw new IOException("Conflicting native town entrance: scene="+scene+" tag="+tag);
            return;
        }
        for(String old:names)if(Integer.parseInt(p.getProperty("entrance."+old+".id"))==entrance.id)
            throw new IOException("One native destination marker would have multiple metadata tags in scene "+scene);
        names.add(tag);p.setProperty("entrances",String.join(",",names));p.setProperty(prefix+"id",Integer.toString(entrance.id));
        p.setProperty(prefix+"x",Float.toString(entrance.x));p.setProperty(prefix+"y",Float.toString(entrance.y));
        p.setProperty(prefix+"rotation",Float.toString(entrance.rotation));writeProperties(folder.resolve("portals.properties"),p);
    }

    private static void verifyIntersection(TudeySceneModel.AreaEntry area,ConfigManager manager,int id)throws Exception {
        AreaConfig.Original config=area.aq(manager);
        if(config.handlers==null)throw new IOException("Native portal area has no handlers: "+id);
        var start=Arrays.stream(config.handlers).filter(h->h instanceof HandlerConfig.IntersectionStart)
            .map(h->(HandlerConfig.IntersectionStart)h).findFirst().orElseThrow(()->new IOException("Native area lacks IntersectionStart: "+id));
        if(start.mask!=65535||!(start.shape instanceof HandlerConfig.DefaultShape shape)||shape.expansion!=0f)
            throw new IOException("Native portal intersection sensor differs from verified Haven shape/mask: "+id);
    }

    private static List<Point> points(TudeySceneModel.Vertex[] vertices,int id)throws IOException {
        if(vertices==null||vertices.length<3||vertices.length>32)throw new IOException("Unsupported portal polygon: "+id);
        return Arrays.stream(vertices).map(v->new Point(v.x,v.y)).toList();
    }

    private static void validateConvex(List<Point> points,int id)throws IOException {
        for(int i=0;i<points.size();i++)for(int j=i+1;j<points.size();j++)
            if(points.get(i).equals(points.get(j)))throw new IOException("Duplicate native portal vertex: "+id);
        float side=0;
        for(int i=0;i<points.size();i++) {
            Point a=points.get(i),b=points.get((i+1)%points.size());
            for(int j=0;j<points.size();j++) {
                if(j==i||j==(i+1)%points.size())continue;
                Point c=points.get(j);float cross=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
                if(cross==0)throw new IOException("Collinear native portal vertices: "+id);
                if(side==0)side=Math.signum(cross);else if(Math.signum(cross)!=side)throw new IOException("Non-convex native portal: "+id);
            }
        }
    }

    private static void validateNetwork(Map<Integer,Path> folders)throws Exception {
        var byId=new HashMap<Integer,Path>();
        for(Path folder:folders.values()) {
            Properties map=properties(folder.resolve("map.properties"));int id=Integer.parseInt(map.getProperty("scene-id"));
            if(byId.putIfAbsent(id,folder)!=null)throw new IOException("Duplicate scene id: "+id);
            if(!map.getProperty("scene-sha256").equals(digest(folder.resolve("scene.dat")))
                ||!map.getProperty("collision-sha256").equals(digest(folder.resolve("walkability.gz"))))
                throw new IOException("Map pack digest mismatch: "+folder);
        }
        var portals=new HashMap<Integer,ScenePortals>();
        for(var entry:byId.entrySet()) {
            Path folder=entry.getValue();Properties map=properties(folder.resolve("map.properties"));
            CollisionMap collision=CollisionMap.read(folder.resolve("walkability.gz"));
            portals.put(entry.getKey(),ScenePortals.load(folder,map.getProperty("scene-sha256"),collision));
        }
        for(var entry:portals.entrySet())for(var trigger:entry.getValue().triggers()) {
            ScenePortals destination=portals.get(trigger.destination());
            if(destination==null||!destination.entrances().containsKey(trigger.entrance()))
                throw new IOException("Broken portal destination "+entry.getKey()+"/"+trigger.id());
            var arrival=destination.entrances().get(trigger.entrance());
            if(destination.triggers().stream().anyMatch(other->other.intersects(arrival.x(),arrival.y())))
                throw new IOException("Portal arrival immediately intersects another trigger: scene="+trigger.destination()+" tag="+trigger.entrance());
        }
    }

    private static Properties sourceManifest(Path project,Path archive,Map<Pack,Path> sources,Path staging)throws Exception {
        Properties p=new Properties();p.setProperty("format",FORMAT);
        var existing=project.resolve("local/defaults/archive-maps/scene-source.properties");
        if(Files.notExists(existing,LinkOption.NOFOLLOW_LINKS))existing=project.resolve("local/defaults/maps/scene-source.properties");
        if(Files.isRegularFile(existing,LinkOption.NOFOLLOW_LINKS)) {
            Properties old=properties(existing);
            for(String key:old.stringPropertyNames())if(key.startsWith("owned."))p.setProperty(key,old.getProperty(key));
            for(Pack pack:PACKS)for(String file:List.of("map.properties","scene.dat","walkability.gz","portals.properties")) {
                String rel=pack.folder+"/"+file;String previous=old.getProperty("source."+rel);
                if(previous!=null)addOwned(p,rel,previous);
            }
            addOwned(p,"scene-source.properties",digest(existing));
        }
        Path bundled=project.resolve("defaults/maps");
        Path previousLocal=project.resolve("local/defaults/maps");
        for(Pack pack:PACKS)for(String file:List.of("map.properties","scene.dat","walkability.gz","portals.properties")) {
            String rel=pack.folder+"/"+file;Path original=bundled.resolve(rel);
            if(Files.isRegularFile(original))addOwned(p,rel,digest(original));
            Path previous=previousLocal.resolve(rel);
            if(Files.isRegularFile(previous,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(previous))addOwned(p,rel,digest(previous));
            Path current=staging.resolve(rel);p.setProperty("source."+rel,digest(current));
        }
        for(Pack pack:PACKS) {
            String key="archive."+pack.id;
            p.setProperty(key+".path",archive.relativize(sources.get(pack)).toString().replace('\\','/'));
            p.setProperty(key+".sha256",pack.digest);
        }
        return p;
    }
    private static void addOwned(Properties properties,String relative,String digest) {
        String key="owned."+relative;String current=properties.getProperty(key);
        var values=new TreeSet<String>();if(current!=null&&!current.isBlank())values.addAll(Arrays.asList(current.split(",")));
        values.add(digest);properties.setProperty(key,String.join(",",values));
    }

    private static String[] sceneArgs(Path game,Path archived,Path folder,Pack pack) {
        var args=new ArrayList<>(List.of(game.toString(),archived.toString(),folder.toString(),"1","Haven"));
        if(pack.spawnX()!=null) {
            args.add(Float.toString(pack.spawnX()));
            args.add(Float.toString(pack.spawnY()));
        }
        return args.toArray(String[]::new);
    }

    /** A prepared source must reproduce every bundled town pack, or a rebuild would silently replace its geometry. */
    private static void verifyBundledTowns(Path project,Map<Integer,Path> folders) throws Exception {
        Path bundled=project.resolve("defaults/maps");
        for(Pack pack:PACKS) {
            Path reference=bundled.resolve(pack.folder);
            if(!Files.isRegularFile(reference.resolve("map.properties"),LinkOption.NOFOLLOW_LINKS))continue;
            Properties prepared=properties(folders.get(pack.id).resolve("map.properties"));
            Properties expected=properties(reference.resolve("map.properties"));
            for(String key:List.of("kind","scene-id","zone-id","zone-name","name","image","instances",
                "version","scene-sha256","collision-sha256"))
                if(!Objects.equals(expected.getProperty(key),prepared.getProperty(key)))
                    throw new IOException("Prepared "+pack.folder+" map differs from the bundled pack: "+key+" is "
                        +prepared.getProperty(key)+", expected "+expected.getProperty(key));
            for(String key:List.of("spawn-x","spawn-y"))
                if(Float.parseFloat(expected.getProperty(key))!=Float.parseFloat(prepared.getProperty(key)))
                    throw new IOException("Prepared "+pack.folder+" map differs from the bundled pack: "+key+" is "
                        +prepared.getProperty(key)+", expected "+expected.getProperty(key));
            if(!digest(folders.get(pack.id).resolve("scene.dat")).equals(digest(reference.resolve("scene.dat"))))
                throw new IOException("Prepared "+pack.folder+" scene export differs from the bundled map");
        }
    }

    private static void verifyKnownPortalMetadata(Path project,Map<Integer,Path> folders)throws Exception {
        for(Pack pack:PACKS)if(TOWNS.contains(pack.id)) {
            Path old=project.resolve("defaults/maps").resolve(pack.folder);
            if(!Files.isRegularFile(old.resolve("map.properties"))||!Files.isRegularFile(old.resolve("portals.properties")))continue;
            Properties currentMap=properties(folders.get(pack.id).resolve("map.properties"));
            Properties oldMap=properties(old.resolve("map.properties"));
            if(!currentMap.getProperty("scene-sha256").equals(oldMap.getProperty("scene-sha256")))continue;
            Properties actual=properties(folders.get(pack.id).resolve("portals.properties"));
            Properties expected=properties(old.resolve("portals.properties"));
            for(String key:expected.stringPropertyNames()) {
                if(key.equals("entrances")) {
                    // Arrival names and coordinates are re-derived from the current archive revision below.
                } else if(key.equals("triggers")) {
                    if(!new TreeSet<>(csv(actual.getProperty(key))).containsAll(csv(expected.getProperty(key))))
                        throw new IOException("Extracted native portal list changed for matching archive scene "+pack.id+": "+key);
                } else if(key.startsWith("entrance.")||key.matches("trigger\\.\\d+\\.entrance")) {
                    // Legacy arrival aliases and coordinates can describe pre-archive markers. Native stock
                    // resolution falls back to the unique default entrance when that exact tag is absent.
                } else if(key.matches("trigger\\.\\d+\\.vertices")) {
                    if(!samePolygon(expected.getProperty(key),actual.getProperty(key)))
                        throw new IOException("Extracted native portal geometry changed for matching archive scene "+pack.id+": "+key);
                } else if(!Objects.equals(expected.getProperty(key),actual.getProperty(key)))
                    throw new IOException("Extracted native portal metadata changed for matching archive scene "+pack.id+": "+key);
            }
        }
    }

    private static boolean samePolygon(String first,String second) {
        if(first==null||second==null)return false;
        String[] a=first.split(";"),b=second.split(";");if(a.length!=b.length)return false;
        for(int start=0;start<b.length;start++)for(int direction:new int[]{1,-1}) {
            boolean same=true;
            for(int i=0;i<a.length;i++) {
                String[] ap=a[i].split(","),bp=b[Math.floorMod(start+direction*i,b.length)].split(",");
                if(ap.length!=2||bp.length!=2||Float.parseFloat(ap[0])!=Float.parseFloat(bp[0])
                    ||Float.parseFloat(ap[1])!=Float.parseFloat(bp[1])) {same=false;break;}
            }
            if(same)return true;
        }
        return false;
    }

    private static int number(Object value,String name,int scene,int area)throws IOException {
        if(value instanceof Number number)return number.intValue();
        throw new IOException("Native portal lacks numeric "+name+": scene="+scene+" area="+area);
    }
    private static String vertices(List<Point> points) {
        return String.join(";",points.stream().map(p->Float.toString(p.x)+","+Float.toString(p.y)).toList());
    }
    private static LinkedHashSet<String> csv(String value) {
        var result=new LinkedHashSet<String>();if(value!=null&&!value.isBlank())result.addAll(Arrays.asList(value.split(",")));return result;
    }
    private static Properties properties(Path path)throws IOException {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new IOException("Missing or unsafe properties: "+path);
        var p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);}return p;
    }
    private static void writeProperties(Path path,Properties values)throws IOException {
        var keys=new ArrayList<>(values.stringPropertyNames());Collections.sort(keys);var lines=new ArrayList<String>();
        for(String key:keys)lines.add(key+"="+values.getProperty(key));
        Files.writeString(path,String.join("\n",lines)+"\n");
    }
    private static String digest(Path file)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)) {
            byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void deleteTree(Path root)throws IOException {
        if(Files.notExists(root))return;try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
    }
}
