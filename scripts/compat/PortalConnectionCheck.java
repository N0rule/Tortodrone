import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.*;
import com.threerings.whirled.data.SceneModel;
import com.threerings.util.Name;
import org.tortodrone.world.*;

/** Unchanged stock input/receiver/move readers walk through both automatic town portals. */
public final class PortalConnectionCheck {
    static final List<int[]> forced=new ArrayList<>();
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Thread thread=Thread.currentThread();at queue=new at(){
            public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        var client=new Client(new ProjectXCredentials(new Name("LanPlayer"),"lan-test-password"),queue);
        client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);
        client.dr(Files.readString(Path.of(args[1])).trim());client.ds("portal-check");client.dt("projectx");client.xg();
        try {
            WorldConnectionCheck.waitFor(client::isLoggedOn);var player=(PlayerObject)client.xc();
            Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
            Object handler=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->{
                if(method.getName().equals("aF"))forced.add(new int[]{(Integer)values[0],(Integer)values[1]});return null;});
            client.xd().a((w)Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(handler));
            var selected=new WorldConnectionCheck.Reply();((aU)client.bU(aU.class)).b(new Name("Town-Knight"),null,selected);
            WorldConnectionCheck.waitFor(()->selected.done);if(selected.reason!=null)throw new AssertionError(selected.reason);
            WorldConnectionCheck.waitFor(()->!forced.isEmpty());
            player.a(new com.threerings.tudey.dobj.a(){public void c(com.threerings.tudey.dobj.SceneDeltaEvent event){WorldConnectionCheck.received(event);}});
            WorldConnectionCheck.enterPrimary(client,player,WorldConnectionCheck.moveScene(client,1));forced.clear();
            var maps=SceneCatalog.load(Path.of("defaults/maps"));
            try(var peer=new GameMasterConnectionCheck.Endpoint(args,queue,"PeerPlayer","Peer-Knight")) {
                WorldConnectionCheck.waitFor(()->peer.occupant(player.pawnId)!=null);
                var identity=player.pawnId;var equipment=player.equipment.clone();
                var town=maps.scene(1);var west=trigger(town,6071);var pawn=WorldConnectionCheck.controlled(player);
                walk(player,route(town,west,pawn.tz().x,pawn.tz().y));
                WorldConnectionCheck.waitFor(()->!forced.isEmpty());verifyForce(2);
                var before=WorldConnectionCheck.controlled(player);float frozenX=before.tz().x,frozenY=before.tz().y;
                // Input already queued when forceScene arrives must not disconnect or move the pawn.
                int time=WorldConnectionCheck.last.QY();
                WorldConnectionCheck.scene.a(time,time,new com.threerings.tudey.data.InputFrame[]{new ProjectXInputFrame(time+1,0,0,1,0)});
                WorldConnectionCheck.enterPrimary(client,player,sceneMove(client,2));
                arrival(player,16.5f,7.5f,-(float)Math.PI/2);
                WorldConnectionCheck.waitFor(()->peer.occupant(player.pawnId)==null);
                if(!client.isLoggedOn()||!peer.client.isLoggedOn()||frozenX>-36.8f||Math.abs(frozenY)>3.3f)
                    throw new AssertionError("Portal did not originate at the verified west sensor");
                System.out.println("PASS: stock walking triggers Town Square west portal, force receiver and same-zone scene RPC load Bazaar at tagged arrival");
                forced.clear();var bazaar=maps.scene(2);var east=trigger(bazaar,3096);pawn=WorldConnectionCheck.controlled(player);
                walk(player,route(bazaar,east,pawn.tz().x,pawn.tz().y));
                WorldConnectionCheck.waitFor(()->!forced.isEmpty());verifyForce(1);
                WorldConnectionCheck.enterPrimary(client,player,WorldConnectionCheck.moveScene(client,1));
                arrival(player,-30.5f,.5f,(float)Math.PI/2);
                WorldConnectionCheck.waitFor(()->peer.occupant(player.pawnId)!=null);
                if(player.pawnId!=identity||!Arrays.equals(player.equipment,equipment))throw new AssertionError("Portal changed character identity/equipment");
                int idle=WorldConnectionCheck.deltas;WorldConnectionCheck.waitFor(()->WorldConnectionCheck.deltas>=idle+8);
                if(forced.size()!=1)throw new AssertionError("Arrival retriggered portal");
                System.out.println("PASS: stock Bazaar east portal round trip, zone RPC, tagged Town Square arrival, fresh baselines, peer departure/rejoin and durable identity");
            }
        }finally{client.Q(false);}
    }
    static void verifyForce(int scene) {
        if(forced.size()!=1||forced.getFirst()[0]!=1||forced.getFirst()[1]!=scene)
            throw new AssertionError("Unexpected forced destination: "+forced.size());
    }
    static void arrival(PlayerObject player,float x,float y,float facing) {
        var actor=WorldConnectionCheck.controlled(player);
        if(Math.abs(actor.tz().x-x)>.001||Math.abs(actor.tz().y-y)>.001||Math.abs(actor.tA()-facing)>.001
                ||actor.adU()!=0||actor.bR(2))throw new AssertionError("Wrong portal arrival: "+actor);
    }
    static ScenePortals.Trigger trigger(SceneCatalog.MapScene map,int id) {
        return map.portals().triggers().stream().filter(portal->portal.id()==id).findFirst()
            .orElseThrow(()->new AssertionError("Missing native portal "+map.id()+"/"+id));
    }
    record Cell(int x,int y){float wx(){return x*.25f;}float wy(){return y*.25f;}}
    static float[][] route(SceneCatalog.MapScene map,ScenePortals.Trigger target,float x,float y) {
        Cell start=new Cell(Math.round(x*4),Math.round(y*4));
        var previous=new HashMap<Cell,Cell>();var pending=new ArrayDeque<Cell>();previous.put(start,null);pending.add(start);
        Cell end=null;int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        while(!pending.isEmpty()) {
            Cell here=pending.removeFirst();if(interior(target,here.wx(),here.wy())){end=here;break;}
            for(var direction:directions) {
                Cell next=new Cell(here.x()+direction[0],here.y()+direction[1]);
                if(Math.abs(next.x())>320||Math.abs(next.y())>320||previous.containsKey(next)||!clear(map,target,next.wx(),next.wy()))continue;
                previous.put(next,here);pending.add(next);
            }
        }
        if(end==null)throw new AssertionError("No stock-collision route to scene "+map.id()+" portal "+target.id());
        var cells=new ArrayList<Cell>();for(Cell cell=end;cell!=null;cell=previous.get(cell))cells.add(cell);Collections.reverse(cells);
        var points=new ArrayList<float[]>();float px=x,py=y;
        for(int i=1;i<cells.size();) {
            int far=i;for(int j=i;j<cells.size();j++){var cell=cells.get(j);if(Math.hypot(cell.wx()-px,cell.wy()-py)>20||!visible(map,target,px,py,cell.wx(),cell.wy()))break;far=j;}
            var cell=cells.get(far);points.add(new float[]{cell.wx(),cell.wy()});px=cell.wx();py=cell.wy();i=far+1;
        }
        if(points.isEmpty())throw new AssertionError("Spawn already inside native portal "+map.id()+"/"+target.id());
        return points.toArray(float[][]::new);
    }
    static boolean clear(SceneCatalog.MapScene map,ScenePortals.Trigger target,float x,float y) {
        return map.collision().walkable(x,y)&&map.portals().triggers().stream().noneMatch(portal->portal!=target&&portal.intersects(x,y));
    }
    static boolean interior(ScenePortals.Trigger target,float x,float y) {
        return target.intersects(x-.1f,y)&&target.intersects(x+.1f,y)&&target.intersects(x,y-.1f)&&target.intersects(x,y+.1f);
    }
    static boolean visible(SceneCatalog.MapScene map,ScenePortals.Trigger target,float ax,float ay,float bx,float by) {
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/.05));
        for(int i=0;i<=steps;i++){float fraction=(float)i/steps;if(!clear(map,target,ax+(bx-ax)*fraction,ay+(by-ay)*fraction))return false;}
        return true;
    }
    static WorldConnectionCheck.Zone sceneMove(Client client,int target)throws Exception {
        client.xb().b(WorldConnectionCheck.scene.xL(),WorldConnectionCheck.primarySubscription);
        var result=new WorldConnectionCheck.Zone();Class<?> service=Class.forName("com.threerings.whirled.a.h"),listener=Class.forName("com.threerings.whirled.a.h$a");
        Object reply=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(proxy,method,values)->{
            if(method.getName().equals("aT"))throw new AssertionError(values[0]);
            if(method.getName().equals("a")&&values.length==3&&values[2] instanceof SceneModel model){result.oid=(Integer)values[0];result.model=model;result.done=true;}return null;});
        service.getMethod("a",int.class,int.class,listener).invoke(client.bU(service),target,0,reply);
        WorldConnectionCheck.waitFor(()->result.done);return result;
    }
    static void walk(PlayerObject player,float[][] path)throws Exception {
        for(var waypoint:path) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
            while(forced.isEmpty()) {
                var actor=WorldConnectionCheck.controlled(player);float dx=waypoint[0]-actor.tz().x,dy=waypoint[1]-actor.tz().y;
                double distance=Math.hypot(dx,dy);if(distance<.025)break;
                if(System.nanoTime()>deadline)throw new AssertionError("Walk stuck at "+actor.tz()+" heading toward "+Arrays.toString(waypoint));
                float heading=(float)Math.atan2(dy,dx);int time=WorldConnectionCheck.last.QY();
                int start=time+100,duration=Math.max(1,Math.min(80,(int)Math.round(distance*200))),stop=start+duration;
                WorldConnectionCheck.scene.a(time,time,new com.threerings.tudey.data.InputFrame[]{
                    new ProjectXInputFrame(start,heading,heading,1,0),new ProjectXInputFrame(stop,heading,heading,0,0)});
                // One outstanding batch: stock TCP output pacing can lag 20 Hz publications.
                // Keep both frames in the future under CPU load and await their acknowledged integration.
                WorldConnectionCheck.waitFor(()->!forced.isEmpty()
                    ||WorldConnectionCheck.last.aef()>=stop&&WorldConnectionCheck.last.QY()>=stop);
            }
            if(!forced.isEmpty())return;
        }
        try{WorldConnectionCheck.waitFor(()->!forced.isEmpty());}
        catch(AssertionError timeout){var actor=WorldConnectionCheck.controlled(player);throw new AssertionError(
            "Walking reached its final stock-collision waypoint without a force callback; position="+actor.tz()+
            " final="+Arrays.toString(path[path.length-1]),timeout);}
    }
}
