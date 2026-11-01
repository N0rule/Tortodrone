package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;

/** Real entrance/sensor/dialogue regression; not the complete tutorial acceptance chain. */
public final class TutorialOpeningCompatibility {
    private record Dialog(SceneConversation definition,ActorState source,int stamp) { }
    private final SceneEntities world;
    private final SceneContent content;
    private final TownMovement movement;
    private final SceneHealth health;
    private final SceneWeapons effects;
    private final SceneCollision collision;
    private final int collisionMask;
    private final List<Dialog> dialogs=new ArrayList<>();
    private int now=1000,attacks;
    private final Set<String> originalShrubs=new HashSet<>();
    private TutorialOpeningCompatibility(SceneContent content,CollisionMap terrain,SceneCollision collision,org.tortodrone.resources.ContentRegistry defaults){
        this.content=content;this.collision=collision;collisionMask=Math.round(content.stats().get("player.collision-mask"));
        world=new SceneEntities(content,terrain,collision);health=new SceneHealth(content);effects=new SceneWeapons(terrain);world.effects(effects::effect);
        effects.targets(world);effects.catalog(defaults.fires());effects.staticCollision(collision);
        movement=new TownMovement(terrain,-21,-69.5f,0);movement.resetInput(now);
        movement.staticCollision(collision,Math.round(content.stats().get("player.collision-mask")));
        movement.dynamicPenetration((x,y)->world.penetration(x,y,219));
        var sword=new WorldProfile.Item(1,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6,true);
        var profile=new WorldProfile(219,1,1,0,0,0,List.of(sword));
        defaults.equipment().stats(content.stats());defaults.equipment().town(defaults.town());
        var combat=new KnightCombat(defaults.weapons(),defaults.equipment());combat.equipment(profile);combat.attacks(start->{attacks++;effects.attack(219,start);});movement.combat(combat);
        effects.damageModifiers((owner,start)->GearStats.damage(content.stats(),profile,sword,defaults.town(),GearStats.damageTags(content.stats())));
        world.conversations((definition,source,stamp,target)->{if(target!=0&&target!=219)throw new AssertionError("Wrong dialogue target");dialogs.add(new Dialog(definition,source,stamp));return true;});
        world.pawnStatuses((id,operation,stamp)->id==219&&(operation.clear()?health.clearStatus(operation.clearMask()):health.applyCondition(operation.condition(),operation.data(),stamp)));
        world.advance(now,List.of(pose()));
        world.actors().stream().filter(a->a.config().name().equals("Block/Breakable/Haven")).forEach(a->originalShrubs.add(a.entity().key()));
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path source=Path.of(args[1]),pack=Path.of(args[2]);byte[] layout=Files.readAllBytes(source);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(layout));
        int version=TutorialRoute.sourceVersion(layout);
        TutorialOpeningCompatibility test;
        if(Files.isRegularFile(pack.resolve("map.yml"))){
            var defaults=org.tortodrone.resources.ContentManager.load(pack.toAbsolutePath().getParent().getParent().getParent());var scene=defaults.scenes().scene(398);
            if(scene.version()!=version||scene.spawnX()!=-21||scene.spawnY()!=-69.5f||!Arrays.equals(scene.layout(),layout))throw new AssertionError("Imported tutorial entrance/revision changed");
            test=new TutorialOpeningCompatibility(scene.content(),scene.collision(),scene.staticCollision(),defaults);
            System.out.println("SOURCE: real scene 398 after unified MapImport and production ContentManager loading");
        }else{
            var p=new Properties();try(var input=Files.newInputStream(pack.resolve("map.properties"))){p.load(input);}
            if(Files.mismatch(source,pack.resolve("scene.dat"))!=-1||!p.getProperty("scene-id").equals("398")||!p.getProperty("version").equals(Integer.toString(version))||!p.getProperty("spawn-x").equals("-21.0")||!p.getProperty("spawn-y").equals("-69.5"))throw new AssertionError("Unverified tutorial entrance/revision");
            var defaults=org.tortodrone.resources.ContentManager.load(Path.of(System.getProperty("tortodrone.deployment")));
            test=new TutorialOpeningCompatibility(SceneContent.load(pack,hash),CollisionMap.read(pack.resolve("walkability.gz")),SceneCollision.load(pack,hash),defaults);
            System.out.println("SOURCE: real scene 398 native export (unified import not exercised)");
        }
        test.run();
        var opening=test.dialogs.stream().filter(d->d.definition().actor().endsWith("Ambushed Knights A")).findFirst().orElseThrow();
        TutorialExitCompatibility.continueFrom(args[0],layout,test.content,test.world,test.effects,test.health,test.movement,opening.definition(),opening.source(),test.now,()->test.attacks);
    }
    private void run()throws Exception{
        if(!dialogs.isEmpty())throw new AssertionError("Intro started before reaching its original area");
        walk(-21,-60,10000);
        dialog("Intro","3481","n.tut_intro2","m.tut_intro2_1a","m.tut_intro2_2a");
        for(int i=0;i<20;i++)step(0,0);
        if(dialogs.size()!=1)throw new AssertionError("Original one-time intro trigger replayed");
        if(world.actors().stream().noneMatch(a->Arrays.asList(world.specification(a.id()).text("tags","").split(",")).contains("music crash")))throw new AssertionError("Opening music actor missing");
        System.out.println("PASS: original entrance 2659 -> normal collision-checked walk into area 3482 -> one-time 3481 Rhendon Intro -> both localized native panels -> closing response; no direct position or progression mutation");
        // Intermediate controls target the native encounter/progress strip centers.
        route(-1.5f,-69);route(-9.5f,-49);route(-13,-29);route(-26,-21);route(-22.5f,-12);route(-7.5f,-10);route(-4.5f,-4);
        dialog("Meetup","3393","n.tut_intro3","m.tut_intro3_1a","m.tut_intro3_2a");
        long remaining=world.actors().stream().filter(a->a.config().name().equals("Block/Breakable/Haven")).count();
        if(attacks==0||remaining>=originalShrubs.size())throw new AssertionError("Opening route never cut the authentic shrub barriers using normal attacks");
        System.out.println("PASS: continued normal movement/Proto Sword through original shrub barriers to area 2965 -> one-time 3393 Rhendon Meetup -> both localized panels and closing response; shrubs cut="+(originalShrubs.size()-remaining));
        route(-3.5f,7.5f);
        for(int i=0;i<12;i++)step(0,0);
        var gate=world.actors().stream().filter(a->a.entity().key().equals("1537")).findFirst().orElseThrow();
        if((gate.flags()&6)!=2)throw new AssertionError("Original one-time button did not finish opening gate 1537");
        route(-3.5f,16.5f);
        var music=world.actors().stream().filter(a->Arrays.asList(world.specification(a.id()).text("tags","").split(",")).contains("music main")).toList();
        if(music.size()!=1||music.getFirst().y()<15||music.getFirst().y()>18)throw new AssertionError("Original area 2955 did not spawn its music at the Activator");
        route(8.5f,39.5f);route(11.5f,56.25f);
        for(int i=0;i<42;i++)step(0,0);
        if(dialogs.stream().filter(d->d.definition().actor().endsWith("Ambushed Knights A")).count()!=1||health.status().modifiers().canMove())throw new AssertionError("Entrance traversal never reached the real ambush/Stop/dialogue chain");
        System.out.println("PASS: original area 2955 spawns music at its live Activator; continued collision-checked movement reaches the ambush and its authored delayed conversation/Stop");
    }
    private Dialog dialog(String name,String key,String speaker,String first,String second)throws Exception{
        var dialog=dialogs.stream().filter(d->d.definition().actor().equals("@conversation/Tutorial/Rhendon "+name)).findFirst().orElseThrow(()->new AssertionError("Missing Rhendon "+name+" at "+position()));
        if(!dialog.source().entity().entrySource()||!dialog.source().entity().key().equals(key)||!dialog.definition().name().equals(speaker)
                ||!dialog.definition().nodes().get("part 1a").message().equals(first)||!dialog.definition().nodes().get("part 2a").message().equals(second))throw new AssertionError("Original Rhendon source/keys changed");
        nativeRead(dialog);
        if(!world.conversationResponse(dialog.definition(),"part 1a",0,dialog.source(),219,now)||!world.conversationResponse(dialog.definition(),"part 2a",0,dialog.source(),219,now))throw new AssertionError("Original Rhendon response rejected");
        return dialog;
    }
    private ActorState pose(){return new ActorState(219,1001,"Fixture",0,1000,movement.x(),movement.y(),movement.rotation(),movement.direction(),movement.flags(),movement.activity(),movement.activityStarted(),new StarterCatalog.Model("Character/PC/Default",Map.of()),false,movement.combatState(),null);}
    private void step(int flags,float heading){now+=50;movement.status(health.status().modifiers());if(!movement.accept(now,heading,heading,flags,now,now,now))throw new AssertionError("Production input rejected");movement.advance(now,now);var players=List.of(pose());world.advance(now,players);effects.advance(now,players);}
    private void walk(float x,float y,int millis){int end=now+millis;while(now<end){if(Math.hypot(movement.x()-x,movement.y()-y)<.16)return;step(1,(float)Math.atan2(y-movement.y(),x-movement.x()));}throw new AssertionError("Original opening walk blocked at "+position());}
    private void route(float x,float y){
        var obstacles=world.actors();
        var path=ScenePathing.route(movement.x(),movement.y(),x,y,(sx,sy,tx,ty)->{
            int count=Math.max(1,(int)Math.ceil(Math.hypot(tx-sx,ty-sy)/.1));
            for(int i=1;i<=count;i++){float px=sx+(tx-sx)*i/count,py=sy+(ty-sy)*i/count;
                if(!collision.floorSupported(px,py)||collision.penetration(collisionMask,px,py,ScenePortals.PAWN_RADIUS).present()||!planningClear(obstacles,px,py))return false;
            }return true;
        });
        if(path.isEmpty())throw new AssertionError("No collision-legal opening route from "+position()+" to "+x+","+y);
        for(var point:path){
            for(int attempt=0;Math.hypot(movement.x()-point.x(),movement.y()-point.y())>=.16;attempt++){
                if(attempt>=200)throw new AssertionError("Opening route remains blocked at "+position()+" toward "+point);
                float facing=(float)Math.atan2(point.y()-movement.y(),point.x()-movement.x());
                float sx=movement.x(),sy=movement.y();step(1,facing);
                if(Math.hypot(movement.x()-sx,movement.y()-sy)<.01){step(4,facing);for(int release=0;release<24;release++)step(0,facing);}
            }
        }
    }
    private boolean planningClear(List<ActorState> obstacles,float x,float y){
        for(var actor:obstacles){
            var spec=world.specification(actor.id());
            if(actor.entity().destroyed()!=Integer.MAX_VALUE||(actor.flags()&6)==2&&actor.entity().kind().equals("Door"))continue;
            if(spec.text("damage-action","").equals("destroy")||Set.of("TimedBlock","StoneBlock").contains(spec.text("kind","")))continue; // production movement must still attack breakables
            if((spec.integer("collision-flags",0)&collisionMask)!=0&&spec.contains(actor.x(),actor.y(),actor.rotation(),x,y,ScenePortals.PAWN_RADIUS))return false;
        }return true;
    }
    private String position(){return "("+movement.x()+", "+movement.y()+")";}
    private void nativeRead(Dialog dialog)throws Exception{
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();
        for(var frame:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),
                codec.conversationDialog(1001,140,dialog.definition(),"part 1a",dialog.source(),1),codec.conversationDialog(1001,140,dialog.definition(),"part 2a",dialog.source(),1)))bytes.write(frame,4,frame.length-4);
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
        for(String node:List.of("part 1a","part 2a")){
            var event=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
            var wire=(com.threerings.projectx.data.ConversationDialogInfo)((com.threerings.presents.dobj.AttributeChangedEvent)event.xE().get(1)).getValue();
            var expected=dialog.definition().nodes().get(node);
            if(!(wire.sourceKey instanceof com.threerings.tudey.data.EntityKey.Entry entry)||!entry.getKey().toString().equals(dialog.source().entity().key())
                    ||!wire.message.equals(expected.message())||!wire.name.equals(dialog.definition().name())||!wire.bundle.equals("conversation")
                    ||!Arrays.equals(wire.responses,expected.responses().stream().map(SceneConversation.Response::text).toArray(String[]::new))
                    ||wire.model==null||!wire.model.getName().equals("character/npc/crew/model_rendon.dat")||wire.sourceCloseAnimation==null
                    ||!wire.sourceCloseAnimation.getName().equals("character/pc/emotes/animation_salute.dat")||wire.conversationService==null)throw new AssertionError("Stock reader disagrees with Rhendon presentation");
        }
        if(input.available()!=0)throw new AssertionError("Native dialogue framing differs");
    }
}
