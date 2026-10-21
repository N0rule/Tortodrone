package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Shared-instance knockback, combat occlusion and independent ignitable oil patches. */
public final class SharedGameplayTest {
    private static final StarterCatalog.Model PLAYER=new StarterCatalog.Model("Character/PC/Level",Map.of());
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        knockback(map);
        barrier(map);
        oil(map);
        System.out.println("PASS: revive knockback, knight collision, thin barrier occlusion, oil spread/ignition/expiry and instance isolation");
    }
    private static SceneContent.Spec monster(){
        return new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture",
            "health","1000","radius",".2","collision-flags","8","collision-mask","79","damage-mask","2"));
    }
    private static ActorState player(int id,float x,float y){return new ActorState(id,1000+id,"Fixture",0,1000,x,y,0,0,0,PLAYER);}
    private static SceneEntities world(SceneCatalog.MapScene map,SceneContent.Entry... entries){
        return new SceneEntities(new SceneContent(List.of(entries),map.content().scales(),Map.of(),map.content().stats(),Map.of(),Map.of(),map.content().statuses()),map.collision());
    }
    private static void knockback(SceneCatalog.MapScene map){
        var entry=new SceneContent.Entry("monster",6.3f,-12.5f,0,monster());
        var world=world(map,entry);var unrelated=world(map,entry);
        var owner=player(7,5.5f,-12.5f);var peer=player(8,7.2f,-12.5f);
        var occupants=List.of(owner,peer);world.advance(1000,occupants);
        int monster=world.actors().getFirst().id();var initial=world.actor(monster);
        var peerImpulses=new ArrayList<KnightCombat.Impulse>();world.playerImpulse((id,impulse)->peerImpulses.add(impulse));
        world.reviveImpulse(owner,1000);
        world.advance(1400,occupants);
        var bumped=world.actor(monster);
        check(bumped.x()>initial.x(),"Emergency Revive pushes nearby enemies away");
        check(bumped.x()<=peer.x()-.2f-ScenePortals.PAWN_RADIUS+.001f,"radial knockback cannot push a monster into another knight");
        check(peerImpulses.isEmpty(),"revive monster mask never knocks a cooperative knight back");
        check(unrelated.actor(monster).x()==initial.x(),"another instance retains its own monster pose");
        var leaving=List.of(owner);world.advance(1450,leaving);world.reviveImpulse(owner,1450);world.advance(1850,leaving);
        check(world.actor(monster).x()>peer.x(),"departed knight leaves no stale collision body");
        var far=world(map,new SceneContent.Entry("far",9,-12.5f,0,monster()));far.advance(1000,List.of(owner));
        far.reviveImpulse(owner,1000);far.advance(1400,List.of(owner));check(far.actors().getFirst().x()==9,"revive radius excludes distant enemies");
    }
    private static StarterCatalog.Model hit(int mask,Object damage){
        return new StarterCatalog.Model("DamageRegionFromPoint",Map.of("damage",damage,"collision-mask",mask,
            "region",new FireCatalog.DataValue("com.threerings.tudey.config.RegionConfig$Default",Map.of("expansion",2f))));
    }
    private static Object normal(){return new FireCatalog.DataValue("Damage$Normal",Map.of("amount",10f,"flags",2));}
    private static void barrier(SceneCatalog.MapScene map){
        for(boolean open:new boolean[]{false,true}){
            var door=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Door","config","Fixture",
                "collision-flags","192","shape","Rectangle","width",".25","height","3","start-open",Boolean.toString(open)));
            var world=world(map,new SceneContent.Entry("gate",6.5f,-12.5f,0,door),new SceneContent.Entry("monster",7,-12.5f,0,monster()));
            int id=world.actors().getLast().id();world.advance(1000,List.of(player(7,6,-12.5f)));
            world.damage(hit(129,normal()),6,-12.5f,0,1000,7);
            check(world.actor(id).entity().health()==(open?.99f:1f),"closed thin gate blocks sword regions; open gate permits damage");
            world.damage(hit(0,normal()),6,-12.5f,0,1001,7);
            check(world.actor(id).entity().health()<1,"mask-zero area effects retain their authored behavior");
        }
        // Authored Training monster barrier: 0.25 units wide, static mask 64|128.
        var world=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("monster",28.9f,-1.5f,0,monster())),map.content().scales()),map.collision(),map.staticCollision());
        int id=world.actors().getFirst().id();world.advance(1000,List.of(player(7,28.1f,-1.5f)));
        world.damage(hit(129,normal()),28.1f,-1.5f,0,1000,7);
        check(world.actor(id).entity().health()==1,"actual static Training barrier blocks sword reach");
    }
    private static void oil(SceneCatalog.MapScene map)throws Exception {
        var fields=new HashMap<String,String>();fields.put("native-type","com.threerings.projectx.dungeon.data.actor.DungeonLift");
        fields.put("config","Dynamic/Lift Objects/Oil Pot");fields.put("radius",".45");fields.put("collision-flags","514");
        var entry=new SceneContent.Entry("oil",5.5f,-12.5f,0,new SceneContent.Spec(fields));
        var world=world(map,entry);var other=world(map,entry);
        var land=SceneEntities.class.getDeclaredMethod("potLand",Class.forName("org.tortodrone.world.SceneEntities$Entity"),int.class);land.setAccessible(true);
        var entities=SceneEntities.class.getDeclaredField("entities");entities.setAccessible(true);
        land.invoke(world,((Map<?,?>)entities.get(world)).values().iterator().next(),1000);
        var slicks=world.actors().stream().filter(a->a.config().name().equals("Bomb/Monster/Oil Slick")).toList();
        check(slicks.size()==9,"oil smash creates nine patches in open supported space");
        for(var a:slicks)for(var b:slicks)if(a.id()!=b.id())check(Math.hypot(a.x()-b.x(),a.y()-b.y())>=.9f,"slicks occupy separate locations");
        var oil=slicks.getFirst();world.damage(hit(0,normal()),oil.x(),oil.y(),0,1100,7);
        check(torches(world)==0,"ordinary damage cannot light oil");
        var fire=new FireCatalog.DataValue("Damage$Status",Map.of("flags",2,"statusCondition",new StarterCatalog.Model("Fire",Map.of())));
        world.damage(hit(0,fire),oil.x(),oil.y(),0,1200,7);int count=torches(world);
        check(count>0,"Fire damage ignites oil with native ScorcherTorch actors");
        world.damage(hit(0,fire),oil.x(),oil.y(),0,1201,7);check(torches(world)==count,"repeated ignition cannot duplicate a patch's torch");
        check(other.actors().size()==1,"oil and fire cannot leak into another instance");
        world.advance(18000,List.of());world.advance(18101,List.of());check(world.actors().stream().allMatch(a->a.config().name().equals("Dynamic/Lift Objects/Oil Pot")),"slicks and torches expire");
    }
    private static int torches(SceneEntities world){return (int)world.actors().stream().filter(a->a.config().name().equals("Bomb/Monster/ScorcherTorch")).count();}
}
