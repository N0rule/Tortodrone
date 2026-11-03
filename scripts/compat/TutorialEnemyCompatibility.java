import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import com.threerings.config.*;
import com.threerings.tudey.config.ActionConfig;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.*;

/** Native tutorial enemy graphs on synthetic terrain; not a level playthrough. */
public final class TutorialEnemyCompatibility {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        if(args.length>1&&args[1].equals("constructs")){
            construct(cm,"Character/NPC/Monster/Construct/Scuttlebot/Scuttlebot (TG)",.5);
            construct(cm,"Character/NPC/Monster/Construct/Lumber/Lumber (TG)",.5);
            battlepod(cm);
            construct(cm,"Character/NPC/Monster/Construct/Retrode/Retrode (TG)",0);
            construct(cm,"Character/NPC/Monster/Construct/Retrode/Retrode (TG)",.999999);
            return;
        }
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>("Character/NPC/Monster/Slime/Jelly Cube (Intro)");
        var action=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var spec=new SceneContent.Spec(action.fields());var policy=SceneTargeting.read(spec);
        check(policy!=null&&policy.expansion()==5.9647f&&policy.range()==1.9997f&&policy.interval()==1999&&policy.attack()==0,"exact archive Intro Jelly targeting binding");
        var content=new SceneContent(List.of(new SceneContent.Entry("intro",0,0,0,spec)),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
        var terrain=open();var world=new SceneEntities(content,terrain);var weapons=new SceneWeapons(terrain);weapons.targets(world);
        var health=new SceneHealth(content);var fires=new ArrayList<Integer>();int[] stamp={SceneClock.now()};
        world.attacks((actor,fire)->{fires.add(stamp[0]);weapons.fire(fire,actor,stamp[0]);});
        world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var player=player(219,6.69f,0);int enemy=world.actors().getFirst().id();
        world.advance(stamp[0],List.of(player));
        stamp[0]+=spec.integer("activate-time",0)+100;world.advance(stamp[0],List.of(player));stamp[0]+=50;world.advance(stamp[0],List.of(player));
        check((world.actor(enemy).flags()&16)!=0,"native expanded monster shape acquires an intersecting knight: flags="+world.actor(enemy).flags()+" radius="+spec.number("radius",-1)+" shape="+spec.text("shape","")+" activity="+world.actor(enemy).activity());
        for(int tick=0;tick<200&&fires.isEmpty();tick++){stamp[0]+=50;world.advance(stamp[0],List.of(player));weapons.advance(stamp[0],List.of(player));}
        check(world.actor(enemy).x()>1&&fires.size()>0,"native jelly pursues and emits its actual Roundhouse fire graph");
        // The knight closes to melee range through normal movement input. An attack
        // start at maximum targeting range need not hit a retreating/stationary edge.
        var movement=new TownMovement(terrain,player.x(),player.y(),0);movement.resetInput(stamp[0]);
        movement.dynamicPenetration((x,y)->world.penetration(x,y,219));
        for(int tick=0;tick<240&&health.state().current()==200;tick++){
            stamp[0]+=50;float dx=world.actor(enemy).x()-movement.x(),dy=world.actor(enemy).y()-movement.y();
            float heading=(float)Math.atan2(dy,dx);int flags=Math.hypot(dx,dy)>1.1?1:0;
            check(movement.accept(stamp[0],heading,heading,flags,stamp[0],stamp[0],stamp[0]),"owned knight movement intent accepted");movement.advance(stamp[0],stamp[0]);
            player=new ActorState(219,1001,"Fixture",0,1,movement.x(),movement.y(),movement.rotation(),movement.direction(),movement.flags(),new StarterCatalog.Model("Player",Map.of()));
            world.advance(stamp[0],List.of(player));weapons.advance(stamp[0],List.of(player));
        }
        if(health.state().current()==200){
            System.out.println("DIAGNOSTIC: enemy="+world.actor(enemy).x()+","+world.actor(enemy).y()+" player="+player.x()+","+player.y()+" fires="+fires);
            for(var key:action.fields().keySet())if(key.startsWith("attack.0.fire.")&&key.endsWith("action"))System.out.println(key+"="+action.fields().get(key));
        }
        check(health.state().current()<200,"original Roundhouse damages the player through SceneWeapons/SceneEntities/SceneHealth");
        var retained=player(219,30,0);stamp[0]+=50;world.advance(stamp[0],List.of(retained));
        check((world.actor(enemy).flags()&16)!=0,"native empty release condition retains a living acquired target outside acquisition range");
        world.playerActive(id->false);stamp[0]+=50;world.advance(stamp[0],List.of(retained));
        check((world.actor(enemy).flags()&16)==0,"departed knight cannot remain targeted");
        var control=new SceneEntities(content,terrain);control.advance(stamp[0],List.of(player(220,6.71f,0)));stamp[0]+=50;control.advance(stamp[0],List.of(player(220,6.71f,0)));
        check((control.actors().getFirst().flags()&16)==0,"outside native acquisition expansion does not acquire");
        weighted(cm,"Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)",false);
        weighted(cm,"Character/NPC/Monster/Beast/Chromalisk/Chromalisk Whelp (Intro)",true);
        giant(cm);
        for(String ally:List.of("Bygs","Warden","Wend"))companion(cm,ally);
        System.out.println("PASS: original Intro Jelly -> sourced targeting -> collision/speed pursuit -> native Roundhouse graph -> real player damage; target retention, departure and acquisition-boundary controls (synthetic terrain)");
    }
    private static void giant(ConfigManager cm)throws Exception {
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant");
        var prepared=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var spec=new SceneContent.Spec(prepared.fields());var policy=SceneTargeting.read(spec);
        check(policy!=null&&policy.includePlayers()&&policy.monsterMask()==4&&policy.attack()==0,"authorized provisional Giant profile selects native chain head 0 and valid opposing combatants");
        var content=new SceneContent(List.of(new SceneContent.Entry("giant",0,0,0,spec)),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
        var terrain=open();var world=new SceneEntities(content,terrain);var weapons=new SceneWeapons(terrain);weapons.targets(world);var health=new SceneHealth(content);
        int[] stamp={SceneClock.now()};var accepted=new ArrayList<Boolean>();world.attacks((actor,fire)->{boolean result=weapons.fire(fire,actor,stamp[0]);accepted.add(result);if(!result)System.out.println("GIANT GRAPH REFUSED: "+fire);});
        world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));var pawn=player(219,3,0);boolean chained=false,warningSpawned=false;
        for(int tick=0;tick<400;tick++){stamp[0]+=50;world.advance(stamp[0],List.of(pawn));weapons.advance(stamp[0],List.of(pawn));chained|=world.actors().getFirst().activity()==5;warningSpawned|=weapons.actors().stream().anyMatch(a->a.config().name().equals("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Parts/Warning")&&a.projectile().nativeType().equals("ACTOR"));if(health.state().current()<200)break;}
        check(chained&&warningSpawned&&!accepted.isEmpty()&&accepted.stream().allMatch(Boolean::booleanValue)&&health.state().current()<200,"actual Giant warning actor/strike chain must execute and damage a knight; accepted="+accepted);
        System.out.println("PASS: provisional capture-calibrated Giant targeting -> native warning/strike chain -> actual knight damage (synthetic terrain; targeting parity limits remain)");
    }
    private static void battlepod(ConfigManager cm)throws Exception {
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>("Character/NPC/Monster/Construct/Blastcore/Presets/BP Training Grounds");
        var prepared=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var spec=new SceneContent.Spec(prepared.fields());var script=SceneScript.read(spec);
        check(script!=null&&script.steps().size()==15&&script.steps().getLast().equals(new SceneScript.Goto(0)),"complete native looping training-ground Battlepod program");
        var content=new SceneContent(List.of(new SceneContent.Entry("boss",0,0,0,spec)),Map.of(),Map.of(),Map.of("player.base-health",2000f),Map.of(),Map.of(),Map.of());
        var terrain=open();var scene=new SceneEntities(content,terrain);var weapons=new SceneWeapons(terrain);weapons.targets(scene);var health=new SceneHealth(content);int[] now={SceneClock.now()};var fires=new ArrayList<Boolean>();var heads=new LinkedHashMap<Integer,Integer>();
        scene.attacks((actor,action)->{heads.put(actor.activityStarted(),actor.activity()-4);fires.add(weapons.fire(action,actor,now[0]));});scene.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var pawn=player(219,5,0);
        for(int tick=0;tick<1800;tick++){now[0]+=50;scene.advance(now[0],List.of(pawn));weapons.advance(now[0],List.of(pawn));}
        var order=new ArrayList<>(heads.values());check(order.size()>=8&&order.subList(0,8).equals(List.of(0,2,3,2,3,2,3,0))&&!fires.isEmpty()&&fires.stream().allMatch(Boolean::booleanValue)&&health.state().current()<2000,"authored expose and alternating volleys execute real damaging graphs and repeat in authored order: heads="+heads+", fires="+fires+", health="+health.state().current());
        System.out.println("PASS: native training-ground Battlepod -> complete authored Wait/Attack/Goto loop -> expose/alternating volleys -> actual player damage (synthetic terrain; hatch interaction and floor not verified)");
    }
    private static void construct(ConfigManager cm,String name,double draw)throws Exception {
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>(name);
        var prepared=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var spec=new SceneContent.Spec(prepared.fields());var policy=SceneTargeting.read(spec);check(policy!=null,"missing construct policy");
        if(name.contains("Retrode")){
            var graph=FireCatalog.actionJson(spec.text("attack.1.fire.0.action",""));var carrier=graph.actor();
            check(carrier!=null&&carrier.timers().size()==1,"beam retains exactly one trail timer");
            var timer=carrier.timers().getFirst();check(timer.delay()==50&&timer.interval()==50&&timer.limit()==9&&timer.action().actor().fuse()==1000,"bounded calibrated beam cadence and native trail lifetime");
            check(Objects.equals(timer.action().actor().config().arguments().get("Color"),carrier.config().arguments().get("Color"))&&timer.action().actor().config().arguments().get("Damage (Status)")==FireCatalog.NullValue.INSTANCE,"trail preserves color and the TG beam's explicit lack of status damage");
        }
        if(name.contains("Lumber")){
            var graph=FireCatalog.actionJson(spec.text("attack.0.fire.0.action",""));
            var shape=graph.children().get(0).config().arguments().get("shape");
            check(SceneShape.intersects(shape,3,.312f,0)&&SceneShape.intersects(shape,1.5f,.312f,0)&&!SceneShape.intersects(shape,0,0,0),"native slam preserves supplied circle and original point, with no invented source radius");
        }
        var content=new SceneContent(List.of(new SceneContent.Entry("construct",0,0,0,spec)),Map.of(),Map.of(),Map.of("player.base-health",2000f),Map.of(),Map.of(),Map.of());
        var terrain=open();var scene=new SceneEntities(content,terrain,SceneCollision.empty(),new Random(1){@Override public double nextDouble(){return draw;}});var weapons=new SceneWeapons(terrain);weapons.targets(scene);var health=new SceneHealth(content);int[] now={SceneClock.now()};var fires=new ArrayList<Boolean>();
        scene.attacks((actor,action)->fires.add(weapons.fire(action,actor,now[0])));scene.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var pawn=player(219,name.contains("Retrode")?(draw==0?5.2f:1):5,0);boolean acquired=false,moved=false,trail=false;
        for(int tick=0;tick<1000&&health.state().current()==2000;tick++){
            now[0]+=50;scene.advance(now[0],List.of(pawn));weapons.advance(now[0],List.of(pawn));
            var actor=scene.actors().getFirst();acquired|=(actor.flags()&16)!=0;moved|=actor.x()>.1;
            trail|=weapons.actors().stream().anyMatch(a->a.config().name().equals("Bullet/Monster/Parts/Mecha Zombie Beam Trail"));
        }
        check(acquired&&!fires.isEmpty()&&fires.stream().allMatch(Boolean::booleanValue)&&health.state().current()<2000,"native construct must acquire and execute real damaging fire graphs: "+name+", draw="+draw+", fires="+fires+", health="+health.state().current());
        if(name.contains("Scuttlebot"))check(moved,"Scuttlebot must pursue through normal movement");
        if(name.contains("Retrode")&&draw==0)check(trail,"beam must spawn actual delayed-damage trail bombs");
        check(SceneWander.read(spec)!=null,"native idle Wander/Base binding");
        System.out.println("PASS: native "+name+" draw="+draw+" -> capture-calibrated acquisition/attack choice -> actual graph/player damage"+(moved?" and pursuit":"")+" (synthetic terrain; original targeting parity not claimed)");
    }
    private static void companion(ConfigManager cm,String role)throws Exception {
        String name="Character/Scripted/Missions/Tutorial/Crashsite/Mini Boss/Knights/"+role;
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>(name);
        var prepared=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var ally=new SceneContent.Spec(prepared.fields());var policy=SceneTargeting.read(ally);
        check(policy!=null&&policy.monsterMask()==2&&policy.expansion()==11.652f,"source helper acquires hostile monsters: "+role);
        var follow=SceneFollow.read(ally);check(follow!=null&&follow.evaluation()==500&&follow.minimum()==0&&follow.maximum()==3,"source helper's idle Follow routine: "+role);
        spawn.actor=new ConfigReference<>("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant");
        var giant=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        // Passive target fixture isolates friendly-fire safety from Giant retaliation.
        var passive=new HashMap<>(giant.fields());passive.keySet().removeIf(k->k.startsWith("targeting."));
        for(double draw:role.equals("Bygs")?new double[]{.2}:new double[]{.2,.99}){
            var content=new SceneContent(List.of(new SceneContent.Entry("helper",0,0,0,ally),new SceneContent.Entry("giant",8,0,0,new SceneContent.Spec(passive))),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
            var terrain=open();var world=new SceneEntities(content,terrain,SceneCollision.empty(),new Random(0){@Override public double nextDouble(){return draw;}});
            var weapons=new SceneWeapons(terrain);weapons.targets(world);var health=new SceneHealth(content);int[] stamp={SceneClock.now()};var fired=new ArrayList<Boolean>();
            world.attacks((actor,fire)->fired.add(weapons.fire(fire,actor,stamp[0])));world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
            var boss=world.actors().stream().filter(a->a.config().name().contains("Ironwood Giant")).findFirst().orElseThrow();
            for(int tick=0;tick<480;tick++){stamp[0]+=50;world.advance(stamp[0],List.of(player(219,0,-1)));weapons.advance(stamp[0],List.of(player(219,0,-1)));if(world.actor(boss.id())==null||world.actor(boss.id()).entity().health()<1)break;}
            check(!fired.isEmpty()&&fired.stream().allMatch(Boolean::booleanValue)&&(world.actor(boss.id())==null||world.actor(boss.id()).entity().health()<1),"native helper "+role+" draw="+draw+" must damage the real Giant through its actual attack graph; accepted="+fired);
            check(health.state().current()==200,"helper cannot damage the nearby player");
        }
        System.out.println("PASS: native "+role+" -> sourced hostile-monster acquisition -> actual attack heads/graphs -> real Giant damage; friendly knight unchanged (synthetic terrain)");
    }
    private static void weighted(ConfigManager cm,String name,boolean whelp)throws Exception {
        var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>(name);
        var prepared=(SceneActions.Spawn)SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(spawn,0,cm)));
        var spec=new SceneContent.Spec(prepared.fields());var policy=SceneTargeting.read(spec);
        check(policy!=null&&policy.modes().size()==(whelp?12:14),"exact archive weighted mode count: "+name);
        var content=new SceneContent(List.of(new SceneContent.Entry("native",0,0,0,spec)),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
        var terrain=open();var random=new Random(0){@Override public double nextDouble(){return whelp?.75:.2;}};
        var world=new SceneEntities(content,terrain,SceneCollision.empty(),random);var weapons=new SceneWeapons(terrain);weapons.targets(world);var health=new SceneHealth(content);
        int[] stamp={SceneClock.now()};world.attacks((actor,fire)->weapons.fire(fire,actor,stamp[0]));world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var knight=player(219,whelp?1:3,0);boolean moved=false,dodge=false;
        for(int tick=0;tick<300;tick++){
            stamp[0]+=50;world.advance(stamp[0],List.of(knight));weapons.advance(stamp[0],List.of(knight));var actor=world.actors().getFirst();
            moved|=Math.hypot(actor.x(),actor.y())>.1;dodge|=actor.activity()==6;
            if(whelp&&moved&&dodge||!whelp&&health.state().current()<200)break;
        }
        if(whelp)check(moved&&dodge,"native Whelp chooses its sourced Dodge Back and moves through its attack impulse");
        else check(health.state().current()<200,"native Gun Puppy emits real projectiles that damage a knight");
        if(whelp){
            check(policy.idleAttack()==1,"native Whelp idle routine uses camouflage head 1");
            var idle=new SceneEntities(content,terrain);int starts=0,previous=0;var effects=new SceneWeapons(terrain);effects.targets(idle);int[] time={SceneClock.now()};
            var accepted=new ArrayList<Boolean>();idle.attacks((actor,fire)->accepted.add(effects.fire(fire,actor,time[0])));
            for(int tick=0;tick<240;tick++){time[0]+=50;idle.advance(time[0],List.of());effects.advance(time[0],List.of());int activity=idle.actors().getFirst().activity();if(activity==5&&previous!=5)starts++;previous=activity;}
            check(starts==1&&!accepted.isEmpty()&&accepted.stream().allMatch(Boolean::booleanValue),"native idle camouflage fires once and executes its actual graph");
        }
        System.out.println("PASS: native "+name+" -> exact conditional modes -> "+(whelp?"real Dodge Back displacement and one idle camouflage action":"real projectile damage"));
    }
    private static ActorState player(int id,float x,float y){return new ActorState(id,1001,"Fixture",0,1,x,y,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static CollisionMap open()throws Exception {
        Path file=Files.createTempFile("tortodrone-enemy-open-",".gz");
        try {try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))){out.writeInt(0x54445731);out.writeInt(256);out.writeInt(256);out.writeFloat(-64);out.writeFloat(-64);out.writeFloat(.5f);byte[] cells=new byte[8192];Arrays.fill(cells,(byte)255);out.write(cells);}return CollisionMap.read(file);}
        finally{Files.delete(file);}
    }
}
