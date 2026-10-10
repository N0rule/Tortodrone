import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPInputStream;

import com.threerings.config.*;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.*;

/** Exports pinned Haven training/laboratory/Heroes layouts and links their native area IDs. */
public final class HavenInteriorsExport {
    private static final String ARCHIVE_COMMIT="ae49faf06788dd83b35a959306c9fe4c25688004";
    private static final String ARCHIVE_BASE="https://raw.githubusercontent.com/reference-source/SpiralKnightsSceneArchive/"+ARCHIVE_COMMIT+"/Master/Static/";
    private record Pack(int id,String directory,String kind,String digest) { }
    private record AreaRoute(int sceneId,int areaId,String areaName,int destination,String entrance) { }
    private record Marker(int sceneId,int id,String tag) { }
    private record Point(float x,float y) { }
    private record Entrance(int id,float x,float y,float rotation) { }
    private static final List<Pack> INTERIORS=List.of(
        new Pack(294,"advanced_training","training","d0cf0863721970d15090f613d217495ceaa18fbf125813ff9d105b16966f2619"),
        new Pack(437,"laboratory","level","5f47c41368b5771f1571de791fe30fae1edddedbdeb74dda4a4d9affa0ea67e0"),
        new Pack(449,"hall_of_heroes","level","fa15e1f1c6b538794bb4b6e560157cdfb7c98b0574beebd2633f223695ba40b6"));
    private static final List<AreaRoute> TOWN_ENTRIES=List.of(
        new AreaRoute(1,3922,"Portals/To Advanced Training",294,"level_start"),
        new AreaRoute(445,3770,"Portals/To Laboratory",437,"level_start"),
        new AreaRoute(445,3568,"Portals/To Hall of Heroes",449,"level_start"));
    private static final List<AreaRoute> INTERIOR_EXITS=List.of(
        new AreaRoute(294,1526,"Portals/Leave Party",1,"from_advanced_training"),
        new AreaRoute(437,945,"Portals/Town Portal",445,"from_lab"),
        new AreaRoute(449,2812,"Portals/Town Portal",445,"from_hoh"));
    private static final List<Marker> RETURN_MARKERS=List.of(
        new Marker(1,3930,"from_advanced_training"),
        new Marker(445,3769,"from_lab"),
        new Marker(445,3575,"from_hoh"));

    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException(
            "Usage: HavenInteriorsExport game-directory source-cache-directory fresh-maps-output-directory");
        Path project=Path.of("").toAbsolutePath(),game=Path.of(args[0]).toAbsolutePath();
        Path cache=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        if(!Files.isRegularFile(game.resolve("code/projectx-pcode.jar")))throw new IOException("Missing installed stock game JAR");
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("Output exists; choose a fresh maps directory");
        Path defaults=project.resolve("defaults/maps");
        verifyTown(defaults.resolve("haven"),1);
        verifyTown(defaults.resolve("bazaar"),2);
        verifyTown(defaults.resolve("arcade"),3);
        verifyTown(defaults.resolve("garrison"),445);
        var sources=new HashMap<Integer,Path>();
        for(Pack pack:INTERIORS)sources.put(pack.id,fetch(pack,cache));

        Files.createDirectories(output.getParent());
        Path staging=Files.createTempDirectory(output.getParent(),".haven-interiors-");
        boolean published=false;
        try {
            copyTree(defaults,staging);
            var generated=new HashMap<Integer,Path>();
            for(Pack pack:INTERIORS) {
                Path folder=staging.resolve(pack.directory);
                ScenePackExport.main(new String[]{game.toString(),sources.get(pack.id).toString(),folder.toString(),"1","Haven"});
                Properties map=properties(folder.resolve("map.properties"));
                if(Integer.parseInt(map.getProperty("scene-id"))!=pack.id||!pack.kind.equals(map.getProperty("kind")))
                    throw new IOException("Stock exporter returned the wrong scene identity for "+pack.id);
                generated.put(pack.id,folder);
            }

            Object resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
            resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
            var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),
                com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");
            manager.init();

            var mapFolders=new HashMap<Integer,Path>();
            mapFolders.put(1,staging.resolve("haven"));mapFolders.put(2,staging.resolve("bazaar"));
            mapFolders.put(3,staging.resolve("arcade"));mapFolders.put(445,staging.resolve("garrison"));
            mapFolders.putAll(generated);
            var scenes=new HashMap<Integer,TudeySceneModel>();
            for(var entry:mapFolders.entrySet())scenes.put(entry.getKey(),readScene(entry.getValue(),manager,entry.getKey()));

            for(Marker marker:RETURN_MARKERS) {
                var scene=scenes.get(marker.sceneId);
                var place=findPlace(scene,marker.id,"Dynamic/Portal/Destination");
                Object tag=place.placeable.kW().get("Tag");
                if(!marker.tag.equals(tag))throw new IOException("Native destination tag differs at "+marker.sceneId+"/"+marker.id+": "+tag);
                PlaceableConfig.Original original=place.placeable.a(manager,PlaceableConfig.class).q(manager);
                if(!(original instanceof PlaceableConfig.Marker nativeMarker)||nativeMarker.defaultEntrance)
                    throw new IOException("Return target is not a non-default stock Destination marker: "+marker.sceneId+"/"+marker.id);
                var point=place.transform.tD();
                var q=place.transform.tE();
                var entrance=new Entrance(marker.id,point.x,point.y,(float)Math.atan2(2f*(q.w*q.z+q.x*q.y),
                    1f-2f*(q.y*q.y+q.z*q.z)));
                if(!walkable(mapFolders.get(marker.sceneId).resolve("walkability.gz"),entrance.x,entrance.y))
                    throw new IOException("Native return marker is not walkable in the prepared map: "+marker.sceneId+"/"+marker.id);
                addEntrance(mapFolders.get(marker.sceneId),marker.tag,entrance);
                System.out.printf("RETURN scene=%d marker=%d tag=%s position=%.6f,%.6f yaw=%.7f%n",
                    marker.sceneId,marker.id,marker.tag,entrance.x,entrance.y,entrance.rotation);
            }
            for(AreaRoute route:TOWN_ENTRIES)addTrigger(route,mapFolders,scenes,manager);
            for(AreaRoute route:INTERIOR_EXITS)addTrigger(route,mapFolders,scenes,manager);
            validateNetwork(mapFolders);
            Path catalog=staging.resolve("catalog.properties");
            if(!properties(catalog).getProperty("entry-scene","1").equals("1"))
                throw new IOException("Copied map catalog has an unexpected entry scene");
            Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);
            published=true;
            for(Pack pack:INTERIORS) {
                Properties p=properties(output.resolve(pack.directory).resolve("map.properties"));
                System.out.printf("EXPORTED scene=%d kind=%s name=%s version=%s layout=%s collision=%s%n",
                    pack.id,p.getProperty("kind"),p.getProperty("name"),p.getProperty("version"),
                    p.getProperty("scene-sha256"),p.getProperty("collision-sha256"));
            }
            System.out.println("PASS: copied four town packs plus three pinned interiors; native entries 3922/3770/3568 and exits 1526/945/2812 are linked to stock Destination tags");
        } finally {
            if(!published)deleteTree(staging);
        }
    }

    private static Path fetch(Pack pack,Path cache)throws Exception {
        Files.createDirectories(cache);
        Path target=cache.resolve(Integer.toString(pack.id));
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target)||Files.size(target)==0||Files.size(target)>900_000
                ||!pack.digest.equals(digest(target)))throw new IOException("Pinned source cache checksum mismatch: "+target);
            return target;
        }
        URI uri=URI.create(ARCHIVE_BASE+pack.id);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        IOException failure=null;
        try {
            for(int attempt=1;attempt<=3;attempt++) {
                try {
                    var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
                    if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode()+" for "+uri);
                    byte[] bytes=response.body();
                    if(bytes.length==0||bytes.length>900_000)throw new IOException("Unexpected archive size for scene "+pack.id);
                    if(!pack.digest.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                        throw new IOException("Pinned archive checksum mismatch for scene "+pack.id);
                    Path temporary=Files.createTempFile(cache,"scene-"+pack.id+"-",".download");
                    try {Files.write(temporary,bytes);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
                    finally {Files.deleteIfExists(temporary);}
                    return target;
                } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Archive download interrupted",e);}
                catch(IOException e) {failure=e;if(attempt<3)Thread.sleep(500L*attempt);}
            }
        } finally {client.close();}
        throw new IOException("Could not fetch pinned scene "+pack.id+" from "+uri,failure);
    }

    private static void verifyTown(Path folder,int id)throws Exception {
        if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder))throw new IOException("Missing stock town pack: "+folder);
        Properties map=properties(folder.resolve("map.properties"));
        if(!"town".equals(map.getProperty("kind"))||Integer.parseInt(map.getProperty("scene-id"))!=id
            ||!map.getProperty("scene-sha256").equals(digest(folder.resolve("scene.dat"))))
            throw new IOException("Existing town pack is not the expected verified source: "+folder);
    }

    private static TudeySceneModel readScene(Path folder,ConfigManager manager,int expectedId)throws Exception {
        Properties p=properties(folder.resolve("map.properties"));
        byte[] bytes=Files.readAllBytes(folder.resolve("scene.dat"));
        if(!p.getProperty("scene-sha256").equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IOException("Scene digest mismatch: "+folder);
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(manager);
        if(scene.sceneId!=expectedId)throw new IOException("Wrong scene ID "+scene.sceneId+" in "+folder);
        return scene;
    }

    private static TudeySceneModel.PlaceableEntry findPlace(TudeySceneModel scene,int id,String name)throws IOException {
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class))
            if(((Number)entry.getKey()).intValue()==id&&entry.placeable.getName().equals(name))return entry;
        throw new IOException("Missing native placeable "+name+" id="+id+" scene="+scene.sceneId);
    }

    private static void addTrigger(AreaRoute route,Map<Integer,Path> folders,Map<Integer,TudeySceneModel> scenes,
                                   ConfigManager manager)throws Exception {
        var scene=scenes.get(route.sceneId);
        TudeySceneModel.AreaEntry area=null;
        for(var candidate:scene.ci(TudeySceneModel.AreaEntry.class))
            if(((Number)candidate.getKey()).intValue()==route.areaId) {area=candidate;break;}
        if(area==null||!area.area.getName().equals(route.areaName))
            throw new IOException("Native area identity mismatch for "+route.sceneId+"/"+route.areaId);
        AreaConfig.Original config=area.aq(manager);
        if(config.handlers==null||Arrays.stream(config.handlers).noneMatch(h->h instanceof HandlerConfig.IntersectionStart))
            throw new IOException("Native area has no IntersectionStart handler: "+route.sceneId+"/"+route.areaId);
        if(area.vertices.length<3||area.vertices.length>32)throw new IOException("Unsupported native exit polygon: "+route.areaId);
        var points=Arrays.stream(area.vertices).map(v->new Point(v.x,v.y)).toList();
        validateConvex(points,route.areaId);
        var target=folders.get(route.destination);
        if(target==null||!properties(target.resolve("map.properties")).getProperty("scene-id").equals(Integer.toString(route.destination)))
            throw new IOException("Route points to an absent/wrong destination scene: "+route.destination);
        Properties destinationPortals=properties(target.resolve("portals.properties"));
        if(!csv(destinationPortals.getProperty("entrances","")).contains(route.entrance))
            throw new IOException("Native area destination tag is not configured: "+route.entrance);
        Properties portal=properties(folders.get(route.sceneId).resolve("portals.properties"));
        String prefix="trigger."+route.areaId+".";
        if(!csv(portal.getProperty("triggers","")).add(Integer.toString(route.areaId)))
            throw new IOException("Duplicate trigger metadata for native area "+route.sceneId+"/"+route.areaId);
        var ids=csv(portal.getProperty("triggers",""));ids.add(Integer.toString(route.areaId));
        portal.setProperty("format","2");
        portal.setProperty("layout-sha256",properties(folders.get(route.sceneId).resolve("map.properties")).getProperty("scene-sha256"));
        portal.setProperty("triggers",String.join(",",ids));
        portal.setProperty(prefix+"scene",Integer.toString(route.destination));
        portal.setProperty(prefix+"entrance",route.entrance);
        portal.setProperty(prefix+"shape","polygon");
        portal.setProperty(prefix+"vertices",vertices(points));
        ScenePackExport.writeProperties(folders.get(route.sceneId).resolve("portals.properties"),portal);
        System.out.printf("ROUTE scene=%d area=%d name=%s -> scene=%d entrance=%s polygon=%s%n",
            route.sceneId,route.areaId,route.areaName,route.destination,route.entrance,vertices(points));
    }

    private static void addEntrance(Path folder,String tag,Entrance entrance)throws Exception {
        Properties portal=properties(folder.resolve("portals.properties"));
        var names=csv(portal.getProperty("entrances",""));
        String prefix="entrance."+tag+".";
        if(names.contains(tag)) {
            if(Integer.parseInt(portal.getProperty(prefix+"id"))!=entrance.id
                ||Float.parseFloat(portal.getProperty(prefix+"x"))!=entrance.x
                ||Float.parseFloat(portal.getProperty(prefix+"y"))!=entrance.y
                ||Float.parseFloat(portal.getProperty(prefix+"rotation"))!=entrance.rotation)
                throw new IOException("Conflicting metadata for native return tag "+tag);
            return;
        }
        names.add(tag);portal.setProperty("format","2");
        portal.setProperty("layout-sha256",properties(folder.resolve("map.properties")).getProperty("scene-sha256"));
        portal.setProperty("entrances",String.join(",",names));
        portal.setProperty(prefix+"id",Integer.toString(entrance.id));
        portal.setProperty(prefix+"x",Float.toString(entrance.x));
        portal.setProperty(prefix+"y",Float.toString(entrance.y));
        portal.setProperty(prefix+"rotation",Float.toString(entrance.rotation));
        ScenePackExport.writeProperties(folder.resolve("portals.properties"),portal);
    }

    private static void validateNetwork(Map<Integer,Path> folders)throws Exception {
        var sceneIds=new HashSet<Integer>();
        for(Path folder:folders.values()) {
            Properties map=properties(folder.resolve("map.properties"));
            int id=Integer.parseInt(map.getProperty("scene-id"));
            if(!sceneIds.add(id))throw new IOException("Duplicate scene ID in prepared output: "+id);
            if(!map.getProperty("scene-sha256").equals(digest(folder.resolve("scene.dat")))
                ||!map.getProperty("collision-sha256").equals(digest(folder.resolve("walkability.gz"))))
                throw new IOException("Prepared scene/collision digest mismatch: "+folder);
            Properties portal=properties(folder.resolve("portals.properties"));
            if(!map.getProperty("scene-sha256").equals(portal.getProperty("layout-sha256")))
                throw new IOException("Portal metadata digest mismatch: "+folder);
            for(String idRaw:csv(portal.getProperty("triggers",""))) {
                int entryId=Integer.parseInt(idRaw);String prefix="trigger."+entryId+".";
                int dest=Integer.parseInt(portal.getProperty(prefix+"scene"));String tag=portal.getProperty(prefix+"entrance");
                Path destination=folders.values().stream().filter(f->{try{return Integer.parseInt(properties(f.resolve("map.properties")).getProperty("scene-id"))==dest;}catch(Exception e){return false;}}).findFirst().orElseThrow(()->new IOException("Missing destination scene "+dest));
                if(!csv(properties(destination.resolve("portals.properties")).getProperty("entrances","")).contains(tag))
                    throw new IOException("Trigger destination entrance is missing: "+dest+"/"+tag);
            }
        }
    }

    private static boolean walkable(Path path,float x,float y)throws IOException {
        try(var in=new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            if(in.readInt()!=0x54445731)throw new IOException("Invalid collision header: "+path);
            int width=in.readInt(),height=in.readInt();float minX=in.readFloat(),minY=in.readFloat(),step=in.readFloat();
            if(width<=0||height<=0||!Float.isFinite(step)||step<=0)throw new IOException("Invalid collision dimensions: "+path);
            int bytes=(int)(((long)width*height+7)/8);byte[] bits=in.readNBytes(bytes);
            if(bits.length!=bytes||in.read()!=-1)throw new IOException("Invalid collision payload: "+path);
            float col=(x-minX)/step,row=(y-minY)/step;
            if(col<0||row<0||col>width-1||row>height-1)return false;
            int index=Math.round(row)*width+Math.round(col);
            return (bits[index>>>3]&(1<<(index&7)))!=0;
        }
    }

    private static void validateConvex(List<Point> points,int id)throws IOException {
        for(int i=0;i<points.size();i++)for(int j=i+1;j<points.size();j++)
            if(points.get(i).equals(points.get(j)))throw new IOException("Duplicate vertex in native area "+id);
        float side=0;
        for(int i=0;i<points.size();i++) {
            Point a=points.get(i),b=points.get((i+1)%points.size());
            for(int j=0;j<points.size();j++) {
                if(j==i||j==(i+1)%points.size())continue;
                Point c=points.get(j);float cross=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
                if(cross==0)throw new IOException("Collinear vertices in native area "+id);
                if(side==0)side=Math.signum(cross);else if(Math.signum(cross)!=side)throw new IOException("Non-convex native area "+id);
            }
        }
    }

    private static String vertices(List<Point> points) {
        return String.join(";",points.stream().map(p->Float.toString(p.x)+","+Float.toString(p.y)).toList());
    }
    private static LinkedHashSet<String> csv(String value) {
        var result=new TreeSet<String>();if(value!=null&&!value.isBlank())result.addAll(Arrays.asList(value.split(",")));
        return new LinkedHashSet<>(result);
    }
    private static Properties properties(Path path)throws IOException {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new IOException("Missing/unsafe properties file: "+path);
        Properties values=new Properties();try(var in=Files.newInputStream(path)){values.load(in);}return values;
    }
    private static String digest(Path path)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void copyTree(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.sorted().toList()) {
                if(Files.isSymbolicLink(path))throw new IOException("Refusing symbolic link in source maps: "+path);
                Path relative=source.relativize(path),destination=target.resolve(relative);
                if(Files.isDirectory(path))Files.createDirectories(destination);
                else {Files.createDirectories(destination.getParent());Files.copy(path,destination);}
            }
        }
    }
    private static void deleteTree(Path root)throws IOException {
        if(!Files.exists(root))return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
