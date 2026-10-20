package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Deterministic hitbox and thrown-lift regressions, without accounts or a ticking server. */
public final class SceneEntitiesTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var trainingEntities=new SceneEntities(map.content(),map.collision());
        var arsenal=trainingEntities.station("OpenArsenal");
        var arsenalSpec=trainingEntities.specification(arsenal.id());
        check(arsenalSpec.contains(19.5f,1.5f,arsenal.rotation(),19.5f,-.45f,0),"arsenal floor zone follows the placed prop's unrotated rectangle");
        check(!arsenalSpec.contains(19.5f,1.5f,arsenal.rotation(),16.5f,1.5f,0),"arsenal zone excludes the former sideways hitbox");
        var arsenalPrompt=trainingEntities.interaction(19.5f,-2.3f,(float)Math.PI/2);
        check(arsenalPrompt!=null&&"1955".equals(arsenalPrompt.entryKey()),"facing the front of the actual arsenal floor mat offers its placed interaction");
        check(trainingEntities.interaction(16.5f,1.5f,0)==null,"old sideways arsenal approach cannot trigger interaction");
        var haven=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(1);
        var townEntities=new SceneEntities(haven.content(),haven.collision());
        var clock=townEntities.actors().stream().filter(a->a.config().name().equals("Character/NPC/Haven/Stranger/Clockworks")).findFirst().orElseThrow();
        var prompt=townEntities.interaction(clock.x()-1.9f,clock.y(),0);
        check(prompt!=null&&prompt.actorId()==clock.id(),"Haven NPC interaction resolves its prepared dialogue binding");
        check(prompt.action().equals("Talk")&&prompt.entryKey()==null,"NPC dialogue uses its native talk icon and actor source");
        check(townEntities.interaction(clock.x()-1.9f,clock.y(),(float)Math.PI)==null,"NPC interaction respects the knight's facing and range");
        var auction=townEntities.station("OpenAuction");
        check(townEntities.actors().stream().noneMatch(a->a.id()==auction.id()),"placed interaction helpers are not published as debug actors");
        var auctionPrompt=townEntities.interaction(.45199376f,14.619257f,1.28f);
        check(auctionPrompt!=null&&auctionPrompt.action().equals("Shop")&&"3733".equals(auctionPrompt.entryKey()),"Auction House uses the native crowns prompt and placed entry identity");
        var hall=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(318);var rules=org.tortodrone.town.TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
        check(hall.content().conversations().size()==9,"all captured Hall of Heroes vendors have authored conversations");
        for(var dialogue:hall.content().conversations().values()){
            String station=dialogue.nodes().get(dialogue.initial()).responses().getFirst().station();var service=rules.station(station);
            check(service!=null&&service.kind().equals("OpenShop")&&!rules.goods(service.shop(),0,1).isEmpty(),"vendor dialogue opens its captured catalogue");
        }
        var hallEntities=new SceneEntities(hall.content(),hall.collision());
        var brazier=hallEntities.actors().stream().filter(a->a.config().name().equals("Dynamic/Breakable Objects/Castle/Brazier, ground")).findFirst().orElseThrow();
        hallEntities.damage(hit(shape("Circle",Map.of("radius",1f)),2,1),brazier.x(),brazier.y(),0,SceneClock.now(),219);
        check(hallEntities.actor(brazier.id())==null&&hallEntities.specification(brazier.id())==null,"destroyed brazier cannot remain an interaction source");
        var lab=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(30893);
        var bellEntry=lab.content().entries().stream().filter(e->e.spec().config().name().equals("Dynamic/Monster Objects/Beast Bell/Beast Bell")).findFirst().orElseThrow();
        var bellEntries=new ArrayList<SceneContent.Entry>();bellEntries.add(bellEntry);
        for(int i=0;i<3;i++){
            var target=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture","kind","Monster","health","100","radius","0.4","damage-mask","2","tags",i==1?"fam_slime":"fam_beast"));
            bellEntries.add(new SceneContent.Entry("bell-target-"+i,bellEntry.x()+(i==2?6:2.5f),bellEntry.y(),0,target));
        }
        var labContent=lab.content();var labEntities=new SceneEntities(new SceneContent(bellEntries,labContent.scales(),Map.of(),labContent.stats(),labContent.items(),labContent.cycles(),labContent.statuses()),lab.collision());
        var bell=labEntities.actors().stream().filter(a->a.config().name().equals("Dynamic/Monster Objects/Beast Bell/Beast Bell")).findFirst().orElseThrow();
        var bellEffects=new ArrayList<FireCatalog.Action>();labEntities.attacks((a,action)->bellEffects.add(action));int bellStamp=SceneClock.now();
        labEntities.damage(hit(shape("Circle",Map.of("radius",1f)),2,1),bell.x(),bell.y(),0,bellStamp,219);
        check(labEntities.actor(bell.id()).entity().state()==1&&bellEffects.stream().anyMatch(a->a.config().name().equals("Objects/Monster Objects/Beast Bell/Ring bell, beast stun")),"bell hit rings the native effect and enters its activation animation");
        var bellTargets=labEntities.actors().stream().filter(a->a.entity().kind().equals("Monster")).toList();
        check(!bellTargets.get(0).entity().status().conditions().isEmpty()&&bellTargets.get(1).entity().status().conditions().isEmpty()&&bellTargets.get(2).entity().status().conditions().isEmpty(),"bell wave stuns nearby beasts while preserving family and range exclusions");
        labEntities.damage(hit(shape("Circle",Map.of("radius",1f)),2,1),bell.x(),bell.y(),0,bellStamp+1,219);check(bellEffects.size()==1,"bell cooldown rejects repeated hits");
        labEntities.advance(bellStamp+8000,List.of());check(labEntities.actor(bell.id()).entity().state()==0,"bell returns to its inactive state after its authored cooldown");
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
        pots(map);
        System.out.println("PASS: rounded/rotated hitboxes, edge damage, damage masks, thrown-lift profiles and the served gym pot chain");
    }
    /** The served gym pot chain: thrown fire pots smash with their Blast Radius depth damage and
     *  Fire ailment (ULTRA status chance, so ignition repeats across spare pots), flip a
     *  damage-toggle lever, and leave a ScorcherTorch hazard retained past its 4 second
     *  damage/flame limit until the served 6 Second cleanup fuse expires; oil pots leave nine inert
     *  slick patches; closed Lift Barriers, native lift-collision bit included, catch
     *  thrown pots before they cross. */
    private static void pots(SceneCatalog.MapScene map)throws java.io.IOException {
        int stamp=SceneClock.now()+600;
        var leverFields=new HashMap<String,String>();
        leverFields.put("native-type","com.threerings.projectx.dungeon.data.actor.Switch");
        leverFields.put("config","Dynamic/Switch/Lever/Damage Toggle");leverFields.put("switch-mode","damage-toggle");
        leverFields.put("radius","0.4");
        var cubeFields=new HashMap<String,String>();
        cubeFields.put("native-type","com.threerings.projectx.dungeon.data.actor.Monster");
        cubeFields.put("config","Training Cube");cubeFields.put("kind","Monster");cubeFields.put("health","500");
        cubeFields.put("damage-mask","2");cubeFields.put("collision-flags","8");
        cubeFields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",shape("Circle",Map.of("radius",.4f))))));
        var potFields=new HashMap<String,String>();
        potFields.put("native-type","com.threerings.projectx.dungeon.data.actor.DungeonLift");
        potFields.put("config","Dynamic/Lift Objects/Fire Pot");potFields.put("kind","DungeonLift");
        potFields.put("damage-mask","0");potFields.put("collision-flags","514");potFields.put("can-throw","true");
        potFields.put("thrown-collision-mask","137");potFields.put("thrown-collision-flags","512");
        potFields.put("speed","11.69");potFields.put("throw-time","650");potFields.put("fall-speed","0");potFields.put("fuse","1500");
        potFields.put("destroy-on-land","true");potFields.put("explode-on-impact","false");
        potFields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",shape("Circle",Map.of("radius",.45f))))));
        var doorFields=new HashMap<String,String>();
        doorFields.put("native-type","com.threerings.projectx.dungeon.data.actor.Door");
        doorFields.put("config","Dynamic/Barrier/Lift/Lift Barrier x3");doorFields.put("collision-flags","8192");
        doorFields.put("door-speed","0");doorFields.put("start-open","false");doorFields.put("is-static","true");
        doorFields.put("shape","Rectangle");doorFields.put("width","1");doorFields.put("height","3");
        doorFields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",shape("Rectangle",Map.of("width",1f,"height",3f))))));
        var oilFields=new HashMap<>(potFields);oilFields.put("config","Dynamic/Lift Objects/Oil Pot");
        var barrier=new SceneContent.Entry("barrier",21f,12f,0,new SceneContent.Spec(doorFields));
        var entries=new ArrayList<SceneContent.Entry>(List.of(
            new SceneContent.Entry("lever",9.4f,9f,0,new SceneContent.Spec(leverFields)),
            new SceneContent.Entry("cube",9.9f,9f,0,new SceneContent.Spec(cubeFields)),
            new SceneContent.Entry("pot",8.4f,9f,0,new SceneContent.Spec(potFields)),
            new SceneContent.Entry("oil",20.4f,12f,0,new SceneContent.Spec(oilFields)),barrier));
        // Spares for the ignition loop: the served Fire Pot ailment carries Status Chance ULTRA
        // (status-chance.ULTRA=0.65), so a landed pot ignites only with native probability.
        for(int i=2;i<=13;i++)entries.add(3,new SceneContent.Entry("pot"+i,8.4f,9f,0,new SceneContent.Spec(potFields)));
        var content=new SceneContent(entries,
            map.content().scales(),Map.of(),map.content().stats(),Map.of(),Map.of(),map.content().statuses());
        var world=new SceneEntities(content,map.collision());
        var players=new ArrayList<ActorState>();
        players.add(new ActorState(219,1001,"Fixture",256,stamp,8.4f,9f,0,0,0,new StarterCatalog.Model("Fixture",Map.of())));
        players.add(new ActorState(220,1002,"Fixture",256,stamp,20.4f,12f,0,0,0,new StarterCatalog.Model("Fixture",Map.of())));
        var pot=world.actors().stream().filter(a->a.entity().key().equals("pot")).findFirst().orElseThrow();
        var oil=world.actors().stream().filter(a->a.entity().key().equals("oil")).findFirst().orElseThrow();
        world.advance(stamp,players);
        check(world.carry(pot.id(),players.getFirst(),stamp+1),"knight grabs the gym fire pot");
        world.throwLift(pot.id(),players.getFirst(),stamp+1);
        world.advance(stamp+120,players);
        var cube=world.actors().stream().filter(a->a.entity().key().equals("cube")).findFirst().orElseThrow();
        var torch=world.actors().stream().filter(a->a.config().name().equals("Bomb/Monster/ScorcherTorch")).findFirst().orElseThrow();
        check(Math.abs(cube.entity().health()-.96f)<.0001,"smash depth damage PC/Damage/Base/Default (20 of 500) reaches the cube");
        check(world.actors().stream().anyMatch(a->a.entity().key().equals("lever")&&a.entity().state()!=0),
            "smash region flips a damage-toggle lever inside the pot's blast");
        check(world.actor(torch.id())!=null,"a ScorcherTorch patch spawns from the Fire Pot's Extra Action");
        world.advance(stamp+620,players);
        // The served Fire Pot ailment carries Status Chance ULTRA (0.65), so ignition is
        // probabilistic: throw the spare pots (and let the torch tick) until Fire sticks.
        boolean ignited=onFire(world.actor(cube.id()));int step=650;
        for(int i=2;i<=13&&!ignited;i++){
            int index=i;
            var spare=world.actors().stream().filter(a->a.entity().key().equals("pot"+index)).findFirst().orElseThrow();
            check(world.carry(spare.id(),players.getFirst(),stamp+step),"knight grabs spare fire pot "+i);
            world.throwLift(spare.id(),players.getFirst(),stamp+step);
            world.advance(stamp+step+120,players);
            var live=world.actor(cube.id());ignited=live!=null&&onFire(live);step+=140;
        }
        check(ignited,"the Fire ailment applies to the cube at its native ULTRA status chance");
        check(world.actor(torch.id())!=null,"the torch patch stays alive while it burns");
        world.advance(stamp+4620,players);
        check(world.actor(torch.id())!=null,"the torch outlives its 4 second flame (the served Lifetime choice keeps a 6 Second cleanup fuse)");
        world.advance(stamp+6200,players);
        check(world.actor(torch.id())==null,"ScorcherTorch patch expires at the served 6 Second cleanup fuse");
        check(world.carry(oil.id(),players.getLast(),stamp+7000),"knight grabs the gym oil pot");
        world.throwLift(oil.id(),players.getLast(),stamp+7000);
        world.advance(stamp+7120,players);
        var slicks=world.actors().stream().filter(a->a.config().name().equals("Bomb/Monster/Oil Slick")).toList();
        check(slicks.size()==9,"oil pot leaves the served nine inert Oil Slick patches");
        check(slicks.stream().allMatch(s->s.x()<21.6f),
            "thrown pot stopped west of the closed Lift Barrier instead of crossing it");
        // The served SubjectiveDoor rule server-side: a knight holding a lift is stopped by a
        // closed Lift Barrier, a knight with nothing in hand passes it, and putting the lift
        // down (release clears the carrier) opens the pawn again.
        entries.add(new SceneContent.Entry("hold",16.5f,12f,0,new SceneContent.Spec(oilFields)));
        var contentHeld=new SceneContent(entries,
            map.content().scales(),Map.of(),map.content().stats(),Map.of(),Map.of(),map.content().statuses());
        var held=new SceneEntities(contentHeld,map.collision());
        var grab=held.actors().stream().filter(a->a.entity().key().equals("hold")).findFirst().orElseThrow();
        var carrier=new ActorState(219,1001,"Fixture",256,stamp,20.4f,12f,0,0,0,new StarterCatalog.Model("Fixture",Map.of()));
        held.advance(stamp,List.of(carrier));
        check(held.carry(grab.id(),carrier,stamp+1),"knight grabs a lift beside the closed Lift Barrier");
        check(held.penetration(21f,12f,carrier.id()).present(),
            "carrying a lift stops the pawn at the closed Lift Barrier's footprint");
        held.throwLift(grab.id(),carrier,stamp+2);
        check(!held.penetration(21f,12f,carrier.id()).present(),
            "after putting the lift down the pawn passes the Lift Barrier again");
    }

    private static boolean onFire(ActorState actor){
        return actor.entity().status().conditions().stream().anyMatch(c->c.name().equals("Fire"));
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
