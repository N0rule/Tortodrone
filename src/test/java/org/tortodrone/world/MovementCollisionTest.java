package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Pawn separation and lunge prediction regressions, independent of scheduler and network timing. */
public final class MovementCollisionTest {
    public static void main(String[] args)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();
        var geometry=geometry("P;0;0;2;0;2;2;0;2");
        var rail=geometry("D;0;0;1;0;-2;-2;3;2");
        check(!rail.clear(43,disc(.25f),2,.1f,0),"two-vertex native polygon retains circle planes beyond endpoints inside compound bounds");
        check(rail.clear(43,disc(.25f),3.3f,.1f,0),"compound bounds stop the extended circle planes");
        var railPush=rail.penetration(43,2,.1f,.25f);
        check(Math.abs(railPush.y()-.15f)<.00001&&railPush.x()==0,"rail separates circular pawns by native plane distance");
        var rotated=geometry("P;4.5;65;5;65;5.000001;69;4.500001;69");
        var edgeContact=rotated.penetration(43,4.5f,65.1f,ScenePortals.PAWN_RADIUS);
        check(edgeContact.x()<-.33f&&Math.abs(edgeContact.y())<.001f,"exact rotated-edge contact keeps its native separating normal");
        for(String invalid:List.of("D;0;0;0;0;-1;-1;1;1","D;0;0;1;0;2;2;3;3"))try{geometry(invalid);throw new AssertionError("Invalid rail accepted");}catch(java.io.IOException expected){}
        var overlap=new TownMovement(terrain,-.2f,1);
        overlap.staticCollision(geometry,43);walk(overlap,1000,(float)Math.PI,100);
        check(overlap.x()<-.7f,"a pawn already overlapping static geometry can walk away");
        var corner=new TownMovement(terrain,-.34f,-.2f);
        corner.staticCollision(geometry,43);walk(corner,1000,0,700);
        check(corner.x()>2.5f&&corner.y()<-.33f,"walking into a rounded corner slides around it instead of sticking");
        check(geometry.clear(43,disc(ScenePortals.PAWN_RADIUS),corner.x(),corner.y(),0),"corner resolution leaves the pawn outside the wall");
        var wall=new TownMovement(terrain,-1,1);
        wall.staticCollision(geometry,43);walk(wall,1000,0,500);
        check(wall.x()<0&&wall.x()>-.34f,"separation stops a pawn at a solid wall without crossing it");
        var edge=new TownMovement(terrain,7.5f,-2);edge.staticCollision(geometry,43);walk(edge,1000,0,500);
        check(edge.x()<8,"collision resolution cannot move a pawn off the authored floor");
        var mob=new SceneContent.Spec(Map.of("native-type","Monster","config","Fixture","health","100","radius",".5","collision-flags","8"));
        var entities=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("mob",0,0,0,mob)),Map.of()),terrain);
        var trapped=new TownMovement(terrain,-.6f,0);
        trapped.dynamicPenetration((x,y)->entities.penetration(x,y,219));walk(trapped,1000,(float)Math.PI,100);
        check(trapped.x()<-1.2f&&entities.walkable(trapped.x(),trapped.y(),219),"a mob overlap cannot magnet a retreating knight");
        var glance=new TownMovement(terrain,-1,-.4f);
        glance.dynamicPenetration((x,y)->entities.penetration(x,y,219));walk(glance,1000,0,700);
        check(glance.x()>1&&glance.y()<-.7f,"a glancing contact slides around the mob's curved shape");
        var thin=geometry("P;0;-4;.02;-4;.02;4;0;4");
        var forced=new TownMovement(terrain,-.4f,0);forced.staticCollision(thin,43);
        var combat=new KnightCombat(WeaponCatalog.empty());forced.combat(combat);forced.resetInput(1000);
        combat.impulse(new KnightCombat.Impulse(1000,100,100,0));forced.advance(1100,1100);
        check(forced.x()<0,"native maximum substep keeps a large impulse from tunnelling through a thin wall");
        impulseFlags(terrain);
        System.out.println("PASS: overlap escape, rounded corner/mob sliding, solid/thin walls and replicated lunge guard");
    }
    private static void impulseFlags(CollisionMap terrain) {
        var attack=new WeaponCatalog.Attack(100,300,500,false,0,200,1,0);
        var sword=new WeaponCatalog.Weapon("Fixture",1000,0,0,true,false,1,1,List.of(attack),attack,attack,null);
        var combat=new KnightCombat(new WeaponCatalog(List.of(sword),List.of()));
        combat.equipment(new WorldProfile(219,1,1,0,0,1,List.of(new WorldProfile.Item(1,WorldProfile.Slot.SWORD,"Fixture",null,0,6,true))));
        var movement=new TownMovement(terrain,0,0);movement.combat(combat);movement.resetInput(1000);
        movement.accept(1000,0,0,4,1000,1000,1000);movement.advance(1100,1100);
        check((movement.flags()&128)!=0&&combat.state().impulse()!=null,"lunge publishes native ATTACK_IMPULSE_APPLIED alongside its impulse");
        movement.accept(1100,0,0,0,1100,1100,1100);movement.advance(1250,1250);
        check((movement.flags()&128)!=0&&combat.state().impulse()==null,"completed impulse keeps its guard until the attack ends");
        movement.advance(1500,1500);check((movement.flags()&128)==0,"idle clears the lunge guard");
        combat.reset(2000);combat.input(1024,2000,2000,0,0,true);
        check((movement.flags()&128)!=0,"dodge also carries the native impulse guard");
    }
    private static void walk(TownMovement movement,int start,float direction,int duration) {
        movement.resetInput(start);
        for(int time=start;time<start+duration;time+=25){
            check(movement.accept(time,direction,direction,1,time,time,time),"movement intent accepted");
            movement.advance(Math.min(time+25,start+duration),time+25);
        }
    }
    private static Object disc(float radius){return new FireCatalog.DataValue("Shape$Circle",Map.of("radius",radius));}
    private static SceneCollision geometry(String shape)throws Exception {
        Path folder=Files.createTempDirectory("tortodrone-pawn-collision-");
        try{
            var properties=new Properties();properties.setProperty("format","1");properties.setProperty("layout-sha256","fixture");
            properties.setProperty("tile-count","0");properties.setProperty("shape-count","1");properties.setProperty("shape.0.mask","43");properties.setProperty("shape.0.geometry",shape);
            int floors=0;for(int y=-8;y<8;y++)for(int x=-8;x<8;x++){
                properties.setProperty("floor."+floors+".x",Integer.toString(x));properties.setProperty("floor."+floors+".y",Integer.toString(y));floors++;
            }
            properties.setProperty("floor-count",Integer.toString(floors));
            try(var out=Files.newOutputStream(folder.resolve("static-collision.properties"))){properties.store(out,"Disposable pawn fixture");}
            return SceneCollision.load(folder,"fixture");
        }finally{Files.deleteIfExists(folder.resolve("static-collision.properties"));Files.delete(folder);}
    }
}
