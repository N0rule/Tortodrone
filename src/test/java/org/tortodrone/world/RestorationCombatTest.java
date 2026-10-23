package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Actual retained player fire graphs against prepared Training Hall destructibles. */
public final class RestorationCombatTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var fires=FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json"));
        var types=new HashSet<String>();
        for(var entry:map.content().entries()) {
            var spec=entry.spec().child();String kind=spec.text("kind","");
            if(!Set.of("TimedBlock","StarlinkBlock","GhostlinkBlock","Block").contains(kind)||spec.integer("damage-mask",0)==0||!types.add(kind))continue;
            var content=new SceneContent(List.of(new SceneContent.Entry("target",0,0,0,spec)),map.content().scales());
            var entities=new SceneEntities(content,SceneWeaponsTest.openTerrain());
            var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.catalog(fires);weapons.targets(entities);
            int start=SceneClock.now()+10;
            var owner=new ActorState(10,1001,"Fixture",0,1,-3,0,0,0,0,292,start,new StarterCatalog.Model("Fixture",Map.of()));
            weapons.attack(10,new KnightCombat.AttackStart("Weapon/Handgun/Slicer/Mandolin",292,start,0,2000));
            int target=entities.actors().getFirst().id();
            for(int t=start+1;t<start+800;t+=16){weapons.advance(t,List.of(owner));entities.advance(t,List.of(owner));}
            var after=entities.actors().stream().filter(a->a.id()==target).findFirst();
            check(after.isEmpty()||after.get().entity().state()!=0||after.get().entity().hitTime()>0,"Mandolin hits Training Hall "+kind);
        }
        check(types.contains("TimedBlock")&&types.contains("Block"),"test exercised bombs and boxes");
        catalysts(fires);
        lethalCatalysts(fires);
        legacy(fires);
        System.out.println("PASS: Mandolin destructibles, eight Catalyzer families/cross-owner tags/expiry, four legacy guns/charges "+types);
    }
    private static SceneEntities target()throws Exception {
        var spec=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture","kind","Monster","health","10000","radius",".5","collision-flags","8","damage-mask","2"));
        return new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("target",0,0,0,spec)),Map.of()),SceneWeaponsTest.openTerrain());
    }
    private static ActorState owner(int id){return new ActorState(id,1000+id,"Fixture",0,1,-3,0,0,0,0,292,1,new StarterCatalog.Model("Fixture",Map.of()));}
    private static void advance(SceneWeapons weapons,SceneEntities entities,int from,int end,List<ActorState> owners){
        for(int t=from;t<=end;t+=10){entities.advance(t,owners);weapons.advance(t,owners);}
    }
    private static void catalysts(FireCatalog fires)throws Exception {
        for(String name:List.of("Catalyzer","Industrial Catalyzer","Volatile Catalyzer","Neutralizer","Toxic Catalyzer","Virulent Catalyzer","Biohazard","Pollinator")){
            var entities=target();var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(entities);weapons.catalog(fires);
            var first=owner(10);var second=owner(11);var owners=List.of(first,second);
            int id=entities.actors().getFirst().id();
            var normal=fires.find("Weapon/Handgun/"+name,292).fireRefs().getFirst().action();
            check(weapons.fire(normal,first,100),"normal shot emits "+name);weapons.fire(normal,second,100);
            advance(weapons,entities,101,1501,owners);
            var tags=weapons.actors().stream().filter(a->a.projectile()!=null&&a.projectile().nativeType().equals("ORBITAL")&&a.projectile().destroyed()==Integer.MAX_VALUE).toList();
            check(tags.size()==2&&tags.stream().allMatch(a->a.projectile().orbit().followId()==id),"two owners' tags follow the actual struck monster: "+name);
            check(Math.abs(entities.actor(id).entity().health()-.9998f)<.000001f,"normal tagging shots do exactly one damage: "+name+" health="+entities.actor(id).entity().health());
            float health=entities.actor(id).entity().health();
            check(weapons.fire(fires.find("Weapon/Handgun/"+name,290).fireRefs().getFirst().action(),second,1600),"charge emits "+name);
            advance(weapons,entities,1601,3001,owners);
            check(weapons.actors().stream().noneMatch(a->a.projectile()!=null&&a.projectile().nativeType().equals("ORBITAL")&&a.projectile().destroyed()==Integer.MAX_VALUE),"another owner's charge detonates both tags: "+name);
            check(entities.actor(id).entity().health()<health-.0002f&&!weapons.effects().isEmpty(),"charge and retained tag explosion damage/effects execute: "+name);
            check(weapons.effects().stream().allMatch(e->e.ownerOid()==0),"server detonations reach shooters and observers");
            var untouched=target();var expiry=new SceneWeapons(SceneWeaponsTest.openTerrain());expiry.targets(untouched);expiry.catalog(fires);
            expiry.fire(normal,first,100);advance(expiry,untouched,101,1501,owners);
            int victim=untouched.actors().getFirst().id();float before=untouched.actor(victim).entity().health();
            advance(expiry,untouched,1511,22001,owners);
            check(untouched.actor(victim).entity().health()==before&&expiry.actors().isEmpty(),"untriggered tags expire harmlessly after twenty seconds: "+name);
        }
    }
    private static void legacy(FireCatalog fires)throws Exception {
        for(String name:List.of("Fiery Diskgun","Static Diskgun","Overcharged Mixmaster (OLD)","Celestial Orbitgun (OLD)"))for(int activity:List.of(292,290)){
            var entities=target();var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(entities);weapons.catalog(fires);var owner=owner(10);
            var fire=fires.find("Weapon/Handgun/"+name,activity).fireRefs().getFirst().action();
            check(weapons.fire(fire,owner,100),"legacy carrier spawns: "+name);
            check(weapons.actors().stream().anyMatch(a->a.entity()!=null&&a.entity().kind().equals("Agent")),"legacy carrier uses native Agent representation");
            advance(weapons,entities,101,1601,List.of(owner));
            check(entities.actors().getFirst().entity().health()<1,"legacy startup orbitals shoot and damage: "+name+"/"+activity);
            advance(weapons,entities,1611,4001,List.of(owner));check(weapons.actors().isEmpty(),"legacy actors clean up with their carrier");
        }
    }

    private static void lethalCatalysts(FireCatalog fires)throws Exception {
        var spec=new SceneContent.Spec(Map.of("native-type","Monster","config","Fixture","health","3","radius",".5","collision-flags","8","damage-mask","2"));
        var entities=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("target",0,0,0,spec)),Map.of()),SceneWeaponsTest.openTerrain());
        var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.catalog(fires);weapons.targets(entities);
        var owner=owner(10);var normal=fires.find("Weapon/Handgun/Catalyzer",292).fireRefs().getFirst().action();
        weapons.fire(normal,owner,100);weapons.fire(normal,owner,100);
        advance(weapons,entities,101,1501,List.of(owner));
        check(weapons.actors().stream().filter(a->a.projectile().nativeType().equals("ORBITAL")).count()==2,"lethal regression has two live tags");
        weapons.fire(fires.find("Weapon/Handgun/Catalyzer",290).fireRefs().getFirst().action(),owner,1600);
        advance(weapons,entities,1601,3001,List.of(owner));
        check(weapons.actors().isEmpty(),"a lethal first tag does not strand remaining tags or the charged projectile");
        check(weapons.fire(normal,owner,3100),"weapon remains usable after lethal detonation");
        advance(weapons,entities,3101,6001,List.of(owner));
        check(weapons.actors().isEmpty(),"subsequent shots still advance and clean up");
    }

}
