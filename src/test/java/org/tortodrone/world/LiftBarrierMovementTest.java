package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Runs the authoritative movement integrator against the live carried-lift collision query. */
public final class LiftBarrierMovementTest {
    public static void main(String[] args)throws Exception {
        Path grid=Files.createTempFile("tortodrone-lift-corridor-",".gz");
        try{
            int size=81;byte[] bits=new byte[(size*size+7)/8];Arrays.fill(bits,(byte)255);
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(grid)))){
                out.writeInt(0x54445731);out.writeInt(size);out.writeInt(size);out.writeFloat(-10);out.writeFloat(-10);out.writeFloat(.25f);out.write(bits);
            }
            var terrain=CollisionMap.read(grid);
            monsters(terrain);
            for(boolean open:new boolean[]{false,true})for(float speed:new float[]{1,20})for(float heading:new float[]{0,.2f,-.2f}){
                var door=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Door","config","Fixture",
                    "collision-flags","8192","start-open",Boolean.toString(open),"shape","Rectangle","width","1","height","8"));
                var lift=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.DungeonLift","config","Fixture","collision-flags","514","shape","Circle","radius",".3"));
                var entities=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("door",0,0,0,door),
                    new SceneContent.Entry("lift",-2,0,0,lift),new SceneContent.Entry("other-lift",-3,0,0,lift)),Map.of()),terrain);
                var player=actor(7,-2,0);var other=actor(8,-2,1);
                var lifts=entities.actors().stream().filter(a->a.entity().kind().equals("DungeonLift")).toList();
                check(entities.carry(lifts.get(0).id(),player,1000)&&entities.carry(lifts.get(1).id(),other,1000),"players own separate lifts");
                var movement=movement(terrain,entities,player,speed);
                walk(movement,heading,1000,600);
                check(open?movement.x()>0:movement.x()<=-.5f-ScenePortals.PAWN_RADIUS+.001f,"authoritative carried movement: open="+open+", speed="+speed+", heading="+heading+", x="+movement.x());
                // Release only this knight's lift. Another carrier must not keep this pawn blocked.
                entities.throwLift(lifts.get(0).id(),player,1400);
                check(entities.penetration(0,0,other.id()).present()!=open,"other carrier remains independently blocked");
                var empty=movement(terrain,entities,player,1);walk(empty,0,1500,800);check(empty.x()>0,"throw immediately releases own barrier collision");
                check(entities.carry(lifts.get(0).id(),player,2300)==false,"cannot steal a thrown lift");
                entities.dropLift(lifts.get(1).id(),other,2300);
                var dropped=movement(terrain,entities,actor(other.id(),-2,2.5f),1);walk(dropped,0,2400,800);check(dropped.x()>0,"drop immediately releases remaining carrier away from the resting lift");
            }
            System.out.println("PASS: authoritative lift-barrier movement, open/closed, high speed/diagonal, drop/throw and independent carriers");
        }finally{Files.delete(grid);}
    }
    private static void monsters(CollisionMap terrain)throws Exception {
        for(int barrierMask:new int[]{1,64,8192})for(boolean open:new boolean[]{false,true}){
            var door=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Door","config","Fixture",
                "collision-flags",Integer.toString(barrierMask),"start-open",Boolean.toString(open),"shape","Rectangle","width","1","height","8"));
            var fields=new HashMap<String,String>();
            fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Monster");fields.put("config","Fixture");
            fields.put("collision-flags","8");fields.put("collision-mask","79");fields.put("radius",".4");fields.put("health","40");
            fields.put("speed","10");fields.put("acquire-distance","20");fields.put("attack-count","0");
            fields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",.4f))))));
            // Bound the corridor so pursuit cannot legitimately walk around the eight-unit door in the open test grid.
            var wall=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Block","config","Fixture",
                "collision-flags","1","shape","Rectangle","width","1","height","8"));
            var entities=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("door",0,0,0,door),
                new SceneContent.Entry("upper-wall",0,8,0,wall),new SceneContent.Entry("lower-wall",0,-8,0,wall),
                new SceneContent.Entry("monster",-2,0,0,new SceneContent.Spec(fields))),Map.of()),terrain);
            int stamp=SceneClock.now();for(int i=1;i<=20;i++)entities.advance(stamp+i*50,List.of(actor(7,3,0)));
            float x=entities.actors().stream().filter(a->a.entity().kind().equals("Monster")).findFirst().orElseThrow().x();
            check(open||barrierMask==8192?x>0:x<=-.899f,"monster uses native mask/shape for live barrier: mask="+barrierMask+", open="+open+", x="+x);
            check(open||barrierMask!=64||!entities.penetration(0,0,7).present(),"monster-only barrier still permits a normal pawn");
        }
    }
    private static ActorState actor(int id,float x,float y){return new ActorState(id,1000+id,"Fixture",0,1000,x,y,0,0,0,new StarterCatalog.Model("Fixture",Map.of()));}
    private static TownMovement movement(CollisionMap terrain,SceneEntities entities,ActorState actor,float speed){
        var m=new TownMovement(terrain,actor.x(),actor.y());m.dynamicPenetration((x,y)->entities.penetration(x,y,actor.id()));
        m.status(new SceneStatus.Modifiers(speed,1,1,1,1,true,true,true,false,false));return m;
    }
    private static void walk(TownMovement m,float heading,int stamp,int duration){
        m.resetInput(stamp);
        for(int t=stamp;t<stamp+duration;t+=50){check(m.accept(t,heading,heading,1,t,t),"fresh intent");m.advance(t+50,t+50);}
    }
}
