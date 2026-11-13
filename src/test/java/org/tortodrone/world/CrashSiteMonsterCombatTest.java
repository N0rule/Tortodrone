package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Bundled Crash Site monsters: native depth-0 defense and health, Proto hit values and the
 * MonsterLogic.shouldReact flinch rule through the production entity damage path. */
public final class CrashSiteMonsterCombatTest {
    private static final String JELLY="Character/NPC/Monster/Slime/Jelly Cube (Intro)",
        WHELP="Character/NPC/Monster/Beast/Chromalisk/Chromalisk Whelp (Intro)",
        PUPPY="Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)",
        GIANT="Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant";

    public static void main(String[] args)throws Exception {
        var map=ContentManager.load(Path.of(".")).scenes().scene(398);
        var spawns=new HashMap<String,List<Map<String,String>>>();
        for(var entry:map.content().entries()){
            if(entry.spec().text("action","").isEmpty())continue;
            for(var fields:spawnFields(SceneActions.rule(entry).action()))if("Monster".equals(fields.get("kind"))&&fields.get("config").startsWith("Character/NPC/Monster/"))
                spawns.computeIfAbsent(fields.get("config"),k->new ArrayList<>()).add(fields);
        }
        check(spawns.keySet().equals(Set.of(JELLY,WHELP,PUPPY,GIANT))&&spawns.get(JELLY).size()==5&&spawns.get(WHELP).size()==2
            &&spawns.get(PUPPY).size()==6&&spawns.get(GIANT).size()==1,"every hostile Crash Site monster spawn is audited: "+spawns.entrySet().stream().map(e->e.getKey()+"="+e.getValue().size()).toList());
        // Depth-0 Monster/Defense scales: Base 20, +25 1420, -10 10 (shared scale table).
        expect(spawns.get(JELLY),"20.0",20,1420,20,10);
        expect(spawns.get(WHELP),"20.0",20,10,1420,20);
        expect(spawns.get(PUPPY),"20.0",20,1420,10,20);
        expect(spawns.get(GIANT),"1440",20,1420,10,20);
        check(spawns.get(WHELP).stream().allMatch(f->"true".equals(f.get("attack.6.interruptable")))
            &&spawns.values().stream().flatMap(List::stream).filter(f->!WHELP.equals(f.get("config"))).noneMatch(f->f.keySet().stream().anyMatch(k->k.endsWith(".interruptable"))),
            "only the Chromalisk panic emote is authored interruptable");

        var jelly=spawns.get(JELLY).getFirst();
        // Proto Sword swings 1-2 (20, react only), swing 3 (23, major), Proto Gun (12.5): live 10/13/6.
        var first=hit(jelly,normal(20,false));
        check(first.health()==10f/20&&first.activity()!=1,"a basic swing lands 10 and does not stun the Jelly");
        var finisher=hit(jelly,normal(23,true));
        check(finisher.health()==7f/20&&finisher.activity()==1,"the major third swing lands 13 and flinches above min-react-percent");
        var shot=hit(jelly,normal(12.5f,false));
        check(shot.health()==14f/20&&shot.activity()!=1,"a basic gun shot lands 6 and does not stun");
        var giant=hit(spawns.get(GIANT).getFirst(),normal(23,true));
        check(giant.health()==1427f/1440&&giant.activity()!=1,"the Ironwood Giant (min-react 1.0) never flinches");

        // Window semantics on a synthetic monster: major hits sum over one second, strict comparison.
        var synthetic=new HashMap<String,String>(Map.of("config","Fixture/Reactor","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster",
            "health","100","damage-mask","2","radius",".2","min-react-percent","0.5"));
        var scene=scene(synthetic);int id=scene.actors().getFirst().id();int stamp=SceneClock.now()+100;
        scene.damage(region(normal(30,true)),0,0,0,stamp,219);check(scene.actor(id).activity()!=1,"one major hit below the threshold does not flinch");
        scene.damage(region(normal(20,true)),0,0,0,stamp+999,219);check(scene.actor(id).activity()!=1,"exactly reaching the threshold is not strictly greater");
        scene.damage(region(normal(1,true)),0,0,0,stamp+1000,219);check(scene.actor(id).activity()==1,"major damage summed inside the one-second window flinches");
        var expired=scene(synthetic);int other=expired.actors().getFirst().id();
        expired.damage(region(normal(30,true)),0,0,0,stamp,219);expired.damage(region(normal(30,true)),0,0,0,stamp+1001,219);
        check(expired.actor(other).activity()!=1,"damage older than the window no longer counts");
        var minor=scene(synthetic);int quiet=minor.actors().getFirst().id();
        for(int i=0;i<3;i++)minor.damage(region(normal(30,false)),0,0,0,stamp+i,219);
        check(minor.actor(quiet).activity()!=1&&minor.actor(quiet).entity().health()==.1f,"reacting but non-major damage never flinches outside an interruptable attack");

        // An interruptable attack yields to any reacting hit; a normal attack does not.
        for(boolean interruptable:List.of(true,false)){
            var attacker=new HashMap<String,String>(synthetic);
            attacker.putAll(Map.of("speed","0","acquire-distance","3","attack-distance","2","attack","0","acquisition-interval","0","attack-interval","100000",
                "attack.0.land","5000","attack.0.clear","6000","attack.0.fire-speed","1","attack.0.fires",""));
            if(interruptable)attacker.put("attack.0.interruptable","true");
            var fight=scene(attacker);int monster=fight.actors().getFirst().id();int start=SceneClock.now()+100;
            var player=new ActorState(219,1001,"Fixture",256,start,.5f,0,0,0,0,0,start,new StarterCatalog.Model("Fixture",Map.of()),false);
            fight.advance(start,List.of(player));check(fight.actor(monster).activity()==4,"fixture monster is attacking");
            fight.damage(region(normal(5,false)),0,0,0,start+1,219);
            check((fight.actor(monster).activity()==1)==interruptable,"reacting hit interrupts only an interruptable attack");
        }
        warning(spawns.get(GIANT).getFirst());
        knockback(map,jelly);
        System.out.println("PASS: Crash Site monster defense/health from native scales, Proto 10/13/6 hits, native one-second major/interruptable flinch rule, timed Giant telegraph and Jelly knockback only on landed hits");
    }
    /** The Giant's slam telegraph is a timed marker (live 1306-1406 ms), not a part that
     * outlives every slam until the Giant dies. */
    private static void warning(Map<String,String> giant)throws Exception {
        var action=FireCatalog.actionJson(giant.get("attack.0.fire.0.action"));
        check(action.actor()!=null&&action.actor().config().name().endsWith("Parts/Warning")&&action.actor().fuse()==1356,"the bundled Giant telegraph carries its capture-bracketed lifetime");
        var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());int stamp=SceneClock.now()+100;
        var source=new ActorState(500,0,"Giant",0,stamp,0,0,0,0,0,4,stamp,new StarterCatalog.Model(GIANT,Map.of()),false);
        java.util.function.LongSupplier live=()->weapons.actors().stream().filter(a->a.projectile().destroyed()==Integer.MAX_VALUE).count();
        check(weapons.fire(action,source,stamp),"a slam start spawns a Warning marker");
        weapons.advance(stamp+1355,List.of());check(live.getAsLong()==1,"the marker lives until its lifetime elapses");
        weapons.advance(stamp+1356,List.of());check(live.getAsLong()==0,"the marker is removed at 1356 ms while the Giant lives");
        check(weapons.fire(action,source,stamp+6000),"the next slam spawns a fresh marker");
        weapons.advance(stamp+6000+1356,List.of());check(live.getAsLong()==0,"markers never accumulate across slams");
    }
    /** A real ticking instance: the bundled Jelly Cube's authored 1.5-unit DamageImpulse pushes
     * the knight only when its first swing lands; a protected knight is neither hurt nor pushed. */
    private static void knockback(SceneCatalog.MapScene crash,Map<String,String> jelly)throws Exception {
        check(swing(crash,jelly,true)==null,"a protected knight is not pushed");
        var impulse=swing(crash,jelly,false);
        check(impulse!=null&&Math.abs(Math.hypot(impulse.x(),impulse.y())-1.5)<1e-3&&impulse.x()>0&&impulse.duration()==250,
            "a landed Jelly hit pushes the knight 1.5 units for 250 ms away from the Jelly");
    }
    private static KnightCombat.Impulse swing(SceneCatalog.MapScene crash,Map<String,String> jelly,boolean protect)throws Exception {
        var real=crash.content();
        var content=new SceneContent(List.of(new SceneContent.Entry("jelly",0,0,0,new SceneContent.Spec(jelly))),real.scales(),Map.of(),real.stats(),real.items(),real.cycles(),real.statuses());
        var map=new SceneCatalog.MapScene(9398,50332041,"Fixture","Fixture","Fixture","level",1,1,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var simulation=new SceneRegistry.Instance(9398,0,map).simulation();int now=SceneClock.now();
        try(var member=simulation.join(new ActorState(219,1001,"Knight",0,now,1,0,(float)Math.PI,0,0,0,now,new StarterCatalog.Model("Character/PC/Level",Map.of()),false),s->{})){
            member.combat(WeaponCatalog.empty(),new org.tortodrone.character.WorldProfile(10,1,1,0,0,1,List.of()));member.enter();
            var field=SceneSimulation.Membership.class.getDeclaredField("health");field.setAccessible(true);
            var health=(SceneHealth)field.get(member);int maximum;
            // Entering a level arms the three-second arrival window; the Jelly's first swing
            // falls inside it, so the unprotected case clears it explicitly.
            var until=SceneHealth.class.getDeclaredField("invulnerableUntil");until.setAccessible(true);
            synchronized(simulation){maximum=health.state().maximum();if(protect)health.protectLoading(SceneClock.now());else until.setInt(health,0);}
            int swung=0;long end=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<end){
                Thread.sleep(10);var snapshot=simulation.snapshot();var knight=snapshot.players().getFirst();var state=snapshot.health(knight.id());
                if(knight.combat()!=null&&knight.combat().impulse()!=null){
                    check(state!=null&&state.current()<maximum,"only a damaging hit pushes the knight");
                    return knight.combat().impulse();
                }
                var attack=snapshot.actors().stream().filter(a->a.config().name().equals(JELLY)&&a.activity()==4).findFirst();
                if(swung==0&&attack.isPresent())swung=attack.get().activityStarted();
                // The Roundhouse fires at land 1322 + delay 150 / fire-speed 1.5 = 1422 ms.
                if(swung!=0&&snapshot.timestamp()>swung+2000){check(!protect||state==null||state.current()==maximum,"protection blocks the Jelly's damage");return null;}
            }
            throw new AssertionError("the bundled Jelly never swung at an adjacent knight");
        }
    }
    /** Spawn actions may sit inside authored sequences, delays or one-shot limits. */
    static List<Map<String,String>> spawnFields(Object action)throws Exception {
        if(action instanceof SceneActions.Spawn spawn)return List.of(spawn.fields());
        var found=new ArrayList<Map<String,String>>();
        if(action instanceof Record record)for(var component:record.getClass().getRecordComponents()){
            Object value=component.getAccessor().invoke(record);
            if(value instanceof SceneActions.Action child)found.addAll(spawnFields(child));
            else if(value instanceof List<?> list)for(Object child:list)found.addAll(spawnFields(child));
        }
        return found;
    }
    private static void expect(List<Map<String,String>> fields,String health,int normal,int piercing,int elemental,int shadow){
        for(var f:fields)check(f.get("health").equals(health)&&Float.parseFloat(f.get("defense.normal"))==normal&&Float.parseFloat(f.get("defense.piercing"))==piercing
            &&Float.parseFloat(f.get("defense.elemental"))==elemental&&Float.parseFloat(f.get("defense.shadow"))==shadow,"native defense/health for "+f.get("config"));
    }
    private static FireCatalog.DataValue normal(float amount,boolean major){
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",amount,"shouldReact",true,"majorReact",major));
    }
    private static StarterCatalog.Model region(FireCatalog.DataValue damage){
        return new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",1f)),"damage",damage));
    }
    private static SceneEntities scene(Map<String,String> fields)throws Exception {
        return new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("monster",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
    }
    private record Result(float health,int activity) { }
    /** One production damage region against a fresh copy of the bundled monster at the origin. */
    private static Result hit(Map<String,String> fields,FireCatalog.DataValue damage)throws Exception {
        var scene=scene(fields);scene.attacks((actor,action)->{});int id=scene.actors().getFirst().id();
        scene.damage(region(damage),0,0,0,SceneClock.now()+100,219);
        var actor=scene.actor(id);check(actor!=null,"monster survives the single audited hit");
        return new Result(actor.entity().health(),actor.activity());
    }
}
