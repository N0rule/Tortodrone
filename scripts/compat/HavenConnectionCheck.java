import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.*;
import com.threerings.util.Name;
import org.tortodrone.world.*;

/** Stock network walking exercises every directed town link; routing is test-only static collision. */
public final class HavenConnectionCheck {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var maps=SceneCatalog.load(Path.of("content/maps"));
        Thread thread=Thread.currentThread();at queue=new at(){
            public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        var client=new Client(new ProjectXCredentials(new Name("LanPlayer"),"lan-test-password"),queue);
        client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);
        client.dr(Files.readString(Path.of(args[1])).trim());client.ds("haven-check");client.dt("projectx");client.xg();
        boolean[] observing={true};
        try {
            WorldConnectionCheck.waitFor(client::isLoggedOn);var player=(PlayerObject)client.xc();
            Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
            Object handler=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->{
                if(method.getName().equals("aF"))PortalConnectionCheck.forced.add(new int[]{(Integer)values[0],(Integer)values[1]});return null;});
            client.xd().a((w)Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(handler));
            var selected=new WorldConnectionCheck.Reply();((aU)client.bU(aU.class)).b(new Name("Town-Knight"),null,selected);
            WorldConnectionCheck.waitFor(()->selected.done);if(selected.reason!=null)throw new AssertionError(selected.reason);
            WorldConnectionCheck.waitFor(()->!PortalConnectionCheck.forced.isEmpty());
            player.a(new com.threerings.tudey.dobj.a(){public void c(com.threerings.tudey.dobj.SceneDeltaEvent event){if(observing[0])WorldConnectionCheck.received(event);}});
            int identity=player.pawnId;var equipment=player.equipment.clone();
            int readySceneId=-1;
            for(var source:maps.scenes())for(var trigger:source.portals().triggers()) {
                // Interior travel has native party/Dungeoneer handling in InteriorConnectionCheck.
                if(!source.town()||!maps.scene(trigger.destination()).town())continue;
                // Direct stock move RPC sets up the source; the link itself must trigger by walking.
                if(readySceneId!=source.id()) {
                    WorldConnectionCheck.enterPrimary(client,player,WorldConnectionCheck.moveScene(client,source.id()));
                    readySceneId=source.id();
                }
                var pawn=WorldConnectionCheck.controlled(player);PortalConnectionCheck.forced.clear();
                var route=route(source,trigger,pawn.tz().x,pawn.tz().y);
                try{PortalConnectionCheck.walk(player,route);}
                catch(AssertionError failure){throw new AssertionError("Scene "+source.id()+" portal "+trigger.id()+" at "+WorldConnectionCheck.controlled(player).tz()+" route="+Arrays.deepToString(route),failure);}
                WorldConnectionCheck.waitFor(()->!PortalConnectionCheck.forced.isEmpty());
                PortalConnectionCheck.verifyForce(trigger.destination());
                WorldConnectionCheck.enterPrimary(client,player,WorldConnectionCheck.moveScene(client,trigger.destination()));
                readySceneId=trigger.destination();
                var destination=maps.scene(trigger.destination());var arrival=destination.portals().entrances().get(trigger.entrance());
                PortalConnectionCheck.arrival(player,arrival);
                if(player.pawnId!=identity||!Arrays.equals(player.equipment,equipment))throw new AssertionError("Travel changed character identity/equipment");
                int idle=WorldConnectionCheck.deltas;WorldConnectionCheck.waitFor(()->WorldConnectionCheck.deltas>=idle+3);
                if(PortalConnectionCheck.forced.size()!=1)throw new AssertionError("Arrival retriggered portal");
                System.out.println("PASS: stock walking Haven link "+source.id()+"/"+trigger.id()+" -> "+trigger.destination()+"/"+trigger.entrance()+", tagged arrival/facing and fresh baseline");
            }
        }finally{observing[0]=false;client.Q(false);}
    }
    record Cell(int x,int y){float wx(){return x*.25f;}float wy(){return y*.25f;}}
    static float[][] route(SceneCatalog.MapScene map,ScenePortals.Trigger target,float x,float y) {
        Cell start=new Cell(Math.round(x*4),Math.round(y*4));
        var previous=new HashMap<Cell,Cell>();var queue=new ArrayDeque<Cell>();previous.put(start,null);queue.add(start);
        Cell end=null;int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        while(!queue.isEmpty()) {
            Cell here=queue.removeFirst();if(interior(target,here.wx(),here.wy())){end=here;break;}
            for(var d:directions) {
                Cell next=new Cell(here.x()+d[0],here.y()+d[1]);
                if(Math.abs(next.x())>320||Math.abs(next.y())>320||previous.containsKey(next)||!clear(map,target,next.wx(),next.wy()))continue;
                previous.put(next,here);queue.add(next);
            }
        }
        if(end==null)throw new AssertionError("No walking route to scene "+map.id()+" portal "+target.id());
        var path=new ArrayList<Cell>();for(Cell p=end;p!=null;p=previous.get(p))path.add(p);Collections.reverse(path);
        var points=new ArrayList<float[]>();float px=x,py=y;
        for(int i=1;i<path.size();) {
            int far=i;for(int j=i;j<path.size();j++) {var p=path.get(j);if(Math.hypot(p.wx()-px,p.wy()-py)>20||!visible(map,target,px,py,p.wx(),p.wy()))break;far=j;}
            var p=path.get(far);points.add(new float[]{p.wx(),p.wy()});px=p.wx();py=p.wy();i=far+1;
        }
        if(points.isEmpty())throw new AssertionError("Spawn already in portal");return points.toArray(float[][]::new);
    }
    static boolean clear(SceneCatalog.MapScene map,ScenePortals.Trigger target,float x,float y) {
        return map.collision().walkable(x,y)&&map.portals().triggers().stream().noneMatch(t->t!=target&&t.intersects(x,y));
    }
    static boolean interior(ScenePortals.Trigger target,float x,float y) {
        // Walk beyond the first grazing sample so the stock stop tolerance cannot miss the sensor.
        return target.intersects(x-.1f,y)&&target.intersects(x+.1f,y)
            &&target.intersects(x,y-.1f)&&target.intersects(x,y+.1f);
    }
    static boolean visible(SceneCatalog.MapScene map,ScenePortals.Trigger target,float ax,float ay,float bx,float by) {
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/.05));
        for(int i=0;i<=steps;i++){float f=(float)i/steps;if(!clear(map,target,ax+(bx-ax)*f,ay+(by-ay)*f))return false;}return true;
    }
}
