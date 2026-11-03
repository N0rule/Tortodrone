package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Production entity damage/fire/scene clocks on synthetic geometry, not a Chasm playthrough. */
public final class SceneActorTemplatesTest {
    public static void main(String[] args)throws Exception {
        var downed=spec("Fixture/Downed",spawn("spawn_1"));
        var restored=spec("Fixture/Restored",spawn("spawn_0"));
        var original=spec("Fixture/Companion",spawn("spawn_0"));
        var entry=new SceneContent.Entry("companion",2,3,.7f,original);
        var definitions=Map.of("spawn_0",downed,"spawn_1",restored);
        var content=new SceneContent(List.of(entry),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),definitions);
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        var isolated=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(world);
        world.attacks((actor,action)->check(weapons.fire(action,actor,clock),"prepared death graph executes"));
        var parent=world.actors().getFirst();clock=1000;
        world.damage(hit(),2,3,0,clock,219);world.damage(hit(),2,3,0,clock,219);
        check(world.actors().size()==1,"death spawns are staged until the next authoritative tick");
        world.advance(1050,List.of());
        var child=named(world,"Fixture/Downed");
        check(child.id()!=parent.id()&&child.x()==2&&child.y()==3&&child.rotation()==.7f,"death replacement retains exact source transform and receives its own identity");
        check(child.entity().health()==1&&child.config().arguments().get("Variant").equals("Fixture/Downed"),"native identity, arguments and authoritative health retained");
        check(isolated.actors().size()==1&&isolated.actors().getFirst().config().name().equals("Fixture/Companion"),"shared immutable definitions do not share actors or queued work");
        world.advance(1150,List.of());check(world.actor(parent.id())==null,"dead source removed after its native lifetime and publication grace");
        clock=1200;world.damage(hit(),2,3,0,clock,219);world.advance(1250,List.of());
        check(named(world,"Fixture/Restored").entity().health()==1,"second lifecycle edge executes without recursively expanding either definition");
        clock=1400;world.advance(clock,List.of());world.damage(hit(),2,3,0,clock,219);world.advance(1450,List.of());
        check(world.actors().stream().filter(a->a.config().name().equals("Fixture/Downed")).count()==1,"cyclic death/respawn graph stays finite and does not replay earlier death actions");
        check(!SceneWeapons.usable(spawn("spawn_0")),"inventory use cannot invoke scene actor lifecycle operations");
        check(!weapons.fire(spawn("spawn_0"),new ActorState(999,1001,"Foreign",0,0,0,0,0,0,0,0,0,new StarterCatalog.Model("Foreign",Map.of())),1500),"nonexistent source cannot create an entity");
        Object validSource=world.sceneActorSource(named(world,"Fixture/Downed").id());check(validSource!=null,"negative checks use a valid local source");
        check(!world.spawnSceneActor("spawn_99",0,0,0,1500,validSource)&&!world.spawnSceneActor("spawn_0",Float.NaN,0,0,1500,validSource),"unknown templates and invalid geometry fail without mutation");
        check(!world.spawnSceneActor("spawn_0",0,0,0,1500,isolated.sceneActorSource(isolated.actors().getFirst().id())),"a valid source lease from another scene cannot create actors here");
        var bounded=new SceneEntities(content,SceneWeaponsTest.openTerrain());var boundedWeapons=new SceneWeapons(SceneWeaponsTest.openTerrain());boundedWeapons.targets(bounded);var source=bounded.actors().getFirst();
        for(int i=0;i<128;i++)check(boundedWeapons.fire(spawn("spawn_0"),source,2000),"bounded native spawn accepted");
        check(!boundedWeapons.fire(spawn("spawn_0"),source,2000),"one scene stamp cannot create unbounded native actors");
        bounded.advance(2050,List.of());check(bounded.actors().size()==129,"bounded batch publishes completely on the next tick");
        for(int i=129;i<4096;i++)check(boundedWeapons.fire(spawn("spawn_0"),source,2000+i),"scene capacity has room");
        check(!boundedWeapons.fire(spawn("spawn_0"),source,9000),"queued actors count toward scene capacity before publication");
        bounded.advance(9050,List.of());check(bounded.actors().size()==4096,"capacity limit includes original plus queued entities");
        refuse(()->new SceneContent(List.of(entry),Map.of()));
        var invalid=new HashMap<>(downed.fields());invalid.put("native-type","java.lang.Runtime");
        refuse(()->new SceneContent(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of("spawn_0",new SceneContent.Spec(invalid))));
        var startup=new HashMap<>(downed.fields());startup.put("startup-action",FireCatalog.actionJson(spawn("spawn_0")));
        refuse(()->new SceneContent(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of("spawn_0",new SceneContent.Spec(startup),"spawn_1",restored)));
        var unsupported=new FireCatalog.Action(FireCatalog.Kind.UNSUPPORTED,null,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"NativeMissingAction");
        var badSibling=new FireCatalog.Action(FireCatalog.Kind.COMPOUND,null,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(spawn("spawn_0"),unsupported),"");
        var unsupportedSpec=spec("Fixture/Unsupported",badSibling);
        refuse(()->new SceneContent(List.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of("spawn_0",unsupportedSpec,"spawn_1",restored)));
        var p=new Properties();p.setProperty("format","1");p.setProperty("layout-sha256","fixture");p.setProperty("actor-templates","spawn_0,spawn_1");
        for(var template:definitions.entrySet())template.getValue().fields().forEach((k,v)->p.setProperty("actor-template."+template.getKey()+"."+k,v));
        check(SceneContent.load(p,"fixture").actorTemplates().equals(definitions),"prepared content round-trip retains finite cyclic references");
        p.setProperty("actor-templates","spawn_0,spawn_0");try{SceneContent.load(p,"fixture");throw new AssertionError("Duplicate template accepted");}catch(java.io.IOException expected){}
        check(FireCatalog.actionJson(FireCatalog.actionJson(spawn("spawn_0"))).equals(spawn("spawn_0")),"typed action JSON round-trips");
        delayed(definitions);
        System.out.println("PASS: scene-owned cyclic death spawns, source transforms/config/health, exact death-once behavior, staging, scene isolation, bounded queues/capacity, invalid sources/templates, inventory refusal and content round-trip (synthetic fixtures; revive trigger policy not implemented)");
    }
    private static int clock;
    private static void delayed(Map<String,SceneContent.Spec> templates)throws Exception {
        var delay=new FireCatalog.Action(FireCatalog.Kind.DELAYED,new StarterCatalog.Model("Delayed",Map.of("delay",500,"variance",0)),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(spawn("spawn_0")),"");
        var content=new SceneContent(List.of(new SceneContent.Entry("parent",2,3,.7f,spec("Fixture/Parent",delay))),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),templates);
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(world);
        world.attacks((actor,action)->check(weapons.fire(action,actor,1000),"delayed shutdown queued"));
        Object lease=world.sceneActorSource(world.actors().getFirst().id());
        world.damage(hit(),2,3,0,1000,219);world.advance(1100,List.of());check(world.actors().isEmpty(),"dead source is gone before delayed spawn");
        check(!world.spawnSceneActor("spawn_0",2,3,.7f,1200,lease),"source removal revokes previously issued scene leases");
        weapons.advance(1499,List.of());world.advance(1499,List.of());check(world.actors().isEmpty(),"no spawn before authored delay");
        weapons.advance(1500,List.of());world.advance(1500,List.of());check(world.actors().isEmpty(),"native ActionLogic.removed cancels delayed lifecycle work when the source disappears");
        var retainedFields=new HashMap<>(spec("Fixture/Parent",delay).fields());retainedFields.put("death-time","1000");
        var retainedContent=new SceneContent(List.of(new SceneContent.Entry("parent",2,3,.7f,new SceneContent.Spec(retainedFields))),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),templates);
        var retained=new SceneEntities(retainedContent,SceneWeaponsTest.openTerrain());var retainedWeapons=new SceneWeapons(SceneWeaponsTest.openTerrain());retainedWeapons.targets(retained);
        retained.attacks((actor,action)->check(retainedWeapons.fire(action,actor,1000),"native delay queued before death animation ends"));
        retained.damage(hit(),2,3,0,1000,219);retained.advance(1499,List.of());retainedWeapons.advance(1499,retained.actors());check(retained.actors().size()==1,"no spawn before delay while source remains published");
        retainedWeapons.advance(1500,retained.actors());retained.advance(1500,List.of());check(named(retained,"Fixture/Downed").x()==2,"delayed lifecycle executes at the authored deadline while source remains present");
        var cancelled=new SceneEntities(content,SceneWeaponsTest.openTerrain());var cancelledWeapons=new SceneWeapons(SceneWeaponsTest.openTerrain());cancelledWeapons.targets(cancelled);
        var source=cancelled.actors().getFirst();check(cancelledWeapons.fire(delay,source,2000),"cancellable lifecycle queued");cancelledWeapons.cancel(source.id());cancelledWeapons.advance(2500,List.of());cancelled.advance(2500,List.of());
        check(cancelled.actors().size()==1,"cancelling source work removes pending deferred spawns");
    }
    private static ActorState named(SceneEntities world,String name){return world.actors().stream().filter(a->a.config().name().equals(name)&&a.entity().health()>0).findFirst().orElseThrow();}
    private static SceneContent.Spec spec(String name,FireCatalog.Action death)throws Exception {
        var fields=new HashMap<String,String>(Map.of("config",name,"kind","Monster","native-type","com.threerings.projectx.dungeon.data.actor.Monster","health","1","speed","0","radius",".4","damage-mask","2","death-time","0"));
        fields.put("config-json",FireCatalog.modelJson(new StarterCatalog.Model(name,Map.of("Variant",name))));fields.put("death-action",FireCatalog.actionJson(death));return new SceneContent.Spec(fields);
    }
    private static FireCatalog.Action spawn(String id){return new FireCatalog.Action(FireCatalog.Kind.SPAWN_SCENE_ACTOR,new StarterCatalog.Model("SceneActorTemplate",Map.of("template",id)),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");}
    private static StarterCatalog.Model hit(){return new StarterCatalog.Model("DamageRegion",Map.of("damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(new FireCatalog.FieldValue("flags","int",2),new FireCatalog.FieldValue("amount","float",1f)))));}
    private static void refuse(Runnable action){try{action.run();throw new AssertionError("Invalid template graph accepted");}catch(IllegalArgumentException expected){}}
}
