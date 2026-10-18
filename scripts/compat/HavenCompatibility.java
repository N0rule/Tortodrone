import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.data.*;
import com.threerings.tudey.config.*;
import com.threerings.math.*;
import com.threerings.tudey.shape.*;
import com.threerings.tudey.shape.config.ShapeConfig;
import org.tortodrone.world.ScenePortals;
import org.tortodrone.world.CollisionMap;

/** Read-only stock-client oracle for the seven modern Haven scenes and native portal metadata. */
public final class HavenCompatibility {
    private static final float EPS = 0.000002f;
    private record V(float x, float y) { }
    private record Portal(int id, int destination, String entrance, List<V> vertices) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException(
            "Usage: HavenCompatibility installed-game [content/maps]");
        Path game=Path.of(args[0]), maps=args.length==2?Path.of(args[1]):Path.of("content/maps");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),
            com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");
        manager.init();

        int[] ids={1,2,3,445,294,30893,318}; int totalTriggers=0,totalEntrances=0;
        for(int id:ids) {
            Path folder=maps.resolve(switch(id){case 1->"haven";case 2->"bazaar";case 3->"arcade";case 445->"garrison";case 294->"advanced_training";case 30893->"laboratory";default->"hall_of_heroes";});
            var prepared=Files.exists(folder.resolve("map.yml"))?org.tortodrone.resources.MapYaml.prepare(folder,maps.getParent().resolve("shared")):null;
            var props=prepared==null?properties(folder.resolve("map.properties")):prepared.descriptor();
            Path nativeFolder=prepared==null?folder:prepared.nativeDirectory();
            if(!"%town:m.haven".equals(props.getProperty("zone-name")))
                throw new AssertionError("Haven zone name differs from the captured qualified Name");
            byte[] bytes=Files.readAllBytes(nativeFolder.resolve("scene.dat"));
            var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes); scene.init(manager);
            if(scene.sceneId!=id||scene.version!=Integer.parseInt(props.getProperty("version"))
                ||!Objects.equals(scene.name,props.getProperty("name"))
                ||!sceneKind(scene.adD()).equals(props.getProperty("kind")))
                throw new AssertionError("Stock scene identity/config mismatch for id "+id);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!digest.equals(props.getProperty("scene-sha256")))throw new AssertionError("Scene digest mismatch for "+id);
            var pp=prepared==null?properties(folder.resolve("portals.properties")):prepared.portals();
            if(!digest.equals(pp.getProperty("layout-sha256"))||!"2".equals(pp.getProperty("format")))
                throw new AssertionError("Portal metadata layout/format mismatch for "+id);
            var server=ScenePortals.load(pp,digest,CollisionMap.read(nativeFolder.resolve("walkability.gz")));
            var archived=new HashMap<Integer,Portal>();
            var destinationEntrances=new HashMap<String,TudeySceneModel.PlaceableEntry>();
            var defaultEntrances=new HashMap<Integer,TudeySceneModel.PlaceableEntry>();
            for(var entry:scene.adH()) {
                if(entry instanceof TudeySceneModel.AreaEntry area) {
                    var ref=area.getReference();
                    int dest;String sourceTag;
                    if(ref.getName().equals("Portals/Portal")) {
                        dest=number(ref.kW().get("Scene ID"));
                        String destTag=string(ref.kW().get("Tag"));
                        var portalRef=(ActionConfig.TaggedPortal)ref.kW().get("Portal");
                        sourceTag=portalRef==null?null:portalRef.tag;
                        if(sourceTag==null||destTag==null)throw new AssertionError("Portal reference arguments missing: "+area.getKey());
                    } else {
                        Portal route=interiorRoute(id,ref.getName());if(route==null)continue;
                        dest=route.destination;sourceTag=route.entrance;
                    }
                    var verts=Arrays.stream(area.vertices).map(v->new V(v.x,v.y)).toList();
                    int areaId=((Number)area.getKey()).intValue();
                    archived.put(areaId,new Portal(areaId,dest,sourceTag,verts));
                    var config=area.aq(manager);
                    var start=Arrays.stream(config.handlers).filter(h->h instanceof HandlerConfig.IntersectionStart).map(h->(HandlerConfig.IntersectionStart)h).findFirst().orElseThrow();
                    if(start.mask!=65535||!(start.shape instanceof HandlerConfig.DefaultShape ds)||ds.expansion!=0f)
                        throw new AssertionError("Portal intersection sensor differs from verified mask/shape: "+area.getKey());
                    checkCircleIntersection(area,manager,server.triggers().stream().filter(t->t.id()==areaId).findFirst().orElseThrow());
                } else if(entry instanceof TudeySceneModel.PlaceableEntry place) {
                    var placeRef=place.getReference();
                    if(placeRef.getName().equals("Dynamic/Portal/Destination")) {
                        String tag=string(placeRef.kW().get("Tag"));
                        if(tag==null||destinationEntrances.put(tag,place)!=null)throw new AssertionError("Missing/duplicate destination portal tag at "+place.getKey());
                    }
                    if(place.placeable.a(manager,PlaceableConfig.class).q(manager).defaultEntrance)
                        defaultEntrances.put(((Number)place.getKey()).intValue(),place);
                }
            }
            if(defaultEntrances.size()!=1)throw new AssertionError("Expected one authored default entrance in scene "+id);
            var authored=defaultEntrances.values().iterator().next();var authoredPos=authored.transform.tD();
            var authoredRot=authored.transform.tE();
            float authoredYaw=(float)(2*Math.atan2(authoredRot.z,authoredRot.w));
            if(Math.abs(Float.parseFloat(props.getProperty("spawn-x"))-authoredPos.x)>EPS
                ||Math.abs(Float.parseFloat(props.getProperty("spawn-y"))-authoredPos.y)>EPS
                ||angleDiff(Float.parseFloat(props.getProperty("spawn-rotation")),authoredYaw)>EPS)
                throw new AssertionError("Map spawn differs from authored default entrance in scene "+id);
            compareTriggers(id,pp,archived,server.triggers());
            totalTriggers+=archived.size();
            var expectedTags=csv(pp.getProperty("entrances",""));
            for(String tag:expectedTags) {
                String p="entrance."+tag+".";
                int key=Integer.parseInt(pp.getProperty(p+"id"));float x=Float.parseFloat(pp.getProperty(p+"x")),y=Float.parseFloat(pp.getProperty(p+"y")),rot=Float.parseFloat(pp.getProperty(p+"rotation"));
                var place=destinationEntrances.get(tag);
                if(place==null)place=defaultEntrances.get(key);
                if(place==null)throw new AssertionError("Destination entrance is neither a tagged nor native default marker: scene="+id+" tag="+tag);
                var tr=place.transform;var pos=tr.tD();var q=tr.tE();
                float actualRot=(float)(2*Math.atan2(q.z,q.w));
                if(((Number)place.getKey()).intValue()!=key||Math.abs(pos.x-x)>EPS||Math.abs(pos.y-y)>EPS||angleDiff(actualRot,rot)>EPS)
                    throw new AssertionError("Destination entrance native placeable differs for scene "+id+" tag "+tag+" key "+place.getKey()+" at "+pos+" rot="+actualRot);
            }
            totalEntrances+=expectedTags.size();
            System.out.println("PASS: scene="+id+" version="+scene.version+" name="+scene.name+" "+scene.adD().getClass().getSimpleName()+"; portals="+archived.size()+" destination entrances="+expectedTags.size());
        }
        System.out.println("PASS: stock native scenes and server portal metadata match; "+totalTriggers+" portal triggers, "+totalEntrances+" entrances, sensor masks/defaults, and native circle-edge/corner intersections checked");
    }

    private static void compareTriggers(int sceneId,Properties pp,Map<Integer,Portal> nativePortals,List<ScenePortals.Trigger> parsed) {
        var listed=new LinkedHashSet<>(csv(pp.getProperty("triggers","")));
        if(listed.size()!=nativePortals.size()||parsed.size()!=nativePortals.size())
            throw new AssertionError("Native portal count mismatch for "+sceneId);
        for(String raw:listed) {
            int id=Integer.parseInt(raw);var nativePortal=nativePortals.get(id);if(nativePortal==null)throw new AssertionError("Metadata names missing native portal "+id);
            int dest=Integer.parseInt(pp.getProperty("trigger."+id+".scene"));String tag=pp.getProperty("trigger."+id+".entrance");
            var server=parsed.stream().filter(t->t.id()==id).findFirst().orElseThrow();
            if(dest!=nativePortal.destination||!tag.equals(nativePortal.entrance)||server.destination()!=dest||!tag.equals(server.entrance()))throw new AssertionError("Portal destination/tag mismatch at "+id);
            var wanted=parseVertices(pp.getProperty("trigger."+id+".vertices"));
            if(!sameCycle(nativePortal.vertices,wanted)||server.vertices().size()!=wanted.size())throw new AssertionError("Portal polygon vertex mismatch at "+id+" native="+nativePortal.vertices+" metadata="+wanted);
            float minX=wanted.stream().map(V::x).min(Float::compare).orElseThrow(),maxX=wanted.stream().map(V::x).max(Float::compare).orElseThrow();
            float minY=wanted.stream().map(V::y).min(Float::compare).orElseThrow(),maxY=wanted.stream().map(V::y).max(Float::compare).orElseThrow();
            if(Math.abs(server.minX()-minX)>EPS||Math.abs(server.maxX()-maxX)>EPS||Math.abs(server.minY()-minY)>EPS||Math.abs(server.maxY()-maxY)>EPS)throw new AssertionError("Portal bounds mismatch at "+id);
            for(int i=0;i<wanted.size();i++)if(Math.abs(server.vertices().get(i).x()-wanted.get(i).x)>EPS||Math.abs(server.vertices().get(i).y()-wanted.get(i).y)>EPS)throw new AssertionError("Server polygon vertex sequence mismatch at "+id);
        }
    }
    private static String sceneKind(TudeySceneConfig config) {
        if(config instanceof com.threerings.projectx.town.data.TownSceneConfig town)return town.getType();
        if(config instanceof com.threerings.projectx.dungeon.data.TrainingSceneConfig training)return training.getType();
        if(config instanceof com.threerings.projectx.dungeon.data.LevelSceneConfig level)return level.getType();
        throw new AssertionError("Unsupported stock scene config "+config.getClass());
    }
    private static Portal interiorRoute(int scene,String name) {
        if(scene==1&&name.equals("Portals/To Advanced Training"))return new Portal(0,294,"level_start",List.of());
        if(scene==445&&name.equals("Portals/To Laboratory"))return new Portal(0,30893,"level_start",List.of());
        if(scene==445&&name.equals("Portals/To Hall of Heroes"))return new Portal(0,318,"level_start",List.of());
        if(scene==294&&name.equals("Portals/Leave Party"))return new Portal(0,1,"from_advanced_training",List.of());
        if(scene==30893&&name.equals("Portals/Town Portal"))return new Portal(0,445,"from_lab",List.of());
        if(scene==318&&name.equals("Portals/Town Portal"))return new Portal(0,445,"from_hoh",List.of());
        return null;
    }
    private static void checkCircleIntersection(TudeySceneModel.AreaEntry area,ConfigManager manager,ScenePortals.Trigger serverTrigger) {
        Shape target=area.av(manager);
        var circleConfig=new ShapeConfig.Circle();circleConfig.radius=ScenePortals.PAWN_RADIUS;Shape unit=circleConfig.acP();
        var pts=Arrays.stream(area.vertices).map(v->new V(v.x,v.y)).toList();
        var samples=new ArrayList<V>();float cx=0,cy=0;for(V p:pts){cx+=p.x;cy+=p.y;}cx/=pts.size();cy/=pts.size();samples.add(new V(cx,cy));
        for(int i=0;i<pts.size();i++) {
            V a=pts.get(i),b=pts.get((i+1)%pts.size());float dx=b.x-a.x,dy=b.y-a.y,len=(float)Math.hypot(dx,dy);
            float nx=-dy/len,ny=dx/len,sign=((cx-a.x)*nx+(cy-a.y)*ny)>=0?-1:1;
            for(float d:new float[]{.33f,.34f})samples.add(new V((a.x+b.x)/2+nx*sign*d,(a.y+b.y)/2+ny*sign*d));
            float vx=a.x-cx,vy=a.y-cy,vlen=(float)Math.hypot(vx,vy);
            for(float d:new float[]{.33f,.34f})samples.add(new V(a.x+vx/vlen*d,a.y+vy/vlen*d));
        }
        for(V p:samples) {Shape moved=unit.d(new Transform2D(new Vector2f(p.x,p.y),0));boolean nativeHit=target.b(moved),serverHit=serverTrigger.intersects(p.x,p.y);
            if(nativeHit!=serverHit)throw new AssertionError("Stock circle/portal geometry disagreement at area "+area.getKey()+" center="+p+" native="+nativeHit+" server="+serverHit);}
    }
    private static boolean sameCycle(List<V> a,List<V>b){if(a.size()!=b.size())return false;for(int start=0;start<b.size();start++)for(int dir:new int[]{1,-1}){boolean ok=true;for(int i=0;i<a.size();i++){V x=a.get(i),y=b.get(Math.floorMod(start+dir*i,b.size()));if(Math.abs(x.x-y.x)>EPS||Math.abs(x.y-y.y)>EPS){ok=false;break;}}if(ok)return true;}return false;}
    private static List<V> parseVertices(String s){return Arrays.stream(s.split(";")).map(v->{String[] xy=v.split(",");return new V(Float.parseFloat(xy[0]),Float.parseFloat(xy[1]));}).toList();}
    private static Set<String> csv(String s){if(s.isBlank())return Set.of();return new LinkedHashSet<>(Arrays.asList(s.split(",")));}
    private static int number(Object v){if(v instanceof Number n)return n.intValue();throw new AssertionError("Expected numeric portal arg, got "+v);}
    private static String string(Object v){if(v instanceof String s)return s;if(v instanceof ConfigReference<?> r)return r.getName();return null;}
    private static float angleDiff(float a,float b){float d=(a-b)%(2*(float)Math.PI);if(d>Math.PI)d-=2*Math.PI;if(d< -Math.PI)d+=2*Math.PI;return Math.abs(d);}
    private static Properties properties(Path p)throws IOException{var x=new Properties();try(var in=Files.newInputStream(p)){x.load(in);}return x;}
}
