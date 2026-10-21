package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Real item graphs against instance-owned enemies; no accounts or client assets. */
public final class EnemyItemStatusTest {
    private static final StarterCatalog.Model MODEL=new StarterCatalog.Model("Fixture",Map.of());
    public static void main(String[] args)throws Exception {
        var prepared=SceneCatalog.load(ContentFixture.path("maps")).scene(294).content();
        var fires=FireCatalog.load(ContentFixture.path("combat/fires.json"));
        var controls=WeaponCatalog.load(ContentFixture.path("combat/weapons.tsv"));
        var terrain=SceneWeaponsTest.openTerrain();
        enemyBehavior(prepared,terrain);
        int clouds=0;
        for(var attack:fires.entries())if(attack.weaponConfig().startsWith("Weapon/Bomb/")&&attack.activity()==290){
            var actors=new ArrayList<FireCatalog.ActorSpec>();attack.fireRefs().forEach(ref->collect(ref.action(),actors));
            var cloud=actors.stream().filter(a->a.config().name().equals("Bomb/Parts/Chemical (Cloud)")).findFirst().orElse(null);
            if(cloud!=null){cloud(prepared,fires,terrain,attack,cloud);clouds++;}
        }
        check(clouds==33,"all 33 chemical bomb definitions exercise real enemy hits");
        for(var sample:List.of(
                new Sample("Weapon/Sword/Vile Striker",292,"Poison",.8f),
                new Sample("Weapon/Sword/Blazebrand",290,"Fire",1.3f),
                new Sample("Weapon/Sword/Blizzbrand",290,"Freeze",1.3f),
                new Sample("Weapon/Sword/Gran Faust",290,"Curse",1.3f),
                new Sample("Weapon/Handgun/Frost Gun",292,"Freeze",1.6f),
                new Sample("Weapon/Handgun/Stun Gun",292,"Stun",1.6f),
                new Sample("Weapon/Handgun/Zapper",292,"Shock",1.6f),
                new Sample("Weapon/Handgun/Obsidian Carbine",292,"Poison",1.6f),
                new Sample("Weapon/Bomb/Cold Snap",290,"Freeze",1.6f)))
            weapon(prepared,fires,controls,terrain,sample);
        System.out.println("PASS: 33 chemical bomb blasts/clouds, cloud timing/range/masks/expiry, sword/gun/bomb statuses and enemy stun/freeze/poison behavior");
    }
    private record Sample(String weapon,int activity,String kind,float x) { }
    private static SceneContent.Spec monster(int mask){
        return new SceneContent.Spec(Map.of("native-type","Monster","config","Monster/Fixture","health","10000",
            "radius",".2","damage-mask",Integer.toString(mask),"collision-flags","8","impulse-level","9"));
    }
    private static SceneContent content(SceneContent prepared,List<SceneContent.Entry> entries){
        var stats=new HashMap<>(prepared.stats());
        // Remove RNG from non-CUSTOM chance selections in this fixture. Keep authored
        // CUSTOM zero-chance placeholders disabled and production probabilities intact.
        stats.replaceAll((key,value)->key.startsWith("status-chance.")&&!key.endsWith(".CUSTOM")?1f:value);
        return new SceneContent(entries,prepared.scales(),Map.of(),stats,prepared.items(),Map.of(),prepared.statuses());
    }
    private static ActorState owner(int stamp,int activity){return new ActorState(219,1001,"Fixture",0,stamp,0,0,0,0,0,activity,stamp,MODEL);}
    private static boolean has(SceneContent prepared,ActorState actor,String kind){
        return actor.entity().status().conditions().stream().anyMatch(ref->{
            var spec=prepared.statuses().get(ref.name());return spec!=null&&spec.text("class","").equals(kind);
        });
    }
    private static void cloud(SceneContent prepared,FireCatalog fires,CollisionMap terrain,FireCatalog.AttackFire attack,FireCatalog.ActorSpec cloud){
        String name=attack.weaponConfig();float radius=((Number)cloud.config().arguments().get("Radius")).floatValue();
        var content=content(prepared,List.of(
            new SceneContent.Entry("blast",.7f,0,0,monster(2)),
            new SceneContent.Entry("edge",radius+.1f,0,0,monster(2)),
            new SceneContent.Entry("outside",radius+.3f,0,0,monster(2)),
            new SceneContent.Entry("masked",0,.7f,0,monster(1))));
        var world=new SceneEntities(content,terrain);var original=world.actors();
        var scene=new SceneWeapons(terrain);scene.catalog(fires);scene.targets(world);
        var actors=new ArrayList<FireCatalog.ActorSpec>();attack.fireRefs().forEach(ref->collect(ref.action(),actors));
        var bomb=actors.getFirst();int stamp=SceneClock.now()+10,burst=stamp+bomb.fuse();
        var player=owner(stamp,290);var players=List.of(player);
        scene.attack(player.id(),new KnightCombat.AttackStart(name,290,stamp,0,1000));
        world.advance(stamp,players);scene.advance(stamp+1,players);scene.advance(burst,players);
        check(world.actor(original.getFirst().id()).entity().health()<1,name+" opening blast reaches a nearby enemy");
        var timer=cloud.timers().getFirst();int first=burst+timer.delay();
        scene.advance(first-1,players);
        check(world.actor(original.get(1).id()).entity().status().conditions().isEmpty(),name+" waits for its status pulse");
        scene.advance(first,players);
        var edge=world.actor(original.get(1).id());
        check(!edge.entity().status().conditions().isEmpty(),name+" cloud affects an enemy overlapping its outer edge");
        check(world.actor(original.get(2).id()).entity().status().conditions().isEmpty(),name+" cloud excludes enemies beyond its radius");
        check(world.actor(original.get(3).id()).entity().status().conditions().isEmpty(),name+" preserves the enemy damage mask");
        var ref=edge.entity().status().conditions().getFirst();String kind=content.statuses().get(ref.name()).text("class","");
        consequences(content,world,edge,kind,first,players);
        int ended=burst+cloud.fuse()+101;scene.advance(ended,players);scene.advance(ended+101,players);
        check(scene.actors().isEmpty(),name+" removes the expired bomb and cloud");
        int expires=world.actor(edge.id()).entity().status().expires().getFirst();
        world.advance(expires+1,players);
        check(!has(content,world.actor(edge.id()),kind),name+" enemy status expires");
    }
    private static void weapon(SceneContent prepared,FireCatalog fires,WeaponCatalog controls,CollisionMap terrain,Sample sample){
        var content=content(prepared,List.of(new SceneContent.Entry("target",sample.x(),0,0,monster(2))));
        var world=new SceneEntities(content,terrain);int id=world.actors().getFirst().id();
        var scene=new SceneWeapons(terrain);scene.catalog(fires);scene.targets(world);
        int stamp=SceneClock.now()+10;var player=owner(stamp,sample.activity());var players=List.of(player);
        var weapon=controls.find(sample.weapon());var timing=sample.activity()==290?weapon.charged():weapon.attacks().get(sample.activity()-292);
        scene.attack(player.id(),new KnightCombat.AttackStart(sample.weapon(),sample.activity(),stamp,timing.land(),timing.clear()));
        boolean applied=false;
        for(int at=stamp+1;at<stamp+6500;at+=20){
            world.advance(at,players);scene.advance(at,players);var target=world.actor(id);
            if(has(content,target,sample.kind())){consequences(content,world,target,sample.kind(),at,players);applied=true;break;}
        }
        check(applied,sample.weapon()+" applies "+sample.kind()+" to the enemy through its actual attack graph");
    }
    private static void consequences(SceneContent content,SceneEntities world,ActorState target,String kind,int stamp,List<ActorState> players){
        var status=target.entity().status().modifiers();
        switch(kind){
            case "Freeze"->check(!status.canMove()&&!status.canRotate(),"enemy freeze blocks movement and rotation");
            case "Stun"->check(status.speed()<1&&status.attackSpeed()<1,"enemy stun slows movement and attacks");
            case "Poison"->check(status.attackDamage()<1&&status.defense()<1&&status.heal()<1,"enemy poison weakens attacks, defense and healing");
            case "Sleep"->check(status.asleep()&&!status.canMove()&&!status.canAttack(),"enemy sleep blocks movement and attacks");
            case "Fire"->{float before=target.entity().health();world.advance(stamp+1,players);check(world.actor(target.id()).entity().health()<before,"enemy fire inflicts periodic damage");}
            case "Shock"->{world.advance(stamp+1,players);check(world.actor(target.id()).entity().status().modifiers().shocked(),"enemy shock enters its spasm");}
            case "Curse"->check(has(content,target,"Curse"),"enemy curse persists after the hit");
            default->throw new AssertionError("Unchecked item status: "+kind);
        }
    }
    private static void collect(FireCatalog.Action action,List<FireCatalog.ActorSpec> actors){
        if(action==null)return;
        if(action.actor()!=null){var actor=action.actor();actors.add(actor);collect(actor.detonation(),actors);actor.timers().forEach(timer->collect(timer.action(),actors));}
        action.children().forEach(child->collect(child,actors));
    }
    private static StarterCatalog.Model region(Object damage){
        return new StarterCatalog.Model("DamageRegion",Map.of("damage",damage,"shape",
            new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f))));
    }
    private static Object status(String kind){
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of("flags",2,
            "statusCondition",new StarterCatalog.Model(kind,Map.of("Chance",1f,"Power",100,"Duration",20000))));
    }
    private static void enemyBehavior(SceneContent prepared,CollisionMap terrain)throws Exception {
        var fields=new HashMap<>(monster(2).fields());fields.putAll(Map.of("acquire-distance","10","attack-distance","2",
            "attack","0","attack-interval","1000","attack.0.land","200","attack.0.clear","600","attack.0.fire-speed","1","attack.0.fires","0"));
        fields.put("attack.0.fire.0.delay","100");
        fields.put("attack.0.fire.0.action",FireCatalog.actionJson(new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,MODEL,null,
            "Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),"")));
        var content=content(prepared,List.of(new SceneContent.Entry("attacker",0,0,0,new SceneContent.Spec(fields))));
        for(boolean stunned:List.of(false,true)){
            var world=new SceneEntities(content,terrain);int id=world.actors().getFirst().id(),stamp=SceneClock.now()+10;
            var players=List.of(owner(stamp,0));int[] fires={0};world.attacks((actor,action)->{if(action.kind()==FireCatalog.Kind.FIRE_EFFECT)fires[0]++;});
            if(stunned)world.damage(region(status("Stun")),0,0,0,stamp,219);
            world.advance(stamp,players);check(world.actor(id).activity()==4,"enemy begins its authored attack");
            float speed=world.actor(id).entity().status().modifiers().attackSpeed();
            int land=Math.round(300/speed),clear=Math.round(600/speed),rearm=Math.round(1000/speed);
            world.advance(stamp+land-1,players);check(fires[0]==0,"enemy attack waits for its status-scaled land time");
            world.advance(stamp+land,players);check(fires[0]==1,"enemy attack fires at its status-scaled land time");
            world.advance(stamp+clear-1,players);check(world.actor(id).activity()==4,"enemy attack retains its status-scaled duration");
            world.advance(stamp+clear,players);check(world.actor(id).activity()==0,"enemy attack clears at its status-scaled deadline");
            world.advance(stamp+rearm-1,players);check(world.actor(id).activity()==0,"enemy rearm respects the status speed");
            world.advance(stamp+rearm,players);check(world.actor(id).activity()==4,"enemy can attack again after its slowed cooldown");
        }
        var frozen=new SceneEntities(content,terrain);int id=frozen.actors().getFirst().id(),stamp=SceneClock.now()+10;
        frozen.damage(region(status("Freeze")),0,0,0,stamp,219);
        var north=new ActorState(219,1001,"Fixture",0,stamp,0,1,0,0,0,MODEL);
        frozen.advance(stamp,List.of(north));check(frozen.actor(id).rotation()==0,"frozen enemy cannot rotate to aim an attack");
        // A status applied during an existing attack takes effect on the next activity.
        var pending=new SceneEntities(content,terrain);var players=List.of(owner(stamp,0));int[] fires={0};pending.attacks((a,b)->{if(b.kind()==FireCatalog.Kind.FIRE_EFFECT)fires[0]++;});
        pending.advance(stamp,players);pending.damage(region(status("Stun")),0,0,0,stamp+100,219);
        pending.advance(stamp+300,players);check(fires[0]==1,"stun preserves an attack's already-captured speed");
        var cursed=new SceneEntities(content,terrain);int cursedId=cursed.actors().getFirst().id();
        cursed.damage(region(status("Curse")),0,0,0,stamp,219);
        cursed.advance(stamp,players);check(cursed.actor(cursedId).entity().health()<1,"a fully powered enemy curse inflicts self damage when the enemy attacks");
        for(boolean poisoned:List.of(false,true)){
            var world=new SceneEntities(content(prepared,List.of(new SceneContent.Entry("healing",0,0,0,monster(2)))),terrain);
            int target=world.actors().getFirst().id();
            world.damage(region(new FireCatalog.DataValue("Damage$Normal",Map.of("flags",2,"amount",100f))),0,0,0,stamp,219);
            world.damage(region(status("Sleep")),0,0,0,stamp,219);
            if(poisoned)world.damage(region(status("Poison")),0,0,0,stamp,219);
            float before=world.actor(target).entity().health();
            Object sleep=prepared.statuses().get("Sleep").model("data").arguments().get("data");
            int firstHeal=stamp+(int)(SceneDamage.number(sleep,"healthRegenDelay",0)+SceneDamage.number(sleep,"healthRegenInterval",0));
            world.advance(firstHeal,List.of(owner(stamp,0)));
            check(poisoned?world.actor(target).entity().health()==before:world.actor(target).entity().health()>before,
                "enemy poison suppresses sleep healing while an unpoisoned enemy regenerates");
        }
    }
}
