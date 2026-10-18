package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Real exported vortex chains, including range, immunity, timing and final damage. */
public final class VortexBombTest {
    public static void main(String[] args)throws Exception {
        var catalog=FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json"));
        for(String name:List.of("Graviton Vortex","Electron Vortex","Celestial Vortex"))verify(catalog,"Weapon/Bomb/"+name);
        System.out.println("PASS: all three vortex bombs pull nearby monsters through five timed stages, respect range/masks/impulse immunity, damage once at the end and terminate");
    }
    private static SceneContent.Spec monster(int mask,int level){
        return new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture",
            "health","10000","damage-mask",Integer.toString(mask),"impulse-level",Integer.toString(level),"radius",".2","collision-flags","8"));
    }
    private static void verify(FireCatalog catalog,String name)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();
        var content=new SceneContent(List.of(
            new SceneContent.Entry("near",1.7f,0,0,monster(2,1)),
            new SceneContent.Entry("outside",3.5f,0,0,monster(2,1)),
            new SceneContent.Entry("mask",0,1.7f,0,monster(1,1)),
            new SceneContent.Entry("immune",0,-1.7f,0,monster(2,9))),Map.of());
        var world=new SceneEntities(content,terrain);var originals=world.actors();
        var feedback=new ArrayList<SceneCombatEvent>();world.feedback(feedback::add);
        var impulses=new ArrayList<Integer>();var damage=new ArrayList<Integer>();
        var scene=new SceneWeapons(terrain);scene.catalog(catalog);
        scene.targets(new SceneWeapons.Targets(){
            public boolean contact(float x,float y,int owner){return false;}
            public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){throw new AssertionError("Missing projectile source shape");}
            public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object shape){
                damage.add(stamp);world.damage(region,x,y,rotation,stamp,owner,shape);
            }
            public void impulse(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
                check(Math.abs(SceneDamage.number(region.arguments().get("shape"),"radius",0)-1.95f)<.001f,name+" retains its native pull radius");
                impulses.add(stamp);world.impulse(region,x,y,rotation,stamp,owner);
            }
        });
        var player=new ActorState(219,1001,"Fixture",0,100,0,0,0,0,0,290,100,new StarterCatalog.Model("Fixture",Map.of()));
        int[] playerImpulses={0};world.playerImpulse((id,impulse)->playerImpulses[0]++);
        scene.attack(player.id(),new KnightCombat.AttackStart(name,290,100,0,400));
        for(int stamp=101;stamp<=6001;stamp+=25){
            world.advance(stamp,List.of(player));scene.advance(stamp,List.of(player));
            if(stamp==2576)check(world.actor(originals.getFirst().id()).x()==1.7f,name+" waits for the first implode fuse");
            if(stamp==2801)check(world.actor(originals.getFirst().id()).x()<1,name+" pulls a target well outside the old fallback area");
        }
        check(impulses.equals(List.of(2600,3100,3600,4100,4600)),name+" executes exactly five half-second pull stages");
        check(damage.equals(List.of(2100,5100)),name+" preserves opening/final damage timing");
        check(feedback.stream().filter(e->e.actor()==originals.getFirst().id()&&e.timestamp()==5100&&e.amount()>0).count()==1,name+" final explosion damages the pulled target once");
        check(feedback.stream().noneMatch(e->e.actor()==originals.get(1).id()),name+" does not damage the distant target");
        for(int i=1;i<originals.size();i++){
            var before=originals.get(i);var after=world.actor(before.id());
            check(before.x()==after.x()&&before.y()==after.y(),name+" respects range, damage mask and impulse immunity");
        }
        check(playerImpulses[0]==0,name+" monster pull does not affect its owner");
        check(scene.actors().isEmpty(),name+" removes all vortex carrier actors");
        scene.advance(20000,List.of(player));check(impulses.size()==5&&damage.size()==2,name+" chain does not repeat");
    }
}
