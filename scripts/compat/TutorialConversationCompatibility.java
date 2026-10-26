package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.math.*;
import com.threerings.tudey.data.*;
import com.threerings.tudey.shape.config.ShapeConfig;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;

/** Real scene 398 sensor/presentation oracle. This is not a complete tutorial playthrough. */
public final class TutorialConversationCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!hash.equals("c43f6fe662c56c7f6d56efe855387e5b55e6723144a2fd2b8e49f18fcc600ecc"))throw new AssertionError("Unverified tutorial revision");
        var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(cm);
        Path pack=Path.of(args[2]);var metadata=new Properties();try(var input=Files.newInputStream(pack.resolve("map.properties"))){metadata.load(input);}
        if(scene.sceneId!=398||scene.version!=2008845103||!hash.equals(metadata.getProperty("scene-sha256"))||!metadata.getProperty("spawn-x").equals("-21.0")||!metadata.getProperty("spawn-y").equals("-69.5"))throw new AssertionError("Tutorial identity or original entrance changed");
        var content=SceneContent.load(pack,hash);
        var sensor=content.entries().stream().filter(e->e.key().equals("3280")&&e.spec().flag("scene-sensor",false)).findFirst().orElseThrow();
        TudeySceneModel.AreaEntry original=null;for(var candidate:scene.ci(TudeySceneModel.AreaEntry.class))if(candidate.getKey().equals(3280))original=candidate;
        if(original==null||!original.area.getName().equals("Player Enter/Spawn Activate"))throw new AssertionError("Original ambush area absent");
        var disc=new ShapeConfig.Circle();disc.radius=ScenePortals.PAWN_RADIUS;int queries=0;
        for(int ix=0;ix<=80;ix++)for(int iy=0;iy<=40;iy++){
            float x=7.5f+ix*.1f,y=55.5f+iy*.1f;
            boolean expected=original.av(cm).b(disc.acP().d(new Transform2D(new Vector2f(x,y),0)));
            if(expected!=sensor.spec().contains(sensor.x(),sensor.y(),sensor.rotation(),x,y,disc.radius))throw new AssertionError("Native ambush intersection differs at "+x+","+y);
            queries++;
        }
        var nativePawn=cm.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").ai(cm);
        if(nativePawn.collisionFlags!=Math.round(content.stats().get("player.collision-flags")))throw new AssertionError("Player sensor flags changed");
        var world=new SceneEntities(content,CollisionMap.read(pack.resolve("walkability.gz")),SceneCollision.load(pack,hash));
        var starts=new ArrayList<SceneConversation>();var sources=new ArrayList<ActorState>();var targets=new ArrayList<Integer>();var times=new ArrayList<Integer>();
        world.conversations((definition,source,stamp,target)->{starts.add(definition);sources.add(source);targets.add(target);times.add(stamp);return true;});
        var player=new ActorState(219,1001,"Fixture",256,1000,11.5f,57.5f,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        for(int stamp=1000;stamp<3000;stamp+=50)world.advance(stamp,List.of(player));
        if(!starts.isEmpty())throw new AssertionError("Authored dialogue delay was shortened");
        world.advance(3000,List.of(player));world.advance(3050,List.of(player));
        if(starts.size()!=1||!times.equals(List.of(3000))||!targets.equals(List.of(219)))throw new AssertionError("Ambush dialogue timing, targeting or duplication differs");
        var definition=starts.getFirst();var source=sources.getFirst();
        if(!source.entity().entrySource()||!source.entity().key().equals("3280")||source.x()!=11.5f||source.y()!=57.5f||world.actors().stream().anyMatch(a->a.id()==0))throw new AssertionError("Dialogue invented a network actor or lost its source entry");
        if(!definition.initial().equals("part 1a")||!definition.nodes().get("part 1a").responses().getFirst().destination().equals("part 2a"))throw new AssertionError("Verified first panel/jump changed");
        var response=definition.nodes().get("part 2a").responses().getFirst();
        if(!new SceneActions.Signal("start tut_miniboss","activate").equals(response.action()))throw new AssertionError("Captured final response lost its encounter signal");
        spawnedEncounter(content,pack,hash,world,definition,source,player);
        var events=new ArrayList<String>();var trigger=content.entries().stream().filter(e->e.key().equals("3265")&&e.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
        var scheduler=new SceneActions(List.of(SceneActions.rule(trigger)),(tag,name,stamp)->{events.add(tag+":"+name);return true;});
        scheduler.perform(response.action(),3100,"entry:3280","actor:219");
        // Production SceneEntities routes signals back into this same scheduler.
        scheduler.advance(3100);if(!events.equals(List.of("start tut_miniboss:activate")))throw new AssertionError("Dialogue action changed");
        events.clear();scheduler.signal("start tut_miniboss","activate",3150,"entry:3280");scheduler.advance(3150);scheduler.advance(3200);
        scheduler.signal("start tut_miniboss","activate",3250,"entry:3280");scheduler.advance(3250);
        if(!events.equals(List.of("spawn boss:activate","boss camera:activate","boss music:activate")))throw new AssertionError("Original one-time encounter trigger changed");
        var output=new ByteArrayOutputStream();var codec=new SessionCodec();
        append(output,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(output,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"));
        append(output,codec.conversationDialog(1001,140,definition,"part 1a",source,100));
        append(output,codec.conversationDialog(1001,140,definition,"part 2a",source,100));
        var input=new com.threerings.io.v(new ByteArrayInputStream(output.toByteArray()));input.qe();input.qe();
        for(String node:List.of("part 1a","part 2a")){
            var event=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
            var dialog=(com.threerings.projectx.data.ConversationDialogInfo)((com.threerings.presents.dobj.AttributeChangedEvent)event.xE().get(1)).getValue();
            var prepared=definition.nodes().get(node);
            if(!(dialog.sourceKey instanceof EntityKey.Entry entry)||!entry.getKey().equals(3280)||!dialog.sourceTranslation.equals(new Vector2f(11.5f,57.5f))||dialog.sourceRotation!=source.rotation()
                    ||!dialog.name.equals("n.tut_warden")||!dialog.bundle.equals("conversation")||!dialog.message.equals(prepared.message())
                    ||dialog.model==null||!dialog.model.getName().equals("character/npc/crew/model_warden_companion.dat")
                    ||!Arrays.equals(dialog.responses,prepared.responses().stream().map(SceneConversation.Response::text).toArray(String[]::new))||dialog.conversationService==null
                    ||dialog.sourceCloseAnimation!=null||dialog.music!=null||dialog.userInterface!=null||dialog.sourceTransient!=null
                    ||(node.equals("part 1a")?dialog.animation!=null:dialog.animation==null||!dialog.animation.getName().equals("character/pc/idle.dat")))throw new AssertionError("Stock reader rejected original ambush presentation");
        }
        if(input.available()!=0)throw new AssertionError("Conversation framing mismatch");scheduler.close();
        System.out.println("PASS: real tutorial revision/entrance; "+queries+" native area intersections; 2000-ms Activator conversation; original response/one-time encounter signals; both panels decoded by stock reader with Entry(3280), localized keys, Warden model and native animation");
        System.out.println("NOT VERIFIED: complete scene traversal, boss targeting/combat, allied squad, final portal and Rescue Camp settlement");
    }
    private static void spawnedEncounter(SceneContent content,Path pack,String hash,SceneEntities world,SceneConversation definition,ActorState source,ActorState player)throws Exception {
        String puppy="Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)",giant="Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant";
        var isolated=new SceneEntities(content,CollisionMap.read(pack.resolve("walkability.gz")),SceneCollision.load(pack,hash));
        var operations=new ArrayList<SceneActions.PawnStatus>();world.pawnStatuses((id,operation,stamp)->{operations.add(operation);return true;});
        if(!world.conversationResponse(definition,"part 1a",0,source,player.id(),3075)||!world.conversationResponse(definition,"part 2a",0,source,player.id(),3100))throw new AssertionError("Original response refused");
        if(world.conversationResponse(definition,"part 2a",1,source,player.id(),3100)||world.conversationResponse(definition,"part 2a",0,null,player.id(),3100))throw new AssertionError("Invalid response/source accepted");
        world.advance(3100,List.of(player));
        var first=world.actors().stream().filter(a->a.config().name().equals(puppy)).toList();
        if(first.size()!=1||first.getFirst().created()!=3100||first.getFirst().activity()!=3)throw new AssertionError("First authored Gun Puppy was not activated with the response");
        for(int stamp=3150;stamp<=11100;stamp+=50){
            world.advance(stamp,List.of(player));isolated.advance(stamp,List.of());
            if(stamp<9100&&world.actors().stream().anyMatch(a->a.config().name().equals(giant)))throw new AssertionError("Giant spawned before its six-second delay");
        }
        var enemies=world.actors().stream().filter(a->a.config().name().equals(puppy)||a.config().name().equals(giant)).toList();
        if(enemies.size()!=3||isolated.actors().stream().anyMatch(a->a.config().name().equals(puppy)||a.config().name().equals(giant)))throw new AssertionError("Encounter count or scene isolation changed");
        var puppies=enemies.stream().filter(a->a.config().name().equals(puppy)).sorted(Comparator.comparingInt(ActorState::created)).toList();
        if(puppies.size()!=2||puppies.get(1).created()<6100||puppies.get(1).created()>7100)throw new AssertionError("Second rocket lost native delay/variance");
        for(var enemy:enemies){
            String key=enemy.config().name().equals(giant)?"3392":enemy.created()==3100?"3321":"3367";
            var marker=content.entries().stream().filter(e->e.key().equals(key)&&e.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
            String wave=key.equals("3392")?"wave miniboss":"boss support 1";
            if(enemy.x()!=marker.x()||enemy.y()!=marker.y()||enemy.rotation()!=marker.rotation()||enemy.entity().health()!=1
                    ||!Arrays.asList(world.specification(enemy.id()).text("tags","").split(",")).contains(wave))throw new AssertionError("Spawn transform, health or wave tag lost at "+key);
            if(key.equals("3392")&&(enemy.created()!=9100||enemy.activity()!=3||world.specification(enemy.id()).number("health",0)!=1440))throw new AssertionError("Native Giant health/activation timing lost");
        }
        if(operations.stream().noneMatch(SceneActions.PawnStatus::clear))throw new AssertionError("Eight-second start-player signal did not reach its status operation");
        world.conversationResponse(definition,"part 2a",0,source,player.id(),11150);world.advance(11150,List.of(player));
        if(world.actors().stream().filter(a->a.config().name().equals(puppy)||a.config().name().equals(giant)).count()!=3)throw new AssertionError("Replayed activation duplicated encounter actors");
        var bytes=new ByteArrayOutputStream();append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"));
        append(bytes,new SessionCodec().sceneDelta(1001,140,0,(short)0,11100,11100,(short)50,enemies,List.of(),new int[0],Map.of(),true));
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
        var delta=(com.threerings.tudey.dobj.SceneDeltaEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
        if(delta.aej().length!=3||Arrays.stream(delta.aej()).anyMatch(a->!(a instanceof com.threerings.projectx.dungeon.data.actor.Monster))||input.available()!=0)throw new AssertionError("Spawned monsters failed stock-reader decoding");
        System.out.println("PASS: real exported response -> production scene scheduler -> Gun Puppy now/3500 +/- 500 ms, Giant at 6000 ms, release at 8000 ms; exact marker transforms, wave tags, health, one-time activation, isolation and stock Monster creation frames (AI not covered)");
    }
    private static void append(ByteArrayOutputStream output,byte[] frame){output.write(frame,4,frame.length-4);}
}
