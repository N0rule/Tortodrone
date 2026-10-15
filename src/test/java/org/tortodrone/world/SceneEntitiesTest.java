package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Deterministic hitbox and thrown-lift regressions, without accounts or a ticking server. */
public final class SceneEntitiesTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);
        var rectangle=shape("Rectangle",Map.of("width",2f,"height",1f));
        var circle=shape("Circle",Map.of("radius",.1f));
        var fields=new HashMap<String,String>();
        fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Monster");
        fields.put("config","Fixture");fields.put("kind","Monster");fields.put("health","40");fields.put("damage-mask","2");
        fields.put("width","2");fields.put("height","1");
        fields.put("collision-flags","8");
        fields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",rectangle))));
        var spec=new SceneContent.Spec(fields);
        check(!spec.contains(0,0,0,1.3f,.8f,.333333f),"rectangle corners do not use a square radius expansion");
        check(spec.contains(0,0,(float)Math.PI/2,.55f,0,.1f),"rotated target edge includes touching discs");
        check(SceneCollision.overlaps(circle,.95f,0,0,rectangle,0,0,0,0),"region overlaps target away from its centre");
        check(!SceneCollision.overlaps(circle,1.3f,.8f,0,rectangle,0,0,0,.2f),"expanded region preserves rounded corners");
        var valve=map.content().entries().stream().filter(e->e.spec().text("config","").endsWith("Valve, wheel 01")).findFirst().orElseThrow();
        var content=new SceneContent(List.of(new SceneContent.Entry("fixture",5.5f,-12.5f,(float)Math.PI/2,spec),valve),Map.of());
        var world=new SceneEntities(content,map.collision());var cube=world.actors().getFirst();int stamp=SceneClock.now()+10;
        var feedback=new ArrayList<SceneCombatEvent>();world.feedback(feedback::add);
        check(world.contact(cube.x()+.55f,cube.y(),0,219,circle,8),"projectile edge contacts full target shape");
        check(!world.contact(cube.x(),cube.y(),0,219,circle,1),"projectile mask excludes noncolliding target factions");
        check(world.contact(valve.x(),valve.y(),0,219,circle,137),"native projectile mask blocks ordinary Actor props too");
        check(!world.contact(valve.x(),valve.y(),0,219,circle,8),"ordinary prop contact still respects its native flags");
        // The edge lies in the rotated shape while the actor centre is outside the attack disc.
        world.damage(hit(circle,2,10),cube.x(),cube.y()+.95f,0,stamp,219);
        check(Math.abs(world.actor(cube.id()).entity().health()-.75f)<.0001,"damage intersects the full rotated target hitbox");
        check(feedback.size()==1&&feedback.getFirst().actor()==cube.id()&&feedback.getFirst().amount()==10
            &&feedback.getFirst().source()==219&&feedback.getFirst().performance().equals("NORMAL_MEDIUM"),"target feedback retains source, damage and size");
        world.damage(hit(circle,8,100),cube.x(),cube.y(),0,stamp+1,219);
        check(world.actor(cube.id())!=null,"target damage mask excludes environmental-only damage");
        check(feedback.size()==1,"excluded damage cannot emit a hit notification");
        var health=new SceneHealth(new SceneContent(List.of(),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of()));
        var playerHit=health.hit(hit(circle,8,7).arguments().get("damage"),stamp);
        check(health.state().current()==193&&playerHit.amount()==7&&playerHit.feedback()==7340033,"native player feedback packs HP loss and normal damage pips");
        thrown(map,-11.5f,-16.5f,(float)Math.PI,true);
        thrown(map,3.5f,-20.5f,(float)Math.PI,false);
        trapOccupants(map);
        monsterStatus(map);
        System.out.println("PASS: rounded/rotated hitboxes, edge damage, damage masks and native thrown-lift collision profiles");
    }
    private static void monsterStatus(SceneCatalog.MapScene map) {
        var pad=map.content().entries().stream().filter(e->e.spec().child().text("native-type","").endsWith(".Monster")
            &&e.spec().child().number("acquire-distance",0)>0).findFirst().orElseThrow();
        var fields=new HashMap<>(pad.spec().child().fields());
        fields.put("activate-time","0");fields.put("acquisition-interval","0");fields.put("attack-distance","3");
        var content=new SceneContent(List.of(new SceneContent.Entry("cube",5.5f,-12.5f,0,new SceneContent.Spec(fields))),
            map.content().scales(),Map.of(),map.content().stats(),Map.of(),Map.of(),map.content().statuses());
        var world=new SceneEntities(content,map.collision());var cube=world.actors().getFirst();int stamp=SceneClock.now()+10;
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var dead=new ActorState(219,1001,"Fixture",256,stamp,6,-12.5f,0,0,0,TownMovement.DEAD,stamp,model,false);
        world.advance(stamp,List.of(dead));
        check(world.actor(cube.id()).activity()==0&&(world.actor(cube.id()).flags()&16)==0,"cube ignores defeated knights");
        var live=new ActorState(219,1001,"Fixture",256,stamp,6,-12.5f,0,0,0,0,stamp,model,false);
        world.advance(stamp+50,List.of(live));
        check(world.actor(cube.id()).activity()>=4,"cube attacks a live target in range");
        int[] fired={0};world.attacks((actor,action)->fired[0]++);
        Object sleep=SceneLifeTest.condition(content,"Sleep");
        // A player attack uses mask 2; the helper's default mask 1 is for player hazards.
        var sleepFields=new ArrayList<>(((FireCatalog.DataValue)sleep).fields());
        sleepFields.set(0,new FireCatalog.FieldValue("flags","int",2));
        world.damage(new StarterCatalog.Model("DamageRegion",Map.of("damage",new FireCatalog.DataValue(((FireCatalog.DataValue)sleep).type(),sleepFields),
            "shape",shape("Circle",Map.of("radius",.1f)))),cube.x(),cube.y(),0,stamp+51,219);
        world.advance(stamp+100,List.of(live));
        check(world.actor(cube.id()).activity()==0&&fired[0]==0,"sleep cancels a pending cube attack before its damage fires");

        var plain=new SceneContent.Spec(Map.of());
        check(SceneDamage.amount(SceneLifeTest.damage(40),content,plain,.5f)==20,"attack damage status scales raw damage before defense");
        check(SceneDamage.amount(SceneLifeTest.damage(40),content,new SceneContent.Spec(Map.of("defense.normal","20")),.5f)==10,
            "defense mitigates the status-adjusted amount");
    }
    private static void trapOccupants(SceneCatalog.MapScene map) {
        var spike=map.content().entries().stream().filter(e->e.spec().text("config","").endsWith("/Spikes")).findFirst().orElseThrow();
        var fields=new HashMap<>(spike.spec().fields());fields.put("cycle","UP");
        var content=new SceneContent(List.of(new SceneContent.Entry("trap",5.5f,-12.5f,0,new SceneContent.Spec(fields))),map.content().scales());
        var world=new SceneEntities(content,map.collision());var weapons=new SceneWeapons(map.collision());weapons.targets(world);
        int[] clock={SceneClock.now()+10};world.attacks((actor,action)->weapons.fire(action,actor,clock[0]));
        var hits=new HashMap<Integer,Integer>();world.playerDamage((id,damage)->hits.merge(id,1,Integer::sum));
        var players=new ArrayList<ActorState>();
        for(int id:List.of(219,220))players.add(new ActorState(id,1000+id,"Fixture",256,clock[0],5.5f,-12.5f,0,0,0,new StarterCatalog.Model("Fixture",Map.of())));
        world.advance(clock[0],players);
        check(hits.equals(Map.of(219,1,220,1)),"overlapping occupants receive one trap hit each");
        clock[0]+=50;world.advance(clock[0],players);
        check(hits.equals(Map.of(219,1,220,1)),"trap refractory applies to every affected occupant");
        clock[0]+=fields.containsKey("hazard-refractory")?Integer.parseInt(fields.get("hazard-refractory")):0;
        world.advance(clock[0],players);
        check(hits.equals(Map.of(219,2,220,2)),"trap resumes for each occupant after its authored refractory");
    }
    private static void thrown(SceneCatalog.MapScene map,float x,float y,float rotation,boolean clear) {
        var item=map.content().items().values().stream().filter(s->s.text("kind","").equals("vial")).findFirst().orElseThrow();
        var content=new SceneContent(List.of(),Map.of(),Map.of(),map.content().stats(),Map.of(),Map.of(),Map.of());
        var world=new SceneEntities(content,map.collision(),map.staticCollision());int stamp=SceneClock.now()+10;
        var player=new ActorState(219,1001,"Fixture",256,stamp,x,y,rotation,rotation,0,new StarterCatalog.Model("Fixture",Map.of()));
        int id=world.vial(player,item,stamp);check(id!=0,"vial can be carried");
        world.advance(stamp,List.of(player));world.throwLift(id,player,stamp);world.advance(stamp+50,List.of(player));
        var lift=world.actor(id);
        if(clear) {
            check(!map.collision().walkableSegment(x,y,x-.6f,y),"regression crosses pawn-only static collision");
            check(lift!=null&&(lift.flags()&2)!=0&&Math.abs(lift.x()-(x-.6f))<.001,"native thrown mask admits flight through pawn-only blockers");
            check(lift.entity().lift().faction()==219,"throw retains owning actor faction");
        } else check(lift==null,"native thrown blockers still shatter a vial");
        world.releaseLifts(player.id(),stamp+100);
    }
    private static StarterCatalog.Model hit(Object shape,int flags,float amount) {
        return new StarterCatalog.Model("DamageRegion",Map.of("shape",shape,"damage",new FireCatalog.DataValue(
            "com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(
                new FireCatalog.FieldValue("flags","int",flags),new FireCatalog.FieldValue("amount","float",amount)))));
    }
    private static Object shape(String type,Map<String,Float> fields) {
        return new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$"+type,
            fields.entrySet().stream().map(e->new FireCatalog.FieldValue(e.getKey(),"float",e.getValue())).toList());
    }
}
