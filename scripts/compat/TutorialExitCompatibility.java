package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.math.Vector2f;
import com.threerings.tudey.data.*;
import com.threerings.tudey.shape.config.ShapeConfig;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;

/** Real scene/weapon input regression for the boss-removal exit; explicitly not fresh onboarding. */
public final class TutorialExitCompatibility {
    private static final String GIANT="Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant";
    private record Dialog(SceneConversation definition,ActorState source,int timestamp) { }
    private record FloorAdvance(int player,ActorState source,int timestamp) { }
    private final SceneContent content;
    private final SceneEntities world;
    private final SceneWeapons weapons;
    private final SceneHealth health;
    private final TownMovement movement;
    private final List<Dialog> dialogs=new ArrayList<>();
    private final List<FloorAdvance> floorAdvances=new ArrayList<>();
    private int now=1000,attacks;
    private java.util.function.IntSupplier attackCount=()->attacks;
    private boolean entranceTraversed;
    private TutorialExitCompatibility(SceneContent prepared,CollisionMap terrain,SceneCollision collision,org.tortodrone.resources.ContentRegistry defaults)throws Exception {
        content=prepared;
        world=new SceneEntities(content,terrain,collision);weapons=new SceneWeapons(terrain);weapons.staticCollision(collision);weapons.targets(world);weapons.catalog(defaults.fires());world.effects(weapons::effect);
        floorAdvanceSink();
        health=new SceneHealth(content);
        world.conversations((definition,source,stamp,target)->{if(target!=0&&target!=219)throw new AssertionError("Wrong dialogue owner");dialogs.add(new Dialog(definition,source,stamp));return true;});
        world.pawnStatuses((id,operation,stamp)->{if(id!=219)return false;if(operation.clear()){health.clearStatus(operation.clearMask());return true;}return health.applyCondition(operation.condition(),operation.data(),stamp);});
        // Explicit fixture deviation: start on the real ambush strip, with a level-zero
        // Proto Sword. No actor HP, door state or subsequent player position is assigned.
        movement=new TownMovement(terrain,11.5f,57.5f,0);movement.resetInput(now);movement.staticCollision(collision,Math.round(content.stats().get("player.collision-mask")));movement.dynamicPenetration((x,y)->world.penetration(x,y,219));
        var item=new WorldProfile.Item(1,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6,true);
        var profile=new WorldProfile(219,1,1,0,0,0,List.of(item));
        defaults.equipment().stats(content.stats());defaults.equipment().town(defaults.town());
        var combat=new KnightCombat(defaults.weapons(),defaults.equipment());combat.equipment(profile);combat.attacks(start->{attacks++;weapons.attack(219,start);});movement.combat(combat);
        var tags=GearStats.damageTags(content.stats());weapons.damageModifiers((owner,start)->GearStats.damage(content.stats(),profile,item,defaults.town(),tags));
        world.advance(now,List.of(pose()));
    }
    /** Continue the very same production world/pawn after the entrance regression; no warp/reset. */
    static void continueFrom(String game,byte[] layout,SceneContent content,SceneEntities world,SceneWeapons weapons,SceneHealth health,TownMovement movement,
            SceneConversation opening,ActorState source,int stamp,java.util.function.IntSupplier attacks)throws Exception{
        new TutorialExitCompatibility(content,world,weapons,health,movement,opening,source,stamp,attacks).run(game,layout);
    }
    private TutorialExitCompatibility(SceneContent content,SceneEntities world,SceneWeapons weapons,SceneHealth health,TownMovement movement,
            SceneConversation opening,ActorState source,int stamp,java.util.function.IntSupplier attacks){
        this.content=content;this.world=world;this.weapons=weapons;this.health=health;this.movement=movement;now=stamp;attackCount=attacks;entranceTraversed=true;
        floorAdvanceSink();
        dialogs.add(new Dialog(opening,source,stamp));
        world.conversations((definition,origin,time,target)->{if(target!=0&&target!=219)throw new AssertionError("Wrong dialogue owner");dialogs.add(new Dialog(definition,origin,time));return true;});
    }
    private void floorAdvanceSink(){world.floorAdvances((player,source,stamp)->{floorAdvances.add(new FloorAdvance(player.id(),source,stamp));return true;});}
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!hash.equals("c43f6fe662c56c7f6d56efe855387e5b55e6723144a2fd2b8e49f18fcc600ecc"))throw new AssertionError("Unverified tutorial revision");
        Path pack=Path.of(args[2]);TutorialExitCompatibility test;
        if(Files.isRegularFile(pack.resolve("map.yml"))){
            var defaults=org.tortodrone.resources.ContentManager.load(pack.toAbsolutePath().getParent().getParent().getParent());var scene=defaults.scenes().scene(398);
            if(scene.version()!=2008845103||!Arrays.equals(scene.layout(),bytes))throw new AssertionError("Imported scene identity/revision differs from the original");
            test=new TutorialExitCompatibility(scene.content(),scene.collision(),scene.staticCollision(),defaults);
            System.out.println("SOURCE: real scene 398 after unified MapImport and production ContentManager loading");
        }else{
            var metadata=new Properties();try(var input=Files.newInputStream(pack.resolve("map.properties"))){metadata.load(input);}
            if(!metadata.getProperty("scene-id").equals("398")||!metadata.getProperty("version").equals("2008845103")||!hash.equals(metadata.getProperty("scene-sha256"))||Files.mismatch(Path.of(args[1]),pack.resolve("scene.dat"))!=-1)throw new AssertionError("Prepared scene identity/revision differs from the original");
            var defaults=org.tortodrone.resources.ContentManager.load(Path.of(System.getProperty("tortodrone.deployment")));
            test=new TutorialExitCompatibility(SceneContent.load(pack,hash),CollisionMap.read(pack.resolve("walkability.gz")),SceneCollision.load(pack,hash),defaults);
            System.out.println("SOURCE: real scene 398 native export (unified import not exercised)");
        }
        test.run(args[0],bytes);
    }
    private void run(String game,byte[] layout)throws Exception {
        advanceTo(3000);var opening=dialogs.stream().filter(d->d.definition().actor().endsWith("Ambushed Knights A")).findFirst().orElseThrow();int start=now,previousAttacks=attackCount.getAsInt();
        if(!world.conversationResponse(opening.definition(),"part 1a",0,opening.source(),219,now)||!world.conversationResponse(opening.definition(),"part 2a",0,opening.source(),219,now))throw new AssertionError("Original encounter response rejected");
        advanceTo(start+8100);var boss=world.actors().stream().filter(a->a.config().name().equals(GIANT)).findFirst().orElseThrow();int bossId=boss.id();
        if(boss.entity().health()!=1||world.specification(bossId).number("health",0)!=1440)throw new AssertionError("Wrong native boss health");
        if(openDoors()!=0||health.status().modifiers().canMove()==false)throw new AssertionError("Fight entry doors/control differ");
        // The control walk is production movement against the unchanged native floor,
        // static geometry and server-owned dynamic actors, while the Giant is alive.
        if(!walk(11.5f,63.5f,10000)||!walk(13.5f,65.5f,10000))throw new AssertionError("Authored arena approach blocked at "+position());
        boolean escaped=walk(13.5f,90.5f,20000);
        if(escaped||movement.y()>=75.5f||openDoors()!=0)throw new AssertionError("Player passed the unopened native exit");
        if(!floorAdvances.isEmpty())throw new AssertionError("Closed exit allowed premature native NextFloor activation");
        System.out.println("PASS: live-boss control walk blocked at "+position()+" by both original closed exit doors");
        if(!walk(13.5f,70,10000)||!walk(14.5f,65,10000)||!walk(boss.x(),boss.y()-1.75f,10000))throw new AssertionError("Legal return to sword range failed at "+position());
        int killTime=0;
        for(int swing=0;swing<600&&killTime==0;swing++){
            float facing=(float)Math.atan2(boss.y()-movement.y(),boss.x()-movement.x());
            step(50,4,facing,0); // fresh normal attack, followed by a release (never synthetic damage)
            for(int tick=0;tick<24;tick++){
                var current=boss(bossId);if(current!=null&&current.entity().health()==0){killTime=now;break;}
                step(50,0,facing,0);
            }
        }
        if(killTime==0)throw new AssertionError("Proto Sword input did not defeat the Giant; attacks="+(attackCount.getAsInt()-previousAttacks)+" health="+boss(bossId).entity().health()+" at "+position());
        var dying=boss(bossId);int destroyed=dying.entity().destroyed();
        if(dying.activity()!=2||destroyed<=killTime||openDoors()!=0)throw new AssertionError("Native boss death/exit eligibility failed");
        while(boss(bossId)!=null)step(50,0,movement.rotation(),0);
        int removed=now;
        var chain=weapons.effects().stream().filter(e->Integer.valueOf(3307).equals(e.targetEntry())).findFirst().orElseThrow(()->new AssertionError("Original removed-wave handler did not fire its tagged chain effect"));
        if(chain.timestamp()!=removed||chain.x()!=12.5f||chain.y()!=66.5f)throw new AssertionError("Chain effect lost original marker identity/transform");
        if(openDoors()!=0)throw new AssertionError("Boss removal opened the doors without the native delay");
        advanceTo(removed+4999);
        if(openDoors()!=0||dialogs.stream().anyMatch(d->d.definition().actor().endsWith("Ambushed Knights B")))throw new AssertionError("Native closing delay was shortened");
        advanceTo(removed+5000);
        if(openDoors()!=2)throw new AssertionError("Both original door leaves did not open after 5000 ms");
        var closing=dialogs.stream().filter(d->d.definition().actor().endsWith("Ambushed Knights B")).findFirst().orElseThrow();
        if(closing.timestamp()!=removed+5000||!closing.source().entity().key().equals("3306")||!closing.definition().nodes().get("part 1a").message().equals("m.tut_warden_3a"))throw new AssertionError("Original closing dialogue changed");
        if(!world.conversationResponse(closing.definition(),"part 1a",0,closing.source(),219,now))throw new AssertionError("Closing dialogue response refused");
        var entryEffectFrame=new SessionCodec().sceneDelta(1001,140,0,(short)0,now,now,(short)50,doors(),List.of(),new int[0],Map.of(),true,List.of(chain));
        nativeRead(entryEffectFrame,closing);
        advanceTo(removed+6000);
        if(!walk(13.5f,70,10000)||!walk(13.5f,90.5f,15000)||!walk(13,96.5f,10000))throw new AssertionError("Walk through opened exit failed at "+position());
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,game+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(layout);scene.init(cm);
        TudeySceneModel.AreaEntry portal=null;for(var area:scene.ci(TudeySceneModel.AreaEntry.class))if(area.getKey().equals(1943))portal=area;
        var circle=new ShapeConfig.Circle();circle.radius=ScenePortals.PAWN_RADIUS;
        if(portal==null||!portal.av(cm).b(circle.acP().d(new com.threerings.math.Transform2D(new Vector2f(movement.x(),movement.y()),0))))throw new AssertionError("Movement never reached the original final portal area");
        if(floorAdvances.size()!=1||floorAdvances.getFirst().player()!=219||!floorAdvances.getFirst().source().entity().entrySource()
                ||!floorAdvances.getFirst().source().entity().key().equals("1943")||floorAdvances.getFirst().timestamp()<removed+5000)throw new AssertionError("Original portal did not perform exactly one native NextFloor action for its activating pawn");
        advanceTo(now+1000);if(floorAdvances.size()!=1)throw new AssertionError("Held portal intersection replayed NextFloor");
        System.out.println("PASS: "+(attackCount.getAsInt()-previousAttacks)+" production Proto Sword attack starts -> real Giant damage/death/removal -> complete captured 3306 graph -> exact 5000-ms door opening/closing dialogue -> normal movement to original portal 1943 -> its actual NextFloor action exactly once at "+position());
        System.out.println("NOT VERIFIED: fresh knight creation"+(entranceTraversed?"":"/entrance traversal")+", Giant targeting/retaliation, complete squad behavior, NextFloor/Rescue Camp settlement, persistence and graphical playthrough. Fixture "+(entranceTraversed?"continues the same world/pawn from the authored entrance":"starts at the ambush strip")+" with an owned-equipment profile, without an account or network session.");
    }
    private ActorState pose(){return new ActorState(219,1001,"Fixture",0,1000,movement.x(),movement.y(),movement.rotation(),movement.direction(),movement.flags(),movement.activity(),movement.activityStarted(),new StarterCatalog.Model("Character/PC/Default",Map.of()),false,movement.combatState(),null);}
    private ActorState boss(int id){return world.actors().stream().filter(a->a.id()==id).findFirst().orElse(null);}
    private List<ActorState> doors(){return world.actors().stream().filter(a->Set.of("3048","3050").contains(a.entity().key())).toList();}
    private long openDoors(){var doors=doors();if(doors.size()!=2)throw new AssertionError("Original door actors missing");return doors.stream().filter(a->(a.flags()&2)!=0).count();}
    private void advanceTo(int stamp){while(now<stamp)step(Math.min(50,stamp-now),0,movement.rotation(),0);}
    private void step(int delta,int flags,float facing,float heading){
        now+=delta;movement.status(health.status().modifiers());if(!movement.accept(now,facing,heading,flags,now,now,now))throw new AssertionError("Production input rejected");movement.advance(now,now);
        var players=List.of(pose());world.advance(now,players);weapons.advance(now,players);
    }
    private boolean walk(float x,float y,int millis){
        int end=now+millis;while(now<end){if(Math.hypot(movement.x()-x,movement.y()-y)<.16)return true;float heading=(float)Math.atan2(y-movement.y(),x-movement.x());step(50,1,heading,heading);}return false;
    }
    private String position(){return "("+movement.x()+", "+movement.y()+")";}
    private void nativeRead(byte[] frame,Dialog closing)throws Exception {
        var output=new ByteArrayOutputStream();for(var part:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),frame,new SessionCodec().conversationDialog(1001,140,closing.definition(),"part 1a",closing.source(),1)))output.write(part,4,part.length-4);
        var input=new com.threerings.io.v(new ByteArrayInputStream(output.toByteArray()));input.qe();input.qe();
        var delta=(com.threerings.tudey.dobj.SceneDeltaEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
        if(delta.aej().length!=2||Arrays.stream(delta.aej()).anyMatch(a->!(a instanceof com.threerings.projectx.dungeon.data.actor.Door)||!a.bR(2))||!(delta.aem()[0].aed() instanceof EntityKey.Entry key)||!key.getKey().equals(3307))throw new AssertionError("Native exit door/effect wire differs");
        var event=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();var dialog=(com.threerings.projectx.data.ConversationDialogInfo)((com.threerings.presents.dobj.AttributeChangedEvent)event.xE().get(1)).getValue();
        if(!dialog.message.equals("m.tut_warden_3a")||!Arrays.equals(dialog.responses,new String[]{"r.tut_warden_3a"})||input.available()!=0)throw new AssertionError("Native closing dialogue wire differs");
    }
}
