package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

public final class ScenePathingTest {
    public static void main(String[] args)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();
        var wall=new SceneContent.Spec(Map.of("native-type","Door","config","Gate","shape","Rectangle","width",".5","height","4","collision-flags","1","tags","gate"));
        var fields=new HashMap<String,String>();
        fields.putAll(Map.of("native-type","Monster","config","Shufflebot fixture","shape","Circle","radius",".3","health","100","collision-flags","8","collision-mask","1","speed","2","acquire-distance","10"));
        fields.putAll(Map.of("attack","0","attack-distance","5","attack-interval","500","attack.0.clear","100","acquisition-interval","100","targeted-speed","2"));
        var enemy=new SceneContent.Spec(fields);
        var button=new SceneContent.Spec(Map.of("native-type","Switch","config","Button","shape","Circle","radius",".2","switch-mode","button","switch-delay","10000","state.1.tag","gate","state.1.signal","open"));
        var content=new SceneContent(List.of(new SceneContent.Entry("wall",0,0,0,wall),new SceneContent.Entry("enemy",-2,0,0,enemy),new SceneContent.Entry("button",5,5,0,button)),Map.of());
        var world=new SceneEntities(content,terrain);int stamp=SceneClock.now();
        var player=new ActorState(219,1001,"Player",0,stamp,2,0,0,0,0,0,stamp,new StarterCatalog.Model("Player",Map.of()));
        world.advance(stamp,List.of(player));
        var first=world.actors().stream().filter(a->a.entity().kind().equals("Monster")).findFirst().orElseThrow();
        check(first.activity()<4,"enemy cannot attack through a closed gate even within range");
        boolean around=false;
        for(int time=stamp+50;time<stamp+3000;time+=50){world.advance(time,List.of(player));var mob=world.actor(first.id());if(Math.abs(mob.y())>2.2)around=true;}
        check(around,"pursuit routes around the gate footprint instead of walking into it");
        world.playerTargetable(id->false);world.advance(stamp+3100,List.of(player));
        check((world.actor(first.id()).flags()&16)==0,"Cloak terminates enemy targeting without disabling physical collision or damage");
        var dynamic=new java.util.concurrent.atomic.AtomicBoolean(true);
        ScenePathing.Traversal traversal=(x,y,tx,ty)->{
            if(Math.abs(tx)>6||Math.abs(ty)>6)return false;
            for(int n=0;n<=100;n++){float px=x+(tx-x)*n/100,py=y+(ty-y)*n/100;if(dynamic.get()&&Math.abs(px)<.3&&Math.abs(py)<2)return false;}
            return true;
        };
        var route=ScenePathing.route(-2,0,2,0,traversal);check(route.size()>1,"closed geometry needs intermediate path nodes");
        dynamic.set(false);check(ScenePathing.route(-2,0,2,0,traversal).equals(List.of(new ScenePathing.Point(2,0))),"opening a gate restores the direct path");
        check(ScenePathing.route(0,0,2,0,(x,y,tx,ty)->false).isEmpty(),"unreachable targets terminate the bounded search");
        var approach=ScenePathing.region(0,0,(x,y)->(float)Math.hypot(5-x,y)-3,
            (x,y,tx,ty)->Math.hypot(5-tx,ty)>1.5);
        check(!approach.isEmpty()&&approach.getLast().x()<=3.5&&Math.hypot(5-approach.getLast().x(),approach.getLast().y())<=3,"occupied target is approached to attack range without entering its body");
        check(ScenePathing.region(0,0,(x,y)->1,(x,y,tx,ty)->false).isEmpty(),"unreachable attack region terminates within its search budget");
        ScenePathing.Traversal narrow=(x,y,tx,ty)->{
            for(int i=0;i<=100;i++){float px=x+(tx-x)*i/100,py=y+(ty-y)*i/100;
                if(!(px>=5&&px<=8&&py>=39.4&&py<=39.8||px>=7.4&&px<=7.6&&py>=39.7&&py<=51))return false;
            }return true;
        };
        var narrowRoute=ScenePathing.route(7.735f,39.7f,7.5f,50.5f,narrow);
        check(!narrowRoute.isEmpty(),"fractional start must find the authored narrow corridor on a world-aligned lattice");
        float px=7.735f,py=39.7f;for(var point:narrowRoute){check(narrow.clear(px,py,point.x(),point.y()),"every fallback path segment still clears authoritative collision");px=point.x();py=point.y();}
        var closed=new java.util.concurrent.atomic.AtomicBoolean(true);
        ScenePathing.Traversal corridor=(x,y,tx,ty)->{
            for(int i=0;i<=100;i++){float qx=x+(tx-x)*i/100,qy=y+(ty-y)*i/100;
                if(qx< -1||qx>10||Math.abs(qy)>1||closed.get()&&qx>=4&&qx<=6)return false;
            }return true;
        };
        ScenePathing.Distance far=(x,y)->(float)Math.hypot(9-x,y);
        check(ScenePathing.region(0,0,far,corridor).isEmpty(),"complete-region search must still refuse the disconnected far room");
        var partial=ScenePathing.partialRegion(0,0,far,corridor);
        check(!partial.isEmpty()&&partial.getLast().x()>3&&partial.getLast().x()<4,"optional partial path approaches the closed gate without claiming to reach the far room");
        px=0;py=0;for(var point:partial){check(corridor.clear(px,py,point.x(),point.y()),"every partial edge preserves live collision");px=point.x();py=point.y();}
        check(ScenePathing.partialRegion(px,py,far,corridor).isEmpty(),"already closest reachable pose does not return a futile loop");
        closed.set(false);var opened=ScenePathing.partialRegion(px,py,far,corridor);
        check(!opened.isEmpty()&&opened.getLast().equals(new ScenePathing.Point(9,0)),"replanning uses the newly opened gate without cached collision");
        check(ScenePathing.partialRegion(0,0,(x,y)->0,corridor).isEmpty(),"already reached region needs no movement");
        check(ScenePathing.partialRegion(0,0,far,(x,y,tx,ty)->false).isEmpty(),"isolated source cannot fabricate a partial route");
        int[] calls={0};var bounded=ScenePathing.partialRegion(0,0,(x,y)->(float)Math.hypot(10000-x,y),(x,y,tx,ty)->{calls[0]++;return true;});
        check(!bounded.isEmpty()&&calls[0]<=32780&&bounded.getLast().x()<10000,"partial paths retain the fixed search budget");
        System.out.println("PASS: bounded complete/partial routing, live closed/open gate edges, no-progress refusal, narrow corridors, attack occlusion and obstacle pursuit");
    }
}
